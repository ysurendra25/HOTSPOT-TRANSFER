package main

import (
	"encoding/json"
	"log"
	"net/http"
	"sync"
	"time"
)

// The phone's laptop controls land here: POST /power {"action": "...",
// "confirm": true}. Every answer is JSON with a human sentence in "message",
// so the phone can show exactly what Windows did instead of a bare status.

var powerActionRunner = runPowerAction

// Sleep and shut down end the network connection the phone is waiting on, so
// the helper answers first and acts a moment later.
var schedulePowerAction = func(action string) {
	recordPowerResult(action, nil, true)
	time.AfterFunc(700*time.Millisecond, func() {
		err := powerActionRunner(action)
		if err != nil {
			log.Printf("[pc-remote] %s failed: %v", action, err)
		}
		recordPowerResult(action, err, false)
	})
}

// powerResult is the outcome of the most recent power action. Sleep, restart
// and shut down answer the phone before they run, so the phone asks for this
// afterwards to show a real error instead of an optimistic "sent".
type powerResult struct {
	Action  string `json:"action"`
	OK      bool   `json:"ok"`
	Pending bool   `json:"pending"`
	Error   string `json:"error,omitempty"`
	At      int64  `json:"at"`
}

var (
	lastPowerMu sync.Mutex
	lastPower   powerResult
)

func recordPowerResult(action string, err error, pending bool) {
	lastPowerMu.Lock()
	defer lastPowerMu.Unlock()
	lastPower = powerResult{Action: action, OK: err == nil, Pending: pending, At: time.Now().UnixMilli()}
	if err != nil {
		lastPower.Error = err.Error()
	}
}

func lastPowerResult() powerResult {
	lastPowerMu.Lock()
	defer lastPowerMu.Unlock()
	return lastPower
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
	{ID: "restart", Label: "Restart", Confirm: true, Deferred: true},
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
	case "reboot", "re-start":
		return "restart"
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
	case "restart":
		return "The laptop is restarting. The connection returns when Windows and this helper start again."
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
		writePowerJSON(w, http.StatusOK, map[string]any{"ok": true, "actions": powerActions(), "last": lastPowerResult()})
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
	status, body, deferred := decidePower(req)
	writePowerJSON(w, status, body)
	if deferred != "" {
		if f, ok := w.(http.Flusher); ok {
			f.Flush()
		}
		schedulePowerAction(deferred)
	}
}

// decidePower validates and runs one power request. Immediate actions run
// here; a deferred one (sleep, restart, shut down) is returned for the caller
// to schedule after the answer has been sent. The phone relay uses it too, so
// a button on the phone behaves exactly like the helper's own route.
func decidePower(req powerRequest) (int, map[string]any, string) {
	req.Action = normalizePowerAction(req.Action)
	action, ok := findPowerAction(req.Action)
	if !ok {
		return http.StatusBadRequest, map[string]any{"ok": false, "error": "Unsupported power action: " + req.Action}, ""
	}
	if action.Confirm && !req.Confirm {
		return http.StatusBadRequest, map[string]any{"ok": false, "error": "Confirm " + action.Label + " on the phone first.", "confirm": true}, ""
	}
	if action.Deferred {
		return http.StatusAccepted, map[string]any{"ok": true, "action": action.ID, "scheduled": true, "message": powerMessage(action.ID)}, action.ID
	}
	err := powerActionRunner(action.ID)
	recordPowerResult(action.ID, err, false)
	if err != nil {
		return http.StatusInternalServerError, map[string]any{"ok": false, "action": action.ID, "error": err.Error()}, ""
	}
	return http.StatusOK, map[string]any{"ok": true, "action": action.ID, "message": powerMessage(action.ID)}, ""
}
