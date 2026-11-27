package main

import (
	"io"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
)

func TestOfflineCameraApprovalActionsReachThePhone(t *testing.T) {
	previous := phoneClient
	defer func() { phoneClient = previous }()
	got := map[string]string{}
	phoneClient = &http.Client{Transport: signalTransport(func(r *http.Request) (*http.Response, error) {
		body, _ := io.ReadAll(r.Body)
		got[r.Method+" "+r.URL.Path] = string(body)
		return &http.Response{StatusCode: 200, Header: make(http.Header), Body: io.NopCloser(strings.NewReader("{}"))}, nil
	})}
	send := func(action, data string) int {
		body := `{"feature":"camera","action":"` + action + `","phone":"http://192.168.43.1:43117","code":"123456"`
		if data != "" {
			body += `,"data":` + data
		}
		body += `,"sdp":"v=0"}`
		req := httptest.NewRequest("POST", "http://127.0.0.1:7070/phone-signal", strings.NewReader(body))
		req.RemoteAddr = "127.0.0.1:40000"
		req.Header.Set("Origin", "http://127.0.0.1:7070")
		rec := httptest.NewRecorder()
		newMux().ServeHTTP(rec, req)
		return rec.Code
	}
	for _, c := range []struct{ action, data string }{
		{"request", `{"name":"Laptop"}`}, {"respond", `{"id":"abc","accept":true}`},
		{"offer", `{"id":"abc","sdp":"v=0"}`}, {"stop", ""}, {"status", ""},
	} {
		if code := send(c.action, c.data); code != 200 {
			t.Fatalf("%s: %d", c.action, code)
		}
	}
	if got["POST /api/camera/respond"] != `{"id":"abc","accept":true}` || got["POST /api/camera/stop"] != "{}" ||
		got["POST /api/camera/offer"] != `{"id":"abc","sdp":"v=0"}` || got["POST /api/camera/request"] != `{"name":"Laptop"}` {
		t.Fatalf("bodies forwarded: %v", got)
	}
	if _, ok := got["GET /api/camera/status"]; !ok {
		t.Fatal("status should be a GET")
	}
	if code := send("hijack", ""); code != 400 {
		t.Fatalf("unknown camera action allowed: %d", code)
	}
	if code := send("respond", `"x"`); code != 400 {
		t.Fatalf("non-object camera body allowed: %d", code)
	}
}
