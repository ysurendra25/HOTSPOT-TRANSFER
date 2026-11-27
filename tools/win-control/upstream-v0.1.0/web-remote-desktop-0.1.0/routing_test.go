package main

import (
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"net/url"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"testing"
)

const testToken = "testtoken42"

// withTestConfig installs a known token and share folder for one test.
func withTestConfig(t *testing.T, shareDir string) {
	t.Helper()
	cfgMu.Lock()
	old := cfg
	cfg = defaultConfig()
	cfg.Token = testToken
	if shareDir != "" {
		cfg.ShareDir = shareDir
	}
	cfgMu.Unlock()
	listingMu.Lock()
	listingCache = nil
	listingMu.Unlock()
	t.Cleanup(func() { cfgMu.Lock(); cfg = old; cfgMu.Unlock() })
}

// fakePower records power actions instead of touching Windows.
func fakePower(t *testing.T) *[]string {
	t.Helper()
	var mu sync.Mutex
	got := []string{}
	prevRun, prevSchedule := powerActionRunner, schedulePowerAction
	powerActionRunner = func(a string) error { mu.Lock(); got = append(got, a); mu.Unlock(); return nil }
	schedulePowerAction = func(a string) { mu.Lock(); got = append(got, "scheduled:"+a); mu.Unlock() }
	t.Cleanup(func() { powerActionRunner, schedulePowerAction = prevRun, prevSchedule })
	return &got
}

func serve(t *testing.T, method, target, body string, headers map[string]string) *httptest.ResponseRecorder {
	t.Helper()
	var req *http.Request
	if body == "" {
		req = httptest.NewRequest(method, target, nil)
	} else {
		req = httptest.NewRequest(method, target, strings.NewReader(body))
		req.Header.Set("Content-Type", "application/json")
	}
	for k, v := range headers {
		req.Header.Set(k, v)
	}
	res := httptest.NewRecorder()
	newMux().ServeHTTP(res, req)
	return res
}

func TestPingNeedsTheTokenAndAnswersFast(t *testing.T) {
	withTestConfig(t, t.TempDir())
	if res := serve(t, "GET", "/ping", "", nil); res.Code != http.StatusUnauthorized {
		t.Fatalf("ping without token: %d", res.Code)
	}
	if res := serve(t, "GET", "/ping?t=wrong", "", nil); res.Code != http.StatusUnauthorized {
		t.Fatalf("ping with wrong token: %d", res.Code)
	}
	res := serve(t, "GET", "/ping?t="+testToken, "", nil)
	if res.Code != http.StatusOK || !strings.Contains(res.Body.String(), `"ok":true`) {
		t.Fatalf("ping: %d %s", res.Code, res.Body.String())
	}
	if res := serve(t, "GET", "/ping", "", map[string]string{"X-Token": testToken}); res.Code != http.StatusOK {
		t.Fatalf("ping with X-Token: %d", res.Code)
	}
}

func TestQRImageRouteRequiresTokenAndReturnsPNG(t *testing.T) {
	withTestConfig(t, t.TempDir())
	if res := serve(t, "GET", "/qr.png?data=hello", "", nil); res.Code != http.StatusUnauthorized {
		t.Fatalf("QR without token: %d", res.Code)
	}
	res := serve(t, "GET", "/qr.png?t="+testToken+"&data=hello", "", nil)
	if res.Code != http.StatusOK || res.Header().Get("Content-Type") != "image/png" || len(res.Body.Bytes()) < 8 {
		t.Fatalf("QR response: %d %q (%d bytes)", res.Code, res.Header().Get("Content-Type"), res.Body.Len())
	}
}

func TestCameraPageIsAuthenticatedAndDoesNotNeedAnExternalRelay(t *testing.T) {
	withTestConfig(t, t.TempDir())
	if res := serve(t, "GET", "/camera.html", "", map[string]string{"Accept": "text/html"}); res.Code != http.StatusUnauthorized {
		t.Fatalf("camera page without helper token: %d", res.Code)
	}
	res := serve(t, "GET", "/camera.html?t="+testToken, "", map[string]string{"Accept": "text/html"})
	if res.Code != http.StatusOK || !strings.Contains(res.Body.String(), "Two-way camera share") {
		t.Fatalf("camera page: %d %s", res.Code, res.Body.String())
	}
	if !strings.Contains(res.Body.String(), "getUserMedia") || !strings.Contains(res.Body.String(), "iceServers:[]") {
		t.Fatal("camera page should request explicit local camera permission and use direct local ICE")
	}
	if strings.Contains(res.Body.String(), "stun:") || strings.Contains(res.Body.String(), "turn:") {
		t.Fatal("offline camera signaling must not depend on an external STUN/TURN service")
	}
}

func TestHelperPageOffersCameraOnLaptopLoopback(t *testing.T) {
	withTestConfig(t, t.TempDir())
	res := serve(t, "GET", "/?t="+testToken, "", map[string]string{"Accept": "text/html"})
	if res.Code != http.StatusOK || !strings.Contains(res.Body.String(), `id="cameraShare"`) {
		t.Fatalf("camera entry button missing: %d", res.Code)
	}
}

func TestPhoneStyleRequestsGetJSONNotTheLoginPage(t *testing.T) {
	withTestConfig(t, t.TempDir())
	// Android's HttpURLConnection sends an Accept header that includes text/html.
	res := serve(t, "GET", "/files?t=wrong", "", map[string]string{"Accept": "text/html, image/gif, image/jpeg, *; q=.2, */*; q=.2"})
	if res.Code != http.StatusUnauthorized || strings.Contains(res.Body.String(), "<html") {
		t.Fatalf("API call with a bad token should get a plain 401, got %d %q", res.Code, res.Body.String())
	}
}

func TestCorsPreflightIsAnsweredWithoutTheToken(t *testing.T) {
	withTestConfig(t, t.TempDir())
	res := serve(t, "OPTIONS", "/power", "", map[string]string{
		"Origin": "http://192.168.43.1:43117", "Access-Control-Request-Method": "POST",
		"Access-Control-Request-Private-Network": "true",
	})
	if res.Code != http.StatusNoContent {
		t.Fatalf("preflight: %d", res.Code)
	}
	if res.Header().Get("Access-Control-Allow-Origin") != "*" || res.Header().Get("Access-Control-Allow-Private-Network") != "true" {
		t.Fatalf("missing CORS headers: %v", res.Header())
	}
	// The real request still needs the token.
	if res := serve(t, "POST", "/power", `{"action":"lock"}`, map[string]string{"Origin": "http://x"}); res.Code != http.StatusUnauthorized {
		t.Fatalf("cross-origin power without token: %d", res.Code)
	}
}

func TestPowerRoutesEveryPhoneButtonToWindows(t *testing.T) {
	withTestConfig(t, t.TempDir())
	calls := fakePower(t)
	cases := []struct {
		body   string
		status int
		want   string
	}{
		{`{"action":"lock"}`, 200, "lock"},
		{`{"action":"wake-display"}`, 200, "wake-display"},
		{`{"action":"wake"}`, 200, "wake-display"},
		{`{"action":"unlock"}`, 200, "unlock"},
		{`{"action":"sleep","confirm":true}`, 202, "scheduled:sleep"},
		{`{"action":"shutdown","confirm":true}`, 202, "scheduled:shutdown"},
	}
	for _, c := range cases {
		before := len(*calls)
		res := serve(t, "POST", "/power?t="+testToken, c.body, nil)
		if res.Code != c.status {
			t.Fatalf("%s: status %d, want %d (%s)", c.body, res.Code, c.status, res.Body.String())
		}
		var reply map[string]any
		if err := json.Unmarshal(res.Body.Bytes(), &reply); err != nil || reply["ok"] != true || reply["message"] == "" {
			t.Fatalf("%s: reply %s", c.body, res.Body.String())
		}
		if len(*calls) != before+1 || (*calls)[before] != c.want {
			t.Fatalf("%s: windows saw %v, want %q", c.body, *calls, c.want)
		}
	}
}

func TestUnlockReplyIsHonestAboutWindowsSignIn(t *testing.T) {
	withTestConfig(t, t.TempDir())
	fakePower(t)
	res := serve(t, "POST", "/power?t="+testToken, `{"action":"unlock"}`, nil)
	if !strings.Contains(res.Body.String(), "PIN or password") {
		t.Fatalf("unlock must say the PIN/password is still needed: %s", res.Body.String())
	}
}

func TestPowerRefusesWithoutConfirmationOrWithBadInput(t *testing.T) {
	withTestConfig(t, t.TempDir())
	calls := fakePower(t)
	for _, c := range []struct {
		method, body string
		status       int
	}{
		{"POST", `{"action":"sleep"}`, 400},
		{"POST", `{"action":"shutdown"}`, 400},
		{"POST", `{"action":"format-disk","confirm":true}`, 400},
		{"POST", `not json`, 400},
		{"PUT", `{"action":"lock"}`, 405},
	} {
		res := serve(t, c.method, "/power?t="+testToken, c.body, nil)
		if res.Code != c.status {
			t.Fatalf("%s %s: %d, want %d", c.method, c.body, res.Code, c.status)
		}
		if !strings.Contains(res.Body.String(), `"error"`) {
			t.Fatalf("%s %s: no error message: %s", c.method, c.body, res.Body.String())
		}
	}
	if len(*calls) != 0 {
		t.Fatalf("refused requests reached Windows: %v", *calls)
	}
}

func TestPowerListsItsActions(t *testing.T) {
	withTestConfig(t, t.TempDir())
	res := serve(t, "GET", "/power?t="+testToken, "", nil)
	for _, id := range []string{"lock", "wake-display", "unlock", "sleep", "shutdown"} {
		if !strings.Contains(res.Body.String(), `"id":"`+id+`"`) {
			t.Fatalf("GET /power is missing %s: %s", id, res.Body.String())
		}
	}
}

func TestFilesArePagedAndCarryReachableUrls(t *testing.T) {
	root := t.TempDir()
	withTestConfig(t, root)
	names := []string{"a b.mp4", "ünïcode фото.jpg", "100% done.pdf", "plus+and&.txt", "e.mp3"}
	for _, n := range names {
		if err := os.WriteFile(filepath.Join(root, n), []byte("data-"+n), 0600); err != nil {
			t.Fatal(err)
		}
	}
	var all []sharedFile
	offset := 0
	for page := 0; page < 10; page++ {
		res := serve(t, "GET", "/files?t="+testToken+"&limit=2&offset="+itoa(offset), "", nil)
		if res.Code != 200 {
			t.Fatalf("page %d: %d %s", page, res.Code, res.Body.String())
		}
		var body struct {
			Files      []sharedFile `json:"files"`
			Total      int          `json:"total"`
			NextOffset int          `json:"nextOffset"`
			HasMore    bool         `json:"hasMore"`
		}
		if err := json.Unmarshal(res.Body.Bytes(), &body); err != nil {
			t.Fatal(err)
		}
		if body.Total != len(names) || len(body.Files) > 2 {
			t.Fatalf("page %d: total %d, %d files", page, body.Total, len(body.Files))
		}
		all = append(all, body.Files...)
		offset = body.NextOffset
		if !body.HasMore {
			break
		}
	}
	if len(all) != len(names) {
		t.Fatalf("paged %d files, want %d", len(all), len(names))
	}
	// Every listed URL must stream back the right file through the router.
	for _, f := range all {
		u, err := url.Parse(f.URL)
		if err != nil {
			t.Fatalf("bad url %q: %v", f.URL, err)
		}
		res := serve(t, "GET", u.EscapedPath()+"?t="+testToken, "", map[string]string{"Range": "bytes=0-4"})
		if res.Code != http.StatusPartialContent || res.Body.String() != "data-" {
			t.Fatalf("%s via %s: %d %q", f.Name, f.URL, res.Code, res.Body.String())
		}
	}
}

func TestFilesExplainAMissingFolder(t *testing.T) {
	withTestConfig(t, filepath.Join(t.TempDir(), "gone"))
	res := serve(t, "GET", "/files?t="+testToken, "", nil)
	if res.Code != http.StatusNotFound || !strings.Contains(res.Body.String(), "unavailable") {
		t.Fatalf("missing folder: %d %s", res.Code, res.Body.String())
	}
}

func TestListingStopsAtItsLimitAndSkipsSystemFolders(t *testing.T) {
	root := t.TempDir()
	for _, dir := range []string{"node_modules", "AppData", "Keep"} {
		if err := os.Mkdir(filepath.Join(root, dir), 0700); err != nil {
			t.Fatal(err)
		}
		for i := 0; i < 3; i++ {
			if err := os.WriteFile(filepath.Join(root, dir, "f"+itoa(i)+".txt"), []byte("x"), 0600); err != nil {
				t.Fatal(err)
			}
		}
	}
	full := buildListing(root, 100, listTimeBudget)
	if len(full.files) != 3 || full.truncated {
		t.Fatalf("expected only Keep's 3 files, got %d (truncated=%v)", len(full.files), full.truncated)
	}
	capped := buildListing(root, 2, listTimeBudget)
	if len(capped.files) != 2 || !capped.truncated || capped.reason != "count" {
		t.Fatalf("cap not applied: %d files, truncated=%v reason=%q", len(capped.files), capped.truncated, capped.reason)
	}
}

func TestLanURLsKeepEveryPrivateAddress(t *testing.T) {
	for _, u := range lanURLs(7070, "tok") {
		if !strings.HasPrefix(u, "http://") || !strings.HasSuffix(u, ":7070/?t=tok") {
			t.Fatalf("bad LAN url %q", u)
		}
	}
}

func itoa(i int) string {
	b, _ := json.Marshal(i)
	return string(b)
}
