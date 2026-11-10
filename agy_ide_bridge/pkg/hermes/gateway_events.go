// Gateway event routing and the model-picker REST surface.
//
// The gateway streams one `event` notification per occurrence; this file maps
// them onto the bridge's own event vocabulary (assistant_start / assistant_delta
// / assistant_done / status / error) that the app already renders, and adds
// the /models and /model endpoints the app's model selector reads.

package hermes

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"io"
	"log"
	"net/http"
	"net/url"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"sync"
	"time"
)

// modelsConnectBudget bounds the connect a models fetch waits for; a cold
// gateway start keeps going in the background instead.
const modelsConnectBudget = 20 * time.Second

// scanBatchBudget bounds one provider's whole scan-batch. Per-model deadlines
// bound a probe; this bounds the sum, so a 300-model catalog on a dead endpoint
// answers (with "budget exhausted" for whatever was not reached) instead of
// holding the HTTP request open for hours.
var scanBatchBudget = 10 * time.Minute

// routeGatewayEvent fans one gateway event out to the bridge session that
// owns the gateway session it names.
func (m *Manager) routeGatewayEvent(params map[string]interface{}) {
	evType, _ := params["type"].(string)
	if evType == "" {
		return
	}
	gwID, _ := params["session_id"].(string)
	payload, _ := params["payload"].(map[string]interface{})
	if payload == nil {
		payload = obj{}
	}

	// gateway-scoped events (gateway.ready and friends) belong to no session
	if strings.HasPrefix(evType, "gateway.") {
		return
	}

	m.mu.Lock()
	sess := m.gw.owners[gwID]
	m.mu.Unlock()
	if sess == nil {
		// health-scan probe sessions have no bridge session: complete the
		// waiter instead of dropping the event
		if ch := m.gw.probeWaiter(gwID); ch != nil {
			switch evType {
			case "message.complete":
				select {
				case ch <- "ok":
				default:
				}
			case "error":
				msg, _ := payload["text"].(string)
				if msg == "" {
					msg, _ = payload["message"].(string)
				}
				if msg == "" {
					msg = "hermes error"
				}
				select {
				case ch <- msg:
				default:
				}
			}
		}
		return
	}

	// fence lifecycle events from turns this session has moved past, then
	// move the view's generation forward (Ink's fenceLifecycleEvent)
	if m.gw.stale(gwID, evType, params, payload) {
		log.Printf("[hermes-gw] dropping stale %s for %s", evType, shortID(gwID))
		return
	}
	if gen := jsonInt(params["execution_generation"]); gen == 0 {
		if gen = jsonInt(payload["execution_generation"]); gen != 0 {
			m.gw.noteEventGen(gwID, gen)
		}
	} else {
		m.gw.noteEventGen(gwID, gen)
	}

	switch evType {
	case "message.start":
		sess.gwAssistantStart()

	case "message.delta":
		// the chunk rides "text" (null text marks a segment boundary — skip)
		if txt, _ := payload["text"].(string); txt != "" {
			sess.gwAppendDelta(txt)
		}

	case "message.complete":
		txt, _ := payload["text"].(string)
		sess.gwFinish(usageFromTokens(payload["usage"]), txt)

	case "tool.start", "tool.complete":
		name, _ := payload["name"].(string)
		if name == "" {
			name, _ = payload["tool"].(string)
		}
		if name != "" {
			mark := "🔧 " + name
			if evType == "tool.complete" {
				mark = "✓ " + name
			}
			sess.emit(obj{"type": "status", "message": mark})
		}

	case "status.update":
		if txt, _ := payload["text"].(string); txt != "" {
			sess.emit(obj{"type": "status", "message": txt})
		}

	case "error":
		msg, _ := payload["text"].(string)
		if msg == "" {
			msg, _ = payload["message"].(string)
		}
		if msg == "" {
			msg = "hermes error"
		}
		sess.emit(obj{"type": "error", "message": msg})
		// a turn-ending error still owes the app a done, or the bubble spins
		sess.gwFinish(nil, "")

	case "approval.request":
		// yolo sessions should not ask; if one does anyway, decline it so
		// the turn cannot hang behind a question nobody can answer here
		if promptID, _ := payload["prompt_id"].(string); promptID != "" {
			_, _ = m.gw.call("approval.respond", obj{
				"session_id": gwID,
				"request_id": promptID,
				"choice":     "deny",
			}, callTimeout)
			sess.emit(obj{"type": "status", "message": "Hermes asked for an approval; declined (no approver attached)"})
		}

	case "clarify.request":
		if promptID, _ := payload["prompt_id"].(string); promptID != "" {
			_, _ = m.gw.call("clarify.respond", obj{
				"session_id": gwID,
				"request_id": promptID,
			}, callTimeout)
		}

	case "session.title":
		if title, _ := payload["title"].(string); title != "" {
			sess.mu.Lock()
			sess.title = title
			sess.mu.Unlock()
		}
	}
}

// onGatewayLost tells every live session its stream died; the reconnect (and
// the re-subscribe) follows in the gateway's own loop.
func (m *Manager) onGatewayLost() {
	m.mu.Lock()
	owners := make([]*Session, 0, len(m.gw.owners))
	for _, s := range m.gw.owners {
		owners = append(owners, s)
	}
	m.mu.Unlock()
	for _, s := range owners {
		s.emit(obj{"type": "error", "message": "Hermes gateway connection lost; reconnecting…"})
		s.gwFinish(nil, "")
	}
}

// onGatewayResumed re-subscribed a session after a reconnect. A turn that was
// streaming died with the old connection: close its bubble so the app does
// not spin, and surface the snapshot's transcript tail as the reply when the
// stream never delivered one.
func (m *Manager) onGatewayResumed(gwID string, snapshot obj) {
	m.mu.Lock()
	sess := m.gw.owners[gwID]
	m.mu.Unlock()
	if sess == nil {
		return
	}
	sess.mu.Lock()
	hadBubble := sess.streamingID != ""
	empty := sess.streamBuf.Len() == 0
	sess.mu.Unlock()
	if hadBubble {
		if empty {
			if txt, _ := snapshot["messages"]; txt != nil {
				// the snapshot's rows are the authoritative transcript; the
				// last assistant row is this turn's answer when it completed
				// before the drop
				if last := lastAssistantText(snapshot["messages"]); last != "" {
					sess.gwFinish(nil, last)
					return
				}
			}
		}
		sess.gwFinish(nil, "")
	}
}

// lastAssistantText pulls the last assistant row's text out of a
// session.resume snapshot's message rows.
func lastAssistantText(rows interface{}) string {
	list, ok := rows.([]interface{})
	if !ok {
		return ""
	}
	for i := len(list) - 1; i >= 0; i-- {
		row, ok := list[i].(map[string]interface{})
		if !ok {
			continue
		}
		if role, _ := row["role"].(string); role == "assistant" {
			switch txt := row["content"].(type) {
			case string:
				return txt
			case []interface{}:
				var b strings.Builder
				for _, part := range txt {
					if p, ok := part.(map[string]interface{}); ok {
						if t, _ := p["text"].(string); t != "" {
							b.WriteString(t)
						}
					}
				}
				return b.String()
			}
		}
	}
	return ""
}

// ── model picker handlers ─────────────────────────────────────────────────────

// handleModels answers GET /sessions/{id}/models with the model picker
// payload: "presets" is Hermes' own model.options (its configured providers
// and models), "custom" lists the app's custom providers (keys stripped).
// sess may be nil: the picker works before the first turn too (profile
// defaults, no session scope).
func (m *Manager) handleModels(w http.ResponseWriter, sess *Session) {
	var gwID string
	if sess != nil {
		sess.mu.Lock()
		gwID = sess.gwID
		sess.mu.Unlock()
	}
	payload := obj{"presets": obj{}, "custom": []obj{}}
	// the picker must not hang on a cold gateway: give the connect a short
	// budget (the attempt itself keeps running and warms the gateway for the
	// next try), then answer with a clear "starting" note
	errCh := make(chan error, 1)
	go func() { errCh <- m.gw.ensure() }()
	select {
	case err := <-errCh:
		if err == nil {
			if res, err := m.gw.models(gwID); err == nil {
				payload["presets"] = res
			} else {
				payload["presets_error"] = err.Error()
			}
		} else {
			payload["presets_error"] = err.Error()
		}
	case <-time.After(modelsConnectBudget):
		payload["presets_error"] = "Hermes' gateway is starting; try again in a moment"
	}
	payload["custom"] = appProvidersForUI(m.homeDir)
	writeJSON(w, http.StatusOK, payload)
}

// handleSetModel answers POST /sessions/{id}/model {model, provider_id}.
// A Hermes-known model switches the live session via session.mutate; a
// custom provider's model can only ride a session created for it (the
// endpoint and key are session.create parameters), so a live session on the
// Hermes default defers it to the next chat instead of half-switching.
func (m *Manager) handleSetModel(w http.ResponseWriter, r *http.Request, sess *Session) {
	var body struct {
		Model      string `json:"model"`
		ProviderID string `json:"provider_id"`
	}
	if err := json.NewDecoder(r.Body).Decode(&body); err != nil || body.Model == "" {
		writeJSON(w, http.StatusBadRequest, obj{"error": "missing model"})
		return
	}
	sess.mu.Lock()
	gwID := sess.gwID
	sess.mu.Unlock()

	if body.ProviderID != "" {
		p := findAppProvider(m.homeDir, body.ProviderID)
		if p == nil {
			writeJSON(w, http.StatusBadRequest, obj{"error": "unknown provider " + body.ProviderID})
			return
		}
		sess.mu.Lock()
		sess.gwModel = body.Model
		sess.gwProvider = "custom"
		sess.gwBaseURL = p.BaseURL
		sess.gwAPIKey = p.APIKey
		live := gwID != "" && sess.gwProviderAtCreate != "custom"
		sess.mu.Unlock()
		if live {
			writeJSON(w, http.StatusOK, obj{"success": false, "deferred": true,
				"message": "This chat runs on Hermes' own provider; the custom model applies from the next chat."})
			return
		}
		writeJSON(w, http.StatusOK, obj{"success": true, "deferred": true})
		return
	}

	// a model Hermes knows: switch the live session now. When the app also holds
	// this provider's own endpoint and key, ride that instead of Hermes' name —
	// the container's .env can have an empty NVIDIA_API_KEY, and Hermes answers
	// such a turn with "unknown provider nvidia".
	baseURL, apiKey := "", ""
	if p := findAppProvider(m.homeDir, body.ProviderID); p != nil && p.BaseURL != "" {
		baseURL, apiKey = p.BaseURL, p.APIKey
	}
	sess.mu.Lock()
	sess.gwModel = body.Model
	sess.gwProvider = ""
	sess.gwBaseURL = baseURL
	sess.gwAPIKey = apiKey
	sess.mu.Unlock()
	if gwID == "" {
		writeJSON(w, http.StatusOK, obj{"success": true, "deferred": true})
		return
	}
	if err := m.gw.ensure(); err != nil {
		writeJSON(w, http.StatusOK, obj{"success": true, "deferred": true,
			"message": "gateway unavailable; the model applies from the next turn"})
		return
	}
	res, err := m.gw.setModel(gwID, body.Model, "")
	if err != nil {
		writeJSON(w, http.StatusOK, obj{"success": false, "error": err.Error()})
		return
	}
	if status, _ := res["status"].(string); status == "confirmation_required" {
		msg, _ := res["confirm_message"].(string)
		writeJSON(w, http.StatusOK, obj{"success": false, "error": msg,
			"message": "Hermes asks for confirmation before this model (cost guard); confirm it in a Hermes terminal or start a new chat."})
		return
	}
	writeJSON(w, http.StatusOK, obj{"success": true, "model": res["model"]})
}

// ── the app's custom provider store (read-only, keys stripped for UI) ────────

// appProviderModel is one model of an app custom provider.
type appProviderModel struct {
	ID   string `json:"id"`
	Name string `json:"name,omitempty"`
}

// appProvider is one custom provider from ~/.claude/agy_providers.json — the
// same store the Claude chats use. The API key never leaves the bridge: the
// UI list carries a hint only, and turns get the real key through
// session.create's api_key parameter.
type appProvider struct {
	ID      string             `json:"id"`
	Name    string             `json:"name"`
	BaseURL string             `json:"base_url"`
	APIKey  string             `json:"api_key,omitempty"`
	Models  []appProviderModel `json:"models"`
}

// appProvidersFile mirrors the store's on-disk shape (pkg/claude writes it).
type appProvidersFile struct {
	Providers []struct {
		ID      string `json:"id"`
		Name    string `json:"name"`
		BaseURL string `json:"base_url"`
		APIKey  string `json:"api_key,omitempty"`
		Models  []struct {
			ID   string `json:"id"`
			Name string `json:"name,omitempty"`
		} `json:"models"`
	} `json:"providers"`
}

func appProvidersPath(homeDir string) string {
	return filepath.Join(homeDir, ".claude", "agy_providers.json")
}

// loadAppProviders reads the app's custom provider store; a missing or
// unreadable store means no custom models, not an error.
func loadAppProviders(homeDir string) []appProvider {
	raw, err := os.ReadFile(appProvidersPath(homeDir))
	if err != nil {
		return nil
	}
	var file appProvidersFile
	if json.Unmarshal(raw, &file) != nil {
		return nil
	}
	out := make([]appProvider, 0, len(file.Providers))
	for _, p := range file.Providers {
		ap := appProvider{ID: p.ID, Name: p.Name, BaseURL: normalizeBaseURL(p.BaseURL), APIKey: p.APIKey}
		for _, mm := range p.Models {
			ap.Models = append(ap.Models, appProviderModel{ID: mm.ID, Name: mm.Name})
		}
		out = append(out, ap)
	}
	return out
}

// normalizeBaseURL gives an OpenAI-compatible API root, the form both
// session.create and the health probe need.
//
// A provider saved as "https://integrate.api.nvidia.com" (no /v1) worked for
// the health probe, which appends /v1 itself, but reached Hermes verbatim: it
// requested /chat/completions on the host root and every real turn died with
// "HTTP 404". Normalizing here keeps every caller on the same URL.
func normalizeBaseURL(raw string) string {
	base := strings.TrimRight(strings.TrimSpace(raw), "/")
	if base == "" {
		return ""
	}
	if strings.HasSuffix(base, "/chat/completions") {
		base = strings.TrimSuffix(base, "/chat/completions")
	}
	// Only add /v1 when the URL carries no path of its own.
	if u, err := url.Parse(base); err == nil {
		if u.Path == "" || u.Path == "/" {
			base += "/v1"
		}
	}
	return base
}

// findAppProvider returns the provider with this id, or nil.
// findAppProvider resolves a provider by its id, and — for the picker's Hermes
// presets — by display name too: the model list shows Hermes slugs ("nvidia")
// while the app saves that same provider under its own id ("https"). Without
// the name fallback the picker's provider slug reached Hermes verbatim and
// every turn died with "unknown provider nvidia".
func findAppProvider(homeDir, id string) *appProvider {
	providers := loadAppProviders(homeDir)
	for _, p := range providers {
		if p.ID == id {
			p := p
			return &p
		}
	}
	for _, p := range providers {
		if p.BaseURL != "" && (strings.EqualFold(p.Name, id) || strings.EqualFold(p.ID, id)) {
			p := p
			return &p
		}
	}
	// A Hermes config custom_providers entry ("custom:omniroute") has no app
	// record; its endpoint lives in config.yaml. Probing it directly beats
	// spinning one gateway session per model.
	if name, ok := customProviderID(id); ok {
		if p := hermesCustomProvider(homeDir, name); p != nil {
			return p
		}
	}
	return nil
}

// customProviderID strips the "custom:" prefix off a picker provider id. The
// match is case-insensitive: the id crosses five layers (Kotlin entry -> HTTP
// JSON -> here) and a stray capital would silently degrade every model of that
// provider to one gateway session per probe.
func customProviderID(id string) (string, bool) {
	if len(id) < len("custom:") || !strings.EqualFold(id[:len("custom:")], "custom:") {
		return "", false
	}
	name := id[len("custom:"):]
	if name == "" {
		return "", false
	}
	return name, true
}

// hermesConfigPath is the config.yaml a custom_providers entry lives in. In the
// container Hermes only reads ~/.hermes/config.yaml, but on a Termux host the
// profile that actually owns the providers is ~/.hermes/profiles/<profile>/,
// so both are read.
func hermesConfigPath(homeDir string) string {
	return filepath.Join(homeDir, ".hermes", "config.yaml")
}

// hermesCustomProvider reads one custom_providers entry out of the config.yaml:
// name, base_url and either api_key or the environment variable behind key_env
// for that block.
func hermesCustomProvider(homeDir, name string) *appProvider {
	raw, err := os.ReadFile(hermesConfigPath(homeDir))
	if err != nil {
		return nil
	}
	base, key, keyEnv := "", "", ""
	inBlock, inCustom := false, false
	for _, line := range strings.Split(string(raw), "\n") {
		trimmed := strings.TrimSpace(line)
		// A top-level key ends the section. Tested before the section starts so
		// `custom_providers:` itself is picked up, not skipped by the break.
		if line != "" && !strings.HasPrefix(line, " ") && !strings.HasPrefix(line, "-") {
			inCustom = strings.HasPrefix(trimmed, "custom_providers:")
			inBlock = false
			continue
		}
		if !inCustom {
			inBlock = false
			continue
		}
		if strings.HasPrefix(trimmed, "- name:") {
			if base != "" {
				break // already matched an earlier block, and blocks do not merge
			}
			wantName := strings.Trim(strings.TrimSpace(strings.TrimPrefix(trimmed, "- name:")), `"'`)
			inBlock = strings.EqualFold(wantName, name)
			continue
		}
		if !inBlock {
			continue
		}
		if v, ok := strings.CutPrefix(trimmed, "base_url:"); ok {
			base = strings.Trim(strings.TrimSpace(v), `"'`)
		}
		if v, ok := strings.CutPrefix(trimmed, "api_key:"); ok {
			key = strings.Trim(strings.TrimSpace(v), `"'`)
		}
		if v, ok := strings.CutPrefix(trimmed, "key_env:"); ok {
			keyEnv = strings.Trim(strings.TrimSpace(v), `"'`)
		}
	}
	if base == "" {
		return nil
	}
	// A key_env provider keeps the secret entirely out of config.yaml; resolve
	// it here so the probe is not an anonymous one that every endpoint 401s.
	if key == "" && keyEnv != "" {
		key = os.Getenv(keyEnv)
	}
	return &appProvider{ID: "custom:" + name, Name: name, BaseURL: normalizeBaseURL(base), APIKey: key}
}

// appProvidersForUI is the provider list the app may see: keys replaced by a
// hint so the UI can show "key saved" without holding the secret.
func appProvidersForUI(homeDir string) []obj {
	out := []obj{}
	for _, p := range loadAppProviders(homeDir) {
		hint := ""
		if p.APIKey != "" {
			if len(p.APIKey) > 6 {
				hint = "…" + p.APIKey[len(p.APIKey)-4:]
			} else {
				hint = "…saved"
			}
		}
		models := make([]obj, 0, len(p.Models))
		for _, mm := range p.Models {
			models = append(models, obj{"id": mm.ID, "name": mm.Name})
		}
		out = append(out, obj{
			"id": p.ID, "name": p.Name, "base_url": p.BaseURL,
			"api_key_hint": hint, "models": models,
		})
	}
	return out
}

// handleGatewayStatus reports the warm connection's state for diagnosis:
// whether the bridge holds a live gateway WebSocket, the endpoint it
// attached to, why the last connect failed, and the hermes CLI's own view
// of the gateway.
func (m *Manager) handleGatewayStatus(w http.ResponseWriter) {
	payload := obj{}
	m.gw.mu.Lock()
	payload["connected"] = m.gw.healthy && m.gw.conn != nil
	payload["endpoint"] = m.gw.endpoint
	payload["last_error"] = m.gw.lastErr
	m.gw.mu.Unlock()
	ctx, cancel := context.WithTimeout(context.Background(), 20*time.Second)
	defer cancel()
	out, err := exec.CommandContext(ctx, m.hermesBin, "gateway", "status").CombinedOutput()
	payload["cli_status"] = strings.TrimSpace(string(out))
	if err != nil {
		payload["cli_status_error"] = err.Error()
	}
	writeJSON(w, http.StatusOK, payload)
}

// handleModelScan answers POST /sessions/{id}/models/scan
// {model, provider_id} with one health probe: {ok, latency_ms, error?}.
// Custom providers are probed directly (the bridge holds their endpoint and
// key); preset models ride a scratch gateway session so Hermes' own
// credentials apply. The app fans these out concurrently for the scan.
func (m *Manager) handleModelScan(w http.ResponseWriter, r *http.Request) {
	var body struct {
		Model      string `json:"model"`
		ProviderID string `json:"provider_id"`
	}
	if err := json.NewDecoder(r.Body).Decode(&body); err != nil || body.Model == "" {
		writeJSON(w, http.StatusBadRequest, obj{"error": "missing model"})
		return
	}
	const probeTimeout = 30 * time.Second
	if p := findAppProvider(m.homeDir, body.ProviderID); p != nil && p.BaseURL != "" {
		latency, errMsg := probeOpenAICompatible(p.BaseURL, p.APIKey, body.Model, probeTimeout)
		writeJSON(w, http.StatusOK, scanResult(latency, errMsg))
		return
	}
	latency, errMsg, err := m.gw.probeModel(body.Model, body.ProviderID, "", "", probeTimeout)
	if err != nil {
		writeJSON(w, http.StatusOK, scanResult(0, err.Error()))
		return
	}
	writeJSON(w, http.StatusOK, scanResult(latency, errMsg))
}

func scanResult(latency int64, errMsg string) obj {
	res := obj{"ok": errMsg == "", "latency_ms": latency}
	if errMsg != "" {
		res["error"] = errMsg
	}
	return res
}

// probeOpenAICompatible pings one model on a plain OpenAI-compatible endpoint
// with the same tiny completion the app-side scanners use.
func probeOpenAICompatible(baseURL, apiKey, model string, timeout time.Duration) (int64, string) {
	base := strings.TrimRight(baseURL, "/")
	if !strings.HasSuffix(base, "/chat/completions") {
		if strings.HasSuffix(base, "/v1") {
			base += "/chat/completions"
		} else {
			base += "/v1/chat/completions"
		}
	}
	payload, _ := json.Marshal(obj{
		"model":      model,
		"max_tokens": 16,
		"messages":   []obj{{"role": "user", "content": "Reply OK"}},
	})
	req, err := http.NewRequestWithContext(context.Background(), http.MethodPost, base, bytes.NewReader(payload))
	if err != nil {
		return 0, err.Error()
	}
	req.Header.Set("Content-Type", "application/json")
	if apiKey != "" {
		req.Header.Set("Authorization", "Bearer "+apiKey)
	}
	client := &http.Client{Timeout: timeout}
	// A 429 is a throttle, not a verdict about the model. Back off and ask
	// again; only the last attempt's answer is reported. Without this a scan
	// that ran 24 probes at once marked 12 of 13 OpenRouter models broken.
	var latency int64
	var msg string
	for attempt := 1; attempt <= 3; attempt++ {
		if attempt > 1 {
			time.Sleep(time.Duration(500*(1<<(attempt-2))) * time.Millisecond)
		}
		started := time.Now()
		resp, err := client.Do(req.Clone(context.Background()))
		if err != nil {
			return 0, err.Error()
		}
		snippet, _ := io.ReadAll(io.LimitReader(resp.Body, 4096))
		resp.Body.Close()
		latency = time.Since(started).Milliseconds()
		if resp.StatusCode >= 200 && resp.StatusCode < 300 {
			return latency, ""
		}
		msg = fmt.Sprintf("HTTP %d", resp.StatusCode)
		if s := strings.TrimSpace(string(snippet)); s != "" {
			msg += ": " + s
		}
		if resp.StatusCode != http.StatusTooManyRequests {
			return latency, msg
		}
	}
	return latency, msg
}

// handleModelScanBatch probes many models at once, server-side, with a short
// per-model deadline. The app's per-model loop is 448 round trips over the
// loopback bridge and serialises at 4 in flight; this does the fan-out inside
// the bridge, where a cold model costs one probe instead of a bridge hop.
//
// Request: {"provider_id":"nvidia","models":["a","b",...],"timeout_ms":15000}
// Response: an NDJSON stream of scan-probe events, flushed as soon as each
// model is verified, followed by a scan-done event. The whole batch is bounded:
// whatever the per-model deadline says, the call answers within scanBatchBudget
// and marks the probed-nothing models as such. Without that bound a big
// catalog on a dead endpoint keeps the HTTP request open for hours and the app
// client times out with no verdicts at all.
func (m *Manager) handleModelScanBatch(w http.ResponseWriter, r *http.Request) {
	var body struct {
		ProviderID string   `json:"provider_id"`
		Models     []string `json:"models"`
		TimeoutMS  int      `json:"timeout_ms"`
	}
	if err := json.NewDecoder(r.Body).Decode(&body); err != nil || len(body.Models) == 0 {
		writeJSON(w, http.StatusBadRequest, obj{"error": "missing models"})
		return
	}
	timeout := time.Duration(body.TimeoutMS) * time.Millisecond
	if timeout <= 0 || timeout > 60*time.Second {
		timeout = 20 * time.Second
	}
	provider := findAppProvider(m.homeDir, body.ProviderID)
	results := make(map[string]obj, len(body.Models))
	var mu sync.Mutex
	var wg sync.WaitGroup

	// A custom app provider is probed directly against its own endpoint, which
	// costs one HTTP call and no gateway session. This is also the honest path
	// for a Hermes preset the app mirrors (nvidia): a probe through Hermes would
	// go green on the app's key while the real turn dies on an empty
	// NVIDIA_API_KEY in the container's .env.
	direct := provider != nil && provider.BaseURL != ""
	// Five at a time, like Mobile-Harness. 24 concurrent probes tripped
	// OpenRouter's per-key limiter and 12 of 13 models came back 429, which the
	// app then reported as "broken" — the scan was the problem, not the models.
	limit := make(chan struct{}, 5)
	gatewayProvider := body.ProviderID
	if direct {
		gatewayProvider = ""
	}
	deadline := time.Now().Add(scanBatchBudget)

	// The response is an NDJSON stream flushed as each model is verified, so
	// the app shows live progress instead of 0→300 in one jump. Only the
	// stream goroutine writes to the ResponseWriter (json.Encoder and Flush
	// are not safe from concurrent writes): workers deposit verdicts under mu
	// and the stream picks them up on its next tick. A worker stuck past its
	// own timeout therefore delays nothing — the other verdicts still arrive,
	// which the single-shot answer could never do.
	w.Header().Set("Content-Type", "application/x-ndjson")
	enc := json.NewEncoder(w)
	flusher := w.(http.Flusher)
	streamDone := make(chan struct{})
	go func() {
		defer close(streamDone)
		emit := func(ev obj) {
			enc.Encode(ev)
			flusher.Flush()
		}
		emit(obj{"type": "scan-start", "provider_id": body.ProviderID, "models": len(body.Models)})
		sent := make(map[string]bool, len(body.Models))
		flushNew := func() {
			mu.Lock()
			events := make([]obj, 0, len(results))
			for model, res := range results {
				if sent[model] {
					continue
				}
				sent[model] = true
				ev := obj{"type": "scan-probe", "model": model}
				for k, v := range res {
					ev[k] = v
				}
				events = append(events, ev)
			}
			mu.Unlock()
			for _, ev := range events {
				emit(ev)
			}
		}
		tick := time.NewTicker(200 * time.Millisecond)
		defer tick.Stop()
		bail := time.After(scanBatchBudget + 5*time.Second)
		for {
			select {
			case <-bail:
				// A probe hung past its own timeout: close the batch anyway.
				// Models it never reached are reported as failures here, so
				// the app records a reason instead of waiting on the EOF.
				flushNew()
				for _, model := range body.Models {
					if model == "" || sent[model] {
						continue
					}
					sent[model] = true
					emit(obj{"type": "scan-probe", "model": model, "ok": false, "latency_ms": int64(0), "error": "scan budget exhausted"})
				}
				emit(obj{"type": "scan-done", "provider_id": body.ProviderID, "models": len(sent)})
				return
			case <-tick.C:
				flushNew()
				allSent := true
				for _, model := range body.Models {
					if model == "" {
						continue
					}
					if !sent[model] {
						allSent = false
						break
					}
				}
				if allSent {
					emit(obj{"type": "scan-done", "provider_id": body.ProviderID, "models": len(sent)})
					return
				}
			}
		}
	}()

	for _, model := range body.Models {
		if model == "" {
			continue
		}
		wg.Add(1)
		go func(model string) {
			defer wg.Done()
			limit <- struct{}{}
			defer func() { <-limit }()
			// Past the batch budget the probe is not worth starting: it would
			// only keep the response open past what the app waits for.
			if time.Now().After(deadline) {
				mu.Lock()
				results[model] = scanResult(0, "scan budget exhausted")
				mu.Unlock()
				return
			}
			var latency int64
			var errMsg string
			if direct {
				latency, errMsg = probeOpenAICompatible(provider.BaseURL, provider.APIKey, model, timeout)
			} else {
				var err error
				latency, errMsg, err = m.gw.probeModel(model, gatewayProvider, "", "", timeout)
				if err != nil {
					latency, errMsg = 0, err.Error()
				}
			}
			mu.Lock()
			results[model] = scanResult(latency, errMsg)
			mu.Unlock()
		}(model)
	}
	<-streamDone
}
