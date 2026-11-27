//go:build windows

package main

import (
	"fmt"
	"os"
	"os/exec"
	"path/filepath"
	"syscall"
	"time"
	"unsafe"

	"golang.org/x/sys/windows"
)

var (
	powerUser32       = syscall.NewLazyDLL("user32.dll")
	lockWorkstation   = powerUser32.NewProc("LockWorkStation")
	exitWindowsEx     = powerUser32.NewProc("ExitWindowsEx")
	powerPowrProf     = syscall.NewLazyDLL("powrprof.dll")
	setSuspendState   = powerPowrProf.NewProc("SetSuspendState")
	powerKernel32     = syscall.NewLazyDLL("kernel32.dll")
	powerSetExecState = powerKernel32.NewProc("SetThreadExecutionState")
)

const (
	ewxShutdown    = 0x00000001
	ewxReboot      = 0x00000002
	ewxPowerOff    = 0x00000008
	ewxForceIfHung = 0x00000010
	// SHTDN_REASON_MAJOR_OTHER | SHTDN_REASON_MINOR_OTHER | SHTDN_REASON_FLAG_PLANNED
	shutdownReason = 0x80000000
)

// enableShutdownPrivilege turns on SeShutdownPrivilege in this process token.
// A normal user holds the privilege but Windows ships it disabled, and both
// SetSuspendState and ExitWindowsEx fail with ERROR_PRIVILEGE_NOT_HELD until
// it is enabled. This was why sleep "succeeded" on the phone and nothing
// happened on the laptop.
func enableShutdownPrivilege() error {
	var token windows.Token
	if err := windows.OpenProcessToken(windows.CurrentProcess(), windows.TOKEN_ADJUST_PRIVILEGES|windows.TOKEN_QUERY, &token); err != nil {
		return fmt.Errorf("open process token: %w", err)
	}
	defer token.Close()
	var luid windows.LUID
	name, _ := windows.UTF16PtrFromString("SeShutdownPrivilege")
	if err := windows.LookupPrivilegeValue(nil, name, &luid); err != nil {
		return fmt.Errorf("look up shutdown privilege: %w", err)
	}
	privileges := windows.Tokenprivileges{PrivilegeCount: 1}
	privileges.Privileges[0] = windows.LUIDAndAttributes{Luid: luid, Attributes: windows.SE_PRIVILEGE_ENABLED}
	if err := windows.AdjustTokenPrivileges(token, false, &privileges, uint32(unsafe.Sizeof(privileges)), nil, nil); err != nil {
		return fmt.Errorf("enable shutdown privilege: %w", err)
	}
	return nil
}

func system32(exe string) string {
	root := os.Getenv("SystemRoot")
	if root == "" {
		root = `C:\Windows`
	}
	return filepath.Join(root, "System32", exe)
}

func runHidden(exe string, args ...string) error {
	cmd := exec.Command(system32(exe), args...)
	hideConsole(cmd)
	out, err := cmd.CombinedOutput()
	if err != nil {
		return fmt.Errorf("%s: %v %s", exe, err, string(out))
	}
	return nil
}

func runPowerAction(action string) error {
	switch action {
	case "lock":
		if ok, _, err := lockWorkstation.Call(); ok == 0 {
			// LockWorkStation fails when called from a non-interactive session;
			// rundll32 runs in the signed-in desktop and tries again.
			if fallback := runHidden("rundll32.exe", "user32.dll,LockWorkStation"); fallback != nil {
				return fmt.Errorf("Windows could not lock the session: %v", err)
			}
		}
		return nil
	case "wake-display":
		wakeDisplayNow()
		// Ask for the display once more so the panel does not drop straight
		// back to sleep before the user looks at it.
		powerSetExecState.Call(wkEsDisplayRequired)
		return nil
	case "unlock":
		// Windows sign-in cannot be bypassed from outside. Wake the panel and
		// nudge the lock screen so its PIN/password box is showing.
		wakeDisplayNow()
		powerSetExecState.Call(wkEsDisplayRequired)
		time.Sleep(300 * time.Millisecond)
		wakeDisplayNow()
		return nil
	case "sleep":
		// The helper holds the system awake on mains power so the phone can
		// reach it; drop that hold or Modern Standby refuses to sleep.
		releaseSystemHoldFor(2 * time.Minute)
		privilegeErr := enableShutdownPrivilege()
		// SetSuspendState(hibernate=FALSE, force=FALSE, disableWakeEvents=FALSE).
		if ok, _, err := setSuspendState.Call(0, 0, 0); ok == 0 {
			// Modern Standby laptops have no S3 sleep for SetSuspendState to
			// enter. Turning the display off with no hold active is how those
			// machines go to standby.
			displayOff()
			if privilegeErr != nil {
				return fmt.Errorf("Windows refused sleep (%v); display turned off instead: %v", privilegeErr, err)
			}
			return nil
		}
		return nil
	case "shutdown":
		releaseSystemHoldFor(5 * time.Minute)
		if err := enableShutdownPrivilege(); err == nil {
			if ok, _, _ := exitWindowsEx.Call(ewxShutdown|ewxPowerOff|ewxForceIfHung, shutdownReason); ok != 0 {
				return nil
			}
		}
		return runHidden("shutdown.exe", "/s", "/t", "0")
	case "restart":
		releaseSystemHoldFor(5 * time.Minute)
		if err := enableShutdownPrivilege(); err == nil {
			if ok, _, _ := exitWindowsEx.Call(ewxReboot|ewxForceIfHung, shutdownReason); ok != 0 {
				return nil
			}
		}
		return runHidden("shutdown.exe", "/r", "/t", "0")
	case "cancel-shutdown":
		return runHidden("shutdown.exe", "/a")
	default:
		return fmt.Errorf("unsupported power action")
	}
}
