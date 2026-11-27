// pc-remote — a single-binary "look at my PC from a phone browser and click"
// remote. Captures the screen, encodes it as H.264 and injects mouse/keyboard
// from the browser. No agent to install on the phone; no external services.
//
// Deliberately self-contained and separate from the camera server: its own Go
// module, own tunnel, own folder. Meant to run behind a Cloudflare tunnel so a
// phone can reach https://<sub>.<domain> without opening any ports.
package main

import (
	"context"
	"crypto/subtle"
	"embed"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"io/fs"
	"log"
	"net"
	"net/http"
	"os"
	"path/filepath"
	"strings"
	"syscall"
)

//go:embed web/*
var webFS embed.FS

// Config is loaded from config.json next to the exe (created on first run).
type Config struct {
	Addr    string `json:"addr"`      // listen address (default 127.0.0.1:7070 — tunnel-only)
	Token   string `json:"token"`     // shared secret required on every request
	Monitor int    `json:"monitor"`   // display index (0 = primary)
	FPS     int    `json:"fps"`       // stream frame rate
	Quality int    `json:"quality"`   // JPEG quality 1..100
	MaxW    int    `json:"max_width"` // downscale wider frames (0 = native)
	// KeepAwake: when to hold the machine out of sleep so it stays reachable.
	// "ac" (default) = only on mains power, "always", "never". The display may
	// still turn off; a viewer connecting wakes it.
	KeepAwake string `json:"keep_awake"`
	ShareDir  string `json:"share_dir"`  // one folder the authenticated file browser may read
	ShareFile string `json:"share_file"` // optional exact file selected by its owner
}

func defaultConfig() Config {
	return Config{
		Addr:      "127.0.0.1:7070",
		Token:     randToken(),
		Monitor:   0,
		FPS:       30,
		Quality:   jpegDefaultQuality,
		MaxW:      jpegDefaultMaxWidth,
		KeepAwake: "ac",
		ShareDir:  defaultShareDir(),
		ShareFile: "",
	}
}

var cfg Config

func main() {
	log.SetFlags(log.LstdFlags)
	saveConfig := loadConfig()
	addr := listenAddr(cfg)
	listener, err := net.Listen("tcp", addr)
	if err != nil {
		if isAddrInUse(err) {
			log.Fatalf("Port %d is already in use. If PocketBridge is open, use the LAN link from its original window.", listenPort(cfg))
		}
		log.Fatalf("Could not start the helper on %s: %v", addr, err)
	}
	defer listener.Close()
	setupLogFile()
	if saveConfig {
		data, err := json.MarshalIndent(cfg, "", "  ")
		if err != nil {
			log.Fatal(err)
		}
		if err := os.WriteFile(configPath(), data, 0600); err != nil {
			log.Fatalf("Could not save config.json. Extract the helper into a writable folder: %v", err)
		}
	}

	n := numDisplays()
	if cfg.Monitor >= n {
		log.Printf("[pc-remote] monitor %d does not exist (have %d) — using 0", cfg.Monitor, n)
		cfg.Monitor = 0
	}

	mux := newMux()

	// Keep the tunnel alive for as long as the server runs.
	go superviseTunnel(context.Background())
	// A sleeping PC drops the tunnel and can't be woken from a phone — hold it
	// awake per policy (see wake_windows.go).
	go keepAwake(func() string { return liveCfg().KeepAwake })

	log.Printf("[pc-remote] listening on %s | monitor=%d fps=%d q=%d maxw=%d", addr, cfg.Monitor, cfg.FPS, cfg.Quality, cfg.MaxW)
	fmt.Printf("On this laptop: http://127.0.0.1:%d/?t=%s\n", listenPort(cfg), cfg.Token)
	links := lanURLs(listenPort(cfg), cfg.Token)
	for _, u := range links {
		fmt.Printf("LAN: %s\n", u)
	}
	if len(links) == 0 {
		fmt.Println("No private IPv4 address found. Connect the laptop to the phone's hotspot and restart this helper.")
	}
	writeLinkFile(links)
	server := &http.Server{Handler: mux, ConnContext: rememberConn}
	if err := server.Serve(listener); err != nil {
		log.Fatal(err)
	}
}

// newMux wires every helper route. It is a function of its own so the routing
// (paths, methods, auth and CORS) is covered by tests instead of only by a
// running Windows machine.
func newMux() *http.ServeMux {
	mux := http.NewServeMux()
	sub, err := fs.Sub(webFS, "web")
	if err != nil {
		log.Fatalf("[pc-remote] embed web: %v", err)
	}
	protect := func(h http.Handler) http.Handler { return cors(auth(h)) }
	mux.Handle("/", protect(http.FileServer(http.FS(sub))))
	mux.HandleFunc("/capture-camera", localCapturePage("camera.html"))
	mux.HandleFunc("/offline-camera", localCapturePage("camera.html"))
	mux.HandleFunc("/offline-screen", localCapturePage("present.html"))
	mux.HandleFunc("/phone-signal", phoneSignal)
	mux.HandleFunc("/capture-signal.js", localCapturePage("capture-signal.js"))
	mux.HandleFunc("/present.html", localCapturePage("present.html"))
	mux.Handle("/ping", protect(http.HandlerFunc(pingHandler)))
	mux.Handle("/input", protect(http.HandlerFunc(inputHandler)))
	mux.Handle("/screen", protect(http.HandlerFunc(screenHandler)))
	mux.Handle("/settings", protect(http.HandlerFunc(settingsHandler)))
	mux.Handle("/ws", protect(http.HandlerFunc(wsInputHandler)))
	mux.Handle("/jpeg", protect(http.HandlerFunc(jpegStreamHandler)))
	mux.Handle("/h264", protect(http.HandlerFunc(h264StreamHandler)))
	mux.Handle("/files", protect(http.HandlerFunc(filesHandler)))
	mux.Handle("/file/", protect(http.HandlerFunc(fileHandler)))
	mux.Handle("/qr.png", protect(http.HandlerFunc(qrImageHandler)))
	mux.Handle("/power", protect(http.HandlerFunc(powerHandler)))
	mux.Handle("/radios", protect(http.HandlerFunc(radiosHandler)))
	mux.Handle("/phone-link", protect(http.HandlerFunc(phoneLinkHandler)))
	mux.Handle("/phone-airplane", protect(http.HandlerFunc(phoneAirplaneHandler)))
	mux.Handle("/hotspot-reconnect", protect(http.HandlerFunc(hotspotReconnectHandler)))
	mux.Handle("/open-vlc", protect(http.HandlerFunc(openVLCHandler)))
	mux.Handle("/open-with", protect(http.HandlerFunc(openWithHandler)))
	mux.Handle("/open-player", protect(http.HandlerFunc(openPlayerHandler)))
	return mux
}

// helperVersion is reported by /ping so the phone can show which helper answered.
const helperVersion = "0.7.0.1"

// pingHandler is the cheap reachability check the phone uses to find the
// helper and to tell "wrong link" from "laptop asleep". It touches no disk and
// no display, so it answers instantly even while a capture is starting.
func pingHandler(w http.ResponseWriter, r *http.Request) {
	w.Header().Set("Content-Type", "application/json")
	w.Header().Set("Cache-Control", "no-store")
	_ = json.NewEncoder(w).Encode(map[string]any{
		"ok":      true,
		"app":     "PocketBridge Windows Control",
		"version": helperVersion,
		"power":   powerActions(),
	})
}

// cors lets a page served from another local origin (the PocketBridge page on
// the phone, for example) call the helper with the token in ?t= or X-Token.
// Preflight requests carry no token, so they are answered before auth; the
// real request still has to authenticate. Credentials (the cookie) are never
// allowed cross-origin, so a foreign page cannot ride an existing login.
func cors(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if origin := r.Header.Get("Origin"); origin != "" {
			h := w.Header()
			h.Set("Access-Control-Allow-Origin", "*")
			h.Set("Access-Control-Allow-Methods", "GET, HEAD, POST, OPTIONS")
			h.Set("Access-Control-Allow-Headers", "Content-Type, X-Token, Range")
			h.Set("Access-Control-Expose-Headers", "Content-Length, Content-Range, Accept-Ranges")
			h.Set("Access-Control-Max-Age", "600")
			// Chrome's Private Network Access preflight for LAN targets.
			if r.Header.Get("Access-Control-Request-Private-Network") == "true" {
				h.Set("Access-Control-Allow-Private-Network", "true")
			}
		}
		if r.Method == http.MethodOptions {
			w.WriteHeader(http.StatusNoContent)
			return
		}
		next.ServeHTTP(w, r)
	})
}

// Only the instance that acquired the listening port publishes its pairing links.
func writeLinkFile(links []string) {
	p := filepath.Join(exeDir(), "pc-remote-links.txt")
	if err := os.WriteFile(p, []byte(strings.Join(links, "\r\n")+"\r\n"), 0600); err != nil {
		log.Printf("Could not save pc-remote-links.txt: %v", err)
	}
}

// isAddrInUse reports whether a listen failed purely because the port is taken.
func isAddrInUse(err error) bool {
	var se *os.SyscallError
	if errors.As(err, &se) {
		return errors.Is(se.Err, syscall.EADDRINUSE) || strings.Contains(se.Err.Error(), "in use") ||
			strings.Contains(se.Err.Error(), "normally permitted") // Windows WSAEADDRINUSE wording
	}
	return strings.Contains(err.Error(), "in use") || strings.Contains(err.Error(), "normally permitted")
}

// Keep diagnostics on disk and in the foreground console. Pairing links go to stdout.
func setupLogFile() {
	p := filepath.Join(exeDir(), "pc-remote.log")
	// Don't let it grow forever on a machine that's always on.
	if fi, err := os.Stat(p); err == nil && fi.Size() > 2<<20 {
		_ = os.Rename(p, p+".1")
	}
	f, err := os.OpenFile(p, os.O_CREATE|os.O_WRONLY|os.O_APPEND, 0644)
	if err != nil {
		return
	}
	log.SetOutput(io.MultiWriter(os.Stderr, f))
	log.Printf("---- pc-remote starting ----")
}

func configPath() string {
	exe, err := os.Executable()
	if err != nil {
		return "config.json"
	}
	return filepath.Join(filepath.Dir(exe), "config.json")
}

// Load config before binding, but let only the process that obtains the port save changes.
func loadConfig() bool {
	b, err := os.ReadFile(configPath())
	cfg = defaultConfig()
	if errors.Is(err, os.ErrNotExist) {
		return true
	}
	if err != nil {
		log.Fatalf("Could not read config.json: %v", err)
	}
	cfg.Token = ""
	if err := json.Unmarshal(b, &cfg); err != nil {
		log.Fatalf("[pc-remote] config.json is invalid: %v", err)
	}
	dirty := cfg.Token == ""
	if dirty {
		cfg.Token = randToken()
	}
	if cfg.FPS <= 0 {
		cfg.FPS = 12
	}
	if cfg.Quality <= 0 || cfg.Quality > 100 {
		cfg.Quality = 65
	}
	// Migrate the previous built-in stream settings to the hotspot-friendly
	// balanced profile. Explicit user presets continue to be preserved.
	if cfg.FPS == 12 && cfg.Quality == 65 && cfg.MaxW == 1600 {
		cfg.FPS, cfg.Quality, cfg.MaxW = 10, 60, 1280
		dirty = true
	}
	// 0.5.9.7 to 0.6.0.12 shipped (and migrated every config to) 30 fps at
	// JPEG quality 95 and native width. On a phone hotspot that is several
	// times more data than the link carries, so frames queued in the laptop's
	// send buffer and the phone showed the laptop seconds late. Move that exact
	// built-in profile (and the older ones) to the latency-safe default; a
	// profile the user picked by hand is left alone.
	if (cfg.FPS == 10 && cfg.Quality == 60 && cfg.MaxW == 1280) || (cfg.FPS == 30 && cfg.Quality == 95 && cfg.MaxW == 0) {
		cfg.FPS, cfg.Quality, cfg.MaxW = 30, jpegDefaultQuality, jpegDefaultMaxWidth
		dirty = true
	}
	return dirty
}

// auth wraps a handler with a constant-time token check. The token may arrive
// as ?t=, header X-Token, or the cookie set after a successful login — so the
// everyday URL is just https://<host>/ with nothing appended.
func auth(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		tok := r.URL.Query().Get("t")
		if tok == "" {
			tok = r.Header.Get("X-Token")
		}
		if tok == "" {
			if c, err := r.Cookie("pcr"); err == nil {
				tok = c.Value
			}
		}
		want := liveCfg().Token
		if subtle.ConstantTimeCompare([]byte(tok), []byte(want)) != 1 {
			// Browsers get the login page; API callers get a plain 401.
			if wantsHTML(r) {
				serveLogin(w, http.StatusUnauthorized)
				return
			}
			http.Error(w, "unauthorized", http.StatusUnauthorized)
			return
		}
		// Persist the token so navigations and the video/websocket requests authenticate
		// without re-appending ?t= everywhere. One year: this is a personal tool
		// on a personal phone, and re-typing the code on every visit defeats it.
		http.SetCookie(w, &http.Cookie{
			Name: "pcr", Value: want, Path: "/", HttpOnly: true,
			SameSite: http.SameSiteStrictMode, MaxAge: 365 * 24 * 3600,
		})
		next.ServeHTTP(w, r)
	})
}

// wantsHTML is true for top-level browser navigations (so we can answer with a
// login page instead of a bare 401).
func wantsHTML(r *http.Request) bool {
	if r.Method != http.MethodGet {
		return false
	}
	switch r.URL.Path {
	case "/input", "/screen", "/settings", "/ws", "/h264", "/jpeg", "/ping", "/files", "/power",
		"/radios", "/phone-link", "/phone-airplane", "/hotspot-reconnect":
		return false
	}
	return strings.Contains(r.Header.Get("Accept"), "text/html")
}

// serveLogin renders the code entry page: submitting it lands back on "/?t=..."
// which the auth wrapper accepts and turns into the long-lived cookie.
func serveLogin(w http.ResponseWriter, status int) {
	w.Header().Set("Content-Type", "text/html; charset=utf-8")
	w.WriteHeader(status)
	_, _ = io.WriteString(w, loginHTML)
}

const loginHTML = `<!doctype html>
<html lang="en"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>PC Remote</title>
<style>
 html,body{height:100%;margin:0;background:#0b1220;color:#e5e7eb;
   font-family:system-ui,-apple-system,"Segoe UI",Roboto,sans-serif;
   display:flex;align-items:center;justify-content:center}
 form{width:min(320px,86vw);text-align:center}
 h1{font-size:18px;font-weight:700;margin:0 0 4px}
 p{font-size:13px;color:#94a3b8;margin:0 0 18px}
 input{width:100%;height:48px;font-size:20px;text-align:center;letter-spacing:.18em;
   border-radius:10px;border:1px solid #1f2937;background:#111827;color:#e5e7eb;margin-bottom:12px}
 button{width:100%;height:46px;font-size:15px;font-weight:600;border:0;border-radius:10px;
   background:#3b82f6;color:#fff}
</style></head>
<body><form method="GET" action="/">
 <h1>PC Remote</h1><p>Enter the access code</p>
 <input name="t" inputmode="text" autocomplete="one-time-code" autocapitalize="off"
        autocorrect="off" spellcheck="false" autofocus placeholder="code">
 <button type="submit">Open</button>
</form></body></html>`

func screenHandler(w http.ResponseWriter, r *http.Request) {
	c := liveCfg()
	b := displayBounds(c.Monitor)
	w.Header().Set("Content-Type", "application/json")
	_ = json.NewEncoder(w).Encode(map[string]any{
		"width":    b.Dx(),
		"height":   b.Dy(),
		"monitor":  c.Monitor,
		"monitors": numDisplays(),
	})
}

// InputEvent is one action from the browser. Coordinates are normalized 0..1
// against the streamed image so they survive downscaling and DPI differences.
type InputEvent struct {
	Type   string  `json:"type"`   // move, down, up, click, dblclick, scroll, key, text
	X      float64 `json:"x"`      // 0..1
	Y      float64 `json:"y"`      // 0..1
	Button string  `json:"button"` // left, right, middle
	Delta  int     `json:"delta"`  // scroll amount (wheel notches)
	Key    string  `json:"key"`    // named key: Enter, Backspace, Tab, ArrowUp...
	Text   string  `json:"text"`   // literal text to type (unicode)
	Ctrl   bool    `json:"ctrl"`
	Alt    bool    `json:"alt"`
	Shift  bool    `json:"shift"`
	Meta   bool    `json:"meta"`
}

func inputHandler(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		http.Error(w, "POST only", http.StatusMethodNotAllowed)
		return
	}
	var ev InputEvent
	if err := json.NewDecoder(r.Body).Decode(&ev); err != nil {
		http.Error(w, "bad json", http.StatusBadRequest)
		return
	}
	injectInput(ev)
	w.WriteHeader(http.StatusNoContent)
}
