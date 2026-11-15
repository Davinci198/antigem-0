package handlers

import (
	"encoding/json"
	"net/http"

	"gemini-server/pkg/agyaccounts"
)

// AGY multi-account endpoints.
//
// AGY stores one Google account on disk, so "multiple accounts" means saved copies
// of the credential file plus a swap that restarts the hub. These handlers are the
// only place the app touches that store; all secret handling stays inside the guest.

func (h *Handler) accountsAvailable(w http.ResponseWriter) bool {
	if h.Accounts == nil {
		writeJSON(w, http.StatusServiceUnavailable, map[string]string{
			"error": "AGY account store is not available",
		})
		return false
	}
	return true
}

// AgyAccountsHandler lists the saved accounts plus the one AGY is signed into.
func (h *Handler) AgyAccountsHandler(w http.ResponseWriter, r *http.Request) {
	if !h.accountsAvailable(w) {
		return
	}
	switch r.Method {
	case http.MethodGet:
		list, err := h.Accounts.List()
		if err != nil {
			writeJSON(w, http.StatusInternalServerError, map[string]string{"error": err.Error()})
			return
		}
		writeJSON(w, http.StatusOK, list)
	default:
		writeJSON(w, http.StatusMethodNotAllowed, map[string]string{"error": "method not allowed"})
	}
}

type agySaveRequest struct {
	Label         string `json:"label"`
	PromptCredits *int64 `json:"promptCredits"`
	FlowCredits   *int64 `json:"flowCredits"`
	Plan          string `json:"plan"`
}

// AgyAccountsSaveHandler snapshots the account AGY is currently signed into.
func (h *Handler) AgyAccountsSaveHandler(w http.ResponseWriter, r *http.Request) {
	if !h.accountsAvailable(w) {
		return
	}
	if r.Method != http.MethodPost {
		writeJSON(w, http.StatusMethodNotAllowed, map[string]string{"error": "method not allowed"})
		return
	}
	var req agySaveRequest
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		writeJSON(w, http.StatusBadRequest, map[string]string{"error": "invalid JSON payload: " + err.Error()})
		return
	}
	profile, err := h.Accounts.SnapshotCurrent(req.Label, req.PromptCredits, req.FlowCredits, req.Plan)
	if err != nil {
		writeJSON(w, http.StatusConflict, map[string]string{"error": err.Error()})
		return
	}
	writeJSON(w, http.StatusOK, map[string]interface{}{"success": true, "account": profile})
}

// AgyAccountsSwitchHandler installs a saved account and returns immediately; the
// restart and verification continue in the background and are polled through
// AgyAccountsStatusHandler.
func (h *Handler) AgyAccountsSwitchHandler(w http.ResponseWriter, r *http.Request) {
	if !h.accountsAvailable(w) {
		return
	}
	if r.Method != http.MethodPost {
		writeJSON(w, http.StatusMethodNotAllowed, map[string]string{"error": "method not allowed"})
		return
	}
	var req agyaccounts.SwitchRequest
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		writeJSON(w, http.StatusBadRequest, map[string]string{"error": "invalid JSON payload: " + err.Error()})
		return
	}
	if req.ID == "" {
		writeJSON(w, http.StatusBadRequest, map[string]string{"error": "account id required"})
		return
	}
	state, err := h.Accounts.Switch(req)
	if err != nil {
		writeJSON(w, http.StatusConflict, map[string]interface{}{"error": err.Error(), "switch": state})
		return
	}
	writeJSON(w, http.StatusAccepted, map[string]interface{}{"success": true, "switch": state})
}

// AgyAccountsStatusHandler reports whether the in-flight swap finished or failed.
func (h *Handler) AgyAccountsStatusHandler(w http.ResponseWriter, r *http.Request) {
	if !h.accountsAvailable(w) {
		return
	}
	if r.Method != http.MethodGet {
		writeJSON(w, http.StatusMethodNotAllowed, map[string]string{"error": "method not allowed"})
		return
	}
	writeJSON(w, http.StatusOK, map[string]interface{}{"switch": h.Accounts.Status()})
}

type agyAccountIDRequest struct {
	ID    string `json:"id"`
	Label string `json:"label"`
}

// AgyAccountsDeleteHandler removes a saved account (never the active one).
func (h *Handler) AgyAccountsDeleteHandler(w http.ResponseWriter, r *http.Request) {
	if !h.accountsAvailable(w) {
		return
	}
	if r.Method != http.MethodPost {
		writeJSON(w, http.StatusMethodNotAllowed, map[string]string{"error": "method not allowed"})
		return
	}
	var req agyAccountIDRequest
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		writeJSON(w, http.StatusBadRequest, map[string]string{"error": "invalid JSON payload: " + err.Error()})
		return
	}
	if req.ID == "" {
		writeJSON(w, http.StatusBadRequest, map[string]string{"error": "account id required"})
		return
	}
	if err := h.Accounts.Delete(req.ID); err != nil {
		writeJSON(w, http.StatusConflict, map[string]string{"error": err.Error()})
		return
	}
	writeJSON(w, http.StatusOK, map[string]interface{}{"success": true})
}

// AgyAccountsLabelHandler renames a saved account; an empty label resets it to the email.
func (h *Handler) AgyAccountsLabelHandler(w http.ResponseWriter, r *http.Request) {
	if !h.accountsAvailable(w) {
		return
	}
	if r.Method != http.MethodPost {
		writeJSON(w, http.StatusMethodNotAllowed, map[string]string{"error": "method not allowed"})
		return
	}
	var req agyAccountIDRequest
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		writeJSON(w, http.StatusBadRequest, map[string]string{"error": "invalid JSON payload: " + err.Error()})
		return
	}
	if req.ID == "" {
		writeJSON(w, http.StatusBadRequest, map[string]string{"error": "account id required"})
		return
	}
	profile, err := h.Accounts.Label(req.ID, req.Label)
	if err != nil {
		writeJSON(w, http.StatusConflict, map[string]string{"error": err.Error()})
		return
	}
	writeJSON(w, http.StatusOK, map[string]interface{}{"success": true, "account": profile})
}
