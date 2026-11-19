//go:build windows

package main

import "testing"

// executableFromOpenCommand reads the Windows registry's open commands, so it
// only exists in the Windows build; keeping its test here lets `go test ./...`
// pass on the Linux/macOS machines that build and vet the helper too.
func TestExecutableFromOpenCommand(t *testing.T) {
	t.Setenv("ProgramFiles", `C:\Program Files`)
	for command, want := range map[string]string{
		`"C:\Apps\VLC\vlc.exe" --started-from-file "%1"`: `C:\Apps\VLC\vlc.exe`,
		`C:\Apps\mpv\mpv.exe "%1"`:                       `C:\Apps\mpv\mpv.exe`,
		`"%ProgramFiles%\VideoLAN\VLC\vlc.exe" "%1"`:     `C:\Program Files\VideoLAN\VLC\vlc.exe`,
	} {
		if got := executableFromOpenCommand(command); got != want {
			t.Errorf("executableFromOpenCommand(%q) = %q, want %q", command, got, want)
		}
	}
	if got := executableFromOpenCommand(`rundll32.exe url.dll,FileProtocolHandler "%1"`); got != "" {
		t.Fatalf("non-absolute system command should be ignored, got %q", got)
	}
}
