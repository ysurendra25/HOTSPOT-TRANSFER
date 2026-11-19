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
	"strconv"
	"strings"
	"sync"
	"time"
)

func defaultShareDir() string {
	home, err := os.UserHomeDir()
	if err != nil {
		return "."
	}
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

// Listing limits. A shared folder can be a whole drive: walking it without a
// bound took longer than the phone was willing to wait, which is why laptop
// files "often" failed to load. The walk now stops at a file cap or a time
// budget, says so in the reply, and the phone pages through the result.
const (
	maxListedFiles   = 20000
	listTimeBudget   = 4 * time.Second
	defaultPageSize  = 500
	maxPageSize      = 2000
	listingCacheLife = 20 * time.Second
)

// Folders that are never what someone browsing from a phone wants, and that
// can hold hundreds of thousands of entries.
var skippedDirNames = map[string]bool{
	"$recycle.bin": true, "system volume information": true, "node_modules": true,
	"appdata": true, "windows": true, "programdata": true, "$windows.~bt": true,
	"$windows.~ws": true, "recovery": true, "config.msi": true,
}

type fileListing struct {
	root      string
	files     []sharedFile
	truncated bool
	reason    string
	built     time.Time
}

var (
	listingMu    sync.Mutex
	listingCache *fileListing
)

// buildListing walks root once, newest files first.
func buildListing(root string, limit int, budget time.Duration) *fileListing {
	deadline := time.Now().Add(budget)
	listing := &fileListing{root: root, files: make([]sharedFile, 0, 256), built: time.Now()}
	_ = filepath.WalkDir(root, func(target string, entry os.DirEntry, walkErr error) error {
		if walkErr != nil {
			// An unreadable subfolder (permissions) must not hide everything else.
			if entry != nil && entry.IsDir() && target != root {
				return filepath.SkipDir
			}
			return nil
		}
		if target == root {
			return nil
		}
		name := entry.Name()
		if strings.HasPrefix(name, ".") || strings.HasPrefix(name, "~$") {
			if entry.IsDir() {
				return filepath.SkipDir
			}
			return nil
		}
		if entry.Type()&os.ModeSymlink != 0 {
			return nil
		}
		if entry.IsDir() {
			if skippedDirNames[strings.ToLower(name)] {
				return filepath.SkipDir
			}
			if time.Now().After(deadline) {
				listing.truncated, listing.reason = true, "time"
				return filepath.SkipAll
			}
			return nil
		}
		info, infoErr := entry.Info()
		if infoErr != nil || !info.Mode().IsRegular() {
			return nil
		}
		relative, relErr := filepath.Rel(root, target)
		if relErr != nil {
			return nil
		}
		relative = filepath.ToSlash(relative)
		listing.files = append(listing.files, sharedFile{
			Name: name, Path: relative, Size: info.Size(), Modified: info.ModTime().UnixMilli(),
			MIME: contentTypeForName(name), URL: "/file/" + urlPathEscape(relative),
		})
		if len(listing.files) >= limit {
			listing.truncated, listing.reason = true, "count"
			return filepath.SkipAll
		}
		if len(listing.files)%256 == 0 && time.Now().After(deadline) {
			listing.truncated, listing.reason = true, "time"
			return filepath.SkipAll
		}
		return nil
	})
	sort.SliceStable(listing.files, func(i, j int) bool { return listing.files[i].Modified > listing.files[j].Modified })
	return listing
}

// currentListing reuses a recent walk of the same folder, so fetching page 2
// does not walk the disk again. ?refresh=1 forces a new walk.
func currentListing(root string, refresh bool) *fileListing {
	listingMu.Lock()
	defer listingMu.Unlock()
	if !refresh && listingCache != nil && listingCache.root == root && time.Since(listingCache.built) < listingCacheLife {
		return listingCache
	}
	listingCache = buildListing(root, maxListedFiles, listTimeBudget)
	return listingCache
}

func filterSharedFiles(files []sharedFile, query string) []sharedFile {
	query = strings.ToLower(strings.TrimSpace(query))
	if query == "" {
		return files
	}
	filtered := make([]sharedFile, 0)
	for _, item := range files {
		if strings.Contains(strings.ToLower(item.Name+" "+item.Path), query) {
			filtered = append(filtered, item)
		}
	}
	return filtered
}

func queryInt(r *http.Request, name string, fallback, lo, hi int) int {
	v, err := strconv.Atoi(r.URL.Query().Get(name))
	if err != nil {
		return fallback
	}
	return clampInt(v, lo, hi)
}

func writeFilesError(w http.ResponseWriter, status int, message string) {
	w.Header().Set("Content-Type", "application/json; charset=utf-8")
	w.Header().Set("Cache-Control", "no-store")
	w.WriteHeader(status)
	_ = json.NewEncoder(w).Encode(map[string]any{"error": message})
}

func filesHandler(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet {
		writeFilesError(w, http.StatusMethodNotAllowed, "GET only")
		return
	}
	shareDir := liveCfg().ShareDir
	root, err := filepath.Abs(shareDir)
	if err != nil {
		writeFilesError(w, http.StatusInternalServerError, "The shared laptop folder setting is invalid.")
		return
	}
	if info, statErr := os.Stat(root); statErr != nil || !info.IsDir() {
		writeFilesError(w, http.StatusNotFound, "The shared laptop folder is unavailable: "+root+". Enter another folder path on the phone.")
		return
	}
	listing := currentListing(root, r.URL.Query().Get("refresh") == "1")
	query := strings.TrimSpace(r.URL.Query().Get("q"))
	files := filterSharedFiles(listing.files, query)
	offset := queryInt(r, "offset", 0, 0, maxListedFiles)
	limit := queryInt(r, "limit", maxListedFiles, 1, maxListedFiles)
	// Old phones ask without paging and expect everything; new ones page.
	if r.URL.Query().Get("limit") != "" {
		limit = clampInt(limit, 1, maxPageSize)
	}
	from := minInt(offset, len(files))
	to := minInt(from+limit, len(files))
	w.Header().Set("Content-Type", "application/json; charset=utf-8")
	w.Header().Set("Cache-Control", "no-store")
	_ = json.NewEncoder(w).Encode(map[string]any{
		"files":       files[from:to],
		"total":       len(files),
		"offset":      from,
		"nextOffset":  to,
		"hasMore":     to < len(files),
		"truncated":   listing.truncated,
		"truncatedBy": listing.reason,
		"root":        root,
		"query":       strings.ToLower(query),
	})
}

func fileHandler(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet && r.Method != http.MethodHead {
		http.Error(w, "GET only", http.StatusMethodNotAllowed)
		return
	}
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
	if err != nil || name == "" || strings.HasPrefix(name, "/") || clean == "/" || strings.HasPrefix(clean, "/../") {
		http.Error(w, "invalid file", http.StatusBadRequest)
		return
	}
	root, err := filepath.Abs(liveCfg().ShareDir)
	if err != nil {
		http.Error(w, "invalid share folder", http.StatusInternalServerError)
		return
	}
	target := filepath.Join(root, filepath.FromSlash(strings.TrimPrefix(clean, "/")))
	resolvedRoot, rootErr := filepath.EvalSymlinks(root)
	resolvedTarget, targetErr := filepath.EvalSymlinks(target)
	if rootErr != nil || targetErr != nil {
		http.NotFound(w, r)
		return
	}
	relative, relErr := filepath.Rel(resolvedRoot, resolvedTarget)
	if relErr != nil || relative == ".." || strings.HasPrefix(relative, ".."+string(filepath.Separator)) {
		http.Error(w, "invalid file", http.StatusForbidden)
		return
	}
	target = resolvedTarget
	info, err := os.Stat(target)
	if err != nil || !info.Mode().IsRegular() {
		http.NotFound(w, r)
		return
	}
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
	for i := range parts {
		parts[i] = url.PathEscape(parts[i])
	}
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
	if known[ext] != "" {
		return known[ext]
	}
	if detected := mime.TypeByExtension(ext); detected != "" {
		return detected
	}
	return "application/octet-stream"
}
func urlPathUnescape(s string) (string, error) { return url.PathUnescape(s) }
func urlQueryEscape(s string) string           { return url.QueryEscape(s) }
