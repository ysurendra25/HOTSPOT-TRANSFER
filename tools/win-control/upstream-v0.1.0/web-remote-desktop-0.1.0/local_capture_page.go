package main

import (
	"net"
	"net/http"
)

// A local tab supplies Edge's secure capture context. These static pages
// carry no credentials and do not grant access to native helper actions.
func localCapturePage(asset string) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		host, _, err := net.SplitHostPort(r.Host)
		if err != nil {
			host = r.Host
		}
		client, _, err := net.SplitHostPort(r.RemoteAddr)
		if err != nil || !net.ParseIP(client).IsLoopback() || (host != "localhost" && !net.ParseIP(host).IsLoopback()) {
			http.Error(w, "Open this capture page on the laptop.", http.StatusForbidden)
			return
		}
		if r.Method != http.MethodGet && r.Method != http.MethodHead {
			w.Header().Set("Allow", "GET, HEAD")
			w.WriteHeader(http.StatusMethodNotAllowed)
			return
		}
		page, err := webFS.ReadFile("web/" + asset)
		if err != nil {
			http.NotFound(w, r)
			return
		}
		w.Header().Set("Content-Type", "text/html; charset=utf-8")
		if asset == "capture-signal.js" {
			w.Header().Set("Content-Type", "text/javascript; charset=utf-8")
		}
		w.Header().Set("Cache-Control", "no-store")
		w.Header().Set("Referrer-Policy", "no-referrer")
		w.Header().Set("X-Frame-Options", "DENY")
		if r.Method == http.MethodGet {
			_, _ = w.Write(page)
		}
	}
}
