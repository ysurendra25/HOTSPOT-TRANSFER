package main

import (
	"encoding/json"
	"mime"
	"net/http"
	"net/url"
	"os"
	"path"
	"path/filepath"
	"sort"
	"strings"
)

func defaultShareDir() string {
	home, err := os.UserHomeDir()
	if err != nil { return "." }
	return filepath.Join(home, "Downloads")
}

type sharedFile struct {
	Name     string `json:"name"`
	Path     string `json:"path"`
	Size     int64  `json:"size"`
	Modified int64  `json:"modified"`
	MIME     string `json:"mime"`
	URL      string `json:"url"`
}

func filesHandler(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet { http.Error(w, "GET only", http.StatusMethodNotAllowed); return }
	root, err := filepath.Abs(liveCfg().ShareDir)
	if err != nil { http.Error(w, "invalid share folder", http.StatusInternalServerError); return }
	if info, statErr := os.Stat(root); statErr != nil || !info.IsDir() { http.Error(w, "share folder unavailable", http.StatusNotFound); return }
	result := make([]sharedFile, 0, 64)
	err = filepath.WalkDir(root, func(target string, entry os.DirEntry, walkErr error) error {
		if walkErr != nil { return nil }
		if target == root { return nil }
		if strings.HasPrefix(entry.Name(), ".") {
			if entry.IsDir() { return filepath.SkipDir }
			return nil
		}
		if entry.Type()&os.ModeSymlink != 0 { return nil }
		if entry.IsDir() { return nil }
		info, infoErr := entry.Info()
		if infoErr != nil || !info.Mode().IsRegular() { return nil }
		relative, relErr := filepath.Rel(root, target)
		if relErr != nil { return nil }
		relative = filepath.ToSlash(relative)
		contentType := contentTypeForName(entry.Name())
		result = append(result, sharedFile{Name: entry.Name(), Path: relative, Size: info.Size(), Modified: info.ModTime().UnixMilli(), MIME: contentType, URL: "/file/" + urlPathEscape(relative)})
		if len(result) >= 5000 { return filepath.SkipAll }
		return nil
	})
	if err != nil { http.Error(w, "share folder unavailable", http.StatusNotFound); return }
	sort.SliceStable(result, func(i, j int) bool { return result[i].Modified > result[j].Modified })
	w.Header().Set("Content-Type", "application/json; charset=utf-8")
	w.Header().Set("Cache-Control", "no-store")
	_ = json.NewEncoder(w).Encode(map[string]any{"files": result})
}

func fileHandler(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet && r.Method != http.MethodHead { http.Error(w, "GET only", http.StatusMethodNotAllowed); return }
	escapedPath := r.URL.EscapedPath()
	if !strings.HasPrefix(escapedPath, "/file/") {
		http.Error(w, "invalid file", http.StatusBadRequest)
		return
	}
	name, err := urlPathUnescape(strings.TrimPrefix(escapedPath, "/file/"))
	name = strings.ReplaceAll(name, "\\", "/")
	for _, segment := range strings.Split(name, "/") {
		if segment == ".." || segment == "." {
			http.Error(w, "invalid file", http.StatusBadRequest)
			return
		}
	}
	clean := path.Clean("/" + name)
	if err != nil || name == "" || strings.HasPrefix(name, "/") || clean == "/" || strings.HasPrefix(clean, "/../") { http.Error(w, "invalid file", http.StatusBadRequest); return }
	root, err := filepath.Abs(liveCfg().ShareDir); if err != nil { http.Error(w, "invalid share folder", http.StatusInternalServerError); return }
	target := filepath.Join(root, filepath.FromSlash(strings.TrimPrefix(clean, "/")))
	resolvedRoot, rootErr := filepath.EvalSymlinks(root)
	resolvedTarget, targetErr := filepath.EvalSymlinks(target)
	if rootErr != nil || targetErr != nil { http.NotFound(w, r); return }
	relative, relErr := filepath.Rel(resolvedRoot, resolvedTarget)
	if relErr != nil || relative == ".." || strings.HasPrefix(relative, ".."+string(filepath.Separator)) { http.Error(w, "invalid file", http.StatusForbidden); return }
	target = resolvedTarget
	info, err := os.Stat(target); if err != nil || !info.Mode().IsRegular() { http.NotFound(w, r); return }
	w.Header().Set("X-Content-Type-Options", "nosniff")
	contentType := contentTypeForName(target)
	w.Header().Set("Content-Type", contentType)
	if strings.HasPrefix(contentType, "video/") || strings.HasPrefix(contentType, "audio/") || strings.HasPrefix(contentType, "image/") || contentType == "application/pdf" {
		w.Header().Set("Content-Disposition", "inline; filename*=UTF-8''"+urlQueryEscape(filepath.Base(target)))
	} else {
		w.Header().Set("Content-Disposition", "attachment; filename*=UTF-8''"+urlQueryEscape(filepath.Base(target)))
	}
	http.ServeFile(w, r, target)
}

func urlPathEscape(s string) string {
	parts := strings.Split(filepath.ToSlash(s), "/")
	for i := range parts { parts[i] = url.PathEscape(parts[i]) }
	return strings.Join(parts, "/")
}
func contentTypeForName(name string) string {
	ext := strings.ToLower(filepath.Ext(name))
	known := map[string]string{
		".mp4": "video/mp4", ".m4v": "video/mp4", ".mov": "video/quicktime", ".mkv": "video/x-matroska", ".webm": "video/webm", ".avi": "video/x-msvideo", ".3gp": "video/3gpp",
		".mp3": "audio/mpeg", ".m4a": "audio/mp4", ".aac": "audio/aac", ".wav": "audio/wav", ".ogg": "audio/ogg", ".opus": "audio/ogg", ".flac": "audio/flac",
		".jpg": "image/jpeg", ".jpeg": "image/jpeg", ".png": "image/png", ".gif": "image/gif", ".webp": "image/webp", ".heic": "image/heic", ".heif": "image/heif",
		".pdf": "application/pdf", ".apk": "application/vnd.android.package-archive",
	}
	if known[ext] != "" { return known[ext] }
	if detected := mime.TypeByExtension(ext); detected != "" { return detected }
	return "application/octet-stream"
}
func urlPathUnescape(s string) (string, error) { return url.PathUnescape(s) }
func urlQueryEscape(s string) string { return url.QueryEscape(s) }
