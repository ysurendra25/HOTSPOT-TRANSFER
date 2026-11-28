package main

// Old vs new transfer paths over loopback (not Wi-Fi: loopback has no radio,
// no air-time sharing and ~zero latency, so it measures CPU and disk cost and
// protocol overhead only). Run:
//
//	go test -run '^$' -bench Transfer -benchtime 1x .
//	PB_BENCH_4G=1 go test -run '^$' -bench 'Transfer/4GB' -benchtime 1x .
//
// "old" upload = one streaming POST per file (the legacy /api/upload shape),
// "new" upload = start + parallel PUTs + finish, or one PBB1 batch for small files.
// "old" download = one plain GET, "new" = parallel Range requests with If-Range.
// The 4 GB case reads a sparse source and discards on the receiving side, so
// it fits a small /tmp; it then measures transport cost without disk writes.

import (
	"bytes"
	"fmt"
	"io"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strconv"
	"testing"
)

// legacyPost is the old one-request-per-file shape used as the baseline.
func legacyPost(dir string, discard bool) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		var dst io.Writer = io.Discard
		if !discard {
			f, err := os.CreateTemp(dir, "legacy-*")
			if err != nil {
				http.Error(w, err.Error(), 500)
				return
			}
			defer func() { f.Close(); os.Remove(f.Name()) }()
			dst = f
		}
		bp := uploadBuffers.Get().(*[]byte)
		defer uploadBuffers.Put(bp)
		if _, err := io.CopyBuffer(onlyWriter{dst}, r.Body, *bp); err != nil {
			http.Error(w, err.Error(), 400)
			return
		}
		w.WriteHeader(201)
		_, _ = w.Write([]byte(`{"ok":true}`))
	})
}

type discardAt struct{}

func (discardAt) WriteAt(p []byte, _ int64) (int, error) { return len(p), nil }
func (discardAt) Sync() error                            { return nil }
func (discardAt) Close() error                           { return nil }

func benchServer(b *testing.B, discard bool) (*httptest.Server, string) {
	b.Helper()
	srv, share := uploadTestServer(b)
	mux := http.NewServeMux()
	mux.Handle("/legacy", legacyPost(b.TempDir(), discard))
	mux.Handle("/", srv.Config.Handler)
	srv.Config.Handler = mux
	if discard {
		prev := openUploadData
		openUploadData = func(path string, size int64, fresh bool) (uploadData, error) {
			if fresh {
				// Keep a zero-length placeholder so a restart check still finds the file.
				f, err := os.Create(path)
				if err != nil {
					return nil, err
				}
				f.Close()
				_ = os.Truncate(path, size) // sparse: no blocks allocated
			}
			return discardAt{}, nil
		}
		prevFree := uploadFreeSpace
		uploadFreeSpace = func(string) (uint64, bool) { return 0, false }
		b.Cleanup(func() { openUploadData, uploadFreeSpace = prev, prevFree })
	}
	return srv, share
}

func sparseFile(b *testing.B, dir string, size int64) string {
	b.Helper()
	p := filepath.Join(dir, "sparse.bin")
	f, err := os.Create(p)
	if err != nil {
		b.Fatal(err)
	}
	if err := f.Truncate(size); err != nil {
		b.Fatal(err)
	}
	f.Close()
	return p
}

func legacyUpload(b *testing.B, c *pbClient, src io.Reader, size int64, name string) {
	res, err := c.do("POST", "/legacy?name="+name, src, size, nil)
	if err != nil {
		b.Fatal(err)
	}
	_, _ = io.Copy(io.Discard, res.Body)
	res.Body.Close()
	if res.StatusCode != 201 {
		b.Fatalf("legacy POST %d", res.StatusCode)
	}
}

func BenchmarkTransfer(b *testing.B) {
	b.Run("small1KBx5000/upload/old", func(b *testing.B) {
		srv, _ := benchServer(b, false)
		c := newPBClient(srv.URL)
		data := bytes.Repeat([]byte("k"), 1024)
		b.SetBytes(5000 * 1024)
		for i := 0; i < b.N; i++ {
			for j := 0; j < 5000; j++ {
				legacyUpload(b, c, bytes.NewReader(data), 1024, "f"+strconv.Itoa(j))
			}
		}
	})
	b.Run("small1KBx5000/upload/new", func(b *testing.B) {
		srv, share := benchServer(b, false)
		c := newPBClient(srv.URL)
		data := bytes.Repeat([]byte("k"), 1024)
		files := map[string][]byte{}
		var order []string
		for j := 0; j < 5000; j++ {
			name := fmt.Sprintf("set/%02d/f%04d.txt", j/500, j)
			files[name] = data
			order = append(order, name)
		}
		body := pbb1(files, order)
		b.SetBytes(5000 * 1024)
		b.ResetTimer()
		for i := 0; i < b.N; i++ {
			res, err := c.do("POST", "/api/upload/batch", bytes.NewReader(body), int64(len(body)), nil)
			if err != nil {
				b.Fatal(err)
			}
			_, _ = io.Copy(io.Discard, res.Body)
			res.Body.Close()
			if res.StatusCode != 201 {
				b.Fatalf("batch %d", res.StatusCode)
			}
			b.StopTimer()
			_ = os.RemoveAll(filepath.Join(share, receiveFolderName, "set"))
			b.StartTimer()
		}
	})
	for _, tc := range []struct {
		label   string
		size    int64
		sparse  bool
		discard bool
	}{
		{"100MB", 100 * mib, false, false},
		{"4GB", 4 << 30, true, true},
	} {
		tc := tc
		gate := func(b *testing.B) {
			if tc.sparse && os.Getenv("PB_BENCH_4G") != "1" {
				b.Skip("set PB_BENCH_4G=1 for the 4 GB case")
			}
		}
		source := func(b *testing.B) (string, *os.File) {
			var p string
			if tc.sparse {
				p = sparseFile(b, b.TempDir(), tc.size)
			} else {
				p, _ = randomFile(b, b.TempDir(), tc.size)
			}
			f, err := os.Open(p)
			if err != nil {
				b.Fatal(err)
			}
			b.Cleanup(func() { f.Close() })
			return p, f
		}
		b.Run(tc.label+"/upload/old", func(b *testing.B) {
			gate(b)
			srv, _ := benchServer(b, tc.discard)
			c := newPBClient(srv.URL)
			_, f := source(b)
			b.SetBytes(tc.size)
			b.ResetTimer()
			for i := 0; i < b.N; i++ {
				legacyUpload(b, c, io.NewSectionReader(f, 0, tc.size), tc.size, "big.bin")
			}
		})
		b.Run(tc.label+"/upload/new", func(b *testing.B) {
			gate(b)
			srv, share := benchServer(b, tc.discard)
			c := newPBClient(srv.URL)
			_, f := source(b)
			b.SetBytes(tc.size)
			b.ResetTimer()
			for i := 0; i < b.N; i++ {
				// A fresh lastModified per round, so nothing resumes.
				if _, err := c.uploadChunked(f, tc.size, "big.bin", int64(1000+i), "", 4, 16*mib); err != nil {
					b.Fatal(err)
				}
				b.StopTimer()
				_ = os.RemoveAll(filepath.Join(share, receiveFolderName))
				resetUploadStores()
				b.StartTimer()
			}
		})
		for _, mode := range []string{"old", "new"} {
			mode := mode
			b.Run(tc.label+"/download/"+mode, func(b *testing.B) {
				gate(b)
				srv, share := benchServer(b, tc.discard)
				c := newPBClient(srv.URL)
				p, _ := source(b)
				if err := os.Rename(p, filepath.Join(share, "big.bin")); err != nil {
					b.Fatal(err)
				}
				b.SetBytes(tc.size)
				b.ResetTimer()
				for i := 0; i < b.N; i++ {
					if mode == "old" {
						res, err := c.do("GET", "/file/big.bin", nil, -1, nil)
						if err != nil {
							b.Fatal(err)
						}
						bp := uploadBuffers.Get().(*[]byte)
						n, _ := io.CopyBuffer(io.Discard, res.Body, *bp)
						uploadBuffers.Put(bp)
						res.Body.Close()
						if n != tc.size {
							b.Fatalf("got %d", n)
						}
					} else {
						n, err := c.downloadRanged("/file/big.bin", discardAt{}, 4, 16*mib)
						if err != nil || n != tc.size {
							b.Fatalf("ranged: %d %v", n, err)
						}
					}
				}
			})
		}
	}
}
