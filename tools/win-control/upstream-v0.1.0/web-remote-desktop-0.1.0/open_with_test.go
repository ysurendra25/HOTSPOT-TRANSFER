package main

import (
	"net/http/httptest"
	"net/url"
	"strings"
	"testing"
)

func TestOpenWithRejectsNonLocalMediaURLs(t *testing.T) {
	for _, mediaURL := range []string{
		"https://example.com/movie.mp4",
		"file:///C:/Videos/movie.mp4",
		"http://user:pass@192.168.0.4:43117/movie.mp4",
		"http://192.0.2.1:43117/movie.mp4",
	} {
		req := httptest.NewRequest("GET", "/open-with?url="+url.QueryEscape(mediaURL), nil)
		res := httptest.NewRecorder()
		openWithHandler(res, req)
		if res.Code != 400 {
			t.Fatalf("URL %q: got %d, want 400", mediaURL, res.Code)
		}
	}
}

func TestPlayerChooserEscapesMediaAndApplicationNames(t *testing.T) {
	mediaURL := `http://192.168.0.4:43117/movie.mp4?code=123456&title="sample"`
	res := httptest.NewRecorder()
	err := renderPlayerChooser(res, mediaURL, []mediaPlayer{{ID: "test-player", Name: `<img src=x onerror="alert(1)">`, Path: `C:\Program Files\player.exe`}})
	if err != nil {
		t.Fatal(err)
	}
	body := res.Body.String()
	if strings.Contains(body, `<img src=x`) || !strings.Contains(body, `&lt;img`) || !strings.Contains(body, `&amp;title=`) {
		t.Fatalf("player chooser did not safely escape its values: %s", body)
	}
}

func TestOpenPlayerRejectsAPlayerOutsideDiscoveredList(t *testing.T) {
	form := url.Values{"player": {"not-installed"}, "url": {"http://192.168.0.4:43117/movie.mp4"}}
	req := httptest.NewRequest("POST", "/open-player", strings.NewReader(form.Encode()))
	req.Header.Set("Content-Type", "application/x-www-form-urlencoded")
	res := httptest.NewRecorder()
	openPlayerHandler(res, req)
	if res.Code != 404 {
		t.Fatalf("got %d, want 404", res.Code)
	}
}
