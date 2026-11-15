// Package agyaccounts stores and swaps Antigravity (AGY) Google accounts.
//
// AGY keeps exactly one signed-in user on disk:
//
//	$HOME/.gemini/jetski-standalone-oauth-token        the OAuth bundle (access + refresh token)
//	$HOME/.config/antigravity/credential-user-scope    a small per-user scope marker
//
// A saved account is a byte-for-byte copy of those two files, kept under
// $HOME/.antigem/agy-accounts. Switching writes the target copy back and restarts
// the AGY hub, because the hub keeps the refreshed token in memory and never
// writes it back to disk (the on-disk token can be expired while AGY still works).
package agyaccounts

import (
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"os"
	"path/filepath"
	"sort"
	"strings"
	"sync"
	"time"
)

// Restarter is the slice of hub.HubManager this package needs. The hub is restarted
// after a swap so the new credential file is read from scratch.
type Restarter interface {
	Restart() error
	IsPortActive() bool
	Port() string
}

// Switch phases reported to the app while a swap is in flight.
const (
	PhaseIdle       = "idle"
	PhaseSwitching  = "switching"
	PhaseRestarting = "restarting"
	PhaseVerifying  = "verifying"
	PhaseReady      = "ready"
	PhaseFailed     = "failed"
)

// Profile is one saved AGY account. The secrets live in <id>.token / <id>.scope;
// the index only carries what the UI shows.
type Profile struct {
	ID                     string    `json:"id"`
	Email                  string    `json:"email"`
	Label                  string    `json:"label,omitempty"`
	SavedAt                time.Time `json:"savedAt"`
	LastKnownPromptCredits *int64    `json:"lastKnownPromptCredits,omitempty"`
	LastKnownFlowCredits   *int64    `json:"lastKnownFlowCredits,omitempty"`
	LastKnownPlan          string    `json:"lastKnownPlan,omitempty"`
	NeedsRelogin           bool      `json:"needsRelogin,omitempty"`
}

// SwitchState is polled by the app after POST /api/agy/accounts/switch.
type SwitchState struct {
	Phase      string     `json:"phase"`
	TargetID   string     `json:"targetId,omitempty"`
	TargetMail string     `json:"targetEmail,omitempty"`
	Message    string     `json:"message,omitempty"`
	StartedAt  *time.Time `json:"startedAt,omitempty"`
	FinishedAt *time.Time `json:"finishedAt,omitempty"`
}

// ListResult is what GET /api/agy/accounts returns.
type ListResult struct {
	Accounts    []Profile   `json:"accounts"`
	ActiveID    string      `json:"activeId"`
	ActiveEmail string      `json:"activeEmail"`
	Switch      SwitchState `json:"switch"`
}

// Manager owns the account store and the (single) in-flight swap.
type Manager struct {
	mu    sync.Mutex
	home  string
	hub   Restarter
	state SwitchState
}

// NewManager builds a manager for the given guest HOME (empty means os.UserHomeDir).
func NewManager(home string, hub Restarter) *Manager {
	if home == "" {
		if h, err := os.UserHomeDir(); err == nil {
			home = h
		}
	}
	return &Manager{home: home, hub: hub, state: SwitchState{Phase: PhaseIdle}}
}

func (m *Manager) tokenPath() string {
	return filepath.Join(m.home, ".gemini", "jetski-standalone-oauth-token")
}
func (m *Manager) scopePath() string {
	return filepath.Join(m.home, ".config", "antigravity", "credential-user-scope")
}
func (m *Manager) dirPath() string { return filepath.Join(m.home, ".antigem", "agy-accounts") }
func (m *Manager) indexPath() string {
	return filepath.Join(m.dirPath(), "index.json")
}

type indexFile struct {
	Accounts []Profile `json:"accounts"`
}

type tokenInfo struct {
	AccessToken  string `json:"access_token"`
	TokenType    string `json:"token_type"`
	RefreshToken string `json:"refresh_token"`
	Expiry       string `json:"expiry"`
	AuthMethod   string `json:"auth_method"`
	Email        string `json:"id_token_email"`
	WriterID     string `json:"writer_id"`
}

// idFor derives the profile id from the account email, so the same account always
// maps to the same files even if it is added twice.
func idFor(email string) string {
	sum := sha256.Sum256([]byte(strings.ToLower(strings.TrimSpace(email))))
	return hex.EncodeToString(sum[:])[:12]
}

func parseToken(raw []byte) (tokenInfo, error) {
	var t tokenInfo
	if err := json.Unmarshal(raw, &t); err != nil {
		return t, fmt.Errorf("token file is not valid JSON: %w", err)
	}
	return t, nil
}

// CurrentEmail reports the account AGY currently has on disk, or "" when signed out.
func (m *Manager) CurrentEmail() string {
	raw, err := os.ReadFile(m.tokenPath())
	if err != nil {
		return ""
	}
	t, err := parseToken(raw)
	if err != nil {
		return ""
	}
	return strings.TrimSpace(t.Email)
}

func (m *Manager) loadIndex() (indexFile, error) {
	var idx indexFile
	raw, err := os.ReadFile(m.indexPath())
	if err != nil {
		if os.IsNotExist(err) {
			return idx, nil
		}
		return idx, err
	}
	if err := json.Unmarshal(raw, &idx); err != nil {
		return idx, fmt.Errorf("corrupt account index: %w", err)
	}
	return idx, nil
}

func (m *Manager) saveIndex(idx indexFile) error {
	if err := os.MkdirAll(m.dirPath(), 0o700); err != nil {
		return err
	}
	sort.Slice(idx.Accounts, func(i, j int) bool { return idx.Accounts[i].SavedAt.After(idx.Accounts[j].SavedAt) })
	raw, err := json.MarshalIndent(idx, "", "  ")
	if err != nil {
		return err
	}
	return writePrivate(m.indexPath(), raw)
}

// writePrivate writes data with mode 0600 through a temp file so a crash never
// leaves a truncated credential behind.
func writePrivate(path string, data []byte) error {
	if err := os.MkdirAll(filepath.Dir(path), 0o700); err != nil {
		return err
	}
	tmp := path + ".tmp"
	if err := os.WriteFile(tmp, data, 0o600); err != nil {
		return err
	}
	if err := os.Chmod(tmp, 0o600); err != nil {
		_ = os.Remove(tmp)
		return err
	}
	return os.Rename(tmp, path)
}

func (m *Manager) profileTokenPath(id string) string {
	return filepath.Join(m.dirPath(), id+".token")
}
func (m *Manager) profileScopePath(id string) string {
	return filepath.Join(m.dirPath(), id+".scope")
}

func (m *Manager) findAccount(idx indexFile, id string) (Profile, int, bool) {
	for i, a := range idx.Accounts {
		if a.ID == id {
			return a, i, true
		}
	}
	return Profile{}, -1, false
}

// SnapshotCurrent saves the account AGY is signed into right now. Credits and plan
// are optional hints the app already knows from GetUserStatus, kept so the list can
// show "last known" numbers for accounts that are not active.
func (m *Manager) SnapshotCurrent(label string, prompt, flow *int64, plan string) (Profile, error) {
	m.mu.Lock()
	defer m.mu.Unlock()
	return m.snapshotLocked(label, prompt, flow, plan)
}

func (m *Manager) snapshotLocked(label string, prompt, flow *int64, plan string) (Profile, error) {
	raw, err := os.ReadFile(m.tokenPath())
	if err != nil {
		if os.IsNotExist(err) {
			return Profile{}, fmt.Errorf("AGY is not signed in on this device (no token file)")
		}
		return Profile{}, err
	}
	t, err := parseToken(raw)
	if err != nil {
		return Profile{}, err
	}
	email := strings.TrimSpace(t.Email)
	if email == "" {
		return Profile{}, fmt.Errorf("token file has no account email")
	}

	id := idFor(email)
	if err := writePrivate(m.profileTokenPath(id), raw); err != nil {
		return Profile{}, err
	}
	if scope, scopeErr := os.ReadFile(m.scopePath()); scopeErr == nil {
		if err := writePrivate(m.profileScopePath(id), scope); err != nil {
			return Profile{}, err
		}
	}

	idx, err := m.loadIndex()
	if err != nil {
		return Profile{}, err
	}
	now := time.Now().UTC()
	prof, pos, found := m.findAccount(idx, id)
	if found {
		prof.Email = email
		prof.NeedsRelogin = false
		if prompt != nil {
			prof.LastKnownPromptCredits = prompt
		}
		if flow != nil {
			prof.LastKnownFlowCredits = flow
		}
		if plan != "" {
			prof.LastKnownPlan = plan
		}
		if label != "" {
			prof.Label = label
		}
		idx.Accounts[pos] = prof
	} else {
		prof = Profile{
			ID:                     id,
			Email:                  email,
			Label:                  label,
			SavedAt:                now,
			LastKnownPromptCredits: prompt,
			LastKnownFlowCredits:   flow,
			LastKnownPlan:          plan,
		}
		idx.Accounts = append(idx.Accounts, prof)
	}
	if err := m.saveIndex(idx); err != nil {
		return Profile{}, err
	}
	return prof, nil
}

// List returns the saved accounts and which one AGY is currently signed into.
func (m *Manager) List() (ListResult, error) {
	m.mu.Lock()
	defer m.mu.Unlock()

	idx, err := m.loadIndex()
	if err != nil {
		return ListResult{}, err
	}
	active := m.CurrentEmail()
	res := ListResult{Accounts: idx.Accounts, ActiveEmail: active, Switch: m.state}
	if res.Accounts == nil {
		res.Accounts = []Profile{}
	}
	if active != "" {
		res.ActiveID = idFor(active)
	}
	return res, nil
}

// Status reports the in-flight swap, if any.
func (m *Manager) Status() SwitchState {
	m.mu.Lock()
	defer m.mu.Unlock()
	return m.state
}

// SwitchRequest is the payload of POST /api/agy/accounts/switch.
type SwitchRequest struct {
	ID            string `json:"id"`
	PromptCredits *int64 `json:"promptCredits,omitempty"`
	FlowCredits   *int64 `json:"flowCredits,omitempty"`
	Plan          string `json:"plan,omitempty"`
}

// Switch validates the request, snapshots the account being left, installs the
// target credential and then finishes the restart + verification in the background
// (the app polls Status). Returning early keeps the HTTP call short: a hub restart
// takes tens of seconds and would otherwise time out the client.
func (m *Manager) Switch(req SwitchRequest) (SwitchState, error) {
	m.mu.Lock()
	if m.state.Phase == PhaseSwitching || m.state.Phase == PhaseRestarting || m.state.Phase == PhaseVerifying {
		m.mu.Unlock()
		return m.state, fmt.Errorf("a swap is already in progress")
	}
	if m.hub == nil {
		m.mu.Unlock()
		return m.state, fmt.Errorf("AGY hub is not available")
	}

	targetRaw, err := os.ReadFile(m.profileTokenPath(req.ID))
	if err != nil {
		m.mu.Unlock()
		return m.state, fmt.Errorf("saved account not found")
	}
	target, err := parseToken(targetRaw)
	if err != nil {
		m.mu.Unlock()
		return m.state, err
	}
	idx, err := m.loadIndex()
	if err != nil {
		m.mu.Unlock()
		return m.state, err
	}
	prof, _, found := m.findAccount(idx, req.ID)
	if !found {
		m.mu.Unlock()
		return m.state, fmt.Errorf("saved account not found")
	}
	if strings.TrimSpace(target.Email) != prof.Email {
		m.mu.Unlock()
		return m.state, fmt.Errorf("stored credential does not match the saved account")
	}

	active := m.CurrentEmail()
	if active != "" && strings.EqualFold(active, prof.Email) {
		m.mu.Unlock()
		return m.state, fmt.Errorf("this account is already active")
	}

	// Snapshot the account we are leaving so its (possibly refreshed) token is not lost.
	if active != "" {
		if _, snapErr := m.snapshotLocked("", req.PromptCredits, req.FlowCredits, req.Plan); snapErr != nil {
			m.mu.Unlock()
			return m.state, fmt.Errorf("could not save the current account before switching: %w", snapErr)
		}
	}

	prevToken, prevTokenErr := os.ReadFile(m.tokenPath())
	prevScope, prevScopeErr := os.ReadFile(m.scopePath())

	if err := writePrivate(m.tokenPath(), targetRaw); err != nil {
		m.mu.Unlock()
		return m.state, fmt.Errorf("could not install the account token: %w", err)
	}
	if scope, scopeErr := os.ReadFile(m.profileScopePath(req.ID)); scopeErr == nil {
		if err := writePrivate(m.scopePath(), scope); err != nil {
			m.mu.Unlock()
			return m.state, fmt.Errorf("could not install the account scope: %w", err)
		}
	}

	now := time.Now().UTC()
	m.state = SwitchState{
		Phase:      PhaseSwitching,
		TargetID:   prof.ID,
		TargetMail: prof.Email,
		Message:    "restarting the AGY hub",
		StartedAt:  &now,
	}
	state := m.state
	m.mu.Unlock()

	rollback := func(reason string) {
		if prevTokenErr == nil {
			_ = writePrivate(m.tokenPath(), prevToken)
		} else {
			_ = os.Remove(m.tokenPath())
		}
		if prevScopeErr == nil {
			_ = writePrivate(m.scopePath(), prevScope)
		}
		// Mark the account we tried to switch to: its saved copy did not authenticate.
		m.mu.Lock()
		if idx, err := m.loadIndex(); err == nil {
			if p, pos, ok := m.findAccount(idx, prof.ID); ok {
				p.NeedsRelogin = true
				idx.Accounts[pos] = p
				_ = m.saveIndex(idx)
			}
		}
		m.mu.Unlock()

		_ = m.hub.Restart()
		finished := time.Now().UTC()
		m.mu.Lock()
		m.state = SwitchState{
			Phase:      PhaseFailed,
			TargetID:   prof.ID,
			TargetMail: prof.Email,
			Message:    reason,
			StartedAt:  &now,
			FinishedAt: &finished,
		}
		m.mu.Unlock()
	}

	go func() {
		m.mu.Lock()
		m.state.Phase = PhaseRestarting
		m.state.Message = "restarting the AGY hub"
		m.mu.Unlock()

		if err := m.hub.Restart(); err != nil {
			rollback(fmt.Sprintf("AGY hub did not start with this account: %v", err))
			return
		}

		m.mu.Lock()
		m.state.Phase = PhaseVerifying
		m.state.Message = "checking the account with AGY"
		port := m.hub.Port()
		m.mu.Unlock()

		valid, probeErr := probeAuth(port)
		if probeErr != nil {
			rollback(fmt.Sprintf("could not verify the account: %v", probeErr))
			return
		}
		if !valid {
			rollback("AGY rejected the saved credential — sign in again for this account")
			return
		}

		finished := time.Now().UTC()
		m.mu.Lock()
		m.state = SwitchState{
			Phase:      PhaseReady,
			TargetID:   prof.ID,
			TargetMail: prof.Email,
			Message:    "switched to " + prof.Email,
			StartedAt:  &now,
			FinishedAt: &finished,
		}
		if idx, err := m.loadIndex(); err == nil {
			if p, pos, ok := m.findAccount(idx, prof.ID); ok {
				p.NeedsRelogin = false
				idx.Accounts[pos] = p
				_ = m.saveIndex(idx)
			}
		}
		m.mu.Unlock()
	}()

	return state, nil
}

// Delete removes a saved account. The active account cannot be deleted: AGY would
// be left signed in with no saved copy to restore.
func (m *Manager) Delete(id string) error {
	m.mu.Lock()
	defer m.mu.Unlock()

	idx, err := m.loadIndex()
	if err != nil {
		return err
	}
	prof, pos, found := m.findAccount(idx, id)
	if !found {
		return fmt.Errorf("saved account not found")
	}
	if email := m.CurrentEmail(); email != "" && strings.EqualFold(email, prof.Email) {
		return fmt.Errorf("cannot delete the active account — switch to another account first")
	}
	idx.Accounts = append(idx.Accounts[:pos], idx.Accounts[pos+1:]...)
	if err := m.saveIndex(idx); err != nil {
		return err
	}
	_ = os.Remove(m.profileTokenPath(id))
	_ = os.Remove(m.profileScopePath(id))
	return nil
}

// Label renames a saved account. An empty label resets it to the email.
func (m *Manager) Label(id, label string) (Profile, error) {
	m.mu.Lock()
	defer m.mu.Unlock()

	idx, err := m.loadIndex()
	if err != nil {
		return Profile{}, err
	}
	prof, pos, found := m.findAccount(idx, id)
	if !found {
		return Profile{}, fmt.Errorf("saved account not found")
	}
	prof.Label = strings.TrimSpace(label)
	idx.Accounts[pos] = prof
	if err := m.saveIndex(idx); err != nil {
		return Profile{}, err
	}
	return prof, nil
}
