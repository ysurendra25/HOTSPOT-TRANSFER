package main

import (
	"encoding/json"
	"log"
	"net/http"
	"time"
)

var powerActionRunner = runPowerAction

var scheduleSleepAction = func() {
	time.AfterFunc(700*time.Millisecond, func() {
		if err := powerActionRunner("sleep"); err != nil {
			log.Printf("[pc-remote] sleep failed: %v", err)
		}
	})
}

type powerRequest struct {
	Action  string `json:"action"`
	Confirm bool   `json:"confirm"`
}

func supportedPowerAction(action string) bool {
	switch action {
	case "lock", "wake-display", "unlock", "sleep", "shutdown", "cancel-shutdown":
		return true
	default:
		return false
	}
}

func powerHandler(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		http.Error(w, "POST only", http.StatusMethodNotAllowed)
		return
	}
	var req powerRequest
	if err := json.NewDecoder(http.MaxBytesReader(w, r.Body, 2048)).Decode(&req); err != nil {
		http.Error(w, "bad json", http.StatusBadRequest)
		return
	}
	if !supportedPowerAction(req.Action) {
		http.Error(w, "unsupported power action", http.StatusBadRequest)
		return
	}
	if (req.Action == "sleep" || req.Action == "shutdown") && !req.Confirm {
		http.Error(w, "confirmation required", http.StatusBadRequest)
		return
	}
	if req.Action == "sleep" {
		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(http.StatusAccepted)
		_ = json.NewEncoder(w).Encode(map[string]any{"ok": true, "action": req.Action, "scheduled": true})
		scheduleSleepAction()
		return
	}
	if err := powerActionRunner(req.Action); err != nil {
		http.Error(w, err.Error(), http.StatusInternalServerError)
		return
	}
	w.Header().Set("Content-Type", "application/json")
	_ = json.NewEncoder(w).Encode(map[string]any{"ok": true, "action": req.Action})
}
