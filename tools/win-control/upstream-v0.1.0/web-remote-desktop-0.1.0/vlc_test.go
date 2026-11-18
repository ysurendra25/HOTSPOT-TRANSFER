package main

import (
	"net/http/httptest"
	"testing"
)

func TestOpenVLCRejectsNonLocalURLs(t *testing.T) {
	for _, mediaURL := range []string{
		"https://example.com/movie.mp4",
		"file:///C:/Users/example/movie.mp4",
		"http://user:pass@192.168.0.4:43117/video",
		"http://192.0.2.1:43117/video",
	} {
		req := httptest.NewRequest("GET", "/open-vlc?url="+mediaURL, nil)
		res := httptest.NewRecorder()
		openVLCHandler(res, req)
		if res.Code != 400 {
			t.Fatalf("URL %q: got %d, want 400", mediaURL, res.Code)
		}
	}
}
