package main

import (
	"net/http/httptest"
	"strings"
	"testing"
)

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
