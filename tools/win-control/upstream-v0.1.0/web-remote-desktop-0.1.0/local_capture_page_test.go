package main

import (
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
)

func TestLocalCapturePageIsOnlyAvailableOnLaptopLoopback(t *testing.T) {
	for _, tc := range []struct {
		remote, host, path, method string
		status                     int
	}{
		{"127.0.0.1:49000", "127.0.0.1:7070", "/capture-camera", "GET", 200},
		{"[::1]:49000", "localhost:7070", "/capture-camera", "HEAD", 200},
		{"127.0.0.1:49000", "127.0.0.1:7070", "/present.html", "GET", 200},
		{"10.0.0.10:49000", "127.0.0.1:7070", "/capture-camera", "GET", 403},
		{"127.0.0.1:49000", "wrong.example:7070", "/present.html", "GET", 403},
		{"127.0.0.1:49000", "localhost:7070", "/capture-camera", "POST", 405},
	} {
		req := httptest.NewRequest(tc.method, tc.path, nil)
		req.RemoteAddr, req.Host = tc.remote, tc.host
		w := httptest.NewRecorder()
		newMux().ServeHTTP(w, req)
		if w.Code != tc.status {
			t.Errorf("%s %s from %s via %s: got %d want %d", tc.method, tc.path, tc.remote, tc.host, w.Code, tc.status)
		}
		if tc.path == "/capture-camera" && tc.method == "GET" && tc.status == http.StatusOK && !strings.Contains(w.Body.String(), "getUserMedia") {
			t.Fatal("camera page missing")
		}
	}
}
