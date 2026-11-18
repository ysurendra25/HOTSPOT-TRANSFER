package main

import (
	"net"
	"net/http"
	"net/url"
	"strings"
)

func openVLCHandler(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet {
		http.Error(w, "GET only", http.StatusMethodNotAllowed)
		return
	}
	mediaURL := r.URL.Query().Get("url")
	parsed, err := url.ParseRequestURI(mediaURL)
	if err != nil || len(mediaURL) > 4096 || (parsed.Scheme != "http" && parsed.Scheme != "https") || parsed.Hostname() == "" || parsed.User != nil || !privateMediaHost(parsed.Hostname()) {
		http.Error(w, "PocketBridge can open only a media URL on your local network.", http.StatusBadRequest)
		return
	}
	if err := launchVLC(mediaURL); err != nil {
		http.Error(w, err.Error(), http.StatusNotFound)
		return
	}
	w.Header().Set("Content-Type", "text/html; charset=utf-8")
	w.Header().Set("Cache-Control", "no-store")
	_, _ = w.Write([]byte("<!doctype html><meta name=viewport content='width=device-width'><title>PocketBridge</title><p>Opening the original stream in VLC. You can close this tab.</p>"))
}

func privateMediaHost(host string) bool {
	if strings.EqualFold(host, "localhost") {
		return true
	}
	ip := net.ParseIP(host)
	return ip != nil && (ip.IsPrivate() || ip.IsLoopback())
}
