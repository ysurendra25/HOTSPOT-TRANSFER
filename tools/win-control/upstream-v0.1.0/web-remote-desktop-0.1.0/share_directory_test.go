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
