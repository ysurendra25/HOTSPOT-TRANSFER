package main

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"io"
	"log"
	"net"
	"net/http"
	"net/url"
	"sync"
	"time"
)

// The phone relay: the helper keeps an outbound long-poll open to the paired
// phone and runs the commands the phone queues there (laptop power buttons,
// "reconnect to the hotspot", laptop radios).
//
// Why: the phone's buttons used to reach the helper only by an inbound
// connection to the laptop on port 7070. Windows Firewall blocks that on many
// laptops (and always on networks marked Public), and the phone needs the
// pasted LAN link with its access code. An outbound request from the laptop to
// the phone's PocketBridge server is allowed by default and is authenticated
// by the phone's six-digit code, so the same buttons work without either.
//
// The relay starts when the laptop pairs with the phone through the local
// capture workspace (/phone-signal) or the helper's own page (/phone-link).

type relayTarget struct {
	host string // phone IPv4
	code string // six-digit phone code
}

type relayCommand struct {
	ID      string          `json:"id"`
	Action  string          `json:"action"`
	Confirm bool            `json:"confirm"`
	Data    json.RawMessage `json:"data,omitempty"`
}

type relayResult struct {
	Action  string `json:"action"`
	ID      string `json:"id"`
	OK      bool   `json:"ok"`
	Message string `json:"message,omitempty"`
	Error   string `json:"error,omitempty"`
}

var (
	relayMu      sync.Mutex
	relayCurrent relayTarget
	relayCancel  context.CancelFunc
	relayState   = "not paired"
	relayClient  = &http.Client{Transport: &http.Transport{Proxy: nil, DialContext: (&net.Dialer{Timeout: 4 * time.Second}).DialContext},
		CheckRedirect: func(*http.Request, []*http.Request) error { return http.ErrUseLastResponse }}
	// relayExecutor runs one command; tests replace it.
	relayExecutor = executeRelayCommand
)

// rememberPhone points the relay at a phone. Pairing the same phone again is
// a no-op; a new phone or a new code restarts the loop.
func rememberPhone(host, code string) {
	relayMu.Lock()
	defer relayMu.Unlock()
	target := relayTarget{host: host, code: code}
	if relayCancel != nil && relayCurrent == target {
		return
	}
	if relayCancel != nil {
		relayCancel()
	}
	ctx, cancel := context.WithCancel(context.Background())
	relayCurrent, relayCancel = target, cancel
	relayState = "connecting to " + host
	go relayLoop(ctx, target)
}

func relayStatus() (string, string) {
	relayMu.Lock()
	defer relayMu.Unlock()
	return relayCurrent.host, relayState
}

func setRelayState(target relayTarget, state string) {
	relayMu.Lock()
	defer relayMu.Unlock()
	if relayCurrent == target {
		relayState = state
	}
}

func phoneURL(target relayTarget, path string) string {
	u := url.URL{Scheme: "http", Host: net.JoinHostPort(target.host, "43117"), Path: path, RawQuery: "code=" + url.QueryEscape(target.code)}
	return u.String()
}

// relayLoop polls until cancelled. While the phone is away (airplane mode,
// hotspot restarting) it keeps retrying every two seconds, so the laptop
// reconnects on its own as soon as the phone is back.
func relayLoop(ctx context.Context, target relayTarget) {
	failures := 0
	for ctx.Err() == nil {
		cmd, status, err := relayPoll(ctx, target)
		if ctx.Err() != nil {
			return
		}
		if status == http.StatusUnauthorized || status == http.StatusForbidden {
			setRelayState(target, "the phone code changed; pair again")
			log.Printf("[pc-remote] phone relay stopped: the phone rejected code (HTTP %d)", status)
			return
		}
		if status == http.StatusTooManyRequests {
			// The phone is locked out after wrong codes; never add to it.
			setRelayState(target, "the phone is refusing codes for a minute")
			sleepCtx(ctx, 60*time.Second)
			continue
		}
		if err != nil {
			failures++
			if failures == 1 || failures%30 == 0 {
				log.Printf("[pc-remote] phone relay: %v (retrying)", err)
			}
			setRelayState(target, "waiting for the phone ("+err.Error()+")")
			sleepCtx(ctx, 2*time.Second)
			continue
		}
		failures = 0
		setRelayState(target, "connected")
		if cmd == nil {
			continue
		}
		result := relayExecutor(*cmd)
		result.Action, result.ID = "result", cmd.ID
		relayPost(ctx, target, result)
	}
}

func relayPoll(ctx context.Context, target relayTarget) (*relayCommand, int, error) {
	body, _ := json.Marshal(map[string]any{"action": "poll", "version": helperVersion, "actions": powerActions(),
		"radios": windowsRadiosSupported()})
	pctx, cancel := context.WithTimeout(ctx, 35*time.Second)
	defer cancel()
	req, err := http.NewRequestWithContext(pctx, http.MethodPost, phoneURL(target, "/api/laptop-commands"), bytes.NewReader(body))
	if err != nil {
		return nil, 0, err
	}
	req.Header.Set("Content-Type", "application/json")
	res, err := relayClient.Do(req)
	if err != nil {
		return nil, 0, fmt.Errorf("phone unreachable")
	}
	defer res.Body.Close()
	data, _ := io.ReadAll(io.LimitReader(res.Body, 64*1024))
	if res.StatusCode != http.StatusOK {
		return nil, res.StatusCode, fmt.Errorf("phone answered HTTP %d", res.StatusCode)
	}
	var reply struct {
		Command *relayCommand `json:"command"`
	}
	if err := json.Unmarshal(data, &reply); err != nil {
		return nil, res.StatusCode, fmt.Errorf("unreadable phone reply")
	}
	return reply.Command, res.StatusCode, nil
}

func relayPost(ctx context.Context, target relayTarget, result relayResult) {
	body, _ := json.Marshal(result)
	for attempt := 0; attempt < 3 && ctx.Err() == nil; attempt++ {
		pctx, cancel := context.WithTimeout(ctx, 6*time.Second)
		req, err := http.NewRequestWithContext(pctx, http.MethodPost, phoneURL(target, "/api/laptop-commands"), bytes.NewReader(body))
		if err != nil {
			cancel()
			return
		}
		req.Header.Set("Content-Type", "application/json")
		res, err := relayClient.Do(req)
		cancel()
		if err == nil {
			res.Body.Close()
			return
		}
		sleepCtx(ctx, time.Second)
	}
}

// executeRelayCommand runs one queued command and describes the outcome.
func executeRelayCommand(cmd relayCommand) relayResult {
	switch cmd.Action {
	case "hotspot-reconnect":
		var in struct {
			Minutes int `json:"minutes"`
		}
		_ = json.Unmarshal(cmd.Data, &in)
		profile, err := startHotspotReconnect(time.Duration(maxInt(2, minInt(15, in.Minutes))) * time.Minute)
		if err != nil {
			return relayResult{OK: false, Error: err.Error()}
		}
		return relayResult{OK: true, Message: "The laptop will rejoin \"" + profile + "\" as soon as the hotspot is back."}
	case "laptop-airplane":
		var in struct {
			On bool `json:"on"`
		}
		_ = json.Unmarshal(cmd.Data, &in)
		state, err := setWindowsRadios(!in.On)
		if err != nil {
			return relayResult{OK: false, Error: err.Error()}
		}
		return relayResult{OK: true, Message: "Laptop radios: " + state}
	}
	status, body, deferred := decidePower(powerRequest{Action: cmd.Action, Confirm: cmd.Confirm})
	if deferred != "" {
		// Answer first: sleep/restart/shut down end this connection.
		go func() { time.Sleep(300 * time.Millisecond); schedulePowerAction(deferred) }()
	}
	result := relayResult{OK: status < 300}
	if m, ok := body["message"].(string); ok {
		result.Message = m
	}
	if e, ok := body["error"].(string); ok {
		result.Error = e
	}
	return result
}

func sleepCtx(ctx context.Context, d time.Duration) {
	t := time.NewTimer(d)
	defer t.Stop()
	select {
	case <-ctx.Done():
	case <-t.C:
	}
}
