//go:build !windows

package main

import "fmt"

func discoverMediaPlayers() []mediaPlayer { return nil }

func launchMediaPlayer(player mediaPlayer, mediaURL string) error {
	return fmt.Errorf("external media players are available only on Windows")
}
