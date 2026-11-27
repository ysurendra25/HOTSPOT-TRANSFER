package main

import (
	"encoding/json"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strconv"
	"testing"
	"time"
)

func names(files []sharedFile) []string {
	out := make([]string, len(files))
	for i, f := range files {
		out[i] = f.Name
	}
	return out
}

func TestNaturalCompareIsCaseInsensitiveAndNumeric(t *testing.T) {
	cases := [][2]string{{"ep 2.mp4", "ep 10.mp4"}, {"img_9.jpg", "IMG_10.jpg"}, {"Alpha", "beta"}, {"a01", "a2"}, {"9 lives", "abc"}}
	for _, c := range cases {
		if naturalCompare(c[0], c[1]) >= 0 || naturalCompare(c[1], c[0]) <= 0 {
			t.Fatalf("%q should sort before %q", c[0], c[1])
		}
	}
}

func TestSortSharedFilesEveryOrder(t *testing.T) {
	files := []sharedFile{
		{Name: "Episode 10.mkv", Path: "b/Episode 10.mkv", Size: 300, Modified: 1},
		{Name: "episode 2.mkv", Path: "a/episode 2.mkv", Size: 100, Modified: 3},
		{Name: "Trailer.mp4", Path: "Trailer.mp4", Size: 200, Modified: 2},
	}
	want := map[string][]string{
		"name_asc":  {"episode 2.mkv", "Episode 10.mkv", "Trailer.mp4"},
		"name_desc": {"Trailer.mp4", "Episode 10.mkv", "episode 2.mkv"},
		"date_desc": {"episode 2.mkv", "Trailer.mp4", "Episode 10.mkv"},
		"date_asc":  {"Episode 10.mkv", "Trailer.mp4", "episode 2.mkv"},
		"size_desc": {"Episode 10.mkv", "Trailer.mp4", "episode 2.mkv"},
		"size_asc":  {"episode 2.mkv", "Trailer.mp4", "Episode 10.mkv"},
		"bogus":     {"episode 2.mkv", "Trailer.mp4", "Episode 10.mkv"},
	}
	for mode, expected := range want {
		got := names(sortSharedFiles(files, mode))
		for i := range expected {
			if got[i] != expected[i] {
				t.Fatalf("%s: got %v want %v", mode, got, expected)
			}
		}
	}
	if files[0].Name != "Episode 10.mkv" {
		t.Fatal("sorting must not reorder the cached listing")
	}
}

func TestFilesEndpointPagesStablyBySize(t *testing.T) {
	dir := t.TempDir()
	for i, size := range []int{5, 1, 4, 2, 3} {
		p := filepath.Join(dir, "f"+string(rune('a'+i))+".bin")
		if err := os.WriteFile(p, make([]byte, size), 0o600); err != nil {
			t.Fatal(err)
		}
		_ = os.Chtimes(p, time.Unix(int64(1000+i), 0), time.Unix(int64(1000+i), 0))
	}
	withShareDir(t, dir)
	var seen []int64
	for offset := 0; offset < 5; offset += 2 {
		req := httptest.NewRequest("GET", "/files?sort=size_asc&limit=2&offset="+strconv.Itoa(offset)+refreshParam(offset), nil)
		w := httptest.NewRecorder()
		filesHandler(w, req)
		var page struct {
			Files []sharedFile
			Sort  string
		}
		if err := json.Unmarshal(w.Body.Bytes(), &page); err != nil {
			t.Fatal(err)
		}
		if page.Sort != "size_asc" {
			t.Fatalf("sort echoed as %q", page.Sort)
		}
		for _, f := range page.Files {
			seen = append(seen, f.Size)
		}
	}
	for i, size := range []int64{1, 2, 3, 4, 5} {
		if i >= len(seen) || seen[i] != size {
			t.Fatalf("paged sizes %v", seen)
		}
	}
}

func refreshParam(offset int) string {
	if offset == 0 {
		return "&refresh=1"
	}
	return ""
}
