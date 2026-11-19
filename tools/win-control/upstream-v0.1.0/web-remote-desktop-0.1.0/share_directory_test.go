package main

import (
	"os"
	"path/filepath"
	"testing"
)

func TestResolveShareDirectoryAcceptsFolderAndFilePaths(t *testing.T) {
	root := t.TempDir()
	file := filepath.Join(root, "movie file.mp4")
	if err := os.WriteFile(file, []byte("video"), 0600); err != nil {
		t.Fatal(err)
	}
	for _, requested := range []string{root, `"` + file + `"`} {
		got, err := resolveShareDirectory(requested)
		if err != nil {
			t.Fatalf("resolveShareDirectory(%q): %v", requested, err)
		}
		if got != root {
			t.Errorf("resolveShareDirectory(%q) = %q, want %q", requested, got, root)
		}
	}
}

func TestResolveShareDirectoryRejectsMissingPath(t *testing.T) {
	if _, err := resolveShareDirectory(filepath.Join(t.TempDir(), "missing")); err == nil {
		t.Fatal("expected a missing path to be rejected")
	}
}
