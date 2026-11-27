package main

import (
	"encoding/json"
	"net"
	"net/http"
	"strings"
	"time"
)

// Laptop radios ("airplane mode" for THIS LAPTOP) and the phone-side airplane
// reset, as seen from the Windows helper.
//
// /radios         GET  state of the laptop's radios (Wi-Fi, Bluetooth, mobile broadband)
//                 POST {"airplane":true|false} from this laptop only: turning the
//                 radios off ends any connection from the phone, so it can never
//                 be triggered remotely.
// /phone-link     POST {"phone":"http://192.168.x.y:43117","code":"123456"} from this
//                 laptop: pair the phone relay (see phone_relay.go).
// /phone-airplane POST from this laptop: ask the PHONE to switch airplane mode on and
//                 off and bring its hotspot back; the laptop keeps rejoining the
//                 hotspot until it returns.
// /hotspot-reconnect POST: keep rejoining the current Wi-Fi network for a while.

func isLocalRequest(r *http.Request) bool {
	peer, _, err := net.SplitHostPort(r.RemoteAddr)
	if err != nil {
		return false
	}
	ip := net.ParseIP(peer)
	return ip != nil && ip.IsLoopback()
}

func writeJSON(w http.ResponseWriter, status int, body map[string]any) {
	w.Header().Set("Content-Type", "application/json")
	w.Header().Set("Cache-Control", "no-store")
	w.WriteHeader(status)
	_ = json.NewEncoder(w).Encode(body)
}

func radiosHandler(w http.ResponseWriter, r *http.Request) {
	switch r.Method {
	case http.MethodGet:
		state, err := windowsRadiosState()
		if err != nil {
			writeJSON(w, http.StatusOK, map[string]any{"ok": false, "supported": windowsRadiosSupported(), "error": err.Error()})
			return
		}
		writeJSON(w, http.StatusOK, map[string]any{"ok": true, "supported": true, "radios": state, "airplane": radiosAllOff(state)})
	case http.MethodPost:
		if !isLocalRequest(r) {
			writeJSON(w, http.StatusForbidden, map[string]any{"ok": false,
				"error": "Laptop airplane mode can only be switched on the laptop itself: it would cut the phone's connection."})
			return
		}
		var in struct {
			Airplane *bool `json:"airplane"`
		}
		if json.NewDecoder(http.MaxBytesReader(w, r.Body, 1024)).Decode(&in) != nil || in.Airplane == nil {
			writeJSON(w, http.StatusBadRequest, map[string]any{"ok": false, "error": `Send {"airplane":true} or {"airplane":false}.`})
			return
		}
		state, err := setWindowsRadios(!*in.Airplane)
		if err != nil {
			writeJSON(w, http.StatusInternalServerError, map[string]any{"ok": false, "error": err.Error()})
			return
		}
		writeJSON(w, http.StatusOK, map[string]any{"ok": true, "radios": state, "airplane": radiosAllOff(state)})
	default:
		writeJSON(w, http.StatusMethodNotAllowed, map[string]any{"ok": false, "error": "Use GET or POST."})
	}
}

// radiosAllOff reads "WiFi=Off;Bluetooth=Off" style state.
func radiosAllOff(state string) bool {
	parts := strings.Split(strings.TrimSpace(state), ";")
	seen := false
	for _, p := range parts {
		kv := strings.SplitN(strings.TrimSpace(p), "=", 2)
		if len(kv) != 2 {
			continue
		}
		seen = true
		if !strings.EqualFold(strings.TrimSpace(kv[1]), "Off") && !strings.EqualFold(strings.TrimSpace(kv[1]), "Disabled") {
			return false
		}
	}
	return seen
}

func phoneLinkHandler(w http.ResponseWriter, r *http.Request) {
	if r.Method == http.MethodGet {
		host, state := relayStatus()
		writeJSON(w, http.StatusOK, map[string]any{"ok": true, "phone": host, "state": state})
		return
	}
	if r.Method != http.MethodPost || !isLocalRequest(r) {
		writeJSON(w, http.StatusForbidden, map[string]any{"ok": false, "error": "Pair the phone from this laptop."})
		return
	}
	var in struct{ Phone, Code string }
	if json.NewDecoder(http.MaxBytesReader(w, r.Body, 2048)).Decode(&in) != nil {
		writeJSON(w, http.StatusBadRequest, map[string]any{"ok": false, "error": "Send the phone link and its six-digit code."})
		return
	}
	host, err := validPhoneLink(in.Phone)
	if err != nil || !pairingDigits.MatchString(in.Code) {
		writeJSON(w, http.StatusBadRequest, map[string]any{"ok": false, "error": "Enter the phone's hotspot link (http://…:43117) and its current six-digit code."})
		return
	}
	rememberPhone(host, in.Code)
	writeJSON(w, http.StatusOK, map[string]any{"ok": true, "phone": host})
}

func hotspotReconnectHandler(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		writeJSON(w, http.StatusMethodNotAllowed, map[string]any{"ok": false, "error": "Use POST."})
		return
	}
	profile, err := startHotspotReconnect(10 * time.Minute)
	if err != nil {
		writeJSON(w, http.StatusInternalServerError, map[string]any{"ok": false, "error": err.Error()})
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{"ok": true, "profile": profile})
}

// phoneAirplaneHandler asks the paired phone to run its airplane reset and
// starts rejoining the hotspot on this laptop.
func phoneAirplaneHandler(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost || !isLocalRequest(r) {
		writeJSON(w, http.StatusForbidden, map[string]any{"ok": false, "error": "Start the phone reset from this laptop."})
		return
	}
	relayMu.Lock()
	target := relayCurrent
	relayMu.Unlock()
	if target.host == "" {
		writeJSON(w, http.StatusConflict, map[string]any{"ok": false, "error": "Pair the phone first: enter its hotspot link and code above."})
		return
	}
	profile, reconnectErr := startHotspotReconnect(10 * time.Minute)
	req, _ := http.NewRequestWithContext(r.Context(), http.MethodPost, phoneURL(target, "/api/airplane"),
		strings.NewReader(`{"action":"cycle","from":"windows-helper"}`))
	req.Header.Set("Content-Type", "application/json")
	res, err := relayClient.Do(req)
	if err != nil {
		writeJSON(w, http.StatusBadGateway, map[string]any{"ok": false, "error": "The phone did not answer. Check that PocketBridge is sharing on the phone."})
		return
	}
	defer res.Body.Close()
	var phone map[string]any
	_ = json.NewDecoder(res.Body).Decode(&phone)
	body := map[string]any{"ok": res.StatusCode < 300, "phone": phone, "profile": profile}
	if reconnectErr != nil {
		body["reconnectError"] = reconnectErr.Error()
	}
	writeJSON(w, http.StatusOK, body)
}
