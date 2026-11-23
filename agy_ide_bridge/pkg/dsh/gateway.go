// Package dsh serves DeepSeek Harness' model calls from inside the bridge.
//
// DSH always authenticates as a provider route: `cordis.patch.yml` names a
// provider and an `api` shape, and the key comes from an environment variable.
// That is inconvenient for an app that already holds the user's credentials (and
// impossible for a route that wants no login at all), so the app points DSH at
// this loopback gateway instead:
//
//	provider: antigem-proxy
//	api: openai-completions
//	baseURL: http://127.0.0.1:<port>/v1
//	apiKeyEnv: <anything non-empty>
//
// The gateway ignores whatever key DSH sends, applies the real provider
// credentials itself, and — for providers that only speak Anthropic — translates
// OpenAI chat/completions to /v1/messages and back. The key therefore never
// enters the container, and a keyless endpoint needs no placeholder dance.
package dsh

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"io"
	"net"
	"net/http"
	"strings"
	"sync"
	"time"
)

// upstreamTimeout bounds one model call. A DSH turn waits for a whole edit-plan
// answer, so this is deliberately longer than a chat round trip.
const upstreamTimeout = 5 * time.Minute

// maxRequestBytes caps a translated request body; DSH can send a large context.
const maxRequestBytes = 64 << 20

// Config is one provider as the gateway needs it: where to send, how to
// authenticate, and whether the endpoint already speaks OpenAI.
type Config struct {
	ID          string
	BaseURL     string
	APIKey      string
	AuthMode    string // "bearer" (default) or "x-api-key"
	Headers     map[string]string
	OpenAIStyle bool
	Models      []string
}

// Gateway serves one provider as an OpenAI-compatible endpoint on loopback.
type Gateway struct {
	cfg    Config
	ln     net.Listener
	srv    *http.Server
	client *http.Client
}

// NewGateway binds a loopback port and starts serving.
func NewGateway(cfg Config) (*Gateway, error) {
	cfg.BaseURL = cleanBase(cfg.BaseURL)
	if cfg.BaseURL == "" {
		return nil, fmt.Errorf("provider %q has no base URL", cfg.ID)
	}
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		return nil, fmt.Errorf("loopback listener: %w", err)
	}
	g := &Gateway{
		cfg:    cfg,
		ln:     ln,
		client: &http.Client{Timeout: upstreamTimeout},
		srv:    &http.Server{Handler: http.NewServeMux()},
	}
	g.srv.Handler.(*http.ServeMux).HandleFunc("/", g.handle)
	go func() { _ = g.srv.Serve(ln) }()
	return g, nil
}

// URL is the base URL DSH should be configured with (`.../v1`).
func (g *Gateway) URL() string { return "http://" + g.ln.Addr().String() + "/v1" }

// Close stops the listener; a rebuilt provider gets a new one.
func (g *Gateway) Close() { _ = g.srv.Close() }

// Serves reports whether this gateway already speaks for exactly cfg — any
// change of endpoint, key, auth mode or headers rebuilds it.
func (g *Gateway) Serves(cfg Config) bool { return sameConfig(g.cfg, cfg) }

// handle answers the endpoints DSH posts to. Anything else gets the same 404 the
// real API would give, so a wrong base URL fails here loudly.
func (g *Gateway) handle(w http.ResponseWriter, r *http.Request) {
	path := r.URL.Path
	switch {
	case strings.HasSuffix(path, "/models") && r.Method == http.MethodGet:
		writeJSON(w, http.StatusOK, map[string]interface{}{
			"object": "list",
			"data":   g.modelList(),
		})
	case strings.HasSuffix(path, "/chat/completions") && r.Method == http.MethodPost:
		g.handleChat(w, r)
	default:
		writeOpenAIError(w, http.StatusNotFound, "not_found", "unsupported gateway endpoint: "+path)
	}
}

func (g *Gateway) modelList() []interface{} {
	out := make([]interface{}, 0, len(g.cfg.Models))
	for _, m := range g.cfg.Models {
		out = append(out, map[string]interface{}{"id": m, "object": "model", "owned_by": "antigem"})
	}
	return out
}

// handleChat turns one OpenAI chat/completions call into whatever the provider
// speaks, then answers in the shape the caller asked for.
func (g *Gateway) handleChat(w http.ResponseWriter, r *http.Request) {
	body, err := io.ReadAll(io.LimitReader(r.Body, maxRequestBytes))
	if err != nil {
		writeOpenAIError(w, http.StatusBadRequest, "invalid_request_error", "could not read the request")
		return
	}
	var req map[string]interface{}
	if err := json.Unmarshal(body, &req); err != nil {
		writeOpenAIError(w, http.StatusBadRequest, "invalid_request_error", "request body is not JSON")
		return
	}
	model := stringOf(req["model"])
	if model == "" && len(g.cfg.Models) > 0 {
		model = g.cfg.Models[0]
	}
	stream, _ := req["stream"].(bool)

	if g.cfg.OpenAIStyle {
		// The endpoint already speaks this shape: forward it untouched, so
		// streaming, tool calls and vendor fields all pass through.
		g.forward(w, r, body, stream)
		return
	}
	payload, _ := json.Marshal(openAIToAnthropic(req, model))
	status, answer, err := g.postAnthropic(r.Context(), payload)
	if err != nil {
		writeOpenAIError(w, http.StatusBadGateway, "api_error", err.Error())
		return
	}
	if status < 200 || status > 299 {
		kind := "api_error"
		if status == http.StatusTooManyRequests {
			kind = "rate_limit_error"
		}
		writeOpenAIError(w, status, kind, upstreamMessage(answer))
		return
	}
	var anth map[string]interface{}
	if err := json.Unmarshal(answer, &anth); err != nil {
		writeOpenAIError(w, http.StatusBadGateway, "api_error", "the provider returned a non-JSON answer")
		return
	}
	completion := anthropicToCompletion(anth, model)
	if stream {
		replayAsOpenAISSE(w, completion)
		return
	}
	writeJSON(w, http.StatusOK, completion)
}

// forward relays the caller's own OpenAI request to an OpenAI-style provider,
// with the provider's credentials in place of whatever DSH sent.
func (g *Gateway) forward(w http.ResponseWriter, r *http.Request, body []byte, stream bool) {
	var lastStatus int
	var lastBody []byte
	var lastErr error
	for _, endpoint := range chatEndpoints(g.cfg.BaseURL) {
		req, err := http.NewRequestWithContext(r.Context(), http.MethodPost, endpoint, bytes.NewReader(body))
		if err != nil {
			writeOpenAIError(w, http.StatusBadGateway, "api_error", err.Error())
			return
		}
		req.Header.Set("Content-Type", "application/json")
		req.Header.Set("User-Agent", "AntiGem/1.0 (Android)")
		g.applyAuth(req)
		resp, err := g.client.Do(req)
		if err != nil {
			lastErr, lastStatus, lastBody = err, 0, nil
			continue
		}
		if resp.StatusCode == http.StatusNotFound {
			// A base URL that disagrees about `/v1` looks exactly like this.
			lastStatus, lastBody = resp.StatusCode, readAll(resp.Body)
			resp.Body.Close()
			continue
		}
		if resp.StatusCode < 200 || resp.StatusCode > 299 {
			lastStatus, lastBody = resp.StatusCode, readAll(resp.Body)
			resp.Body.Close()
			break
		}
		defer resp.Body.Close()
		w.Header().Set("Content-Type", contentTypeOf(resp, stream))
		w.WriteHeader(resp.StatusCode)
		flush(w)
		_, _ = io.Copy(&flushWriter{w: w}, resp.Body)
		return
	}
	if lastStatus == 0 {
		message := "could not reach the provider"
		if lastErr != nil {
			message = lastErr.Error()
		}
		writeOpenAIError(w, http.StatusBadGateway, "api_error", message)
		return
	}
	if lastStatus < 200 || lastStatus > 299 {
		writeOpenAIError(w, lastStatus, "api_error", upstreamMessage(lastBody))
		return
	}
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(lastStatus)
	_, _ = w.Write(lastBody)
}

// postAnthropic posts to the provider's /v1/messages endpoint, trying both
// spellings of the base like the Claude gateway does.
func (g *Gateway) postAnthropic(ctx context.Context, payload []byte) (int, []byte, error) {
	var lastStatus int
	var lastBody []byte
	var lastErr error
	for _, endpoint := range anthropicEndpoints(g.cfg.BaseURL) {
		req, err := http.NewRequestWithContext(ctx, http.MethodPost, endpoint, bytes.NewReader(payload))
		if err != nil {
			return 0, nil, err
		}
		req.Header.Set("Content-Type", "application/json")
		req.Header.Set("User-Agent", "AntiGem/1.0 (Android)")
		req.Header.Set("anthropic-version", "2023-06-01")
		g.applyAuth(req)
		resp, err := g.client.Do(req)
		if err != nil {
			lastErr, lastStatus, lastBody = err, 0, nil
			continue
		}
		status, answer := resp.StatusCode, readAll(resp.Body)
		resp.Body.Close()
		if status == http.StatusNotFound {
			lastStatus, lastBody = status, answer
			continue
		}
		return status, answer, nil
	}
	if lastStatus == 0 && lastErr != nil {
		return 0, nil, lastErr
	}
	return lastStatus, lastBody, nil
}

// applyAuth puts the real credentials on an upstream request. The key DSH sent
// is dropped: it is a placeholder (or an empty-ish route value), never a secret.
func (g *Gateway) applyAuth(req *http.Request) {
	req.Header.Del("Authorization")
	req.Header.Del("x-api-key")
	if g.cfg.APIKey != "" {
		if g.cfg.AuthMode == "x-api-key" {
			req.Header.Set("x-api-key", g.cfg.APIKey)
		} else {
			req.Header.Set("Authorization", "Bearer "+g.cfg.APIKey)
		}
	}
	for k, v := range g.cfg.Headers {
		req.Header.Set(k, v)
	}
}

// Cache reuses one gateway per provider id and rebuilds it when the saved
// provider changes, so a port is not leaked on every turn.
type Cache struct {
	mu sync.Mutex
	m  map[string]*Gateway
}

// For returns the gateway that serves cfg, creating or rebuilding it as needed.
func (c *Cache) For(cfg Config) (*Gateway, error) {
	c.mu.Lock()
	defer c.mu.Unlock()
	if c.m == nil {
		c.m = map[string]*Gateway{}
	}
	if g := c.m[cfg.ID]; g != nil {
		if g.Serves(cfg) {
			return g, nil
		}
		g.Close()
		delete(c.m, cfg.ID)
	}
	g, err := NewGateway(cfg)
	if err != nil {
		return nil, err
	}
	c.m[cfg.ID] = g
	return g, nil
}

// Close drops every gateway; the bridge calls it on shutdown.
func (c *Cache) Close() {
	c.mu.Lock()
	defer c.mu.Unlock()
	for id, g := range c.m {
		g.Close()
		delete(c.m, id)
	}
}

// flushWriter flushes after every write, so an SSE stream is not held back by
// the response buffer.
type flushWriter struct{ w http.ResponseWriter }

func (f *flushWriter) Write(p []byte) (int, error) {
	n, err := f.w.Write(p)
	flush(f.w)
	return n, err
}

func flush(w http.ResponseWriter) {
	if f, ok := w.(http.Flusher); ok {
		f.Flush()
	}
}

func contentTypeOf(resp *http.Response, stream bool) string {
	if ct := resp.Header.Get("Content-Type"); ct != "" {
		return ct
	}
	if stream {
		return "text/event-stream"
	}
	return "application/json"
}

func readAll(body io.Reader) []byte {
	out, _ := io.ReadAll(io.LimitReader(body, 4<<20))
	return out
}
