// Persistent connection to the profile's Hermes gateway (the "warm" path).
//
// Instead of spawning `hermes chat --oneshot` for every turn — a full Python
// boot each message, tens of seconds in the proot container — the bridge keeps
// ONE live WebSocket to the gateway and dispatches turns over it. The gateway
// is the same long-running process Hermes' own TUI and OMH speak to; a turn
// then costs model latency only.
//
// Wire (validated against Hermes aa910ca036, protocol hermes-gateway-v1):
//
//   - `hermes gateway attach` prints {"url","ticket","protocol",...}; the
//     ticket rides the WebSocket subprotocol "hermes-gateway-ticket.<ticket>"
//     next to "hermes-gateway-v1", and is single-use.
//   - Frames are JSON-RPC. The first client call is `runtime.describe` {},
//     whose result.session_create lists the accepted session.create keys.
//   - `session.create` {cwd, yolo, source:"tui", request_id, model?, provider?,
//     base_url?, api_key?, title?} → {session_id, subscription_id,
//     execution_generation, authority_epoch, revision, messages, ...}.
//   - `prompt.submit` {session_id, text, input_id} → {status:"queued", ...};
//     the turn streams back as `event` notifications (message.start /
//     message.delta / message.complete / tool.start / tool.complete /
//     status.update / error / session.title), each params carrying
//     session_id + payload (+ execution_generation on lifecycle events).
//   - `session.interrupt` {session_id, execution_generation} stops a turn;
//     `session.resume` re-reads a snapshot (and re-subscribes after a
//     reconnect); `session.detach` {session_id, subscription_id, end_reason}
//     closes it; `session.mutate` {…, operation:"model", payload:{model,
//     provider?}} switches the model; `model.options` lists the picker.
//
// A stale lifecycle event (older execution_generation than the view's) is
// dropped, as Hermes' own clients do, so an interrupted turn's late
// message.complete cannot end the turn that replaced it.

package hermes

import (
	"bufio"
	"context"
	"encoding/json"
	"fmt"
	"log"
	"net"
	"os/exec"
	"path/filepath"
	"strings"
	"sync"
	"time"

	"github.com/google/uuid"
	"github.com/gorilla/websocket"
)

const (
	gatewayProtocol = "hermes-gateway-v1"
	attachTimeout   = 180 * time.Second // a cold gateway start takes 33–62 s on this phone, more in proot
	callTimeout     = 30 * time.Second
	createTimeout   = 180 * time.Second
	// A model pick rides out a busy window (Hermes holds the session's
	// mutation lock through post-turn finalize, and a reconnecting gateway
	// answers nothing for a while), so it gets its own, larger budget.
	pickCallTimeout   = 35 * time.Second
	pickBudget        = 75 * time.Second
	reconnectDelay    = 3 * time.Second
	reconnectMaxDelay = 30 * time.Second
)

// lifecycleEvents are fenced by execution generation: a late event from an
// interrupted turn must not end the turn that replaced it.
var lifecycleEvents = map[string]bool{
	"session.info":     true,
	"message.start":    true,
	"message.complete": true,
	"error":            true,
}

// gateway is one live connection to the Hermes gateway, shared by every
// bridge session.
type gateway struct {
	mgr     *Manager
	mu      sync.Mutex
	conn    *websocket.Conn
	wmu     sync.Mutex // serialises writes
	startMu sync.Mutex // single-flight for the connect itself

	nextID  int64
	pending map[int64]chan map[string]interface{}

	// gateway session id → the bridge session that owns it
	owners map[string]*Session
	// health-scan waiters: gateway session id → channel that receives
	// "ok" or the error text when the probe turn ends
	probes map[string]chan string
	// gateway session id → the view this connection holds (generation,
	// epoch, subscription) for fencing and interrupts
	views map[string]*gwView

	healthy bool
	// last diagnostics for the /api/hermes/gateway status endpoint
	endpoint string // api_origin of the last successful attach
	lastErr  string // why the last connect attempt failed
	closing  bool
}

// gwView is this connection's view of one gateway session.
type gwView struct {
	sub     string // subscription_id, needed to detach
	gen     int    // execution_generation the session runs (0 = unknown)
	epoch   int    // authority_epoch of the last snapshot
	running bool
}

func newGateway(mgr *Manager) *gateway {
	return &gateway{
		mgr:     mgr,
		pending: make(map[int64]chan map[string]interface{}),
		owners:  make(map[string]*Session),
		views:   make(map[string]*gwView),
	}
}

// ensure connects (or reconnects) the gateway. It returns once the connection
// is ready for calls; on failure the error says why. Concurrent callers share
// one connect attempt (the attach ticket is single-use).
func (g *gateway) ensure() error {
	g.mu.Lock()
	healthy := g.healthy && g.conn != nil
	g.mu.Unlock()
	if healthy {
		return nil
	}
	g.startMu.Lock()
	defer g.startMu.Unlock()
	g.mu.Lock()
	healthy = g.healthy && g.conn != nil
	g.mu.Unlock()
	if healthy {
		return nil
	}

	url, ticket, err := g.attach()
	if err != nil {
		return err
	}
	dialer := websocket.Dialer{
		Subprotocols: []string{gatewayProtocol, "hermes-gateway-ticket." + ticket},
		// a gateway that just finished booting can take a while before its API
		// listener accepts; 15 s cut it too close in the proot container
		HandshakeTimeout: 30 * time.Second,
	}
	conn, _, err := dialer.Dial(url, nil)
	if err != nil {
		return fmt.Errorf("gateway websocket: %w", err)
	}
	if conn.Subprotocol() != gatewayProtocol {
		conn.Close()
		return fmt.Errorf("gateway answered with protocol %q, not %q", conn.Subprotocol(), gatewayProtocol)
	}
	_ = conn.UnderlyingConn().SetDeadline(time.Time{})

	g.mu.Lock()
	g.conn = conn
	g.nextID = 0
	g.pending = make(map[int64]chan map[string]interface{})
	g.views = make(map[string]*gwView) // subscriptions are per connection
	g.probes = make(map[string]chan string)
	g.healthy = true
	g.lastErr = ""
	g.mu.Unlock()

	go g.readLoop()

	// learn the session contract; also proves the connection serves calls
	if _, err := g.call("runtime.describe", obj{}, callTimeout); err != nil {
		g.drop(err)
		return fmt.Errorf("gateway describe: %w", err)
	}
	// re-subscribe every live session: a fresh connection holds no views
	g.resubscribeAll()
	log.Printf("[hermes-gw] connected %s", url)
	return nil
}

// attach runs `hermes gateway attach` and returns the WS URL and ticket. The
// command also starts the gateway when it is not running (cold start: tens of
// seconds on this phone), hence the generous timeout. Hermes builds without
// the attach subcommand fall back to ensure + control-socket ticket.
func (g *gateway) attach() (string, string, error) {
	ctx, cancel := context.WithTimeout(context.Background(), attachTimeout)
	defer cancel()
	cmd := exec.CommandContext(ctx, g.mgr.hermesBin, "gateway", "attach")
	cmd.Dir = g.mgr.homeDir
	var stderr tailBuffer
	stderr.max = 1024
	cmd.Stderr = &stderr
	out, err := cmd.Output()
	if err != nil {
		// older Hermes builds have no `gateway attach`; the same handshake is
		// reachable through `gateway ensure` + the home's control socket
		if strings.Contains(stderr.String(), "is not a") && strings.Contains(stderr.String(), "gateway") {
			return g.attachLegacy()
		}
		g.mu.Lock()
		g.lastErr = fmt.Sprintf("hermes gateway attach: %v: %s", err, stderr.String())
		g.mu.Unlock()
		return "", "", fmt.Errorf("hermes gateway attach: %v: %s", err, stderr.String())
	}
	var info struct {
		URL      string `json:"url"`
		Ticket   string `json:"ticket"`
		Protocol string `json:"protocol"`
		State    string `json:"state"`
	}
	if err := json.Unmarshal(out, &info); err != nil {
		return "", "", fmt.Errorf("hermes gateway attach: bad JSON: %v", err)
	}
	if info.URL == "" || info.Ticket == "" {
		return "", "", fmt.Errorf("hermes gateway attach: no url/ticket (state %q)", info.State)
	}
	g.mu.Lock()
	g.endpoint = info.URL
	g.mu.Unlock()
	return info.URL, info.Ticket, nil
}

// call sends a JSON-RPC request and waits for its answer.
func (g *gateway) call(method string, params obj, timeout time.Duration) (obj, error) {
	g.mu.Lock()
	conn := g.conn
	if conn == nil || !g.healthy {
		g.mu.Unlock()
		return nil, fmt.Errorf("gateway not connected")
	}
	g.nextID++
	id := g.nextID
	ch := make(chan map[string]interface{}, 1)
	g.pending[id] = ch
	g.mu.Unlock()

	frame, _ := json.Marshal(obj{"jsonrpc": "2.0", "id": id, "method": method, "params": params})
	if err := g.writeRaw(frame); err != nil {
		g.mu.Lock()
		delete(g.pending, id)
		g.mu.Unlock()
		return nil, err
	}

	select {
	case reply := <-ch:
		if e, ok := reply["error"].(map[string]interface{}); ok {
			msg, _ := e["message"].(string)
			if msg == "" {
				msg = fmt.Sprintf("code %v", e["code"])
			}
			return nil, fmt.Errorf("%s: %s", method, msg)
		}
		res, _ := reply["result"].(map[string]interface{})
		if res == nil {
			res = obj{}
		}
		return res, nil
	case <-time.After(timeout):
		g.mu.Lock()
		delete(g.pending, id)
		g.mu.Unlock()
		return nil, fmt.Errorf("%s timed out after %s", method, timeout)
	}
}

func (g *gateway) writeRaw(frame []byte) error {
	g.wmu.Lock()
	defer g.wmu.Unlock()
	g.mu.Lock()
	conn := g.conn
	g.mu.Unlock()
	if conn == nil {
		return fmt.Errorf("gateway not connected")
	}
	conn.SetWriteDeadline(time.Now().Add(10 * time.Second))
	return conn.WriteMessage(websocket.TextMessage, frame)
}

// readLoop drains the gateway until it drops; then it reconnects in the
// background so the next turn finds a warm connection.
func (g *gateway) readLoop() {
	g.mu.Lock()
	conn := g.conn
	g.mu.Unlock()
	for {
		_, raw, err := conn.ReadMessage()
		if err != nil {
			break
		}
		var frame map[string]interface{}
		if json.Unmarshal(raw, &frame) != nil {
			continue
		}
		g.handleFrame(frame)
	}
	g.drop(nil)
	// background reconnect: sessions re-subscribe when it lands
	go func() {
		delay := reconnectDelay
		for {
			time.Sleep(delay)
			if err := g.ensure(); err != nil {
				log.Printf("[hermes-gw] reconnect failed: %v", err)
				delay = min(delay*2, reconnectMaxDelay)
				continue
			}
			return
		}
	}()
}

// drop tears the connection down after a read/write failure.
func (g *gateway) drop(cause error) {
	g.mu.Lock()
	conn := g.conn
	wasHealthy := g.healthy
	g.healthy = false
	g.conn = nil
	for id, ch := range g.pending {
		select {
		case ch <- map[string]interface{}{"error": map[string]interface{}{"code": -1, "message": "gateway connection lost"}}:
		default:
			close(ch)
			delete(g.pending, id)
		}
	}
	g.mu.Unlock()
	if conn != nil {
		conn.Close()
	}
	if cause != nil {
		log.Printf("[hermes-gw] dropped: %v", cause)
	}
	if wasHealthy {
		g.mgr.onGatewayLost()
	}
}

func (g *gateway) handleFrame(frame map[string]interface{}) {
	method, _ := frame["method"].(string)
	if method == "event" {
		params, _ := frame["params"].(map[string]interface{})
		g.mgr.routeGatewayEvent(params)
		return
	}
	// a server→client request: answer everything politely so the gateway
	// never waits out a deadline on us (approvals arrive as events on this
	// wire, and yolo sessions do not ask)
	if method != "" {
		if id, ok := frame["id"]; ok {
			reply, _ := json.Marshal(obj{"jsonrpc": "2.0", "id": id, "result": obj{}})
			_ = g.writeRaw(reply)
		}
		return
	}
	// a reply to one of our calls
	if idf, ok := frame["id"].(float64); ok {
		g.mu.Lock()
		ch, waiting := g.pending[int64(idf)]
		if waiting {
			delete(g.pending, int64(idf))
		}
		g.mu.Unlock()
		if waiting {
			ch <- frame
		}
	}
}

// resubscribeAll re-reads a snapshot for every live session after a
// reconnect: the resume re-subscribes this connection and refreshes the
// generation/epoch the fencing needs.
func (g *gateway) resubscribeAll() {
	g.mu.Lock()
	ids := make([]string, 0, len(g.owners))
	for id := range g.owners {
		ids = append(ids, id)
	}
	g.mu.Unlock()
	for _, id := range ids {
		res, err := g.call("session.resume", obj{"session_id": id}, callTimeout)
		if err != nil {
			log.Printf("[hermes-gw] resubscribe %s: %v", shortID(id), err)
			continue
		}
		g.noteView(id, res, true)
		g.mgr.onGatewayResumed(id, res)
	}
}

// noteView records what a create/resume snapshot (or a receipt) says about a
// session: subscription, generation, epoch. Only a snapshot replaces
// generation and epoch; events and receipts only move them forward.
func (g *gateway) noteView(gwID string, res obj, snapshot bool) {
	if gwID == "" {
		return
	}
	g.mu.Lock()
	v := g.views[gwID]
	if v == nil {
		v = &gwView{}
		g.views[gwID] = v
	}
	if s, ok := res["subscription_id"].(string); ok && s != "" {
		v.sub = s
	}
	if snapshot {
		if n, ok := res["execution_generation"].(float64); ok {
			v.gen = int(n)
		}
		if n, ok := res["authority_epoch"].(float64); ok {
			v.epoch = int(n)
		}
	}
	if r, ok := res["running"].(bool); ok {
		v.running = r
	}
	g.mu.Unlock()
}

// noteEventGen moves the view's generation forward on events and receipts.
func (g *gateway) noteEventGen(gwID string, gen int) {
	if gwID == "" || gen <= 0 {
		return
	}
	g.mu.Lock()
	v := g.views[gwID]
	if v == nil {
		v = &gwView{}
		g.views[gwID] = v
	}
	if gen > v.gen {
		v.gen = gen
	}
	g.mu.Unlock()
}

// viewOf copies the current view of a session.
func (g *gateway) viewOf(gwID string) gwView {
	g.mu.Lock()
	defer g.mu.Unlock()
	if v := g.views[gwID]; v != nil {
		return *v
	}
	return gwView{}
}

// stale reports whether a lifecycle event belongs to a generation or epoch
// this session has moved past (Hermes' fenceLifecycleEvent).
func (g *gateway) stale(gwID string, evType string, params, payload obj) bool {
	if !lifecycleEvents[evType] {
		return false
	}
	v := g.viewOf(gwID)
	if v.gen == 0 && v.epoch == 0 {
		return false // no view yet: nothing to fence against
	}
	gen := jsonInt(params["execution_generation"])
	if gen == 0 {
		gen = jsonInt(payload["execution_generation"])
	}
	epoch := jsonInt(params["authority_epoch"])
	if epoch == 0 {
		epoch = jsonInt(payload["authority_epoch"])
	}
	if v.epoch != 0 && epoch != 0 && epoch != v.epoch {
		return true
	}
	return gen != 0 && v.gen != 0 && gen < v.gen
}

// ── session operations ────────────────────────────────────────────────────────

// createSession opens a gateway session for a bridge session.
func (g *gateway) createSession(s *Session) error {
	params := obj{
		"cwd":        s.homeDir,
		"yolo":       true,
		"source":     "tui",
		"request_id": uuid.New().String(),
	}
	s.mu.Lock()
	title := s.title
	model, provider, baseURL, apiKey := s.gwModel, s.gwProvider, s.gwBaseURL, s.gwAPIKey
	s.mu.Unlock()
	if title != "" && title != "New Chat" {
		params["title"] = title
	}
	if model != "" {
		params["model"] = model
	}
	if provider != "" {
		params["provider"] = provider
	}
	if baseURL != "" {
		params["base_url"] = baseURL
	}
	if apiKey != "" {
		params["api_key"] = apiKey
	}
	res, err := g.call("session.create", params, createTimeout)
	if err != nil {
		return err
	}
	gwID, _ := res["session_id"].(string)
	if gwID == "" {
		return fmt.Errorf("session.create returned no session id")
	}
	g.noteView(gwID, res, true)
	g.mu.Lock()
	g.owners[gwID] = s
	g.mu.Unlock()
	s.mu.Lock()
	s.gwID = gwID
	s.mu.Unlock()
	log.Printf("[hermes-gw] session %s -> %s", shortID(s.id), shortID(gwID))
	return nil
}

// submit sends one turn; the reply only says "queued", the answer streams
// back as events.
func (g *gateway) submit(gwID, text string) error {
	_, err := g.call("prompt.submit", obj{
		"session_id": gwID,
		"text":       text,
		"input_id":   uuid.New().String(),
	}, callTimeout)
	return err
}

// interrupt stops a running turn. The generation comes from the view; when
// the view has none (fresh connection), a resume reads it first.
func (g *gateway) interrupt(gwID string) error {
	v := g.viewOf(gwID)
	if v.gen == 0 {
		res, err := g.call("session.resume", obj{"session_id": gwID}, callTimeout)
		if err != nil {
			return err
		}
		g.noteView(gwID, res, true)
		v = g.viewOf(gwID)
		if !v.running {
			return nil // the turn already ended on its own
		}
	}
	_, err := g.call("session.interrupt", obj{
		"session_id":           gwID,
		"execution_generation": v.gen,
	}, callTimeout)
	return err
}

// detach releases a gateway session (bridge session deleted / app gone).
func (g *gateway) detach(gwID string) {
	v := g.viewOf(gwID)
	if v.sub == "" {
		return
	}
	_, _ = g.call("session.detach", obj{
		"session_id":      gwID,
		"subscription_id": v.sub,
		"end_reason":      "tui_close",
	}, callTimeout)
	g.mu.Lock()
	delete(g.owners, gwID)
	delete(g.views, gwID)
	g.mu.Unlock()
}

// models asks the gateway for the model picker payload (Hermes' own
// providers and models). An empty sessionID asks for the profile default.
func (g *gateway) models(sessionID string) (obj, error) {
	params := obj{}
	if sessionID != "" {
		params["session_id"] = sessionID
	}
	return g.call("model.options", params, 60*time.Second)
}

// gatewayCall is the JSON-RPC seam setModel retries through: production uses
// (*gateway).call, tests script a fake.
type gatewayCall func(method string, params obj, timeout time.Duration) (obj, error)

// setModel switches a live session's model (Ink's session.mutate model). A
// provider the gateway does not know is refused; the caller falls back to a
// fresh session for custom endpoints.
func (g *gateway) setModel(gwID, model, provider string) (obj, error) {
	payload := obj{"model": model}
	if provider != "" {
		payload["provider"] = provider
	}
	// The real seam re-establishes the connection before every attempt and,
	// when a call times out on a session with no running turn, drops the
	// socket: a lost reply leaves the pending map waiting on a dead pipe, so
	// every later call would time out too — the retry then reconnects instead
	// of failing four times in a row. A running turn keeps its connection:
	// dropping it would cut the turn's event stream.
	pickCall := func(method string, params obj, timeout time.Duration) (obj, error) {
		if err := g.ensure(); err != nil {
			return nil, err
		}
		res, err := g.call(method, params, timeout)
		if err != nil && isCallTimeout(err) && !g.viewOf(gwID).running {
			g.drop(err)
		}
		return res, err
	}
	return g.setModelWith(pickCall, gwID, payload)
}

// setModelWith retries a switch that lost a race with a turn which finished
// after this connection's last snapshot (revision_conflict /
// stale_generation) or whose call timed out: the gateway refuses with fences
// the caller may not have applied, so every retry re-reads the session before
// fencing again. Any other refusal (unknown model, busy session, disconnected
// gateway) is returned at once. The whole loop is bounded by pickBudget and
// four attempts so a wedged gateway cannot hold the picker forever.
func (g *gateway) setModelWith(call gatewayCall, gwID string, payload obj) (obj, error) {
	deadline := time.Now().Add(pickBudget)
	const maxAttempts = 4
	var lastErr error
	for attempt := 0; attempt < maxAttempts; attempt++ {
		remaining := time.Until(deadline)
		if remaining <= 0 {
			return nil, lastErr
		}
		timeout := remaining
		if timeout > pickCallTimeout {
			timeout = pickCallTimeout
		}
		snap, err := call("session.resume", obj{"session_id": gwID}, timeout)
		if err != nil {
			if !retryablePick(err) {
				return nil, err
			}
			lastErr = err
			continue
		}
		g.noteView(gwID, snap, true)
		res, err := call("session.mutate", obj{
			"session_id":          gwID,
			"request_id":          uuid.New().String(),
			"expected_revision":   jsonInt(snap["revision"]),
			"expected_generation": g.viewOf(gwID).gen,
			"operation":           "model",
			"payload":             payload,
		}, timeout)
		if err == nil {
			return res, nil
		}
		lastErr = err
		if !retryablePick(err) {
			return nil, err
		}
	}
	return nil, lastErr
}

// mutateRace reports whether a refused model switch is the retryable
// revision/generation race instead of a real refusal.
func mutateRace(err error) bool {
	if err == nil {
		return false
	}
	msg := err.Error()
	return strings.Contains(msg, "revision_conflict") || strings.Contains(msg, "stale_generation")
}

// isCallTimeout reports whether a gateway call gave up waiting for its reply.
func isCallTimeout(err error) bool {
	if err == nil {
		return false
	}
	return strings.Contains(err.Error(), " timed out after ")
}

// retryablePick is the set of failures a model pick may ride out: the
// revision race, or a call that timed out (the socket is dropped and the
// retry reconnects).
func retryablePick(err error) bool {
	return mutateRace(err) || isCallTimeout(err)
}

// history reads a session's stored transcript as display rows.
func (g *gateway) history(gwID string) (obj, error) {
	return g.call("session.history", obj{"session_id": gwID}, callTimeout)
}

func shortID(id string) string {
	if len(id) > 12 {
		return id[:12] + "…"
	}
	return id
}

// probeWaiter returns (and keeps) the health-scan channel for a gateway
// session, or nil when the session is not a probe.
func (g *gateway) probeWaiter(gwID string) chan string {
	g.mu.Lock()
	defer g.mu.Unlock()
	return g.probes[gwID]
}

// probeRefusal turns a finished probe answer into the reason it is not an
// answer. Hermes' Nous Portal wrapper reports a credit-gated model as the
// assistant text ("Billing or credits exhausted: ... requires available
// credits") and still emits message.complete. A probe that watched only the
// event type therefore marked such a model healthy at a plausible latency, and
// the picker offered it as functional while every real turn failed with the
// same credit error.
func probeRefusal(text string) string {
	for _, mark := range []string{
		"requires available credits",
		"Billing or credits exhausted",
		"insufficient_quota",
		"exceeded your current quota",
		"no active subscription",
	} {
		if !strings.Contains(text, mark) {
			continue
		}
		line := strings.TrimSpace(strings.SplitN(text, "\n", 2)[0])
		if runes := []rune(line); len(runes) > 180 {
			line = string(runes[:180]) + "…"
		}
		return line
	}
	return ""
}

// probeModel answers one health-scan probe: create a scratch gateway session
// pinned to the model/provider, submit a one-word prompt, wait for the reply
// or the error, then drop the session. The latency covers the whole turn —
// the number the model picker shows. Hermes' own provider credentials ride
// along, so preset models are probed with the same keys a real chat uses.
func (g *gateway) probeModel(model, provider, baseURL, apiKey string, timeout time.Duration) (int64, string, error) {
	if err := g.ensure(); err != nil {
		return 0, "", err
	}
	params := obj{
		"cwd":        g.mgr.homeDir,
		"yolo":       true,
		"source":     "tui",
		"request_id": uuid.New().String(),
	}
	if model != "" {
		params["model"] = model
	}
	if provider != "" {
		params["provider"] = provider
	}
	if baseURL != "" {
		params["base_url"] = baseURL
	}
	if apiKey != "" {
		params["api_key"] = apiKey
	}
	res, err := g.call("session.create", params, createTimeout)
	if err != nil {
		return 0, "", err
	}
	gwID, _ := res["session_id"].(string)
	if gwID == "" {
		return 0, "", fmt.Errorf("session.create returned no session id")
	}
	g.noteView(gwID, res, true)
	ch := make(chan string, 1)
	g.mu.Lock()
	g.probes[gwID] = ch
	g.mu.Unlock()
	defer func() {
		g.mu.Lock()
		delete(g.probes, gwID)
		g.mu.Unlock()
		g.detach(gwID)
	}()
	started := time.Now()
	if err := g.submit(gwID, "Reply with exactly: OK"); err != nil {
		return 0, "", err
	}
	select {
	case verdict := <-ch:
		if verdict == "ok" {
			return time.Since(started).Milliseconds(), "", nil
		}
		return 0, verdict, nil
	case <-time.After(timeout):
		return 0, "", fmt.Errorf("no answer in %s", timeout)
	}
}

// attachLegacy handles Hermes builds without `gateway attach` (the container's
// copy predates it): `hermes gateway ensure` answers the endpoint JSON, and
// the ticket is minted over the home's gateway.sock — the same same-user
// private control verb `hermes chat` itself authenticates with.
func (g *gateway) attachLegacy() (string, string, error) {
	ctx, cancel := context.WithTimeout(context.Background(), attachTimeout)
	defer cancel()
	cmd := exec.CommandContext(ctx, g.mgr.hermesBin, "gateway", "ensure", "--timeout", "150")
	cmd.Dir = g.mgr.homeDir
	var stderr tailBuffer
	stderr.max = 1024
	cmd.Stderr = &stderr
	out, err := cmd.Output()
	if err != nil {
		g.mu.Lock()
		g.lastErr = fmt.Sprintf("hermes gateway ensure: %v: %s", err, stderr.String())
		g.mu.Unlock()
		return "", "", fmt.Errorf("hermes gateway ensure: %v: %s", err, stderr.String())
	}
	var res struct {
		State    string `json:"state"`
		Endpoint *struct {
			APIOrigin  string `json:"api_origin"`
			ProfileID  string `json:"profile_id"`
			InstanceID string `json:"instance_id"`
		} `json:"endpoint"`
	}
	if err := json.Unmarshal(out, &res); err != nil {
		return "", "", fmt.Errorf("hermes gateway ensure: bad JSON: %v", err)
	}
	if res.Endpoint == nil || res.Endpoint.APIOrigin == "" {
		return "", "", fmt.Errorf("hermes gateway ensure: state %q has no endpoint", res.State)
	}
	ticket, err := g.controlTicket(res.Endpoint.ProfileID, res.Endpoint.InstanceID)
	if err != nil {
		return "", "", err
	}
	url := res.Endpoint.APIOrigin
	url = strings.Replace(url, "https:", "wss:", 1)
	url = strings.Replace(url, "http:", "ws:", 1)
	return url + "/api/ws", ticket, nil
}

// controlTicket mints a session ticket over <home>/.hermes/gateway.sock:
// one JSON request line in, one JSON answer line out
// ({"ok":true,"protocol":1,"id":1,"result":{...,"ticket":...}}).
func (g *gateway) controlTicket(profileID, instanceID string) (string, error) {
	sock := filepath.Join(g.mgr.homeDir, ".hermes", "gateway.sock")
	conn, err := net.DialTimeout("unix", sock, 5*time.Second)
	if err != nil {
		return "", fmt.Errorf("gateway control socket: %v", err)
	}
	defer conn.Close()
	_ = conn.SetDeadline(time.Now().Add(5 * time.Second))
	request := fmt.Sprintf(
		`{"protocol":1,"id":1,"verb":"session-ticket","params":{"profile_id":%q,"instance_id":%q,"purpose":"interactive"}}`+"\n",
		profileID, instanceID)
	if _, err := conn.Write([]byte(request)); err != nil {
		return "", fmt.Errorf("gateway control write: %v", err)
	}
	line, err := bufio.NewReader(conn).ReadString('\n')
	if err != nil {
		return "", fmt.Errorf("gateway control read: %v", err)
	}
	var response struct {
		OK       bool `json:"ok"`
		Protocol int  `json:"protocol"`
		ID       int  `json:"id"`
		Result   struct {
			Ticket string `json:"ticket"`
		} `json:"result"`
	}
	if err := json.Unmarshal([]byte(strings.TrimRight(line, "\n")), &response); err != nil {
		return "", fmt.Errorf("gateway control answer: bad JSON: %v", err)
	}
	if !response.OK || response.Result.Ticket == "" {
		return "", fmt.Errorf("gateway control refused the ticket")
	}
	return response.Result.Ticket, nil
}
