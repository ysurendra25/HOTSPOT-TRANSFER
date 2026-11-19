package main

import (
	"fmt"
	"os"
	"path/filepath"
	"strings"
)

// resolveShareDirectory accepts a user-selected Windows folder or file path.
// If the user enters a file, its parent folder becomes the shared root so the
// phone can list that file and use the existing path search.
func resolveShareDirectory(requested string) (string, error) {
	requested = strings.Trim(strings.TrimSpace(requested), `"'`)
	if strings.EqualFold(requested, "@home") {
		home, err := os.UserHomeDir()
		if err != nil {
			return "", fmt.Errorf("could not find the Windows user folder")
		}
		requested = home
	}
	if requested == "" {
		return "", fmt.Errorf("enter a laptop folder or file path")
	}
	if !filepath.IsAbs(requested) {
		home, err := os.UserHomeDir()
		if err != nil {
			return "", fmt.Errorf("could not find the Windows user folder")
		}
		requested = filepath.Join(home, requested)
	}
	absolute, err := filepath.Abs(filepath.Clean(requested))
	if err != nil {
		return "", fmt.Errorf("invalid laptop path")
	}
	info, err := os.Stat(absolute)
	if err != nil {
		return "", fmt.Errorf("that laptop folder or file does not exist")
	}
	if !info.IsDir() {
		absolute = filepath.Dir(absolute)
	}
	resolved, err := filepath.EvalSymlinks(absolute)
	if err != nil {
		return "", fmt.Errorf("could not open that laptop folder")
	}
	info, err = os.Stat(resolved)
	if err != nil || !info.IsDir() {
		return "", fmt.Errorf("the selected path is not a laptop folder")
	}
	return resolved, nil
}
