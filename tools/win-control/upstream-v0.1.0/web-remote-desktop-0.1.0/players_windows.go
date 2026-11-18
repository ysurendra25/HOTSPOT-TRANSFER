//go:build windows

package main

import (
	"os"
	"os/exec"
	"path/filepath"
	"regexp"
	"sort"
	"strings"

	"golang.org/x/sys/windows/registry"
)

type playerSpec struct {
	id, name string
	exes     []string
	dirs     []string
}

func discoverMediaPlayers() []mediaPlayer {
	programRoots := []string{os.Getenv("ProgramFiles"), os.Getenv("ProgramFiles(x86)"), os.Getenv("LOCALAPPDATA"), os.Getenv("USERPROFILE")}
	specs := []playerSpec{
		{"vlc", "VLC media player", []string{"vlc.exe"}, []string{"VideoLAN/VLC"}},
		{"mpv", "mpv", []string{"mpv.exe"}, []string{"mpv", "Programs/mpv", "scoop/apps/mpv/current"}},
		{"mpc-hc", "MPC-HC", []string{"mpc-hc64.exe", "mpc-hc.exe"}, []string{"MPC-HC", "K-Lite Codec Pack/MPC-HC64", "K-Lite Codec Pack/MPC-HC"}},
		{"mpc-be", "MPC-BE", []string{"mpc-be64.exe", "mpc-be.exe"}, []string{"MPC-BE", "MPC-BE x64"}},
		{"potplayer", "PotPlayer", []string{"PotPlayerMini64.exe", "PotPlayerMini.exe"}, []string{"DAUM/PotPlayer", "PotPlayer"}},
		{"gom", "GOM Player", []string{"GOM.exe"}, []string{"GOM/GOMPlayer", "GOM/GOMPlayer64"}},
		{"kmplayer", "KMPlayer", []string{"KMPlayer.exe"}, []string{"KMPlayer"}},
		{"wmp", "Windows Media Player", []string{"wmplayer.exe"}, []string{"Windows Media Player"}},
	}
	players := make([]mediaPlayer, 0, len(specs))
	seen := map[string]bool{}
	for _, spec := range specs {
		if executable := findPlayerExecutable(spec.exes, spec.dirs, programRoots); executable != "" {
			players = append(players, mediaPlayer{ID: spec.id, Name: spec.name, Path: executable})
			for _, name := range spec.exes {
				seen[strings.ToLower(name)] = true
			}
		}
	}
	for _, exe := range registeredVideoPlayerNames() {
		name := strings.ToLower(filepath.Base(exe))
		if seen[name] {
			continue
		}
		if executable := findPlayerExecutable([]string{exe}, nil, programRoots); executable != "" {
			id := strings.TrimSuffix(name, ".exe")
			players = append(players, mediaPlayer{ID: id, Name: strings.TrimSuffix(filepath.Base(executable), filepath.Ext(executable)), Path: executable})
			seen[name] = true
		}
	}
	for _, executable := range registeredVideoPlayerExecutables() {
		key := strings.ToLower(filepath.Clean(executable))
		nameKey := strings.ToLower(filepath.Base(executable))
		if seen[key] || seen[nameKey] || !regularExecutable(executable) {
			continue
		}
		name := filepath.Base(executable)
		players = append(players, mediaPlayer{
			ID:   strings.TrimSuffix(strings.ToLower(name), filepath.Ext(name)),
			Name: strings.TrimSuffix(name, filepath.Ext(name)),
			Path: executable,
		})
		seen[key] = true
		seen[nameKey] = true
	}
	sort.Slice(players, func(i, j int) bool { return strings.ToLower(players[i].Name) < strings.ToLower(players[j].Name) })
	return players
}

func findPlayerExecutable(names, dirs, roots []string) string {
	for _, name := range names {
		if path, err := exec.LookPath(name); err == nil {
			return path
		}
		if path := appPathRegistryValue(name); path != "" && regularExecutable(path) {
			return path
		}
		for _, root := range roots {
			if root == "" {
				continue
			}
			for _, dir := range dirs {
				candidate := filepath.Join(root, filepath.FromSlash(dir), name)
				if regularExecutable(candidate) {
					return candidate
				}
			}
			if regularExecutable(filepath.Join(root, name)) {
				return filepath.Join(root, name)
			}
		}
	}
	return ""
}

func regularExecutable(path string) bool {
	info, err := os.Stat(path)
	return err == nil && !info.IsDir()
}

func appPathRegistryValue(exe string) string {
	keys := []struct {
		root registry.Key
		path string
	}{
		{registry.CURRENT_USER, `Software\Microsoft\Windows\CurrentVersion\App Paths\` + exe},
		{registry.LOCAL_MACHINE, `Software\Microsoft\Windows\CurrentVersion\App Paths\` + exe},
		{registry.LOCAL_MACHINE, `Software\WOW6432Node\Microsoft\Windows\CurrentVersion\App Paths\` + exe},
	}
	for _, item := range keys {
		key, err := registry.OpenKey(item.root, item.path, registry.QUERY_VALUE)
		if err != nil {
			continue
		}
		value, _, err := key.GetStringValue("")
		key.Close()
		if err == nil && regularExecutable(value) {
			return value
		}
	}
	return ""
}

func registeredVideoPlayerNames() []string {
	var names []string
	for _, ext := range []string{".mp4", ".mkv", ".avi", ".mov"} {
		key, err := registry.OpenKey(registry.CURRENT_USER,
			`Software\Microsoft\Windows\CurrentVersion\Explorer\FileExts\`+ext+`\OpenWithList`, registry.QUERY_VALUE)
		if err != nil {
			continue
		}
		values, _ := key.ReadValueNames(0)
		for _, valueName := range values {
			if strings.EqualFold(valueName, "MRUList") {
				continue
			}
			value, _, readErr := key.GetStringValue(valueName)
			if readErr == nil && strings.HasSuffix(strings.ToLower(value), ".exe") {
				names = append(names, filepath.Base(value))
			}
		}
		key.Close()
	}
	return names
}

// RegisteredApplications lists media handlers Windows already associates with
// common video formats. This supplements the built-in player list, so a player
// installed in a custom folder can still appear in PocketBridge's chooser.
func registeredVideoPlayerExecutables() []string {
	var executables []string
	roots := []registry.Key{registry.CURRENT_USER, registry.LOCAL_MACHINE}
	for _, root := range roots {
		for _, ext := range []string{".mp4", ".mkv", ".avi", ".mov", ".webm", ".m4v"} {
			key, err := registry.OpenKey(root, `Software\Classes\`+ext+`\OpenWithProgids`, registry.QUERY_VALUE|registry.ENUMERATE_SUB_KEYS)
			if err != nil {
				continue
			}
			progIDs, _ := key.ReadValueNames(0)
			key.Close()
			for _, progID := range progIDs {
				commandKey, openErr := registry.OpenKey(root, `Software\Classes\`+progID+`\shell\open\command`, registry.QUERY_VALUE)
				if openErr != nil {
					continue
				}
				command, _, readErr := commandKey.GetStringValue("")
				commandKey.Close()
				if readErr != nil {
					continue
				}
				if executable := executableFromOpenCommand(command); executable != "" {
					executables = append(executables, executable)
				}
			}
		}
	}
	return executables
}

func executableFromOpenCommand(command string) string {
	command = strings.TrimSpace(command)
	if command == "" {
		return ""
	}
	var executable string
	if command[0] == '"' {
		if end := strings.IndexByte(command[1:], '"'); end >= 0 {
			executable = command[1 : end+1]
		}
	} else if end := strings.Index(strings.ToLower(command), ".exe"); end >= 0 {
		executable = strings.TrimSpace(command[:end+4])
	}
	if executable == "" {
		return ""
	}
	executable = regexp.MustCompile(`%([^%]+)%`).ReplaceAllStringFunc(executable, func(variable string) string {
		return os.Getenv(strings.Trim(variable, "%"))
	})
	if !filepath.IsAbs(executable) || !strings.EqualFold(filepath.Ext(executable), ".exe") {
		return ""
	}
	return filepath.Clean(executable)
}

func launchMediaPlayer(player mediaPlayer, mediaURL string) error {
	return exec.Command(player.Path, mediaURL).Start()
}
