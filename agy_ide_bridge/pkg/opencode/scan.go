package opencode

// Health scans for the custom providers.
//
// The Hermes side already probes models one-word-at-a-time (gateway_events.go);
// OpenCode's picker had no scan at all, so the same endpoint looked "not checked
// yet" in one agent and green in the other. The data to probe is already here:
// custom_providers.json holds base_url + api_key + models, so a scan needs no
// OpenCode session — one tiny completion per model, straight to the endpoint,
// exactly like the app-side scanners and Hermes' direct probes.

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"strings"
	"sync"
	"time"
)

// scanBatchBudget bounds one provider's whole scan. A 300-model catalog on a
// dead endpoint must not hold the HTTP response open for hours; past the
// budget the unreached models are reported as "scan budget exhausted", a
// verdict the app re-probes on the next scan instead of trusting.
var scanBatchBudget = 12 * time.Minute

type scanProbeEvent struct {
	Type      string `json:"type"`
	Provider  string `json:"provider_id,omitempty"`
	BaseURL   string `json:"base_url,omitempty"`
	Model     string `json:"model,omitempty"`
	OK        bool   `json:"ok,omitempty"`
	LatencyMS int64  `json:"latency_ms,omitempty"`
	Error     string `json:"error,omitempty"`
	Models    int    `json:"models,omitempty"`
}

// handleScan answers POST /custom-providers/scan
// {"provider_id":"my-local-ollama","models":["a","b"]?,"timeout_ms":15000}
// with an NDJSON stream: scan-start, one scan-probe per model as it finishes
// (so the picker fills in live), then scan-done. Without `models` every model
// stored on the provider is probed. provider_id may also name one of OpenCode's
// own integrations (nvidia, google, ...): the bridge then resolves the endpoint
// URL from the serve's provider catalog and the key from its credential store,
// so scanning needs no re-typed endpoint and no key ever crosses to the app.
func (m *Manager) handleScan(w http.ResponseWriter, r *http.Request) {
	var body struct {
		ProviderID string   `json:"provider_id"`
		Models     []string `json:"models"`
		TimeoutMS  int      `json:"timeout_ms"`
	}
	if err := json.NewDecoder(io.LimitReader(r.Body, 1<<20)).Decode(&body); err != nil || body.ProviderID == "" {
		writeJSON(w, http.StatusBadRequest, map[string]interface{}{"success": false, "error": "missing provider_id"})
		return
	}
	timeout := time.Duration(body.TimeoutMS) * time.Millisecond
	if timeout <= 0 || timeout > 60*time.Second {
		timeout = 20 * time.Second
	}

	provider, err := m.scanTarget(r.Context(), body.ProviderID)
	if err != nil {
		writeJSON(w, http.StatusOK, map[string]interface{}{"success": false, "error": err.Error()})
		return
	}
	models := body.Models
	if len(models) == 0 {
		for _, md := range provider.Models {
			models = append(models, md.ID)
		}
	}
	if len(models) == 0 {
		writeJSON(w, http.StatusOK, map[string]interface{}{"success": false, "error": "the provider has no models to probe"})
		return
	}

	// The NDJSON stream pattern from the Hermes batch: only this goroutine
	// writes the response (json.Encoder and Flush are not concurrent-safe);
	// workers deposit verdicts under mu and the streamer emits them on its
	// tick, so one hung probe never blocks the verdicts that already answered.
	w.Header().Set("Content-Type", "application/x-ndjson")
	enc := json.NewEncoder(w)
	flusher, _ := w.(http.Flusher)
	emit := func(ev scanProbeEvent) {
		_ = enc.Encode(ev)
		if flusher != nil {
			flusher.Flush()
		}
	}
	emit(scanProbeEvent{Type: "scan-start", Provider: provider.ID, BaseURL: provider.BaseURL, Models: len(models)})

	results := make(chan scanProbeEvent, len(models))
	var wg sync.WaitGroup
	limit := make(chan struct{}, 8) // same fan-out that stayed under OpenRouter's per-key limiter
	deadline := time.Now().Add(scanBatchBudget)
	probe := provider.probe
	for _, model := range models {
		if model == "" {
			continue
		}
		wg.Add(1)
		go func(model string) {
			defer wg.Done()
			limit <- struct{}{}
			defer func() { <-limit }()
			if time.Now().After(deadline) {
				results <- scanProbeEvent{Type: "scan-probe", Model: model, Error: "scan budget exhausted", BaseURL: provider.BaseURL}
				return
			}
			latency, errMsg := probe(model, timeout)
			ev := scanProbeEvent{Type: "scan-probe", Model: model, OK: errMsg == "", LatencyMS: latency, BaseURL: provider.BaseURL}
			if errMsg != "" {
				ev.Error = errMsg
			}
			results <- ev
		}(model)
	}
	go func() { wg.Wait(); close(results) }()
	sent := 0
	for ev := range results {
		emit(ev)
		sent++
	}
	emit(scanProbeEvent{Type: "scan-done", Provider: provider.ID, Models: sent})
}

// scanTarget finds what to probe for a picker provider id: the app's own
// custom endpoint store first (it holds URL + key + models), then OpenCode's
// integration catalog — a provider the user signed into through OpenCode's
// auth (nvidia, google, ...) is scannable too, resolved from the running
// serve so nothing has to be typed twice and no key leaves the guest.
func (m *Manager) scanTarget(ctx context.Context, id string) (*CustomProvider, error) {
	m.provMu.Lock()
	list, err := m.loadProviders()
	m.provMu.Unlock()
	if err != nil {
		return nil, err
	}
	for i := range list {
		if list[i].ID == id {
			return &list[i], nil
		}
	}
	var catalog struct {
		Data []map[string]interface{} `json:"data"`
	}
	if err := m.Call(ctx, http.MethodGet, "/api/provider", nil, &catalog); err != nil {
		return nil, fmt.Errorf("unknown provider %s: not in the app's custom endpoints and OpenCode's catalog could not be read (%v)", id, err)
	}
	var creds struct {
		Data []map[string]interface{} `json:"data"`
	}
	// A missing store is not an error: public and keyless integrations probe fine.
	_ = m.Call(ctx, http.MethodGet, "/api/credential", nil, &creds)
	return integrationProvider(id, catalog.Data, creds.Data)
}

// integrationProvider resolves one serve catalog entry into a probe target.
func integrationProvider(id string, providers, credentials []map[string]interface{}) (*CustomProvider, error) {
	for _, p := range providers {
		if pid, _ := p["id"].(string); pid != id {
			continue
		}
		pkg, _ := p["package"].(string)
		settings, _ := p["settings"].(map[string]interface{})
		base, _ := settings["baseURL"].(string)
		if base == "" {
			return nil, fmt.Errorf("provider %s has no endpoint URL in OpenCode's catalog", id)
		}
		kind := KindOpenAI
		if strings.Contains(strings.ToLower(pkg), "anthropic") {
			kind = KindAnthropic
		}
		key := credentialKey(id, credentials)
		// Some integrations carry their key in the config itself ("public" is
		// the marker for OpenCode's own keyless front, never a real key).
		if pub, _ := settings["apiKey"].(string); key == "" && pub != "" && pub != "public" {
			key = pub
		}
		provider := &CustomProvider{ID: id, Name: id, Kind: kind, BaseURL: base, APIKey: key}
		if hs, ok := p["headers"].(map[string]interface{}); ok {
			hdr := map[string]string{}
			for k, v := range hs {
				if s, ok := v.(string); ok && s != "" {
					hdr[k] = s
				}
			}
			if len(hdr) > 0 {
				provider.Headers = hdr
			}
		}
		return provider, nil
	}
	return nil, fmt.Errorf("unknown provider: %s (not a custom endpoint and not an OpenCode integration)", id)
}

// credentialKey picks the secret stored for one integration. The field names
// follow what the serve returns; the first non-empty wins across them because
// older and newer OpenCode versions spell the same secret differently.
func credentialKey(id string, credentials []map[string]interface{}) string {
	for _, c := range credentials {
		integ, _ := c["integrationID"].(string)
		if integ != "" {
			if integ != id {
				continue
			}
		} else if cid, _ := c["id"].(string); cid != id {
			continue
		}
		if act, ok := c["active"].(bool); ok && !act {
			continue
		}
		if v, ok := c["value"].(map[string]interface{}); ok {
			for _, k := range []string{"key", "apiKey", "token"} {
				if s, ok := v[k].(string); ok && s != "" {
					return s
				}
			}
		}
	}
	return ""
}

// probe pings one model with the same tiny completion the other scanners use:
// a one-word turn that costs nothing and answers as a real call would.
// A 429 is a throttle, not a verdict about the model: back off and ask again,
// only the last attempt's answer is reported.
func (p *CustomProvider) probe(model string, timeout time.Duration) (int64, string) {
	url, payload, headers, err := p.probeRequest(model)
	if err != nil {
		return 0, err.Error()
	}
	client := &http.Client{Timeout: timeout}
	var latency int64
	var msg string
	for attempt := 1; attempt <= 3; attempt++ {
		if attempt > 1 {
			time.Sleep(time.Duration(500*(1<<(attempt-2))) * time.Millisecond)
		}
		req, err := http.NewRequest(http.MethodPost, url, bytes.NewReader(payload))
		if err != nil {
			return 0, err.Error()
		}
		req.Header.Set("Content-Type", "application/json")
		for k, v := range headers {
			req.Header.Set(k, v)
		}
		started := time.Now()
		resp, err := client.Do(req)
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

// probeRequest builds the tiny completion for one model, per the provider's
// kind. The URL rules match the probe the Hermes bridge uses against the same
// kind of endpoint, so a scan means the same thing in both agents.
func (p *CustomProvider) probeRequest(model string) (string, []byte, map[string]string, error) {
	base := strings.TrimRight(p.BaseURL, "/")
	// The endpoint header map is the user's own (a gateway may need a custom
	// auth header); copy it first, then layer the kind-specific headers on top.
	headers := map[string]string{}
	for k, v := range p.Headers {
		headers[k] = v
	}
	body := map[string]interface{}{
		"model":      model,
		"max_tokens": 16,
		"messages":   []map[string]interface{}{{"role": "user", "content": "Reply OK"}},
	}
	payload, err := json.Marshal(body)
	if err != nil {
		return "", nil, nil, err
	}
	if p.Kind == KindAnthropic {
		if !strings.HasSuffix(base, "/messages") {
			if strings.HasSuffix(base, "/v1") {
				base += "/messages"
			} else {
				base += "/v1/messages"
			}
		}
		if p.APIKey != "" {
			headers["x-api-key"] = p.APIKey
		}
		headers["anthropic-version"] = "2023-06-01"
		return base, payload, headers, nil
	}
	if !strings.HasSuffix(base, "/chat/completions") {
		if strings.HasSuffix(base, "/v1") {
			base += "/chat/completions"
		} else {
			base += "/v1/chat/completions"
		}
	}
	if p.APIKey != "" {
		headers["Authorization"] = "Bearer " + p.APIKey
	}
	return base, payload, headers, nil
}
