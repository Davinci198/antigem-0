package agyaccounts

import (
	"bytes"
	"fmt"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"
)

type fakeHub struct {
	restarts, stops, starts int
	ready                   bool
	port                    string
}

func (f *fakeHub) Restart() error {
	f.stops++
	f.starts++
	f.restarts++
	return nil
}

func (f *fakeHub) IsPortActive() bool { return f.ready }
func (f *fakeHub) Port() string       { return f.port }

func TestManager_SnapshotCurrent(t *testing.T) {
	m, teardown := makeManager(t, nil)
	defer teardown()

	prof, err := m.SnapshotCurrent("work", nil, nil, "")
	if err != nil {
		t.Fatalf("SnapshotCurrent: %v", err)
	}
	if prof.Email != "user@example.com" {
		t.Fatalf("expected user@example.com, got %q", prof.Email)
	}
	if prof.Label != "work" {
		t.Fatalf("label = %q", prof.Label)
	}
	if prof.SavedAt.IsZero() {
		t.Fatal("SavedAt is zero")
	}

	// Snapshotting the same account again keeps a single entry.
	prof2, err := m.SnapshotCurrent("work", nil, nil, "")
	if err != nil {
		t.Fatalf("snapshot 2: %v", err)
	}
	if prof2.Email != "user@example.com" {
		t.Fatalf("expected user@example.com, got %q", prof2.Email)
	}
	if idx, _ := m.loadIndex(); len(idx.Accounts) != 1 {
		t.Fatalf("expected 1 profile, got %d", len(idx.Accounts))
	}
}

func TestManager_MissingTokenReturnsError(t *testing.T) {
	m, teardown := makeManager(t, nil)
	defer teardown()

	// Move the token so the manager cannot read it.
	backup := m.tokenPath()
	if err := os.Rename(backup, backup+".backup"); err != nil {
		t.Fatal(err)
	}
	defer os.Rename(backup+".backup", backup)

	_, err := m.SnapshotCurrent("orphan", nil, nil, "")
	if err == nil {
		t.Fatal("expected error for missing token file")
	}
}

func TestManager_DeleteActiveForbidden(t *testing.T) {
	m, teardown := makeManager(t, nil)
	defer teardown()

	if err := m.Delete("alice"); err == nil {
		t.Fatal("expected error deleting active account")
	}
}

func TestManager_CurrentEmail(t *testing.T) {
	m, teardown := makeManager(t, nil)
	defer teardown()

	if m.CurrentEmail() != "user@example.com" {
		t.Fatalf("current email = %q", m.CurrentEmail())
	}
	if err := os.Remove(m.tokenPath()); err != nil {
		t.Fatal(err)
	}
	if m.CurrentEmail() != "" {
		t.Fatalf("after removal, email = %q", m.CurrentEmail())
	}
}

func makeManager(t *testing.T, hub Restarter) (*Manager, func()) {
	tmp := t.TempDir()
	home := filepath.Join(tmp, "home")
	if err := os.MkdirAll(filepath.Join(home, ".gemini"), 0o700); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(home, ".gemini", "jetski-standalone-oauth-token"),
		makeTestToken("user@example.com"), 0o600); err != nil {
		t.Fatal(err)
	}
	if err := os.MkdirAll(filepath.Join(home, ".config", "antigravity"), 0o700); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(home, ".config", "antigravity", "credential-user-scope"),
		[]byte("u111"), 0o600); err != nil {
		t.Fatal(err)
	}

	m := NewManager(home, hub)
	return m, func() {}
}

func makeTestToken(email string) []byte {
	now := time.Now().UTC()
	return []byte(fmt.Sprintf(`{
  "access_token": "ya29.test",
  "token_type": "Bearer",
  "refresh_token": "1//0test",
  "expiry": "%s",
  "expires_in": 3599,
  "auth_method": "consumer",
  "id_token_email": "%s",
  "writer_id": "w1"
}`,
		now.Add(-24*time.Hour).Format(time.RFC3339), email))
}

// writeToken replaces the on-disk token, as signing into another account would.
func writeToken(t *testing.T, m *Manager, email string) {
	t.Helper()
	if err := os.WriteFile(m.tokenPath(), makeTestToken(email), 0o600); err != nil {
		t.Fatal(err)
	}
}

func waitForPhase(t *testing.T, m *Manager, want string, timeout time.Duration) SwitchState {
	t.Helper()
	deadline := time.Now().Add(timeout)
	for time.Now().Before(deadline) {
		state := m.Status()
		if state.Phase == want {
			return state
		}
		if state.Phase == PhaseFailed && want != PhaseFailed {
			t.Fatalf("switch failed early: %s", state.Message)
		}
		time.Sleep(50 * time.Millisecond)
	}
	t.Fatalf("phase never became %q (last: %+v)", want, m.Status())
	return SwitchState{}
}

// TestManager_SwitchHappyPath installs a saved account and verifies it through a fake
// hub RPC, exactly as the bridge does after a restart.
func TestManager_SwitchHappyPath(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if !strings.HasSuffix(r.URL.Path, "/GetAuthStatus") {
			w.WriteHeader(http.StatusNotFound)
			return
		}
		w.Header().Set("Content-Type", "application/json")
		_, _ = w.Write([]byte(`{"authResult":{"hasValidAuth":true}}`))
	}))
	defer srv.Close()
	port := strings.TrimPrefix(srv.URL, "http://127.0.0.1:")

	m, teardown := makeManager(t, &fakeHub{ready: true, port: port})
	defer teardown()

	// user@example.com is signed in; save them, then sign in as bob and save bob too.
	alice, err := m.SnapshotCurrent("alice", ptrInt64(10), nil, "Pro")
	if err != nil {
		t.Fatalf("snapshot alice: %v", err)
	}
	writeToken(t, m, "bob@example.com")
	bob, err := m.SnapshotCurrent("", nil, nil, "")
	if err != nil {
		t.Fatalf("snapshot bob: %v", err)
	}
	if bob.ID == alice.ID {
		t.Fatal("alice and bob must be different profiles")
	}

	state, err := m.Switch(SwitchRequest{ID: alice.ID, PromptCredits: ptrInt64(0), FlowCredits: ptrInt64(7)})
	if err != nil {
		t.Fatalf("Switch: %v", err)
	}
	if state.Phase != PhaseSwitching {
		t.Fatalf("initial phase = %q", state.Phase)
	}
	waitForPhase(t, m, PhaseReady, 10*time.Second)

	// The credential on disk must now be alice's.
	raw, err := os.ReadFile(m.tokenPath())
	if err != nil {
		t.Fatal(err)
	}
	if !bytes.Contains(raw, []byte("user@example.com")) {
		t.Fatalf("token on disk is not alice's: %s", raw)
	}
	// Bob was snapshotted before leaving, so switching back stays possible.
	idx, err := m.loadIndex()
	if err != nil {
		t.Fatal(err)
	}
	if _, _, ok := m.findAccount(idx, bob.ID); !ok {
		t.Fatalf("bob profile lost after switch: %+v", idx.Accounts)
	}
	// Bob's last-known credits were recorded on the way out.
	if _, _, ok := m.findAccount(idx, bob.ID); ok {
		for _, p := range idx.Accounts {
			if p.ID == bob.ID && (p.LastKnownPromptCredits == nil || *p.LastKnownPromptCredits != 0) {
				t.Fatalf("bob credits not recorded: %+v", p)
			}
		}
	}
	// Switching to the active account is refused.
	if _, err := m.Switch(SwitchRequest{ID: alice.ID}); err == nil {
		t.Fatal("expected error switching to the active account")
	}
}

// TestManager_SwitchRollbackWhenAuthRejected restores the previous account when AGY
// rejects the saved credential, and marks the target as needing a re-login.
func TestManager_SwitchRollbackWhenAuthRejected(t *testing.T) {
	// Port "" makes probeAuth fail, which exercises the same rollback as a rejected token.
	m, teardown := makeManager(t, &fakeHub{ready: false, port: ""})
	defer teardown()

	target, err := m.SnapshotCurrent("alice", nil, nil, "")
	if err != nil {
		t.Fatalf("snapshot alice: %v", err)
	}
	// Sign in as bob: the token on disk changes, alice stays on disk as a profile.
	writeToken(t, m, "bob@example.com")
	if _, err := m.SnapshotCurrent("", nil, nil, ""); err != nil {
		t.Fatalf("snapshot bob: %v", err)
	}
	before, err := os.ReadFile(m.tokenPath())
	if err != nil {
		t.Fatal(err)
	}

	if _, err := m.Switch(SwitchRequest{ID: target.ID}); err != nil {
		t.Fatalf("Switch: %v", err)
	}
	waitForPhase(t, m, PhaseFailed, 10*time.Second)

	// The token must be back to bob's (the account we left).
	after, err := os.ReadFile(m.tokenPath())
	if err != nil {
		t.Fatal(err)
	}
	if !bytes.Contains(after, []byte("bob@example.com")) {
		t.Fatalf("rollback did not restore the previous token: %s", after)
	}
	if !bytes.Equal(bytes.TrimSpace(before), bytes.TrimSpace(after)) {
		t.Fatalf("token differs after rollback:\nbefore: %s\nafter:  %s", before, after)
	}
	// The rejected profile is flagged so the UI can offer a re-login.
	idx, err := m.loadIndex()
	if err != nil {
		t.Fatal(err)
	}
	p, _, ok := m.findAccount(idx, target.ID)
	if !ok || !p.NeedsRelogin {
		t.Fatalf("target profile not flagged NeedsRelogin: %+v", idx.Accounts)
	}
}

func ptrInt64(v int64) *int64 { return &v }
