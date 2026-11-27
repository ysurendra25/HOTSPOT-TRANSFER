//go:build windows

package main

import (
	"encoding/base64"
	"encoding/binary"
	"fmt"
	"os/exec"
	"regexp"
	"strings"
	"sync"
	"time"
)

func windowsRadiosSupported() bool { return true }

// The Windows.Devices.Radios WinRT API, called through Windows PowerShell 5.1
// (always present on Windows 10/11), so the helper stays a single static Go
// binary. Turning every radio Off is what airplane mode does; the Settings
// "Airplane mode" switch itself is not exposed to apps.
func radiosScript(target string) string {
	return `$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Runtime.WindowsRuntime
$asTask = ([System.WindowsRuntimeSystemExtensions].GetMethods() | Where-Object { $_.Name -eq 'AsTask' -and $_.GetParameters().Count -eq 1 -and $_.GetParameters()[0].ParameterType.Name -eq 'IAsyncOperation` + "`" + `1' })[0]
function Await($op, [Type]$type) { $task = $asTask.MakeGenericMethod($type).Invoke($null, @($op)); $task.Wait(-1) | Out-Null; $task.Result }
[Windows.Devices.Radios.Radio, Windows.System.Devices, ContentType = WindowsRuntime] | Out-Null
[Windows.Devices.Radios.RadioAccessStatus, Windows.System.Devices, ContentType = WindowsRuntime] | Out-Null
[Windows.Devices.Radios.RadioState, Windows.System.Devices, ContentType = WindowsRuntime] | Out-Null
$access = Await ([Windows.Devices.Radios.Radio]::RequestAccessAsync()) ([Windows.Devices.Radios.RadioAccessStatus])
if ($access -ne 'Allowed') { throw "Windows denied radio control ($access). Allow it under Settings > Privacy > Radios." }
$radios = Await ([Windows.Devices.Radios.Radio]::GetRadiosAsync()) ([System.Collections.Generic.IReadOnlyList[Windows.Devices.Radios.Radio]])
$target = '` + target + `'
$out = @()
foreach ($radio in $radios) {
  if ($target -ne 'Query') {
    $result = Await ($radio.SetStateAsync([Windows.Devices.Radios.RadioState]::$target)) ([Windows.Devices.Radios.RadioAccessStatus])
    if ($result -ne 'Allowed') { $out += ('{0}={1}({2})' -f $radio.Kind, $radio.State, $result); continue }
  }
  $out += ('{0}={1}' -f $radio.Kind, $radio.State)
}
[Console]::Out.Write(($out -join ';'))`
}

var radiosMu sync.Mutex

// encodePowerShell returns the UTF-16LE base64 form -EncodedCommand expects,
// which avoids every command-line quoting rule.
func encodePowerShell(script string) string {
	u := make([]byte, 0, len(script)*2)
	for _, r := range script {
		if r > 0xFFFF {
			r = '?'
		}
		u = binary.LittleEndian.AppendUint16(u, uint16(r))
	}
	return base64.StdEncoding.EncodeToString(u)
}

func runRadios(target string) (string, error) {
	radiosMu.Lock()
	defer radiosMu.Unlock()
	cmd := exec.Command(system32(`WindowsPowerShell\v1.0\powershell.exe`), "-NoProfile", "-NonInteractive",
		"-ExecutionPolicy", "Bypass", "-EncodedCommand", encodePowerShell(radiosScript(target)))
	hideConsole(cmd)
	out, err := cmd.CombinedOutput()
	text := strings.TrimSpace(string(out))
	if err != nil {
		if text == "" {
			text = err.Error()
		}
		return "", fmt.Errorf("Windows radio control failed: %s", lastLine(text))
	}
	if text == "" {
		return "", fmt.Errorf("this laptop reports no controllable radios")
	}
	return text, nil
}

func lastLine(s string) string {
	lines := strings.Split(strings.TrimSpace(s), "\n")
	return strings.TrimSpace(lines[len(lines)-1])
}

func windowsRadiosState() (string, error) { return runRadios("Query") }

// setWindowsRadios turns every radio on (on=true) or off (airplane).
func setWindowsRadios(on bool) (string, error) {
	if on {
		return runRadios("On")
	}
	return runRadios("Off")
}

// ---- rejoining the phone hotspot ----

var (
	reconnectMu    sync.Mutex
	reconnectUntil time.Time
	reconnectName  string
)

var netshProfile = regexp.MustCompile(`(?m)^\s*Profile\s*:\s*(.+?)\s*$`)
var netshState = regexp.MustCompile(`(?m)^\s*State\s*:\s*(.+?)\s*$`)

func wlanInterfaces() (profile string, connected bool) {
	cmd := exec.Command(system32("netsh.exe"), "wlan", "show", "interfaces")
	hideConsole(cmd)
	out, err := cmd.Output()
	if err != nil {
		return "", false
	}
	text := string(out)
	if m := netshProfile.FindStringSubmatch(text); m != nil {
		profile = m[1]
	}
	if m := netshState.FindStringSubmatch(text); m != nil {
		connected = strings.EqualFold(m[1], "connected")
	}
	return profile, connected
}

// startHotspotReconnect remembers the Wi-Fi profile in use now (the phone's
// hotspot) and, for the given time, rejoins it every few seconds whenever the
// laptop is not connected to it. Windows does rejoin a saved network by
// itself, but only on its own schedule; after an airplane cycle that can take
// a minute or more.
func startHotspotReconnect(d time.Duration) (string, error) {
	profile, _ := wlanInterfaces()
	reconnectMu.Lock()
	if profile == "" {
		profile = reconnectName
	}
	if profile == "" {
		reconnectMu.Unlock()
		return "", fmt.Errorf("the laptop is not on a Wi-Fi network, so there is no hotspot to rejoin")
	}
	running := time.Now().Before(reconnectUntil)
	reconnectName = profile
	reconnectUntil = time.Now().Add(d)
	reconnectMu.Unlock()
	if !running {
		go reconnectLoop()
	}
	return profile, nil
}

func reconnectLoop() {
	sawDrop := false
	for {
		time.Sleep(3 * time.Second)
		reconnectMu.Lock()
		name, until := reconnectName, reconnectUntil
		reconnectMu.Unlock()
		if time.Now().After(until) {
			return
		}
		current, connected := wlanInterfaces()
		if connected && strings.EqualFold(current, name) {
			if sawDrop {
				// Back on the hotspot: keep the loop alive a little longer in
				// case the phone restarts it again, then stop.
				reconnectMu.Lock()
				if time.Until(reconnectUntil) > 20*time.Second {
					reconnectUntil = time.Now().Add(20 * time.Second)
				}
				reconnectMu.Unlock()
				sawDrop = false
			}
			continue
		}
		sawDrop = true
		cmd := exec.Command(system32("netsh.exe"), "wlan", "connect", "name="+name)
		hideConsole(cmd)
		_ = cmd.Run()
	}
}
