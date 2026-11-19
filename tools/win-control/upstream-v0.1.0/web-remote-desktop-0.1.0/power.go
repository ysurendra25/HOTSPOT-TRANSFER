package main

import (
	"encoding/json"
	"log"
	"net/http"
	"time"
)

// The phone's laptop controls land here: POST /power {"action": "...",
// "confirm": true}. Every answer is JSON with a human sentence in "message",
// so the phone can show exactly what Windows did instead of a bare status.

var powerActionRunner = runPowerAction

// Sleep and shut down end the network connection the phone is waiting on, so
// the helper answers first and acts a moment later.
var schedulePowerAction = func(action string) {
	time.AfterFunc(700*time.Millisecond, func() {
		if err := powerActionRunner(action); err != nil {
			log.Printf("[pc-remote] %s failed: %v", action, err)
		}
	})
}

type powerRequest struct {
	Action  string `json:"action"`
	Confirm bool   `json:"confirm"`
}

type powerAction struct {
	ID       string `json:"id"`
	Label    string `json:"label"`
	Confirm  bool   `json:"confirm"`
	Deferred bool   `json:"deferred"`
	Note     string `json:"note,omitempty"`
}

var powerCatalog = []powerAction{
	{ID: "lock", Label: "Lock laptop"},
	{ID: "wake-display", Label: "Wake display"},
	{ID: "unlock", Label: "Wake & show sign-in",
		Note: "Windows sign-in cannot be bypassed: this wakes the display so the PIN or password box is ready on the laptop."},
	{ID: "sleep", Label: "Sleep", Confirm: true, Deferred: true},
	{ID: "shutdown", Label: "Shut down", Confirm: true, Deferred: true},
	{ID: "cancel-shutdown", Label: "Cancel shutdown"},
}

func powerActions() []powerAction { return powerCatalog }

func findPowerAction(action string) (powerAction, bool) {
	for _, a := range powerCatalog {
		if a.ID == action {
			return a, true
		}
	}
	return powerAction{}, false
}

func supportedPowerAction(action string) bool {
	_, ok := findPowerAction(action)
	return ok
}

// normalizePowerAction accepts the spellings older phones and pages used.
func normalizePowerAction(action string) string {
	switch action {
	case "wake", "wake_display", "wakeDisplay", "display-on":
		return "wake-display"
	case "show-sign-in", "signin", "sign-in":
		return "unlock"
	case "suspend":
		return "sleep"
	case "poweroff", "power-off", "shut-down":
		return "shutdown"
	}
	return action
}

func powerMessage(action string) string {
	switch action {
	case "lock":
		return "Laptop locked."
	case "wake-display":
		return "Laptop display woken."
	case "unlock":
		return "Display woken and sign-in screen shown. Enter the PIN or password on the laptop; Windows does not allow remote sign-in."
	case "sleep":
		return "The laptop will sleep in a moment. It disconnects until it wakes."
	case "shutdown":
		return "The laptop is shutting down. The connection will end."
	case "cancel-shutdown":
		return "Pending shutdown cancelled."
	}
	return "Done."
}

func writePowerJSON(w http.ResponseWriter, status int, body map[string]any) {
	w.Header().Set("Content-Type", "application/json")
	w.Header().Set("Cache-Control", "no-store")
	w.WriteHeader(status)
	_ = json.NewEncoder(w).Encode(body)
}

func powerHandler(w http.ResponseWriter, r *http.Request) {
	switch r.Method {
	case http.MethodGet, http.MethodHead:
		writePowerJSON(w, http.StatusOK, map[string]any{"ok": true, "actions": powerActions()})
		return
	case http.MethodPost:
	default:
		w.Header().Set("Allow", "GET, POST")
		writePowerJSON(w, http.StatusMethodNotAllowed, map[string]any{"ok": false, "error": "Use POST to run a power action."})
		return
	}
	var req powerRequest
	if err := json.NewDecoder(http.MaxBytesReader(w, r.Body, 2048)).Decode(&req); err != nil {
		// Also accept a plain form post (?action=lock or action=lock in the body).
		if a := r.URL.Query().Get("action"); a != "" {
			req.Action = a
			req.Confirm = r.URL.Query().Get("confirm") == "true" || r.URL.Query().Get("confirm") == "1"
		} else {
			writePowerJSON(w, http.StatusBadRequest, map[string]any{"ok": false, "error": "Send JSON like {\"action\":\"lock\"}."})
			return
		}
	}
	req.Action = normalizePowerAction(req.Action)
	action, ok := findPowerAction(req.Action)
	if !ok {
		writePowerJSON(w, http.StatusBadRequest, map[string]any{"ok": false, "error": "Unsupported power action: " + req.Action})
		return
	}
	if action.Confirm && !req.Confirm {
		writePowerJSON(w, http.StatusBadRequest, map[string]any{"ok": false, "error": "Confirm " + action.Label + " on the phone first.", "confirm": true})
		return
	}
	if action.Deferred {
		writePowerJSON(w, http.StatusAccepted, map[string]any{"ok": true, "action": action.ID, "scheduled": true, "message": powerMessage(action.ID)})
		if f, ok := w.(http.Flusher); ok {
			f.Flush()
		}
		schedulePowerAction(action.ID)
		return
	}
	if err := powerActionRunner(action.ID); err != nil {
		writePowerJSON(w, http.StatusInternalServerError, map[string]any{"ok": false, "action": action.ID, "error": err.Error()})
		return
	}
	writePowerJSON(w, http.StatusOK, map[string]any{"ok": true, "action": action.ID, "message": powerMessage(action.ID)})
}
