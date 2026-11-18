//go:build windows

package main

import (
	"fmt"
	"os"
	"os/exec"
	"path/filepath"
)

func launchVLC(mediaURL string) error {
	var candidates []string
	if path, err := exec.LookPath("vlc.exe"); err == nil {
		candidates = append(candidates, path)
	}
	for _, root := range []string{os.Getenv("ProgramFiles"), os.Getenv("ProgramFiles(x86)"), os.Getenv("LOCALAPPDATA")} {
		if root != "" {
			candidates = append(candidates, filepath.Join(root, "VideoLAN", "VLC", "vlc.exe"))
		}
	}
	for _, executable := range candidates {
		if info, err := os.Stat(executable); err == nil && !info.IsDir() {
			if err := exec.Command(executable, mediaURL).Start(); err == nil {
				return nil
			}
		}
	}
	return fmt.Errorf("VLC was not found. Install VLC, or use the browser player.")
}
