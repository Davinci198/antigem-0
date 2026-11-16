package hermes

import (
	"bufio"
	"context"
	"encoding/json"
	"fmt"
	"net/http"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"sync"
	"time"

	"github.com/google/uuid"
)

const nousLoginLifetime = 15 * time.Minute

type nousLoginSession struct {
	ID       string
	State    string
	URL      string
	Code     string
	Error    string
	mu       sync.Mutex
	cmd      *exec.Cmd
	cancel   context.CancelFunc
	ready    chan struct{}
	done     chan struct{}
	readySet bool
}

type nousAuthStore struct {
	Providers      map[string]json.RawMessage `json:"providers"`
	CredentialPool map[string]json.RawMessage `json:"credential_pool"`
}

func (m *Manager) nousLoggedIn() bool {
	hermesHome := m.homeDir
	if configured := os.Getenv("HERMES_HOME"); configured != "" {
		return nonEmptyAuthStore(filepath.Join(configured, "auth.json"))
	}
	path := filepath.Join(hermesHome, ".hermes", "auth.json")
	return nonEmptyAuthStore(path)
}

func nonEmptyAuthStore(path string) bool {
	raw, err := os.ReadFile(path)
	if err != nil {
		return false
	}
	var store nousAuthStore
	if json.Unmarshal(raw, &store) != nil {
		return false
	}
	return nonEmptyJSON(store.Providers["nous"]) || nonEmptyJSON(store.CredentialPool["nous"])
}

func nonEmptyJSON(raw json.RawMessage) bool {
	if len(raw) == 0 || string(raw) == "null" {
		return false
	}
	var value interface{}
	if json.Unmarshal(raw, &value) != nil {
		return false
	}
	switch v := value.(type) {
	case map[string]interface{}:
		return len(v) > 0
	case []interface{}:
		return len(v) > 0
	case string:
		return strings.TrimSpace(v) != ""
	default:
		return value != nil
	}
}

func (m *Manager) nousLogin(id string) *nousLoginSession {
	m.nousLoginsMu.Lock()
	defer m.nousLoginsMu.Unlock()
	return m.nousLogins[id]
}

func (m *Manager) startNousLogin() (*nousLoginSession, error) {
	m.nousLoginsMu.Lock()
	for _, existing := range m.nousLogins {
		existing.mu.Lock()
		pending := existing.State == "pending"
		existing.mu.Unlock()
		if pending {
			m.nousLoginsMu.Unlock()
			return nil, fmt.Errorf("a Nous sign-in is already in progress")
		}
	}
	m.nousLoginsMu.Unlock()

	ctx, cancel := context.WithTimeout(context.Background(), nousLoginLifetime)
	// Target the default (root) Hermes home explicitly: the sticky active profile
	// (active_profile) would otherwise re-home this process to profiles/opencode,
	// which neither nousLoggedIn() nor the default-profile gateway reads.
	cmd := exec.CommandContext(ctx, m.hermesBin, "--profile", "default", "auth", "add",
		"nous", "--type", "oauth", "--no-browser")
	cmd.Dir = m.homeDir
	cmd.Env = append(os.Environ(), "HOME="+m.homeDir)
	// The CLI runs an interactive model picker after a successful device-code login.
	// With stdin closed it skips the picker (EOF -> no selection), restores the
	// previous active provider, and still saves the Nous credentials — exactly what
	// the app wants. Inheriting an open pipe could otherwise block forever.
	cmd.Stdin = strings.NewReader("")
	stdout, err := cmd.StdoutPipe()
	if err != nil {
		cancel()
		return nil, err
	}
	stderr, err := cmd.StderrPipe()
	if err != nil {
		cancel()
		return nil, err
	}
	if err := cmd.Start(); err != nil {
		cancel()
		return nil, err
	}
	login := &nousLoginSession{
		ID: uuid.NewString(), State: "pending", cmd: cmd, cancel: cancel,
		ready: make(chan struct{}), done: make(chan struct{}),
	}
	m.nousLoginsMu.Lock()
	if m.nousLogins == nil {
		m.nousLogins = make(map[string]*nousLoginSession)
	}
	m.nousLogins[login.ID] = login
	m.nousLoginsMu.Unlock()

	var readers sync.WaitGroup
	readers.Add(2)
	readLines := func(scanner *bufio.Scanner) {
		defer readers.Done()
		scanner.Buffer(make([]byte, 4096), 1024*1024)
		for scanner.Scan() {
			login.consumeLine(scanner.Text())
		}
	}
	go readLines(bufio.NewScanner(stdout))
	go readLines(bufio.NewScanner(stderr))
	go func() {
		err := cmd.Wait()
		readers.Wait()
		login.mu.Lock()
		if login.State == "pending" {
			if m.nousLoggedIn() {
				login.State = "success"
			} else if err != nil {
				login.State = "error"
				login.Error = err.Error()
			} else {
				login.State = "error"
				login.Error = "Hermes exited without saving Nous credentials"
			}
		}
		login.mu.Unlock()
		close(login.done)
		cancel()
	}()

	select {
	case <-login.ready:
		return login, nil
	case <-login.done:
		login.mu.Lock()
		defer login.mu.Unlock()
		if login.State == "error" {
			return nil, fmt.Errorf("Nous login did not start: %s", login.Error)
		}
		return login, fmt.Errorf("Nous login finished before device-code instructions were received")
	case <-time.After(30 * time.Second):
		login.cancelLogin()
		return nil, fmt.Errorf("timed out waiting for Nous device-code instructions")
	}
}

func (s *nousLoginSession) consumeLine(line string) {
	trimmed := strings.TrimSpace(line)
	s.mu.Lock()
	defer s.mu.Unlock()
	if i := strings.Index(trimmed, "Open:"); i >= 0 && s.URL == "" {
		s.URL = strings.TrimSpace(trimmed[i+len("Open:"):])
	}
	if i := strings.Index(strings.ToLower(trimmed), "enter code:"); i >= 0 && s.Code == "" {
		s.Code = strings.TrimSpace(trimmed[i+len("enter code:"):])
	}
	if s.URL != "" && s.Code != "" && !s.readySet {
		s.readySet = true
		close(s.ready)
	}
}

type nousLoginSnapshot struct {
	ID    string `json:"login_id"`
	State string `json:"state"`
	URL   string `json:"url"`
	Code  string `json:"code"`
	Error string `json:"error,omitempty"`
}

func (s *nousLoginSession) snapshot() nousLoginSnapshot {
	s.mu.Lock()
	defer s.mu.Unlock()
	return nousLoginSnapshot{ID: s.ID, State: s.State, URL: s.URL, Code: s.Code, Error: s.Error}
}

func (s *nousLoginSession) cancelLogin() {
	s.mu.Lock()
	if s.State == "pending" {
		s.State = "cancelled"
	}
	cancel := s.cancel
	s.mu.Unlock()
	if cancel != nil {
		cancel()
	}
}

func (m *Manager) handleNousAuth(w http.ResponseWriter, r *http.Request, rest string) {
	if rest == "status" && r.Method == http.MethodGet {
		writeJSON(w, http.StatusOK, obj{"logged_in": m.nousLoggedIn()})
		return
	}
	if rest == "login" && r.Method == http.MethodPost {
		login, err := m.startNousLogin()
		if err != nil {
			writeJSON(w, http.StatusOK, obj{"success": false, "error": err.Error()})
			return
		}
		writeJSON(w, http.StatusOK, obj{"success": true, "login": login.snapshot()})
		return
	}
	if strings.HasPrefix(rest, "login/") {
		id := strings.TrimPrefix(rest, "login/")
		if id == "" || strings.Contains(id, "/") || !idRe.MatchString(id) {
			writeJSON(w, http.StatusNotFound, obj{"error": "unknown login"})
			return
		}
		login := m.nousLogin(id)
		if login == nil {
			writeJSON(w, http.StatusNotFound, obj{"error": "unknown login"})
			return
		}
		switch r.Method {
		case http.MethodGet:
			writeJSON(w, http.StatusOK, obj{"success": true, "login": login.snapshot()})
		case http.MethodDelete:
			login.cancelLogin()
			m.nousLoginsMu.Lock()
			delete(m.nousLogins, id)
			m.nousLoginsMu.Unlock()
			writeJSON(w, http.StatusOK, obj{"success": true})
		default:
			writeJSON(w, http.StatusMethodNotAllowed, obj{"error": "method not allowed"})
		}
		return
	}
	writeJSON(w, http.StatusNotFound, obj{"error": "unknown Nous auth endpoint"})
}
