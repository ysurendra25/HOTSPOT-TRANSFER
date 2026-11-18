package main

import (
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
)

func TestSleepAcknowledgesBeforeLaptopSuspends(t *testing.T) {
	previous := scheduleSleepAction
	defer func() { scheduleSleepAction = previous }()
	called := false
	scheduleSleepAction = func() { called = true }
	req := httptest.NewRequest("POST", "/power", strings.NewReader(`{"action":"sleep","confirm":true}`))
	res := httptest.NewRecorder()
	powerHandler(res, req)
	if res.Code != http.StatusAccepted {
		t.Fatalf("got %d, want 202", res.Code)
	}
	if !strings.Contains(res.Body.String(), `"scheduled":true`) || !called {
		t.Fatalf("sleep was not acknowledged and scheduled: %s", res.Body.String())
	}
}

func TestPowerHandlerRequiresExplicitConfirmation(t *testing.T) {
	for _, body := range []string{`{"action":"sleep"}`, `{"action":"shutdown"}`} {
		req := httptest.NewRequest("POST", "/power", strings.NewReader(body))
		res := httptest.NewRecorder()
		powerHandler(res, req)
		if res.Code != 400 {
			t.Fatalf("%s: got %d, want 400", body, res.Code)
		}
	}
}

func TestPowerHandlerRejectsUnknownAction(t *testing.T) {
	req := httptest.NewRequest("POST", "/power", strings.NewReader(`{"action":"reboot","confirm":true}`))
	res := httptest.NewRecorder()
	powerHandler(res, req)
	if res.Code != 400 {
		t.Fatalf("got %d, want 400", res.Code)
	}
}

func TestUnlockActionIsSupported(t *testing.T) {
	if !supportedPowerAction("unlock") {
		t.Fatal("unlock must be accepted so it can wake the sign-in display")
	}
}

func TestLockActionIsForwardedToWindows(t *testing.T) {
	previous := powerActionRunner
	defer func() { powerActionRunner = previous }()
	action := ""
	powerActionRunner = func(value string) error { action = value; return nil }
	req := httptest.NewRequest("POST", "/power", strings.NewReader(`{"action":"lock"}`))
	res := httptest.NewRecorder()
	powerHandler(res, req)
	if res.Code != http.StatusOK || action != "lock" {
		t.Fatalf("got status %d and action %q, want 200 and lock", res.Code, action)
	}
}
