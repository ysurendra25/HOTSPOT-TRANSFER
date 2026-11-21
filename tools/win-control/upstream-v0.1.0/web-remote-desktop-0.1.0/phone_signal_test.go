package main

import (
	"io"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
)

type signalTransport func(*http.Request) (*http.Response, error)

func (f signalTransport) RoundTrip(r *http.Request) (*http.Response, error) { return f(r) }

func TestOfflinePairing(t *testing.T) {
	previous := phoneClient
	defer func() { phoneClient = previous }()
	calls := 0
	phoneClient = &http.Client{Transport: signalTransport(func(r *http.Request) (*http.Response, error) {
		calls++
		if r.URL.Host != "10.255.251.221:43117" || r.URL.Query().Get("code") != "123456" {
			t.Fatal("unexpected destination")
		}
		if r.URL.Path != "/api/laptop-cast" && r.URL.Path != "/api/camera/status" {
			t.Fatal("wrong route")
		}
		return &http.Response{StatusCode: 200, Header: make(http.Header), Body: io.NopCloser(strings.NewReader("{}"))}, nil
	})}
	for _, tc := range []struct {
		feature, phone, origin string
		status                 int
	}{
		{"screen", "10.255.251.221", "http://127.0.0.1:7070", 200},
		{"camera", "10.255.251.221", "http://127.0.0.1:7070", 200},
		{"camera", "127.0.0.1", "http://127.0.0.1:7070", 400},
		{"camera", "example.com", "http://127.0.0.1:7070", 400},
		{"screen", "10.255.251.221", "http://example.com", 403},
	} {
		body := "{\"feature\":\"" + tc.feature + "\",\"action\":\"status\",\"data\":{\"action\":\"poll\"},\"phone\":\"http://" + tc.phone + ":43117\",\"code\":\"123456\"}"
		req := httptest.NewRequest("POST", "http://127.0.0.1:7070/phone-signal", strings.NewReader(body))
		req.RemoteAddr = "127.0.0.1:40000"
		req.Header.Set("Origin", tc.origin)
		rec := httptest.NewRecorder()
		newMux().ServeHTTP(rec, req)
		if rec.Code != tc.status {
			t.Errorf("%s %s got %d want %d", tc.feature, tc.phone, rec.Code, tc.status)
		}
	}
	if calls != 2 {
		t.Fatal("unexpected upstream request count", calls)
	}
}
