// Package hermes bridges the Hermes Agent CLI with the AntiGem app.
//
// REST surface (under /api/hermes/):
//
//	POST   /api/hermes/sessions                          create a session → {session_id}
//	POST   /api/hermes/sessions/{id}/send                send a turn (JSON body: {content})
//	POST   /api/hermes/sessions/{id}/abort               interrupt the running turn
//	POST   /api/hermes/sessions/{id}/delete              remove a session
//	GET    /api/hermes/sessions/{id}/history             past messages → {messages:[{role,content,id}]}
//	GET    /api/hermes/sessions/{id}/stream              WebSocket: NDJSON event stream
//
// The WS stream emits:
//
//	{"type":"status",         "message":"…"}
//	{"type":"assistant_start"}
//	{"type":"assistant_delta","delta":"…"}
//	{"type":"assistant_done", "usage":{…}, "title":"…"}
//	{"type":"error",           "message":"…"}
package hermes

import (
	"bufio"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"log"
	"net/http"
	"os/exec"
	"path/filepath"
	"regexp"
	"strings"
	"sync"
	"time"

	"github.com/google/uuid"
	"github.com/gorilla/websocket"
)

// Manager handles all Hermes sessions.
type Manager struct {
	mu        sync.Mutex
	sessions  map[string]*Session
	hermesBin string // path to the hermes CLI
	homeDir   string
	upgrader  websocket.Upgrader
	// gw is the live connection to the profile's Hermes gateway: turns go
	// through it (warm, model latency only) instead of spawning a fresh
	// `hermes chat --oneshot` per message. The spawn path stays as fallback.
	gw *gateway
}

// NewManager creates a Manager. hermesBin may be empty (we search PATH).
func NewManager(homeDir, hermesBin string) *Manager {
	if hermesBin == "" || hermesBin == "~" {
		hermesBin = filepath.Join(homeDir, ".local", "bin", "hermes")
	} else if strings.HasPrefix(hermesBin, "~/") {
		hermesBin = filepath.Join(homeDir, hermesBin[2:])
	}
	m := &Manager{
		sessions:  make(map[string]*Session),
		hermesBin: hermesBin,
		homeDir:   homeDir,
		upgrader: websocket.Upgrader{
			CheckOrigin:     func(*http.Request) bool { return true },
			ReadBufferSize:  4096,
			WriteBufferSize: 65536,
		},
	}
	m.gw = newGateway(m)
	return m
}

// session returns or creates a session.
func (m *Manager) session(id string) *Session {
	if s := m.peek(id); s != nil {
		return s
	}
	m.mu.Lock()
	defer m.mu.Unlock()
	s := &Session{
		id:        id,
		msgs:      []Message{},
		subs:      map[*sub]struct{}{},
		abortCh:   make(chan struct{}, 1),
		hermesBin: m.hermesBin,
		homeDir:   m.homeDir,
		mgr:       m,
	}
	m.sessions[id] = s
	return s
}

// peek returns an existing session without creating one (nil otherwise).
func (m *Manager) peek(id string) *Session {
	m.mu.Lock()
	defer m.mu.Unlock()
	return m.sessions[id]
}

func (m *Manager) delete(id string) {
	m.mu.Lock()
	var gwID string
	if s, ok := m.sessions[id]; ok {
		s.abort()
		gwID = s.gwID
	}
	delete(m.sessions, id)
	m.mu.Unlock()
	if gwID != "" {
		go m.gw.detach(gwID)
	}
}

func (m *Manager) StopAll() {
	m.mu.Lock()
	defer m.mu.Unlock()
	for _, s := range m.sessions {
		s.abort()
		if s.gwID != "" {
			go m.gw.detach(s.gwID)
		}
	}
}

var idRe = regexp.MustCompile(`^[a-zA-Z0-9_-]{1,64}$`)

// ServeHTTP routes /api/hermes/ requests.
func (m *Manager) ServeHTTP(w http.ResponseWriter, r *http.Request) {
	// strip prefix
	path := strings.TrimPrefix(r.URL.Path, "/api/hermes")
	path = strings.TrimPrefix(path, "/")

	switch {
	case path == "sessions" && r.Method == http.MethodPost:
		m.handleCreate(w, r)
	case path == "gateway":
		m.handleGatewayStatus(w)

	default:
		// /sessions/{id}/…
		parts := strings.SplitN(path, "/", 3)
		if len(parts) < 3 || parts[0] != "sessions" || !idRe.MatchString(parts[1]) {
			writeJSON(w, http.StatusNotFound, obj{"error": "not found"})
			return
		}
		id, sub := parts[1], parts[2]
		switch {
		case sub == "send" && r.Method == http.MethodPost:
			m.handleSend(w, r, m.session(id))
		case sub == "abort" && r.Method == http.MethodPost:
			m.handleAbort(w, m.session(id))
		case sub == "delete" && r.Method == http.MethodPost:
			m.delete(id)
			writeJSON(w, http.StatusOK, obj{"success": true})
		case sub == "history" && r.Method == http.MethodGet:
			m.handleHistory(w, m.session(id))
		case sub == "models" && r.Method == http.MethodGet:
			// read-only: never materialise a session just to list models
			m.handleModels(w, m.peek(id))
		case sub == "model" && r.Method == http.MethodPost:
			sess := m.peek(id)
			if sess == nil {
				writeJSON(w, http.StatusNotFound, obj{"error": "unknown session"})
				return
			}
			m.handleSetModel(w, r, sess)
		case sub == "stream":
			m.handleStream(w, r, m.session(id))
		default:
			writeJSON(w, http.StatusNotFound, obj{"error": "unknown endpoint"})
		}
	}
}

// ── handlers ─────────────────────────────────────────────────────────────────

func (m *Manager) handleCreate(w http.ResponseWriter, r *http.Request) {
	var body struct {
		ConversationID string `json:"conversation_id"`
		Title          string `json:"title"`
		// Model selects the Hermes model for this chat: a model id Hermes
		// knows (its own picker), or a model of a custom provider below.
		Model string `json:"model"`
		// ProviderID names an app custom provider from ~/.claude/agy_providers.json;
		// its endpoint and key are resolved here and never sent by the app.
		ProviderID string `json:"provider_id"`
	}
	_ = json.NewDecoder(r.Body).Decode(&body)
	id := uuid.New().String()
	sess := m.session(id)
	sess.mu.Lock()
	sess.conversationID = body.ConversationID
	sess.title = body.Title
	sess.gwModel = body.Model
	sess.gwProvider = ""
	sess.gwBaseURL = ""
	sess.gwAPIKey = ""
	if body.ProviderID != "" {
		if p := findAppProvider(m.homeDir, body.ProviderID); p != nil {
			// Hermes runs a custom endpoint as provider "custom" with the
			// endpoint and key carried on the session itself.
			sess.gwProvider = "custom"
			sess.gwBaseURL = p.BaseURL
			sess.gwAPIKey = p.APIKey
			if body.Model == "" && len(p.Models) > 0 {
				sess.gwModel = p.Models[0].ID
			}
		} else {
			sess.mu.Unlock()
			writeJSON(w, http.StatusBadRequest, obj{"error": "unknown provider " + body.ProviderID})
			return
		}
	}
	sess.mu.Unlock()
	writeJSON(w, http.StatusOK, obj{"session_id": id})
}

func (m *Manager) handleSend(w http.ResponseWriter, r *http.Request, sess *Session) {
	var body struct {
		Content string `json:"content"`
	}
	if err := json.NewDecoder(r.Body).Decode(&body); err != nil || body.Content == "" {
		writeJSON(w, http.StatusBadRequest, obj{"error": "missing content"})
		return
	}
	go sess.runTurn(body.Content)
	writeJSON(w, http.StatusOK, obj{"success": true})
}

func (s *Session) abort() {
	// gateway path: interrupt the running turn; the spawn path drains abortCh
	s.mu.Lock()
	gwID := s.gwID
	s.mu.Unlock()
	if gwID != "" {
		if err := s.mgr.gw.ensure(); err == nil {
			_ = s.mgr.gw.interrupt(gwID)
		}
	}
	select {
	case s.abortCh <- struct{}{}:
	default:
	}
}

func (m *Manager) handleAbort(w http.ResponseWriter, sess *Session) {
	sess.abort()
	writeJSON(w, http.StatusOK, obj{"success": true})
}

func (m *Manager) handleHistory(w http.ResponseWriter, sess *Session) {
	sess.mu.Lock()
	msgs := make([]Message, len(sess.msgs))
	copy(msgs, sess.msgs)
	sess.mu.Unlock()
	writeJSON(w, http.StatusOK, obj{"messages": msgs})
}

func (m *Manager) handleStream(w http.ResponseWriter, r *http.Request, sess *Session) {
	conn, err := m.upgrader.Upgrade(w, r, nil)
	if err != nil {
		return
	}
	_ = conn.UnderlyingConn().SetDeadline(time.Time{})

	s := &sub{conn: conn, ch: make(chan []byte, 512), done: make(chan struct{})}
	sess.mu.Lock()
	sess.subs[s] = struct{}{}
	replay := make([][]byte, len(sess.recent))
	copy(replay, sess.recent)
	sess.mu.Unlock()

	go s.writeLoop()
	// tell the subscriber where the session's event counter stands: a seq
	// below the client's last-seen seq means the session was recreated
	// (bridge restart) and the client must drop its dedupe cursor
	sess.mu.Lock()
	hello, _ := json.Marshal(obj{"type": "hello", "seq": sess.seq})
	sess.mu.Unlock()
	s.send(hello)
	// catch the new subscriber up with everything it missed; live events
	// broadcast after the lock above was released queue behind the replay
	for _, b := range replay {
		s.send(b)
	}
	defer func() {
		sess.mu.Lock()
		delete(sess.subs, s)
		sess.mu.Unlock()
		s.close()
	}()
	// keep alive until client disconnects
	for {
		if _, _, err := conn.ReadMessage(); err != nil {
			return
		}
	}
}

// ── Session ───────────────────────────────────────────────────────────────────

// Message is a stored turn (user or assistant).
type Message struct {
	ID      string `json:"id"`
	Role    string `json:"role"`
	Content string `json:"content"`
}

// Session is one conversation thread with Hermes.
type Session struct {
	mu             sync.Mutex
	id             string
	conversationID string
	title          string
	msgs           []Message
	subs           map[*sub]struct{}
	abortCh        chan struct{}
	hermesBin      string
	homeDir        string
	mgr            *Manager
	// resume flag: the Hermes session id reported by --pass-session-id
	hermesSessionID string
	// recent events, replayed to subscribers that join mid-turn
	recent [][]byte
	// seq tags every emitted event so clients can skip already-seen replays
	seq int

	// ── gateway path (warm turns) ──
	// gwID is the gateway session this bridge session runs on ("" until the
	// first turn creates it). gwModel/gwProvider/gwBaseURL/gwAPIKey are the
	// launch options for that create; gwProviderAtCreate remembers what the
	// session was created on, so a mid-chat custom switch can be refused
	// honestly instead of half-applied.
	gwID               string
	gwModel            string
	gwProvider         string
	gwBaseURL          string
	gwAPIKey           string
	gwProviderAtCreate string
	// the assistant bubble currently streaming on the gateway path
	streamingID string
	streamBuf   strings.Builder
	streamDone  bool
	turnContent string
}

// maxReplayEvents bounds the per-session replay buffer.
const maxReplayEvents = 512

func (s *Session) broadcast(b []byte) {
	s.mu.Lock()
	defer s.mu.Unlock()
	for sub := range s.subs {
		sub.send(b)
	}
}

func (s *Session) emit(v interface{}) {
	// tag every event with a per-session sequence number so a client that
	// reconnects mid-turn can skip the events it has already processed
	s.mu.Lock()
	s.seq++
	if m, ok := v.(obj); ok {
		m["seq"] = s.seq
	}
	b, _ := json.Marshal(v)
	// remember the event so a subscriber that connects later can catch up
	s.recent = append(s.recent, b)
	if len(s.recent) > maxReplayEvents {
		s.recent = s.recent[len(s.recent)-maxReplayEvents:]
	}
	s.mu.Unlock()
	s.broadcast(b)
}

func (s *Session) runTurn(content string) {
	// store user message
	userMsg := Message{ID: uuid.New().String(), Role: "user", Content: content}
	s.mu.Lock()
	s.msgs = append(s.msgs, userMsg)
	hermesID := s.hermesSessionID
	s.mu.Unlock()

	// warm path: dispatch the turn over the live gateway connection; fall
	// back to spawning `hermes chat --oneshot` only when the gateway cannot
	// be reached at all (it answers with model latency alone once warm)
	if err := s.mgr.gw.ensure(); err == nil {
		if s.runTurnGateway(content) {
			return
		}
	} else {
		log.Printf("[hermes] gateway unavailable (%v); spawning hermes chat", err)
		s.emit(obj{"type": "status", "message": fmt.Sprintf("Gateway unavailable (%v); trying a direct launch", err)})
	}
	s.emit(obj{"type": "status", "message": "Starting Hermes…"})
	s.runTurnSpawn(content, hermesID)
}

// runTurnGateway sends one turn over the gateway. It returns false when the
// turn could not even be queued, so the caller falls back to the spawn path.
func (s *Session) runTurnGateway(content string) bool {
	g := s.mgr.gw
	s.mu.Lock()
	gwID := s.gwID
	s.mu.Unlock()
	if gwID == "" {
		s.emit(obj{"type": "status", "message": "Starting Hermes…"})
		if err := g.createSession(s); err != nil {
			log.Printf("[hermes] gateway session failed: %v", err)
			s.emit(obj{"type": "status", "message": fmt.Sprintf("Gateway session failed (%v); trying a direct launch", err)})
			return false
		}
		s.mu.Lock()
		s.gwProviderAtCreate = s.gwProvider
		gwID = s.gwID
		s.mu.Unlock()
	}
	s.mu.Lock()
	s.streamingID = ""
	s.streamBuf.Reset()
	s.streamDone = false
	s.turnContent = content
	s.mu.Unlock()
	if err := g.submit(gwID, content); err != nil {
		s.emit(obj{"type": "error", "message": err.Error()})
		s.gwFinish(nil, "")
	}
	return true
}

// gwAssistantStart opens the turn's assistant bubble (once per bubble).
func (s *Session) gwAssistantStart() {
	s.mu.Lock()
	if s.streamingID != "" {
		s.mu.Unlock()
		return
	}
	s.streamingID = uuid.New().String()
	s.mu.Unlock()
	s.emit(obj{"type": "assistant_start"})
}

// gwAppendDelta streams one chunk into the open bubble.
func (s *Session) gwAppendDelta(delta string) {
	if delta == "" {
		return
	}
	s.gwAssistantStart()
	s.mu.Lock()
	s.streamBuf.WriteString(delta)
	s.mu.Unlock()
	s.emit(obj{"type": "assistant_delta", "delta": delta})
}

// gwFinish closes the current bubble: emits assistant_done (with usage and
// the derived title) and stores the reply. text is the complete reply when no
// deltas arrived; usage mirrors the app's token shape, nil when unknown.
func (s *Session) gwFinish(usage interface{}, text string) {
	s.mu.Lock()
	if s.streamDone {
		s.mu.Unlock()
		return
	}
	s.streamDone = true
	id := s.streamingID
	s.streamingID = ""
	body := s.streamBuf.String()
	if body == "" {
		body = text
	}
	content := s.turnContent
	s.mu.Unlock()
	s.emit(obj{"type": "assistant_done", "usage": usage, "title": s.derivedTitle(content)})
	if id == "" || body == "" {
		return
	}
	s.mu.Lock()
	s.msgs = append(s.msgs, Message{ID: id, Role: "assistant", Content: body})
	s.mu.Unlock()
}

// runTurnSpawn is the legacy path: one `hermes chat --oneshot` process per turn.
func (s *Session) runTurnSpawn(content string, hermesID string) {

	args := []string{
		"chat",
		"--format", "stream-json",
		"--oneshot",
		"-q", content,
		"--pass-session-id",
		"--accept-hooks",
		"--yolo",
	}
	if hermesID != "" {
		args = append(args, "--resume", hermesID)
	}

	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()

	// Watch for abort signal
	go func() {
		select {
		case <-s.abortCh:
			cancel()
		case <-ctx.Done():
		}
	}()

	cmd := exec.CommandContext(ctx, s.hermesBin, args...)
	cmd.Dir = s.homeDir
	stderr := &tailBuffer{max: 8 * 1024}
	cmd.Stderr = stderr
	stdout, err := cmd.StdoutPipe()
	if err != nil {
		s.emit(obj{"type": "error", "message": fmt.Sprintf("spawn failed: %v", err)})
		return
	}
	if err := cmd.Start(); err != nil {
		s.emit(obj{"type": "error", "message": fmt.Sprintf("hermes start: %v", err)})
		return
	}
	log.Printf("[hermes] session %s pid %d", s.id, cmd.Process.Pid)

	// parse NDJSON from hermes --format stream-json
	assistantText := strings.Builder{}
	msgID := uuid.New().String()
	startedAssistant := false
	doneSent := false
	var usage interface{}

	emitAssistantStart := func() {
		if startedAssistant {
			return
		}
		startedAssistant = true
		s.emit(obj{"type": "assistant_start"})
	}
	appendDelta := func(delta string) {
		if delta == "" {
			return
		}
		emitAssistantStart()
		assistantText.WriteString(delta)
		s.emit(obj{"type": "assistant_delta", "delta": delta})
	}
	emitDone := func() {
		if doneSent {
			return
		}
		doneSent = true
		s.emit(obj{"type": "assistant_done", "usage": usage, "title": s.derivedTitle(content)})
		if assistantText.Len() == 0 {
			return
		}
		s.mu.Lock()
		s.msgs = append(s.msgs, Message{ID: msgID, Role: "assistant", Content: assistantText.String()})
		s.mu.Unlock()
	}

	scanner := bufio.NewScanner(stdout)
	scanner.Buffer(make([]byte, 4*1024*1024), 4*1024*1024)
	for scanner.Scan() {
		line := scanner.Text()
		if line == "" {
			continue
		}
		var ev map[string]interface{}
		if err := json.Unmarshal([]byte(line), &ev); err != nil {
			continue
		}
		evType, _ := ev["type"].(string)
		switch evType {
		case "session_created", "system":
			// stream-json reports the session id in system/init; keep it so the
			// next turn resumes the same Hermes session instead of starting over.
			if sid, ok := ev["session_id"].(string); ok && sid != "" {
				s.mu.Lock()
				s.hermesSessionID = sid
				s.mu.Unlock()
			}
		case "assistant", "text":
			// legacy schema used assistant/content; stream-json uses text/text
			delta, _ := ev["content"].(string)
			if delta == "" {
				delta, _ = ev["text"].(string)
			}
			appendDelta(delta)

		case "tool_use", "tool":
			// show tool name as status
			name, _ := ev["name"].(string)
			if name != "" {
				s.emit(obj{"type": "status", "message": "🔧 " + name})
			}

		case "result":
			// The gateway assigns the session id after system/init, so the
			// result envelope is the authoritative place to learn it.
			if sid, ok := ev["session_id"].(string); ok && sid != "" {
				s.mu.Lock()
				s.hermesSessionID = sid
				s.mu.Unlock()
			}
			// result.text is the complete reply; use it when no deltas arrived
			// (replay gap, or providers that only report the final answer).
			if txt, ok := ev["text"].(string); ok && txt != "" && assistantText.Len() == 0 {
				appendDelta(txt)
			}
			if u := usageFromTokens(ev["tokens"]); u != nil {
				usage = u
			}
			if msg, _ := ev["error"].(string); msg != "" {
				s.emit(obj{"type": "error", "message": msg})
			} else if code := jsonInt(ev["exit_code"]); code != 0 {
				s.emit(obj{"type": "error", "message": exitMessage(code, stderr.String())})
			}
			emitDone()

		case "error":
			msg, _ := ev["message"].(string)
			if msg == "" {
				msg, _ = ev["error"].(string)
			}
			if msg == "" {
				msg = "hermes error"
			}
			s.emit(obj{"type": "error", "message": msg})
		}
	}

	_ = stdout.Close()
	waitErr := cmd.Wait()
	if !doneSent {
		// Surface provider/auth failures instead of a silent empty reply. An
		// aborted turn cancels ctx, so it is not reported as an error.
		if waitErr != nil && ctx.Err() == nil {
			s.emit(obj{"type": "error", "message": exitMessage(exitCodeOf(waitErr), stderr.String())})
		}
		emitDone()
	}
}

// tailBuffer keeps the last max bytes written to it so a failed Hermes run can
// report the end of stderr without buffering an unbounded log.
type tailBuffer struct {
	buf []byte
	max int
}

func (t *tailBuffer) Write(p []byte) (int, error) {
	t.buf = append(t.buf, p...)
	if len(t.buf) > t.max {
		t.buf = t.buf[len(t.buf)-t.max:]
	}
	return len(p), nil
}

func (t *tailBuffer) String() string { return string(t.buf) }

func jsonInt(v interface{}) int {
	switch n := v.(type) {
	case float64:
		return int(n)
	case int:
		return n
	case json.Number:
		i, _ := n.Int64()
		return int(i)
	}
	return 0
}

// usageFromTokens maps stream-json result.tokens onto the token shape the app
// renders (input_tokens/output_tokens).
func usageFromTokens(v interface{}) interface{} {
	tokens, ok := v.(map[string]interface{})
	if !ok {
		return nil
	}
	return obj{
		"input_tokens":  jsonInt(tokens["input"]),
		"output_tokens": jsonInt(tokens["output"]),
		"total_tokens":  jsonInt(tokens["total"]),
	}
}

func exitCodeOf(err error) int {
	var ee *exec.ExitError
	if errors.As(err, &ee) {
		return ee.ExitCode()
	}
	return 1
}

func exitMessage(code int, stderr string) string {
	stderr = strings.TrimSpace(stderr)
	if stderr == "" {
		return fmt.Sprintf("hermes exited with code %d", code)
	}
	lines := strings.Split(stderr, "\n")
	if len(lines) > 6 {
		lines = lines[len(lines)-6:]
	}
	return fmt.Sprintf("hermes exited with code %d: %s", code, strings.Join(lines, " | "))
}

// derivedTitle truncates the first user message into a conversation title.
func (s *Session) derivedTitle(firstMsg string) string {
	if s.title != "" && s.title != "New Chat" {
		return s.title
	}
	r := []rune(firstMsg)
	if len(r) > 48 {
		r = append(r[:45], []rune("…")...)
	}
	title := string(r)
	s.mu.Lock()
	s.title = title
	s.mu.Unlock()
	return title
}

// ── subscriber ────────────────────────────────────────────────────────────────

type sub struct {
	conn *websocket.Conn
	ch   chan []byte
	done chan struct{}
	once sync.Once
}

func (s *sub) send(b []byte) {
	select {
	case <-s.done:
	case s.ch <- b:
	default:
		s.close()
	}
}

func (s *sub) close() {
	s.once.Do(func() {
		close(s.done)
		// say goodbye properly: the close frame turns every server-side
		// close into a clean closure for the client. WriteControl may run
		// next to a data write and happens off the session lock so a slow
		// socket cannot stall the broadcaster.
		go func() {
			_ = s.conn.WriteControl(websocket.CloseMessage,
				websocket.FormatCloseMessage(websocket.CloseNormalClosure, ""),
				time.Now().Add(2*time.Second))
			_ = s.conn.Close()
		}()
	})
}

func (s *sub) writeLoop() {
	for {
		select {
		case <-s.done:
			return
		case b := <-s.ch:
			if err := s.conn.WriteMessage(websocket.TextMessage, b); err != nil {
				s.close()
				return
			}
		}
	}
}

// ── helpers ───────────────────────────────────────────────────────────────────

type obj = map[string]interface{}

func writeJSON(w http.ResponseWriter, code int, v interface{}) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(code)
	_ = json.NewEncoder(w).Encode(v)
}

// ensure io import is used (scanner uses it internally through stdout pipe)
var _ io.ReadCloser
