package main

import (
	"encoding/json"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"testing"
)

func withShareDir(t *testing.T, dir string) {
	t.Helper()
	cfgMu.Lock()
	old := cfg
	cfg = defaultConfig()
	cfg.ShareDir = dir
	cfgMu.Unlock()
	t.Cleanup(func() { cfgMu.Lock(); cfg = old; cfgMu.Unlock() })
}

func TestFilesHandlerListsNestedMedia(t *testing.T) {
	root := t.TempDir()
	withShareDir(t, root)
	mediaDir := filepath.Join(root, "Trips")
	if err := os.Mkdir(mediaDir, 0700); err != nil { t.Fatal(err) }
	if err := os.WriteFile(filepath.Join(mediaDir, "clip.mp4"), []byte("video"), 0600); err != nil { t.Fatal(err) }
	req := httptest.NewRequest("GET", "/files", nil)
	res := httptest.NewRecorder()
	filesHandler(res, req)
	if res.Code != 200 { t.Fatalf("status %d: %s", res.Code, res.Body.String()) }
	var body struct { Files []sharedFile `json:"files"` }
	if err := json.Unmarshal(res.Body.Bytes(), &body); err != nil { t.Fatal(err) }
	if len(body.Files) != 1 { t.Fatalf("got %d files", len(body.Files)) }
	got := body.Files[0]
	if got.Path != "Trips/clip.mp4" || got.MIME != "video/mp4" || got.URL != "/file/Trips/clip.mp4" {
		t.Fatalf("unexpected nested file metadata: %+v", got)
	}
}

func TestFilesHandlerSearchesNamesAndRelativePaths(t *testing.T) {
	root := t.TempDir()
	withShareDir(t, root)
	if err := os.Mkdir(filepath.Join(root, "Movies"), 0700); err != nil { t.Fatal(err) }
	if err := os.WriteFile(filepath.Join(root, "Movies", "Mandaadi.mp4"), []byte("video"), 0600); err != nil { t.Fatal(err) }
	if err := os.WriteFile(filepath.Join(root, "other.txt"), []byte("text"), 0600); err != nil { t.Fatal(err) }
	req := httptest.NewRequest("GET", "/files?q=mandaadi", nil)
	res := httptest.NewRecorder()
	filesHandler(res, req)
	if res.Code != 200 { t.Fatalf("status %d: %s", res.Code, res.Body.String()) }
	var body struct { Files []sharedFile `json:"files"`; Query string `json:"query"` }
	if err := json.Unmarshal(res.Body.Bytes(), &body); err != nil { t.Fatal(err) }
	if len(body.Files) != 1 || body.Files[0].Name != "Mandaadi.mp4" || body.Query != "mandaadi" {
		t.Fatalf("unexpected search result: %+v (query %q)", body.Files, body.Query)
	}
}

func TestFileHandlerStreamsMediaWithRange(t *testing.T) {
	root := t.TempDir()
	withShareDir(t, root)
	content := []byte("0123456789")
	if err := os.WriteFile(filepath.Join(root, "clip.mp4"), content, 0600); err != nil { t.Fatal(err) }
	req := httptest.NewRequest("GET", "/file/clip.mp4", nil)
	req.Header.Set("Range", "bytes=2-5")
	res := httptest.NewRecorder()
	fileHandler(res, req)
	if res.Code != 206 { t.Fatalf("status %d: %s", res.Code, res.Body.String()) }
	if got := res.Header().Get("Content-Type"); !strings.HasPrefix(got, "video/mp4") { t.Fatalf("content type %q", got) }
	if got := res.Header().Get("Content-Disposition"); !strings.HasPrefix(got, "inline;") { t.Fatalf("disposition %q", got) }
	if res.Body.String() != "2345" { t.Fatalf("range body %q", res.Body.String()) }
}

func TestFileHandlerRejectsTraversal(t *testing.T) {
	root := t.TempDir()
	withShareDir(t, root)
	req := httptest.NewRequest("GET", "/file/%2E%2E%2Fsecret.txt", nil)
	res := httptest.NewRecorder()
	fileHandler(res, req)
	if res.Code < 400 { t.Fatalf("expected rejection, got %d", res.Code) }
}

func TestFileHandlerSupportsEscapedNames(t *testing.T) {
	root := t.TempDir()
	withShareDir(t, root)
	if err := os.WriteFile(filepath.Join(root, "100% video.mp4"), []byte("video"), 0600); err != nil {
		t.Fatal(err)
	}
	req := httptest.NewRequest("GET", "/file/100%25%20video.mp4", nil)
	res := httptest.NewRecorder()
	fileHandler(res, req)
	if res.Code != 200 || res.Body.String() != "video" {
		t.Fatalf("status %d, body %q", res.Code, res.Body.String())
	}
}
