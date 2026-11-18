//go:build windows

package main

import (
	"fmt"
	"os/exec"
	"syscall"
)

var powerUser32 = syscall.NewLazyDLL("user32.dll")
var lockWorkstation = powerUser32.NewProc("LockWorkStation")
var sendMessage = powerUser32.NewProc("SendMessageW")

func runPowerAction(action string) error {
	switch action {
	case "lock":
		ok, _, err := lockWorkstation.Call()
		if ok == 0 {
			return fmt.Errorf("Windows could not lock the session: %v", err)
		}
		return nil
	case "wake-display":
		// Broadcast WM_SYSCOMMAND/SC_MONITORPOWER with -1: request that monitors turn on.
		_, _, _ = sendMessage.Call(0xffff, 0x0112, 0xF170, ^uintptr(1))
		return nil
	case "sleep":
		return exec.Command("rundll32.exe", "powrprof.dll,SetSuspendState", "0,1,0").Run()
	case "shutdown":
		return exec.Command("shutdown.exe", "/s", "/t", "15", "/c", "PocketBridge remote shutdown (15 second delay; cancel with PocketBridge)").Run()
	case "cancel-shutdown":
		return exec.Command("shutdown.exe", "/a").Run()
	default:
		return fmt.Errorf("unsupported power action")
	}
}
