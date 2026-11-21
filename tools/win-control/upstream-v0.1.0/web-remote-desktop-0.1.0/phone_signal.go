package main

import (
	"bytes"
	"encoding/json"
	"io"
	"net"
	"net/http"
	"net/netip"
	"net/url"
	"regexp"
	"time"
)

var pairingDigits = regexp.MustCompile("^[0-9]{6}$")
var phoneClient = &http.Client{Transport: &http.Transport{Proxy: nil}, Timeout: 35 * time.Second,
	CheckRedirect: func(*http.Request, []*http.Request) error { return http.ErrUseLastResponse }}

// The local capture window can signal one explicitly paired phone. Only the
// fixed PocketBridge port and its screen/camera signaling routes are reachable.
func phoneSignal(w http.ResponseWriter, r *http.Request) {
	host, _, _ := net.SplitHostPort(r.Host)
	peer, _, _ := net.SplitHostPort(r.RemoteAddr)
	if !net.ParseIP(peer).IsLoopback() || host != "127.0.0.1" || r.Header.Get("Origin") != "http://"+r.Host || r.Method != "POST" {
		http.Error(w, "Open the capture workspace on this laptop.", 403)
		return
	}
	var in struct {
		Phone, Code, Feature, Action, SDP string
		Data                              json.RawMessage
	}
	if json.NewDecoder(http.MaxBytesReader(w, r.Body, 256*1024)).Decode(&in) != nil || !pairingDigits.MatchString(in.Code) {
		http.Error(w, "Enter the current six-digit phone code.", 400)
		return
	}
	u, err := url.Parse(in.Phone)
	if err != nil || u.Scheme != "http" || u.Port() != "43117" || u.User != nil || (u.Path != "" && u.Path != "/") || u.RawQuery != "" || u.Fragment != "" {
		http.Error(w, "Paste the hotspot link from Connect.", 400)
		return
	}
	ip, err := netip.ParseAddr(u.Hostname())
	if err != nil || !ip.Is4() || !(ip.IsPrivate() || ip.IsLinkLocalUnicast() || netip.MustParsePrefix("192.0.0.0/24").Contains(ip)) {
		http.Error(w, "Use the phone's local IPv4 address.", 400)
		return
	}
	path, method := "", "POST"
	var body []byte
	switch in.Feature {
	case "camera":
		switch in.Action {
		case "status":
			method = "GET"
		case "offer":
			body, _ = json.Marshal(map[string]string{"sdp": in.SDP})
		case "stop":
		default:
			http.Error(w, "Unknown camera action.", 400)
			return
		}
		path = "/api/camera/" + in.Action
	case "screen":
		var action struct{ Action string }
		if json.Unmarshal(in.Data, &action) != nil {
			http.Error(w, "Invalid screen request.", 400)
			return
		}
		switch action.Action {
		case "start", "poll", "answer", "stop", "frame":
		default:
			http.Error(w, "Unknown presenter action.", 400)
			return
		}
		body = in.Data
		path = "/api/laptop-cast"
	default:
		http.Error(w, "Unknown capture feature.", 400)
		return
	}
	dest := url.URL{Scheme: "http", Host: net.JoinHostPort(ip.String(), "43117"), Path: path, RawQuery: "code=" + in.Code}
	req, err := http.NewRequestWithContext(r.Context(), method, dest.String(), bytes.NewReader(body))
	if err != nil {
		http.Error(w, "Invalid request.", 400)
		return
	}
	req.Header.Set("Content-Type", "application/json")
	resp, err := phoneClient.Do(req)
	if err != nil {
		http.Error(w, "Phone unreachable. Check its hotspot link and keep PocketBridge sharing.", 502)
		return
	}
	defer resp.Body.Close()
	if resp.StatusCode >= 300 && resp.StatusCode < 400 {
		http.Error(w, "Unexpected phone response.", 502)
		return
	}
	w.Header().Set("Content-Type", "application/json")
	w.Header().Set("Cache-Control", "no-store")
	w.WriteHeader(resp.StatusCode)
	_, _ = io.Copy(w, io.LimitReader(resp.Body, 512*1024))
}
