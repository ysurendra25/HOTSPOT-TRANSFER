//go:build !windows

package main

import "fmt"

func runPowerAction(action string) error {
	return fmt.Errorf("PC power controls are available only on Windows (asked for %q)", action)
}
