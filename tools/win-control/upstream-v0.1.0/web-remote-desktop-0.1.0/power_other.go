//go:build !windows

package main

import "fmt"

func runPowerAction(string) error {
	return fmt.Errorf("PC power controls are available only on Windows")
}
