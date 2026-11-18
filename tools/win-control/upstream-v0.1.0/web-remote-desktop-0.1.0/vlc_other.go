//go:build !windows

package main

import "fmt"

func launchVLC(string) error { return fmt.Errorf("VLC launch is available only on Windows") }
