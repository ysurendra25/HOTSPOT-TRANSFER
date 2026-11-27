package main

import (
	"context"
	"encoding/json"
	"io"
	"net"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync"
	"testing"
	"time"
)

func TestRestartNeedsConfirmationAndIsDeferred(t *testing.T) {
	status, body, deferred := decidePower(powerRequest{Action: "restart"})
	if status != http.StatusBadRequest || body["confirm"] != true || deferred != "" {
		t.Fatalf("restart without confirm: %d %v %q", status, body, deferred)
	}
	status, body, deferred = decidePower(powerRequest{Action: "reboot", Confirm: true})
	if status != http.StatusAccepted || deferred != "restart" || body["ok"] != true {
		t.Fatalf("restart: %d %v %q", status, body, deferred)
	}
}

func TestImmediatePowerFailureIsReportedAndRemembered(t *testing.T) {
	previous := powerActionRunner
	defer func() { powerActionRunner = previous }()
	powerActionRunner = func(string) error { return io.ErrUnexpectedEOF }
	status, body, _ := decidePower(powerRequest{Action: "lock"})
	if status != http.StatusInternalServerError || body["ok"] != false {
		t.Fatalf("lock failure not reported: %d %v", status, body)
	}
	if last := lastPowerResult(); last.Action != "lock" || last.OK || last.Error == "" {
		t.Fatalf("last result not recorded: %+v", last)
	}
	req := httptest.NewRequest("GET", "/power", nil)
	res := httptest.NewRecorder()
	powerHandler(res, req)
	if !strings.Contains(res.Body.String(), `"last"`) {
		t.Fatalf("GET /power does not report the last result: %s", res.Body.String())
	}
}

func TestRadiosAllOff(t *testing.T) {
	cases := map[string]bool{
		"WiFi=Off;Bluetooth=Off":    true,
		"WiFi=On;Bluetooth=Off":     false,
		"":                          false,
		"MobileBroadband=Disabled":  true,
		"WiFi=Off;Bluetooth=On(Denied)": false,
	}
	for in, want := range cases {
		if got := radiosAllOff(in); got != want {
			t.Errorf("radiosAllOff(%q) = %v, want %v", in, got, want)
		}
	}
}

func TestRadiosPostOnlyFromThisLaptop(t *testing.T) {
	req := httptest.NewRequest("POST", "/radios", strings.NewReader(`{"airplane":true}`))
	req.RemoteAddr = "192.168.43.10:5000"
	res := httptest.NewRecorder()
	radiosHandler(res, req)
	if res.Code != http.StatusForbidden {
		t.Fatalf("remote radio switch allowed: %d", res.Code)
	}
}

func TestFrameSkipperWaitsWhenTheLinkIsSlow(t *testing.T) {
	var s frameSkipper
	interval := int64(33 * time.Millisecond)
	if !s.ready(0) {
		t.Fatal("first frame must be ready")
	}
	// A 133 ms write on a 33 ms interval: wait 100 ms more before capturing.
	s.sent(0, int64(133*time.Millisecond), interval)
	if s.ready(int64(200 * time.Millisecond)) {
		t.Fatal("captured again before the link drained")
	}
	if !s.ready(int64(234 * time.Millisecond)) {
		t.Fatal("never captured again")
	}
	s.sent(0, int64(10*time.Millisecond), interval)
	if !s.ready(int64(11 * time.Millisecond)) {
		t.Fatal("fast link must not skip")
	}
}

func TestValidPhoneLink(t *testing.T) {
	if host, err := validPhoneLink("http://192.168.43.1:43117/"); err != nil || host != "192.168.43.1" {
		t.Fatalf("valid link refused: %v %q", err, host)
	}
	for _, bad := range []string{"https://192.168.43.1:43117", "http://8.8.8.8:43117", "http://192.168.43.1:7070", "http://192.168.43.1:43117/x"} {
		if _, err := validPhoneLink(bad); err == nil {
			t.Errorf("accepted %q", bad)
		}
	}
}

// The relay polls the phone, runs the queued command and posts the result.
func TestRelayRunsPhoneCommandAndReportsResult(t *testing.T) {
	var mu sync.Mutex
	served := false
	results := make(chan map[string]any, 1)
	phone := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path != "/api/laptop-commands" || r.URL.Query().Get("code") != "123456" {
			http.Error(w, "no", 403)
			return
		}
		var in map[string]any
		_ = json.NewDecoder(r.Body).Decode(&in)
		w.Header().Set("Content-Type", "application/json")
		if in["action"] == "result" {
			results <- in
			_, _ = io.WriteString(w, `{"ok":true}`)
			return
		}
		mu.Lock()
		first := !served
		served = true
		mu.Unlock()
		if first {
			_, _ = io.WriteString(w, `{"command":{"id":"c1","action":"lock"}}`)
			return
		}
		time.Sleep(50 * time.Millisecond)
		_, _ = io.WriteString(w, `{"command":null}`)
	}))
	defer phone.Close()
	_, port, _ := net.SplitHostPort(strings.TrimPrefix(phone.URL, "http://"))

	previousRunner := powerActionRunner
	defer func() { powerActionRunner = previousRunner }()
	locked := make(chan bool, 1)
	powerActionRunner = func(action string) error { locked <- action == "lock"; return nil }

	previousClient := relayClient
	defer func() { relayClient = previousClient }()
	relayClient = &http.Client{Transport: &http.Transport{Proxy: nil, DialContext: func(ctx context.Context, network, addr string) (net.Conn, error) {
		return (&net.Dialer{}).DialContext(ctx, network, "127.0.0.1:"+port)
	}}}

	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	go relayLoop(ctx, relayTarget{host: "192.168.43.1", code: "123456"})
	select {
	case ok := <-locked:
		if !ok {
			t.Fatal("wrong action ran")
		}
	case <-time.After(5 * time.Second):
		t.Fatal("the relayed lock never ran")
	}
	select {
	case r := <-results:
		if r["id"] != "c1" || r["ok"] != true || !strings.Contains(r["message"].(string), "locked") {
			t.Fatalf("unexpected result: %v", r)
		}
	case <-time.After(5 * time.Second):
		t.Fatal("the result was never posted to the phone")
	}
}
