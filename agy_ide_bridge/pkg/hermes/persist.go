package hermes

// Session persistence.
//
// The app's chat drawer must still show its conversations after the app is
// closed and reopened. OpenCode persists its own storage and the Claude CLI
// writes JSONL transcripts, but Hermes sessions lived only in
// Manager.sessions — a bridge restart (which the app's life cycle drives)
// dropped every conversation. Manager now mirrors each session to a small
// JSON file under ~/.claude/agy_hermes_sessions/ and re-reads it lazily, so
// history and the drawer survive restarts.
//
// Secrets stay out of the file: a custom provider is stored by id only and
// its endpoint/key are re-resolved from agy_providers.json on load.

import (
	"encoding/json"
	"log"
	"os"
	"path/filepath"
	"sort"
	"strings"
	"time"
)

func logf(format string, args ...interface{}) {
	log.Printf("[hermes] "+format, args...)
}

// sessionRecord is the on-disk form of a Session.
type sessionRecord struct {
	ID              string    `json:"id"`
	ConversationID  string    `json:"conversation_id"`
	Title           string    `json:"title"`
	UpdatedAt       int64     `json:"updated_at"`
	Model           string    `json:"model,omitempty"`
	ProviderID      string    `json:"provider_id,omitempty"`
	HermesSessionID string    `json:"hermes_session_id,omitempty"`
	Messages        []Message `json:"messages"`
}

func (m *Manager) sessionsDir() string {
	return filepath.Join(m.homeDir, ".claude", "agy_hermes_sessions")
}

func (m *Manager) sessionFile(id string) string {
	return filepath.Join(m.sessionsDir(), id+".json")
}

// persistSession writes the session's current state to disk, best-effort:
// a failed write logs and the turn continues — persistence must never break
// a live conversation. The file is replaced atomically (write temp + rename)
// so a kill mid-write cannot leave a half-written record.
func (m *Manager) persistSession(s *Session) {
	s.mu.Lock()
	rec := sessionRecord{
		ID:              s.id,
		ConversationID:  s.conversationID,
		Title:           s.title,
		Model:           s.gwModel,
		ProviderID:      s.gwProviderID,
		HermesSessionID: s.hermesSessionID,
		Messages:        append([]Message{}, s.msgs...),
		UpdatedAt:       time.Now().UnixMilli(),
	}
	s.mu.Unlock()
	m.writeRecord(rec)
}

func (m *Manager) writeRecord(rec sessionRecord) {
	if !idRe.MatchString(rec.ID) {
		return
	}
	b, err := json.Marshal(&rec)
	if err != nil {
		logf("hermes: persist marshal %s: %v", rec.ID, err)
		return
	}
	dir := m.sessionsDir()
	if err := os.MkdirAll(dir, 0o700); err != nil {
		logf("hermes: persist mkdir %s: %v", dir, err)
		return
	}
	tmp := m.sessionFile(rec.ID) + ".tmp"
	if err := os.WriteFile(tmp, b, 0o600); err != nil {
		logf("hermes: persist write %s: %v", tmp, err)
		return
	}
	if err := os.Rename(tmp, m.sessionFile(rec.ID)); err != nil {
		logf("hermes: persist rename %s: %v", rec.ID, err)
		_ = os.Remove(tmp)
	}
}

// loadRecord reads one session file; nil when absent or unreadable.
func (m *Manager) loadRecord(id string) *sessionRecord {
	if !idRe.MatchString(id) {
		return nil
	}
	b, err := os.ReadFile(m.sessionFile(id))
	if err != nil {
		return nil
	}
	var rec sessionRecord
	if err := json.Unmarshal(b, &rec); err != nil || rec.ID != id {
		return nil
	}
	return &rec
}

// restoreSession materialises a persisted session into memory (nil on miss).
// Custom providers are re-resolved so a restored chat sends on the same
// endpoint it was created on.
func (m *Manager) restoreSession(id string) *Session {
	rec := m.loadRecord(id)
	if rec == nil {
		return nil
	}
	s := &Session{
		id:              rec.ID,
		conversationID:  rec.ConversationID,
		title:           rec.Title,
		msgs:            rec.Messages,
		subs:            map[*sub]struct{}{},
		abortCh:         make(chan struct{}, 1),
		hermesBin:       m.hermesBin,
		homeDir:         m.homeDir,
		mgr:             m,
		hermesSessionID: rec.HermesSessionID,
		gwModel:         rec.Model,
		gwProviderID:    rec.ProviderID,
	}
	if s.msgs == nil {
		s.msgs = []Message{}
	}
	if rec.ProviderID == "nous" && m.nousLoggedIn() {
		s.gwProvider = "nous"
		s.gwProviderAtCreate = "nous"
	} else if rec.ProviderID != "" {
		if p := findAppProvider(m.homeDir, rec.ProviderID); p != nil {
			// Same mapping as handleCreate: a builtin base (Kilo) restores as
			// its Hermes slug with no wire key, so a reopened chat does not
			// revert to "custom" and 401.
			if builtin := hermesBuiltinProvider(p.BaseURL); builtin != "" {
				s.gwProvider = builtin
				s.gwBaseURL = ""
				s.gwAPIKey = ""
			} else {
				s.gwProvider = "custom"
				s.gwBaseURL = p.BaseURL
				s.gwAPIKey = p.APIKey
			}
			s.gwProviderAtCreate = s.gwProvider
		}
	}
	m.mu.Lock()
	if existing, ok := m.sessions[id]; ok {
		m.mu.Unlock()
		return existing
	}
	m.sessions[id] = s
	m.mu.Unlock()
	return s
}

// listSessions returns every persisted session, newest turn last written
// first. Sessions that exist only in memory (not yet persisted — impossible
// today since create persists immediately) are merged in by id.
func (m *Manager) listSessions() []sessionRecord {
	byID := map[string]sessionRecord{}
	entries, _ := os.ReadDir(m.sessionsDir())
	for _, e := range entries {
		if e.IsDir() || !strings.HasSuffix(e.Name(), ".json") {
			continue
		}
		id := strings.TrimSuffix(e.Name(), ".json")
		if rec := m.loadRecord(id); rec != nil {
			byID[rec.ID] = *rec
		}
	}
	m.mu.Lock()
	live := make([]*Session, 0, len(m.sessions))
	for _, s := range m.sessions {
		live = append(live, s)
	}
	m.mu.Unlock()
	for _, s := range live {
		s.mu.Lock()
		rec := sessionRecord{
			ID:              s.id,
			ConversationID:  s.conversationID,
			Title:           s.title,
			Model:           s.gwModel,
			ProviderID:      s.gwProviderID,
			HermesSessionID: s.hermesSessionID,
			Messages:        append([]Message{}, s.msgs...),
			UpdatedAt:       time.Now().UnixMilli(),
		}
		s.mu.Unlock()
		if existing, ok := byID[rec.ID]; ok && existing.UpdatedAt >= rec.UpdatedAt {
			continue
		}
		byID[rec.ID] = rec
	}
	out := make([]sessionRecord, 0, len(byID))
	for _, rec := range byID {
		out = append(out, rec)
	}
	sort.Slice(out, func(i, j int) bool { return out[i].UpdatedAt > out[j].UpdatedAt })
	return out
}

// deleteSessionFile drops the on-disk record with the in-memory session.
func (m *Manager) deleteSessionFile(id string) {
	if !idRe.MatchString(id) {
		return
	}
	if err := os.Remove(m.sessionFile(id)); err != nil && !os.IsNotExist(err) {
		logf("hermes: delete %s: %v", id, err)
	}
}
