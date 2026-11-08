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
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"time"
)

// modelsConnectBudget bounds the connect a models fetch waits for; a cold
// gateway start keeps going in the background instead.
const modelsConnectBudget = 20 * time.Second

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

	// a model Hermes knows: switch the live session now
	sess.mu.Lock()
	sess.gwModel = body.Model
	sess.gwProvider = ""
	sess.gwBaseURL = ""
	sess.gwAPIKey = ""
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
		ap := appProvider{ID: p.ID, Name: p.Name, BaseURL: p.BaseURL, APIKey: p.APIKey}
		for _, mm := range p.Models {
			ap.Models = append(ap.Models, appProviderModel{ID: mm.ID, Name: mm.Name})
		}
		out = append(out, ap)
	}
	return out
}

// findAppProvider returns the provider with this id, or nil.
func findAppProvider(homeDir, id string) *appProvider {
	for _, p := range loadAppProviders(homeDir) {
		if p.ID == id {
			p := p
			return &p
		}
	}
	return nil
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
	started := time.Now()
	resp, err := client.Do(req)
	if err != nil {
		return 0, err.Error()
	}
	defer resp.Body.Close()
	snippet, _ := io.ReadAll(io.LimitReader(resp.Body, 4096))
	if resp.StatusCode >= 200 && resp.StatusCode < 300 {
		return time.Since(started).Milliseconds(), ""
	}
	msg := fmt.Sprintf("HTTP %d", resp.StatusCode)
	if s := strings.TrimSpace(string(snippet)); s != "" {
		msg += ": " + s
	}
	return time.Since(started).Milliseconds(), msg
}
