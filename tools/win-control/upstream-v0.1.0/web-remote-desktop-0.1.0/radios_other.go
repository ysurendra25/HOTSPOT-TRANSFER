//go:build !windows

package main

import (
	"fmt"
	"time"
)

func windowsRadiosSupported() bool { return false }

func windowsRadiosState() (string, error) {
	return "", fmt.Errorf("laptop airplane mode is available only on Windows")
}

func setWindowsRadios(on bool) (string, error) {
	return "", fmt.Errorf("laptop airplane mode is available only on Windows")
}

func startHotspotReconnect(d time.Duration) (string, error) {
	return "", fmt.Errorf("rejoining the hotspot automatically is available only on Windows")
}
