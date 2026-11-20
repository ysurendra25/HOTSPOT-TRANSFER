package main

import (
	"fmt"
	"os"
	"path/filepath"
	"strings"
)

// resolveSharePath accepts a user-selected Windows folder or exact file path.
// Exact-file selections keep the parent as the read root and return the
// basename separately, so the phone lists only the requested file.
func resolveSharePath(requested string) (string, string, error) {
	requested = strings.Trim(strings.TrimSpace(requested), `"'`)
	if strings.EqualFold(requested, "@home") {
		home, err := os.UserHomeDir()
		if err != nil {
			return "", "", fmt.Errorf("could not find the Windows user folder")
		}
		requested = home
	} else if strings.EqualFold(requested, "@default") {
		requested = defaultShareDir()
	}
	if requested == "" {
		return "", "", fmt.Errorf("enter a laptop folder or file path")
	}
	if !filepath.IsAbs(requested) {
		home, err := os.UserHomeDir()
		if err != nil {
			return "", "", fmt.Errorf("could not find the Windows user folder")
		}
		requested = filepath.Join(home, requested)
	}
	absolute, err := filepath.Abs(filepath.Clean(requested))
	if err != nil {
		return "", "", fmt.Errorf("invalid laptop path")
	}
	info, err := os.Stat(absolute)
	if err != nil {
		return "", "", fmt.Errorf("that laptop folder or file does not exist")
	}
	selectedFile := ""
	if !info.IsDir() {
		if !info.Mode().IsRegular() {
			return "", "", fmt.Errorf("select a regular laptop file or a folder")
		}
		absolute = filepath.Dir(absolute)
		// Stat returns the actual on-disk spelling on Windows, so exact matching
		// still succeeds when a user pasted the path with different letter case.
		selectedFile = info.Name()
	}
	resolved, err := filepath.EvalSymlinks(absolute)
	if err != nil {
		return "", "", fmt.Errorf("could not open that laptop folder")
	}
	info, err = os.Stat(resolved)
	if err != nil || !info.IsDir() {
		return "", "", fmt.Errorf("the selected path is not a laptop folder")
	}
	return resolved, selectedFile, nil
}

// resolveShareDirectory remains for callers that only need the containing
// folder; file selection details are carried by resolveSharePath.
func resolveShareDirectory(requested string) (string, error) {
	directory, _, err := resolveSharePath(requested)
	return directory, err
}
