package main

import (
	"encoding/json"
	"net/http"
)

type powerRequest struct {
	Action  string `json:"action"`
	Confirm bool   `json:"confirm"`
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
	if req.Action != "lock" && req.Action != "wake-display" && req.Action != "sleep" && req.Action != "shutdown" && req.Action != "cancel-shutdown" {
		http.Error(w, "unsupported power action", http.StatusBadRequest)
		return
	}
	if (req.Action == "sleep" || req.Action == "shutdown") && !req.Confirm {
		http.Error(w, "confirmation required", http.StatusBadRequest)
		return
	}
	if err := runPowerAction(req.Action); err != nil {
		http.Error(w, err.Error(), http.StatusInternalServerError)
		return
	}
	w.Header().Set("Content-Type", "application/json")
	_ = json.NewEncoder(w).Encode(map[string]any{"ok": true, "action": req.Action})
}
