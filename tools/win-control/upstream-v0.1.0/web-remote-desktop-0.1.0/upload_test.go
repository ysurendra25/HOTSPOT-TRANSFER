package main

import (
	"bytes"
	"crypto/rand"
	"crypto/sha256"
	"encoding/binary"
	"encoding/json"
	"fmt"
	"io"
	"net"
	"net/http"
	"net/http/httptest"
	"net/url"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"sync"
	"testing"
	"time"
)

// ---- a small client for the upload protocol (tests and benchmarks) ----

type startReply struct {
	ID          string     `json:"id"`
	Size        int64      `json:"size"`
	Received    int64      `json:"received"`
	Missing     [][2]int64 `json:"missing"`
	Chunk       int64      `json:"chunk"`
	Connections int        `json:"connections"`
	Resumed     bool       `json:"resumed"`
}

type pbClient struct {
	base  string
	token string
	http  *http.Client
}

func newPBClient(base string) *pbClient {
	tr := &http.Transport{MaxIdleConnsPerHost: 16, DisableCompression: true}
	return &pbClient{base: base, token: testToken, http: &http.Client{Transport: tr}}
}

func (c *pbClient) do(method, path string, body io.Reader, length int64, header map[string]string) (*http.Response, error) {
	req, err := http.NewRequest(method, c.base+path, body)
	if err != nil {
		return nil, err
	}
	if length >= 0 {
		req.ContentLength = length
	}
	req.Header.Set("Authorization", "Bearer "+c.token)
	for k, v := range header {
		req.Header.Set(k, v)
	}
	return c.http.Do(req)
}

func (c *pbClient) jsonCall(method, path string, in any, out any) (int, error) {
	var body io.Reader
	length := int64(0)
	if in != nil {
		b, _ := json.Marshal(in)
		body, length = bytes.NewReader(b), int64(len(b))
	}
	res, err := c.do(method, path, body, length, map[string]string{"Content-Type": "application/json"})
	if err != nil {
		return 0, err
	}
	defer res.Body.Close()
	data, _ := io.ReadAll(res.Body)
	if out != nil && len(data) > 0 {
		_ = json.Unmarshal(data, out)
	}
	if res.StatusCode >= 400 {
		return res.StatusCode, fmt.Errorf("%s %s: %d %s", method, path, res.StatusCode, data)
	}
	return res.StatusCode, nil
}

func (c *pbClient) start(name string, size, modified int64, rel string) (startReply, error) {
	var st startReply
	_, err := c.jsonCall("POST", "/api/upload/start", map[string]any{"name": name, "size": size, "lastModified": modified, "relPath": rel}, &st)
	return st, err
}

// uploadChunked sends src with parallel PUTs of the missing ranges, then finishes.
func (c *pbClient) uploadChunked(src io.ReaderAt, size int64, name string, modified int64, rel string, conns int, chunk int64) (map[string]any, error) {
	st, err := c.start(name, size, modified, rel)
	if err != nil {
		return nil, err
	}
	if chunk <= 0 {
		chunk = st.Chunk
	}
	var jobs [][2]int64
	for _, gap := range st.Missing {
		for at := gap[0]; at < gap[1]; at += chunk {
			jobs = append(jobs, [2]int64{at, min(gap[1], at+chunk)})
		}
	}
	ch := make(chan [2]int64)
	var wg sync.WaitGroup
	var mu sync.Mutex
	var firstErr error
	for i := 0; i < conns; i++ {
		wg.Add(1)
		go func() {
			defer wg.Done()
			for j := range ch {
				res, err := c.do("PUT", "/api/upload/"+st.ID+"?offset="+strconv.FormatInt(j[0], 10),
					io.NewSectionReader(src, j[0], j[1]-j[0]), j[1]-j[0], map[string]string{"Content-Type": "application/octet-stream"})
				if err == nil {
					_, _ = io.Copy(io.Discard, res.Body)
					res.Body.Close()
					if res.StatusCode != 200 {
						err = fmt.Errorf("PUT %d", res.StatusCode)
					}
				}
				if err != nil {
					mu.Lock()
					if firstErr == nil {
						firstErr = err
					}
					mu.Unlock()
				}
			}
		}()
	}
	for _, j := range jobs {
		ch <- j
	}
	close(ch)
	wg.Wait()
	if firstErr != nil {
		return nil, firstErr
	}
	var done map[string]any
	_, err = c.jsonCall("POST", "/api/upload/"+st.ID+"/finish", nil, &done)
	return done, err
}

// downloadRanged fetches url with conns parallel Range requests (If-Range on
// the ETag from a first probe) into sink.
func (c *pbClient) downloadRanged(path string, sink io.WriterAt, conns int, chunk int64) (int64, error) {
	res, err := c.do("GET", path, nil, -1, map[string]string{"Range": "bytes=0-0"})
	if err != nil {
		return 0, err
	}
	_, _ = io.Copy(io.Discard, res.Body)
	res.Body.Close()
	if res.StatusCode != http.StatusPartialContent {
		return 0, fmt.Errorf("probe: %d", res.StatusCode)
	}
	cr := res.Header.Get("Content-Range")
	size, _ := strconv.ParseInt(cr[strings.LastIndex(cr, "/")+1:], 10, 64)
	etag := res.Header.Get("ETag")
	ch := make(chan [2]int64)
	var wg sync.WaitGroup
	var mu sync.Mutex
	var firstErr error
	for i := 0; i < conns; i++ {
		wg.Add(1)
		go func() {
			defer wg.Done()
			bp := uploadBuffers.Get().(*[]byte)
			defer uploadBuffers.Put(bp)
			for j := range ch {
				err := func() error {
					res, err := c.do("GET", path, nil, -1, map[string]string{
						"Range": fmt.Sprintf("bytes=%d-%d", j[0], j[1]-1), "If-Range": etag})
					if err != nil {
						return err
					}
					defer res.Body.Close()
					if res.StatusCode != http.StatusPartialContent {
						return fmt.Errorf("range: %d", res.StatusCode)
					}
					_, err = io.CopyBuffer(io.NewOffsetWriter(sink, j[0]), res.Body, *bp)
					return err
				}()
				if err != nil {
					mu.Lock()
					if firstErr == nil {
						firstErr = err
					}
					mu.Unlock()
				}
			}
		}()
	}
	for at := int64(0); at < size; at += chunk {
		ch <- [2]int64{at, min(size, at+chunk)}
	}
	close(ch)
	wg.Wait()
	return size, firstErr
}

func randomFile(t testing.TB, dir string, size int64) (string, [32]byte) {
	t.Helper()
	p := filepath.Join(dir, "source.bin")
	data := make([]byte, size)
	_, _ = rand.Read(data)
	if err := os.WriteFile(p, data, 0o600); err != nil {
		t.Fatal(err)
	}
	return p, sha256.Sum256(data)
}

func sha256File(t testing.TB, p string) [32]byte {
	t.Helper()
	f, err := os.Open(p)
	if err != nil {
		t.Fatal(err)
	}
	defer f.Close()
	h := sha256.New()
	_, _ = io.Copy(h, f)
	var out [32]byte
	copy(out[:], h.Sum(nil))
	return out
}

// uploadTestServer starts the real mux with a fresh share folder.
func uploadTestServer(t testing.TB) (*httptest.Server, string) {
	t.Helper()
	share := t.TempDir()
	cfgMu.Lock()
	old := cfg
	cfg = defaultConfig()
	cfg.Token = testToken
	cfg.ShareDir = share
	cfgMu.Unlock()
	resetUploadStores()
	srv := httptest.NewServer(newMux())
	t.Cleanup(func() {
		srv.Close()
		cfgMu.Lock()
		cfg = old
		cfgMu.Unlock()
		resetUploadStores()
	})
	return srv, share
}

// resetUploadStores forgets open sessions, like a helper restart.
func resetUploadStores() {
	uploadStoresMu.Lock()
	for _, s := range uploadStores {
		s.mu.Lock()
		for _, sess := range s.open {
			sess.close()
		}
		s.mu.Unlock()
	}
	uploadStores = map[string]*uploadStore{}
	uploadStoresMu.Unlock()
}

// ---- tests ----

func TestSafeRelPathRejectsEscapes(t *testing.T) {
	good := map[string]string{
		"Trip/day 1/a.jpg": "Trip/day 1/a.jpg",
		`Trip\day 1\a.jpg`: "Trip/day 1/a.jpg",
		"a//b/./c.txt":     "a/b/c.txt",
		"what?.txt":        "what_.txt",
		"folder. /x":       "folder/x",
		"console.log":      "console.log",
	}
	for in, want := range good {
		got, err := safeRelPath(in)
		if err != nil || got != want {
			t.Errorf("safeRelPath(%q) = %q, %v; want %q", in, got, err, want)
		}
	}
	for _, bad := range []string{"../x", "a/../../x", "/etc/passwd", `\\server\share\x`, "C:/Windows/x", "c:x",
		"CON", "a/nul.txt", "COM1.log", "LPT9", "", "  ", "./.", strings.Repeat("a/", 40) + "x"} {
		if got, err := safeRelPath(bad); err == nil {
			t.Errorf("safeRelPath(%q) = %q, want an error", bad, got)
		}
	}
	if safeFileName("../../x") != ".._.._x" && safeFileName("../../x") == "" {
		t.Fatal("file name not made safe")
	}
	if safeFileName("CON") != "_CON" || safeFileName("") != "shared-file" {
		t.Fatalf("device/empty names: %q %q", safeFileName("CON"), safeFileName(""))
	}
}

func TestResumeManifestMatchesTheJavaFormat(t *testing.T) {
	m := &resumeManifest{size: 100, validator: "100:5", source: "name:a"}
	m.add(50, 60)
	m.add(0, 10)
	m.add(10, 20)
	want := "PBRESUME 1\nsize=100\nvalidator=100:5\nsource=name:a\ndone=0-20,50-60\n"
	if got := m.encode(); got != want {
		t.Fatalf("encode:\n%q\nwant\n%q", got, want)
	}
	back := decodeManifest(want)
	if back == nil || back.completed() != 30 || fmt.Sprint(back.missing()) != "[[20 50] [60 100]]" {
		t.Fatalf("decode: %+v", back)
	}
	if decodeManifest("nope") != nil || decodeManifest("PBRESUME 1\nsize=x\n") != nil {
		t.Fatal("invalid manifests must not decode")
	}
}

func TestSessionIDMatchesThePhone(t *testing.T) {
	// UploadSessions.sessionId("a.bin\n10\n5\n") on the phone.
	sum := sha256.Sum256([]byte("a.bin\n10\n5\n"))
	if got := uploadSessionID("a.bin", 10, 5, ""); got != fmt.Sprintf("%x", sum[:16]) || len(got) != 32 {
		t.Fatalf("id %s", got)
	}
}

func TestFileRouteHasStrongETagAndHonoursIfRange(t *testing.T) {
	dir := t.TempDir()
	withTestConfig(t, dir)
	if err := os.WriteFile(filepath.Join(dir, "a.bin"), bytes.Repeat([]byte("x"), 1000), 0o600); err != nil {
		t.Fatal(err)
	}
	res := serve(t, "GET", "/file/a.bin?t="+testToken, "", map[string]string{"Range": "bytes=0-9"})
	etag := res.Header().Get("ETag")
	if res.Code != 206 || !strings.HasPrefix(etag, `"pb-`) || strings.HasPrefix(etag, "W/") {
		t.Fatalf("probe: %d etag=%q", res.Code, etag)
	}
	if res.Header().Get("Accept-Ranges") != "bytes" || res.Header().Get("Content-Range") != "bytes 0-9/1000" {
		t.Fatalf("range headers: %v", res.Header())
	}
	same := serve(t, "GET", "/file/a.bin?t="+testToken, "", map[string]string{"Range": "bytes=10-19", "If-Range": etag})
	if same.Code != 206 {
		t.Fatalf("matching If-Range: %d", same.Code)
	}
	stale := serve(t, "GET", "/file/a.bin?t="+testToken, "", map[string]string{"Range": "bytes=10-19", "If-Range": `"pb-0-0"`})
	if stale.Code != 200 || stale.Body.Len() != 1000 {
		t.Fatalf("stale If-Range must send the whole file: %d %d", stale.Code, stale.Body.Len())
	}
}

func TestCorsAllowsUploadsAndExposesRangeHeaders(t *testing.T) {
	withTestConfig(t, t.TempDir())
	res := serve(t, "OPTIONS", "/api/upload/start", "", map[string]string{"Origin": "http://192.168.43.1:43117", "Access-Control-Request-Method": "PUT"})
	h := res.Header()
	for _, want := range []string{"PUT", "DELETE"} {
		if !strings.Contains(h.Get("Access-Control-Allow-Methods"), want) {
			t.Fatalf("methods %q lack %s", h.Get("Access-Control-Allow-Methods"), want)
		}
	}
	for _, want := range []string{"Range", "If-Range"} {
		if !strings.Contains(h.Get("Access-Control-Allow-Headers"), want) {
			t.Fatalf("allowed headers %q lack %s", h.Get("Access-Control-Allow-Headers"), want)
		}
	}
	for _, want := range []string{"ETag", "Content-Range", "Accept-Ranges"} {
		if !strings.Contains(h.Get("Access-Control-Expose-Headers"), want) {
			t.Fatalf("exposed headers %q lack %s", h.Get("Access-Control-Expose-Headers"), want)
		}
	}
}

func TestUploadNeedsTheToken(t *testing.T) {
	srv, _ := uploadTestServer(t)
	c := newPBClient(srv.URL)
	c.token = "wrong"
	if code, _ := c.jsonCall("POST", "/api/upload/start", map[string]any{"name": "a", "size": 1}, nil); code != 401 {
		t.Fatalf("start without the token: %d", code)
	}
}

func TestLoopbackUploadAndRangedDownloadKeepTheBytes(t *testing.T) {
	srv, share := uploadTestServer(t)
	c := newPBClient(srv.URL)
	const size = 24*mib + 12345
	src, want := randomFile(t, t.TempDir(), size)
	f, _ := os.Open(src)
	defer f.Close()
	done, err := c.uploadChunked(f, size, "movie.bin", 1700000000000, "", 4, 4*mib)
	if err != nil {
		t.Fatal(err)
	}
	if done["name"] != "movie.bin" || int64(done["bytes"].(float64)) != size {
		t.Fatalf("finish: %v", done)
	}
	landed := filepath.Join(share, receiveFolderName, "movie.bin")
	if sha256File(t, landed) != want {
		t.Fatal("uploaded file differs")
	}
	if info, _ := os.Stat(landed); info.ModTime().UnixMilli() != 1700000000000 {
		t.Fatalf("lastModified not restored: %v", info.ModTime())
	}
	if entries, _ := os.ReadDir(filepath.Join(share, receiveFolderName, uploadStateFolderName)); len(entries) != 0 {
		t.Fatalf("state left behind: %v", entries)
	}
	out := filepath.Join(t.TempDir(), "down.bin")
	sink, _ := os.Create(out)
	n, err := c.downloadRanged("/file/"+receiveFolderName+"/movie.bin", sink, 4, 3*mib)
	sink.Close()
	if err != nil || n != size {
		t.Fatalf("download: %d %v", n, err)
	}
	if sha256File(t, out) != want {
		t.Fatal("downloaded file differs")
	}
	// Same name again: kept beside it, never overwritten.
	f2, _ := os.Open(src)
	defer f2.Close()
	again, err := c.uploadChunked(f2, size, "movie.bin", 1700000000001, "", 2, 8*mib)
	if err != nil || again["name"] != "movie (2).bin" {
		t.Fatalf("second upload: %v %v", again, err)
	}
}

func TestUploadResumesAfterAnInterruptionAndARestart(t *testing.T) {
	srv, share := uploadTestServer(t)
	c := newPBClient(srv.URL)
	const size = 6 * mib
	src, want := randomFile(t, t.TempDir(), size)
	data, _ := os.ReadFile(src)
	st, err := c.start("clip.bin", size, 42, "Trip/day 1/clip.bin")
	if err != nil || st.Received != 0 || len(st.Missing) != 1 {
		t.Fatalf("start: %+v %v", st, err)
	}
	// Announce the whole file in one PUT but drop the connection after 2.5 MiB.
	u, _ := url.Parse(srv.URL)
	conn, err := net.Dial("tcp", u.Host)
	if err != nil {
		t.Fatal(err)
	}
	sent := 5 * mib / 2
	fmt.Fprintf(conn, "PUT /api/upload/%s?offset=0 HTTP/1.1\r\nHost: x\r\nAuthorization: Bearer %s\r\nContent-Length: %d\r\n\r\n", st.ID, testToken, size)
	_, _ = conn.Write(data[:sent])
	time.Sleep(200 * time.Millisecond)
	conn.Close()
	// The bytes that arrived stay recorded (the handler saves on the dropped read).
	var state startReply
	deadline := time.Now().Add(5 * time.Second)
	for time.Now().Before(deadline) {
		if _, err := c.jsonCall("GET", "/api/upload/"+st.ID, nil, &state); err == nil && state.Received == int64(sent) {
			break
		}
		time.Sleep(50 * time.Millisecond)
	}
	if state.Received != int64(sent) || fmt.Sprint(state.Missing) != fmt.Sprintf("[[%d %d]]", sent, size) {
		t.Fatalf("after the drop: %+v", state)
	}
	// Helper restart: sessions are reloaded from their manifest.
	resetUploadStores()
	again, err := c.start("clip.bin", size, 42, "Trip/day 1/clip.bin")
	if err != nil || !again.Resumed || again.ID != st.ID || again.Received != int64(sent) {
		t.Fatalf("resume: %+v %v", again, err)
	}
	f, _ := os.Open(src)
	defer f.Close()
	done, err := c.uploadChunked(f, size, "clip.bin", 42, "Trip/day 1/clip.bin", 3, mib)
	if err != nil || done["name"] != "Trip/day 1/clip.bin" {
		t.Fatalf("finish: %v %v", done, err)
	}
	if sha256File(t, filepath.Join(share, receiveFolderName, "Trip", "day 1", "clip.bin")) != want {
		t.Fatal("resumed upload differs")
	}
}

func TestUploadRoutesRefuseBadInput(t *testing.T) {
	srv, _ := uploadTestServer(t)
	c := newPBClient(srv.URL)
	if code, _ := c.jsonCall("POST", "/api/upload/start", map[string]any{"name": "a", "size": 10, "relPath": "../../evil"}, nil); code != 400 {
		t.Fatalf("escaping relPath: %d", code)
	}
	if code, _ := c.jsonCall("POST", "/api/upload/start", map[string]any{"name": "a"}, nil); code != 400 {
		t.Fatalf("missing size: %d", code)
	}
	if code, _ := c.jsonCall("GET", "/api/upload/"+strings.Repeat("0", 32), nil, nil); code != 404 {
		t.Fatalf("unknown id: %d", code)
	}
	st, _ := c.start("a.bin", 10, 1, "")
	res, _ := c.do("PUT", "/api/upload/"+st.ID+"?offset=5", strings.NewReader("0123456789"), 10, nil)
	res.Body.Close()
	if res.StatusCode != 416 {
		t.Fatalf("part outside the file: %d", res.StatusCode)
	}
	if code, _ := c.jsonCall("POST", "/api/upload/"+st.ID+"/finish", nil, nil); code != 409 {
		t.Fatalf("finish incomplete: %d", code)
	}
	if code, _ := c.jsonCall("DELETE", "/api/upload/"+st.ID, nil, nil); code != 200 {
		t.Fatalf("cancel: %d", code)
	}
	if code, _ := c.jsonCall("GET", "/api/upload/"+st.ID, nil, nil); code != 404 {
		t.Fatalf("cancelled id: %d", code)
	}
}

func pbb1(entries map[string][]byte, order []string) []byte {
	var b bytes.Buffer
	b.WriteString("PBB1")
	for _, name := range order {
		_ = binary.Write(&b, binary.BigEndian, uint16(len(name)))
		b.WriteString(name)
		_ = binary.Write(&b, binary.BigEndian, uint64(len(entries[name])))
		b.Write(entries[name])
	}
	b.Write([]byte{0, 0})
	return b.Bytes()
}

func TestBatchKeepsFoldersAndRejectsEscapes(t *testing.T) {
	srv, share := uploadTestServer(t)
	c := newPBClient(srv.URL)
	files := map[string][]byte{"Trip/a.txt": []byte("alpha"), "Trip/sub/b.txt": []byte("beta"), "c.txt": {}}
	order := []string{"Trip/a.txt", "Trip/sub/b.txt", "c.txt"}
	body := pbb1(files, order)
	res, err := c.do("POST", "/api/upload/batch", bytes.NewReader(body), int64(len(body)), nil)
	if err != nil {
		t.Fatal(err)
	}
	var out struct {
		Count int `json:"count"`
		Bytes int `json:"bytes"`
	}
	_ = json.NewDecoder(res.Body).Decode(&out)
	res.Body.Close()
	if res.StatusCode != 201 || out.Count != 3 || out.Bytes != 9 {
		t.Fatalf("batch: %d %+v", res.StatusCode, out)
	}
	if got, _ := os.ReadFile(filepath.Join(share, receiveFolderName, "Trip", "sub", "b.txt")); string(got) != "beta" {
		t.Fatalf("b.txt = %q", got)
	}
	bad := pbb1(map[string][]byte{"ok.txt": []byte("1"), "../evil.txt": []byte("2")}, []string{"ok.txt", "../evil.txt"})
	res, _ = c.do("POST", "/api/upload/batch", bytes.NewReader(bad), int64(len(bad)), nil)
	res.Body.Close()
	if res.StatusCode != 400 {
		t.Fatalf("escaping batch: %d", res.StatusCode)
	}
	if _, err := os.Stat(filepath.Join(share, receiveFolderName, "ok.txt")); !os.IsNotExist(err) {
		t.Fatal("a refused batch must remove what it wrote")
	}
	if _, err := os.Stat(filepath.Join(share, "evil.txt")); !os.IsNotExist(err) {
		t.Fatal("escaped the receive folder")
	}
	short := body[:len(body)-6]
	res, _ = c.do("POST", "/api/upload/batch", bytes.NewReader(short), int64(len(short)), nil)
	res.Body.Close()
	if res.StatusCode != 400 {
		t.Fatalf("truncated batch: %d", res.StatusCode)
	}
}

func TestPruneDropsWeekOldSessions(t *testing.T) {
	dir := t.TempDir()
	s := newUploadStore(dir)
	_ = os.MkdirAll(s.stateDir(), 0o755)
	old := filepath.Join(s.stateDir(), strings.Repeat("a", 32)+".data")
	_ = os.WriteFile(old, nil, 0o600)
	past := time.Now().Add(-8 * 24 * time.Hour)
	_ = os.Chtimes(old, past, past)
	fresh := filepath.Join(s.stateDir(), strings.Repeat("b", 32)+".data")
	_ = os.WriteFile(fresh, nil, 0o600)
	if n := s.prune(time.Now()); n != 1 {
		t.Fatalf("pruned %d", n)
	}
	if _, err := os.Stat(fresh); err != nil {
		t.Fatal("fresh session removed")
	}
}

func TestStartRefusesWhenTheDiskIsFull(t *testing.T) {
	s := newUploadStore(t.TempDir())
	s.free = func(string) (uint64, bool) { return 10, true }
	if _, err := s.start("a", 1000, 1, ""); err == nil || err.(*uploadFailure).status != http.StatusInsufficientStorage {
		t.Fatalf("want 507, got %v", err)
	}
}
