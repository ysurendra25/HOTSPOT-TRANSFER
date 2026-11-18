package main

import (
	"html/template"
	"net/http"
	"net/url"
)

type mediaPlayer struct {
	ID   string
	Name string
	Path string
}

var playerChooserTemplate = template.Must(template.New("player-chooser").Parse(`<!doctype html>
<html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<meta name="color-scheme" content="dark"><title>Open video with · PocketBridge</title>
<style>
:root{color-scheme:dark;background:#091318;color:#e7f0f2;font:15px/1.5 system-ui,-apple-system,"Segoe UI",sans-serif}
*{box-sizing:border-box}body{margin:0;padding:24px;min-height:100vh;display:grid;place-items:center}
main{width:min(520px,100%);padding:24px;background:#111f25;border:1px solid #293b42;border-radius:20px}
h1{font-size:22px;margin:0 0 6px}p{color:#9aafb7;margin:0 0 20px}.players{display:grid;gap:10px}
form{margin:0}button{width:100%;padding:14px 16px;border:1px solid #293b42;border-radius:12px;background:#172930;color:#e7f0f2;text-align:left;font:inherit;cursor:pointer}
button:hover,button:focus-visible{outline:2px solid #24b68d;outline-offset:2px}button strong,button span{display:block}button span{font-size:12px;color:#9aafb7;margin-top:3px}
.empty{padding:14px;border:1px dashed #3a4f57;border-radius:12px;color:#9aafb7}
</style></head><body><main><h1>Choose a player</h1><p>Pick an installed player on this laptop. PocketBridge streams the file over your local network.</p>
<div class="players">{{if .Players}}{{range .Players}}<form method="post" action="/open-player"><input type="hidden" name="url" value="{{$.MediaURL}}"><button type="submit" name="player" value="{{.ID}}"><strong>{{.Name}}</strong><span>Play this local stream</span></button></form>{{end}}{{else}}<div class="empty">No supported video players were found. Install VLC, mpv, MPC-HC, MPC-BE, PotPlayer, or Windows Media Player, then restart the helper.</div>{{end}}</div>
</main></body></html>`))

func localMediaURL(raw string) (*url.URL, bool) {
	parsed, err := url.ParseRequestURI(raw)
	if err != nil || len(raw) > 4096 || (parsed.Scheme != "http" && parsed.Scheme != "https") || parsed.Hostname() == "" || parsed.User != nil {
		return nil, false
	}
	if !privateMediaHost(parsed.Hostname()) {
		return nil, false
	}
	return parsed, true
}

func openWithHandler(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet {
		http.Error(w, "GET only", http.StatusMethodNotAllowed)
		return
	}
	mediaURL := r.URL.Query().Get("url")
	if _, ok := localMediaURL(mediaURL); !ok {
		http.Error(w, "PocketBridge can open only a media URL on your local network.", http.StatusBadRequest)
		return
	}
	w.Header().Set("Cache-Control", "no-store")
	w.Header().Set("Content-Type", "text/html; charset=utf-8")
	if err := playerChooserTemplate.Execute(w, struct {
		MediaURL string
		Players  []mediaPlayer
	}{mediaURL, discoverMediaPlayers()}); err != nil {
		http.Error(w, "Could not prepare the player list.", http.StatusInternalServerError)
	}
}

func openPlayerHandler(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		http.Error(w, "POST only", http.StatusMethodNotAllowed)
		return
	}
	r.Body = http.MaxBytesReader(w, r.Body, 8192)
	if err := r.ParseForm(); err != nil {
		http.Error(w, "invalid player request", http.StatusBadRequest)
		return
	}
	mediaURL := r.FormValue("url")
	if _, ok := localMediaURL(mediaURL); !ok {
		http.Error(w, "PocketBridge can open only a media URL on your local network.", http.StatusBadRequest)
		return
	}
	requestedID := r.FormValue("player")
	for _, player := range discoverMediaPlayers() {
		if player.ID != requestedID {
			continue
		}
		if err := launchMediaPlayer(player, mediaURL); err != nil {
			http.Error(w, "Could not start "+player.Name+": "+err.Error(), http.StatusInternalServerError)
			return
		}
		w.Header().Set("Cache-Control", "no-store")
		w.Header().Set("Content-Type", "text/html; charset=utf-8")
		_, _ = w.Write([]byte("<!doctype html><meta name=viewport content='width=device-width'><title>PocketBridge</title><p>Opening the video in " + template.HTMLEscapeString(player.Name) + ". You can close this tab.</p>"))
		return
	}
	http.Error(w, "That player is no longer available. Refresh the chooser and try again.", http.StatusNotFound)
}

func renderPlayerChooser(w http.ResponseWriter, mediaURL string, players []mediaPlayer) error {
	w.Header().Set("Cache-Control", "no-store")
	w.Header().Set("Content-Type", "text/html; charset=utf-8")
	return playerChooserTemplate.Execute(w, struct {
		MediaURL string
		Players  []mediaPlayer
	}{mediaURL, players})
}
