package main

import (
	"encoding/json"
	"net/http"
	"net/url"
	"os"
	"path/filepath"
	"strings"
)

func defaultShareDir() string {
	home, err := os.UserHomeDir()
	if err != nil { return "." }
	return filepath.Join(home, "Downloads")
}

type sharedFile struct { Name string `json:"name"`; Size int64 `json:"size"`; Modified int64 `json:"modified"`; URL string `json:"url"` }

func filesHandler(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet { http.Error(w, "GET only", http.StatusMethodNotAllowed); return }
	root, err := filepath.Abs(liveCfg().ShareDir)
	if err != nil { http.Error(w, "invalid share folder", http.StatusInternalServerError); return }
	entries, err := os.ReadDir(root)
	if err != nil { http.Error(w, "share folder unavailable", http.StatusNotFound); return }
	result := make([]sharedFile, 0, len(entries))
	for _, entry := range entries {
		if entry.IsDir() || strings.HasPrefix(entry.Name(), ".") { continue }
		info, err := entry.Info(); if err != nil || !info.Mode().IsRegular() { continue }
		result = append(result, sharedFile{Name: entry.Name(), Size: info.Size(), Modified: info.ModTime().UnixMilli(), URL: "/file/" + urlPathEscape(entry.Name())})
		if len(result) >= 1000 { break }
	}
	w.Header().Set("Content-Type", "application/json; charset=utf-8")
	w.Header().Set("Cache-Control", "no-store")
	_ = json.NewEncoder(w).Encode(map[string]any{"files": result})
}

func fileHandler(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet && r.Method != http.MethodHead { http.Error(w, "GET only", http.StatusMethodNotAllowed); return }
	name, err := urlPathUnescape(strings.TrimPrefix(r.URL.Path, "/file/"))
	if err != nil || name == "" || filepath.Base(name) != name || strings.ContainsAny(name, `/\\`) { http.Error(w, "invalid file", http.StatusBadRequest); return }
	root, err := filepath.Abs(liveCfg().ShareDir); if err != nil { http.Error(w, "invalid share folder", http.StatusInternalServerError); return }
	target := filepath.Join(root, name)
	info, err := os.Stat(target); if err != nil || !info.Mode().IsRegular() { http.NotFound(w, r); return }
	w.Header().Set("X-Content-Type-Options", "nosniff")
	w.Header().Set("Content-Disposition", "attachment; filename*=UTF-8''"+urlQueryEscape(name))
	http.ServeFile(w, r, target)
}

func urlPathEscape(s string) string { return url.PathEscape(s) }
func urlPathUnescape(s string) (string, error) { return url.PathUnescape(s) }
func urlQueryEscape(s string) string { return url.QueryEscape(s) }
