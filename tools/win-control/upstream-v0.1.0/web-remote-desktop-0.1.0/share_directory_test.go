package main

import (
	"os"
	"path/filepath"
	"strings"
	"testing"
)

func TestResolveShareDirectoryAcceptsFolderAndFilePaths(t *testing.T) {
	root := t.TempDir()
	expectedRoot, err := filepath.EvalSymlinks(root)
	if err != nil {
		t.Fatal(err)
	}
	file := filepath.Join(root, "movie file.mp4")
	if err := os.WriteFile(file, []byte("video"), 0600); err != nil {
		t.Fatal(err)
	}
	for _, requested := range []string{root, `"` + file + `"`} {
		got, err := resolveShareDirectory(requested)
		if err != nil {
			t.Fatalf("resolveShareDirectory(%q): %v", requested, err)
		}
		if !strings.EqualFold(filepath.Clean(got), filepath.Clean(expectedRoot)) {
			t.Errorf("resolveShareDirectory(%q) = %q, want %q", requested, got, expectedRoot)
		}
	}
}

func TestResolveShareDirectoryRejectsMissingPath(t *testing.T) {
	if _, err := resolveShareDirectory(filepath.Join(t.TempDir(), "missing")); err == nil {
		t.Fatal("expected a missing path to be rejected")
	}
}

func TestResolveShareDirectoryHomeAlias(t *testing.T) {
	got, err := resolveShareDirectory("@home")
	if err != nil { t.Fatal(err) }
	home, err := os.UserHomeDir()
	if err != nil { t.Fatal(err) }
	if !strings.EqualFold(filepath.Clean(got), filepath.Clean(home)) {
		t.Fatalf("@home resolved to %q, want %q", got, home)
	}
}

func TestResolveShareDirectoryHandlesTheProvidedMandaadiFilename(t *testing.T) {
	root := t.TempDir()
	container := filepath.Join(root, "38833FF26BA1D.UnigramPreview_g9c9v27vpyspw!App")
	if err := os.Mkdir(container, 0700); err != nil { t.Fatal(err) }
	expectedContainer, err := filepath.EvalSymlinks(container)
	if err != nil { t.Fatal(err) }
	name := "Mandaadi (2026) Telugu HQ HDRip - 1080p - x264 - (DD+5.1.mkv"
	file := filepath.Join(container, name)
	if err := os.WriteFile(file, []byte("video"), 0600); err != nil { t.Fatal(err) }
	got, err := resolveShareDirectory(`"` + file + `"`)
	if err != nil { t.Fatal(err) }
	if !strings.EqualFold(filepath.Clean(got), filepath.Clean(expectedContainer)) {
		t.Fatalf("file path resolved to %q; want containing folder %q", got, expectedContainer)
	}
}
