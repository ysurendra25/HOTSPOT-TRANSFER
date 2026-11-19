package main

import (
	"encoding/json"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"testing"
)

func TestSettingsHandlerUpdatesThePhoneSelectedSharedFolder(t *testing.T) {
	previous := liveCfg()
	cfgMu.Lock()
	cfg = defaultConfig()
	cfgMu.Unlock()
	t.Cleanup(func() { cfgMu.Lock(); cfg = previous; cfgMu.Unlock() })

	root := t.TempDir()
	expectedRoot, err := filepath.EvalSymlinks(root)
	if err != nil {
		t.Fatal(err)
	}
	requestBody, err := json.Marshal(map[string]string{"share_dir": root})
	if err != nil {
		t.Fatal(err)
	}
	request := httptest.NewRequest("POST", "/settings", strings.NewReader(string(requestBody)))
	response := httptest.NewRecorder()
	settingsHandler(response, request)
	if response.Code != 200 {
		t.Fatalf("status %d: %s", response.Code, response.Body.String())
	}
	if got := liveCfg().ShareDir; !strings.EqualFold(filepath.Clean(got), filepath.Clean(expectedRoot)) {
		t.Fatalf("share folder = %q, want %q", got, expectedRoot)
	}
	if err := os.Remove(configPath()); err != nil && !os.IsNotExist(err) {
		t.Fatalf("remove temporary helper config: %v", err)
	}
}

func TestSettingsHandlerRejectsMissingShareFolder(t *testing.T) {
	requestBody := `{"share_dir":"C:\\missing-pocketbridge-folder"}`
	request := httptest.NewRequest("POST", "/settings", strings.NewReader(requestBody))
	response := httptest.NewRecorder()
	settingsHandler(response, request)
	if response.Code != 400 {
		t.Fatalf("status %d, want 400: %s", response.Code, response.Body.String())
	}
}
