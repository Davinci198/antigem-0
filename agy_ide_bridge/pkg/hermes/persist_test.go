package hermes

import (
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"os"
	"strings"
	"testing"
)

// POST /sessions is keyed by the app's conversation id and idempotent: the
// app never stores a conv.id→session_id mapping, so a create for the same
// conversation must return the same session, never a fresh empty one.
func TestCreateKeysByConversationID(t *testing.T) {
	home := t.TempDir()
	m := NewManager(home, "")

	convID := "11111111-2222-3333-4444-555555555555"
	body := `{"conversation_id":"` + convID + `","title":"My chat"}`

	sid := createSession(t, m, body)
	if sid != convID {
		t.Fatalf("session id %q, want conversation id %q", sid, convID)
	}
	sid2 := createSession(t, m, body)
	if sid2 != sid {
		t.Fatalf("second create returned %q, want the same %q", sid2, sid)
	}

	// the record is on disk right after create
	if rec := m.loadRecord(convID); rec == nil {
		t.Fatal("session not persisted after create")
	}

	// no conversation id: the bridge generates one, still persisted
	sid3 := createSession(t, m, `{}`)
	if sid3 == "" || sid3 == convID {
		t.Fatalf("generated id %q unexpected", sid3)
	}
	if m.loadRecord(sid3) == nil {
		t.Fatal("generated session not persisted")
	}
}

func createSession(t *testing.T, m *Manager, body string) string {
	t.Helper()
	req := httptest.NewRequest(http.MethodPost, "/api/hermes/sessions", strings.NewReader(body))
	rec := httptest.NewRecorder()
	m.ServeHTTP(rec, req)
	if rec.Code != http.StatusOK {
		t.Fatalf("create: status %d body %s", rec.Code, rec.Body.String())
	}
	var out struct {
		SessionID string `json:"session_id"`
	}
	if err := json.Unmarshal(rec.Body.Bytes(), &out); err != nil {
		t.Fatalf("create reply: %v", err)
	}
	return out.SessionID
}

// Messages appended by a turn are written through, and a manager rebuilt with
// the same home (the bridge restart after an app close) restores them.
func TestHistorySurvivesManagerRestart(t *testing.T) {
	home := t.TempDir()
	m := NewManager(home, "")
	convID := "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"

	s := m.session(convID)
	s.mu.Lock()
	s.title = "Restored please"
	s.mu.Unlock()
	m.persistSession(s)
	s.runTurnRecorded("hello world")

	m2 := NewManager(home, "")
	s2 := m2.session(convID)
	if s2 == nil {
		t.Fatal("session not restored after restart")
	}
	s2.mu.Lock()
	msgs := append([]Message{}, s2.msgs...)
	title := s2.title
	s2.mu.Unlock()
	if len(msgs) != 1 || msgs[0].Role != "user" || msgs[0].Content != "hello world" {
		t.Fatalf("restored messages: %+v", msgs)
	}
	if title != "Restored please" {
		t.Fatalf("restored title %q", title)
	}
}

// runTurnRecorded appends a user message the way runTurn does, without
// touching the gateway — persistence is what this package tests here.
func (s *Session) runTurnRecorded(content string) {
	s.mu.Lock()
	s.msgs = append(s.msgs, Message{ID: "u1", Role: "user", Content: content})
	s.mu.Unlock()
	s.mgr.persistSession(s)
}

// GET /sessions lists the conversations newest-first with the fields the
// app's drawer needs.
func TestListSessionsEndpoint(t *testing.T) {
	home := t.TempDir()
	m := NewManager(home, "")

	createSession(t, m, `{"conversation_id":"old00000-0000-0000-0000-000000000000","title":"Old"}`)
	// force distinct UpdatedAt ordering
	rec := m.loadRecord("old00000-0000-0000-0000-000000000000")
	rec.UpdatedAt = 1
	m.writeRecord(*rec)
	createSession(t, m, `{"conversation_id":"new00000-0000-0000-0000-000000000000","title":"New"}`)
	rec = m.loadRecord("new00000-0000-0000-0000-000000000000")
	rec.UpdatedAt = 2
	m.writeRecord(*rec)

	req := httptest.NewRequest(http.MethodGet, "/api/hermes/sessions", nil)
	w := httptest.NewRecorder()
	m.ServeHTTP(w, req)
	if w.Code != http.StatusOK {
		t.Fatalf("list: status %d", w.Code)
	}
	var out struct {
		Sessions []sessionRecord `json:"sessions"`
	}
	if err := json.Unmarshal(w.Body.Bytes(), &out); err != nil {
		t.Fatalf("list reply: %v", err)
	}
	if len(out.Sessions) != 2 {
		t.Fatalf("want 2 sessions, got %d", len(out.Sessions))
	}
	if out.Sessions[0].ConversationID != "new00000-0000-0000-0000-000000000000" {
		t.Fatalf("not newest-first: %+v", out.Sessions)
	}
	if out.Sessions[0].Title != "New" {
		t.Fatalf("title not listed: %+v", out.Sessions[0])
	}
}

// delete removes the in-memory session AND its record, so a closed
// conversation does not reappear after the next bridge restart.
func TestDeleteRemovesRecord(t *testing.T) {
	home := t.TempDir()
	m := NewManager(home, "")
	sid := createSession(t, m, `{"conversation_id":"dead0000-0000-0000-0000-000000000000"}`)

	req := httptest.NewRequest(http.MethodPost, "/api/hermes/sessions/"+sid+"/delete", strings.NewReader(`{}`))
	w := httptest.NewRecorder()
	m.ServeHTTP(w, req)
	if w.Code != http.StatusOK {
		t.Fatalf("delete: status %d body %s", w.Code, w.Body.String())
	}
	if m.loadRecord(sid) != nil {
		t.Fatal("record survived delete")
	}
	if s := m.session(sid); s != nil && len(s.msgs) != 0 {
		t.Fatal("deleted session still has messages")
	}
	// a fresh manager must not see it either
	m2 := NewManager(home, "")
	if len(m2.listSessions()) != 0 {
		t.Fatalf("deleted conversation reappears after restart: %+v", m2.listSessions())
	}
}

// The stored record must never carry provider secrets: only the id is kept,
// and the endpoint/key are re-resolved from the provider store on restore.
func TestRecordKeepsNoSecrets(t *testing.T) {
	home := t.TempDir()
	m := NewManager(home, "")
	s := m.session("sec00000-0000-0000-0000-000000000001")
	s.mu.Lock()
	s.gwProviderID = "myprov"
	s.gwProvider = "custom"
	s.gwBaseURL = "https://api.example.com/v1"
	s.gwAPIKey = "super-secret-key"
	s.mu.Unlock()
	m.persistSession(s)

	b, err := os.ReadFile(m.sessionFile(s.id))
	if err != nil {
		t.Fatalf("record file: %v", err)
	}
	if strings.Contains(string(b), "super-secret-key") || strings.Contains(string(b), "api.example.com") {
		t.Fatalf("secret leaked into the record: %s", b)
	}
	if !strings.Contains(string(b), "myprov") {
		t.Fatalf("provider id not stored: %s", b)
	}
}
