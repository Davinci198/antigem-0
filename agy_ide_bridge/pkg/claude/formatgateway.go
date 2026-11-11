package claude

// Translation gateway for providers that speak OpenAI's chat/completions
// instead of Anthropic's /v1/messages — NVIDIA Integrate and most local
// servers are OpenAI-only, yet Claude Code only ever talks Anthropic.
//
// For such a provider the chat's --settings file points ANTHROPIC_BASE_URL
// at a loopback listener owned by the bridge: every /v1/messages request is
// rewritten as one non-streaming chat/completions call, and the finished
// answer is replayed to the CLI as the SSE stream it asked for. The real key
// lives only here; the CLI gets a placeholder, exactly like Mobile-Harness'
// LocalFormatGateway does for its Claude runtime.

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

// loopbackKey is what the CLI sends to the gateway; the gateway answers the
// upstream with the provider's real key and ignores whatever came in.
const loopbackKey = "loopback"

// gatewayUpstreamTimeout bounds one translated request. A whole Claude Code
// turn runs through a single chat/completions call, so this is deliberately
// longer than the CLI's own patience; API_TIMEOUT_MS still cuts it sooner.
const gatewayUpstreamTimeout = 5 * time.Minute

// formatGateway serves one provider's OpenAI endpoint as an Anthropic one.
type formatGateway struct {
	provider Provider
	ln       net.Listener
	srv      *http.Server
}

// newFormatGateway binds a loopback port and starts serving it.
func newFormatGateway(p Provider) (*formatGateway, error) {
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		return nil, fmt.Errorf("loopback gateway: %w", err)
	}
	g := &formatGateway{provider: p, ln: ln, srv: &http.Server{Handler: http.NewServeMux()}}
	g.srv.Handler.(*http.ServeMux).HandleFunc("/", g.handle)
	go func() { _ = g.srv.Serve(ln) }()
	return g, nil
}

// URL is the ANTHROPIC_BASE_URL the CLI should use.
func (g *formatGateway) URL() string { return "http://" + g.ln.Addr().String() }

// serves reports whether this gateway already translates for exactly p —
// any change of endpoint, key, auth mode or headers rebuilds it.
func (g *formatGateway) serves(p Provider) bool {
	return sameProviderConfig(g.provider, p)
}

func (g *formatGateway) Close() { _ = g.srv.Close() }

func sameProviderConfig(a, b Provider) bool {
	if a.BaseURL != b.BaseURL || a.APIKey != b.APIKey || a.AuthMode != b.AuthMode || len(a.Headers) != len(b.Headers) {
		return false
	}
	for k, v := range a.Headers {
		if b.Headers[k] != v {
			return false
		}
	}
	return true
}

// gwMu guards the per-provider listener cache; a gateway is created lazily
// when a chat spawns and rebuilt when the provider is saved again.
type gatewayCache struct {
	mu sync.Mutex
	gw map[string]*formatGateway
}

func (c *gatewayCache) forProvider(p Provider) (*formatGateway, error) {
	c.mu.Lock()
	defer c.mu.Unlock()
	if c.gw == nil {
		c.gw = map[string]*formatGateway{}
	}
	if g := c.gw[p.ID]; g != nil {
		if g.serves(p) {
			return g, nil
		}
		g.Close()
		delete(c.gw, p.ID)
	}
	g, err := newFormatGateway(p)
	if err != nil {
		return nil, err
	}
	c.gw[p.ID] = g
	return g, nil
}

func (c *gatewayCache) close(id string) {
	c.mu.Lock()
	defer c.mu.Unlock()
	if g := c.gw[id]; g != nil {
		g.Close()
		delete(c.gw, id)
	}
}

// handle answers the two endpoints the CLI posts to: /v1/messages and
// /v1/messages/count_tokens. Everything else is the same 404 the real API
// would give, so a wrong base URL fails here loudly instead of silently.
func (g *formatGateway) handle(w http.ResponseWriter, r *http.Request) {
	body, err := io.ReadAll(io.LimitReader(r.Body, 32<<20))
	if err != nil {
		writeAnthropicError(w, http.StatusBadRequest, "invalid_request_error", "could not read the request")
		return
	}
	if strings.HasSuffix(r.URL.Path, "/count_tokens") {
		// The CLI only uses this number to watch its context pressure, and a
		// tokenizer lives nowhere near the phone: a quarter of the bytes is
		// what the real API lands on for prose.
		writeJSONObject(w, http.StatusOK, map[string]interface{}{"input_tokens": len(body)/4 + 1})
		return
	}
	if r.Method != http.MethodPost || !strings.HasSuffix(r.URL.Path, "/messages") {
		writeAnthropicError(w, http.StatusNotFound, "not_found", "unsupported gateway endpoint: "+r.URL.Path)
		return
	}
	var req map[string]interface{}
	if err := json.Unmarshal(body, &req); err != nil {
		writeAnthropicError(w, http.StatusBadRequest, "invalid_request_error", "request body is not JSON")
		return
	}
	stream, _ := req["stream"].(bool)
	upstream := anthropicToOpenAI(req, g.provider)
	payload, _ := json.Marshal(upstream)
	status, answer, err := g.postUpstream(r.Context(), payload)
	if err != nil {
		writeAnthropicError(w, http.StatusBadGateway, "api_error", err.Error())
		return
	}
	if status < 200 || status > 299 {
		// A throttle keeps its own type so the CLI waits instead of giving up.
		kind := "api_error"
		if status == http.StatusTooManyRequests {
			kind = "rate_limit_error"
		}
		writeAnthropicError(w, status, kind, upstreamError(answer))
		return
	}
	var oa map[string]interface{}
	if err := json.Unmarshal(answer, &oa); err != nil {
		writeAnthropicError(w, http.StatusBadGateway, "api_error", "the provider returned a non-JSON answer")
		return
	}
	msg := openAIToAnthropic(oa, modelOf(req, g.provider))
	if stream {
		replayAsSSE(w, msg)
		return
	}
	writeJSONObject(w, http.StatusOK, msg)
}

// postUpstream posts to the provider's chat/completions endpoint, trying
// both spellings of the base (with and without /v1) like the app's own
// testers do, and keeps the first answer that is not a 404.
func (g *formatGateway) postUpstream(ctx context.Context, payload []byte) (int, []byte, error) {
	p := g.provider
	client := &http.Client{Timeout: gatewayUpstreamTimeout}
	var lastStatus int
	var lastBody []byte
	var lastErr error
	for _, endpoint := range chatCompletionEndpoints(p.BaseURL) {
		req, err := http.NewRequestWithContext(ctx, http.MethodPost, endpoint, bytes.NewReader(payload))
		if err != nil {
			return 0, nil, err
		}
		req.Header.Set("Content-Type", "application/json")
		req.Header.Set("User-Agent", "AntiGem/1.0 (Android)")
		if p.APIKey != "" {
			if p.AuthMode == authXAPIKey {
				req.Header.Set("x-api-key", p.APIKey)
			} else {
				req.Header.Set("Authorization", "Bearer "+p.APIKey)
			}
		}
		for k, v := range p.Headers {
			req.Header.Set(k, v)
		}
		resp, err := client.Do(req)
		if err != nil {
			lastErr, lastStatus, lastBody = err, 0, nil
			continue
		}
		data, _ := io.ReadAll(io.LimitReader(resp.Body, 8<<20))
		resp.Body.Close()
		if resp.StatusCode != http.StatusNotFound {
			return resp.StatusCode, data, nil
		}
		lastStatus, lastBody, lastErr = resp.StatusCode, data, nil
	}
	if lastErr != nil {
		return 0, nil, lastErr
	}
	return lastStatus, lastBody, nil
}

// chatCompletionEndpoints: a base may end in /v1 or not; both spellings work
// against most servers, and only the wrong guess 404s.
func chatCompletionEndpoints(base string) []string {
	base = strings.TrimRight(base, "/")
	if strings.HasSuffix(base, "/v1") {
		return []string{base + "/chat/completions"}
	}
	return []string{base + "/v1/chat/completions", base + "/chat/completions"}
}

func modelOf(req map[string]interface{}, p Provider) string {
	if m, _ := req["model"].(string); strings.TrimSpace(m) != "" {
		return strings.TrimSpace(m)
	}
	if len(p.Models) > 0 {
		return p.Models[0].ID
	}
	return p.SmallModel
}

// anthropicToOpenAI rewrites one Anthropic request as its OpenAI twin.
// Fields the OpenAI API does not know (thinking, betas, citations…) are
// dropped, because strict OpenAI-compatible servers reject them.
func anthropicToOpenAI(req map[string]interface{}, p Provider) map[string]interface{} {
	out := map[string]interface{}{
		"model":    modelOf(req, p),
		"stream":   false,
		"messages": []interface{}{},
	}
	if v, ok := intOf(req["max_tokens"]); ok && v > 0 {
		out["max_tokens"] = v
	} else {
		out["max_tokens"] = 4096
	}
	for _, k := range []string{"temperature", "top_p"} {
		if v, ok := req[k]; ok {
			out[k] = v
		}
	}
	if stops, ok := req["stop_sequences"].([]interface{}); ok && len(stops) > 0 {
		out["stop"] = stops
	}
	messages := out["messages"].([]interface{})
	if text := flattenText(req["system"]); text != "" {
		messages = append(messages, map[string]interface{}{"role": "system", "content": text})
	}
	if list, ok := req["messages"].([]interface{}); ok {
		for _, raw := range list {
			msg, _ := raw.(map[string]interface{})
			if msg == nil {
				continue
			}
			role, _ := msg["role"].(string)
			switch content := msg["content"].(type) {
			case string:
				messages = append(messages, map[string]interface{}{"role": role, "content": content})
			case nil:
				messages = append(messages, map[string]interface{}{"role": role, "content": ""})
			default:
				var text strings.Builder
				var toolCalls []interface{}
				var toolResults []interface{}
				for _, rawPart := range toList(content) {
					part, _ := rawPart.(map[string]interface{})
					if part == nil {
						continue
					}
					switch t, _ := part["type"].(string); t {
					case "text":
						s, _ := part["text"].(string)
						text.WriteString(s)
					case "tool_use":
						args := "{}"
						if input, ok := part["input"].(map[string]interface{}); ok {
							if b, err := json.Marshal(input); err == nil {
								args = string(b)
							}
						}
						id, _ := part["id"].(string)
						name, _ := part["name"].(string)
						toolCalls = append(toolCalls, map[string]interface{}{
							"id":   id,
							"type": "function",
							"function": map[string]interface{}{
								"name":      name,
								"arguments": args,
							},
						})
					case "tool_result":
						id, _ := part["tool_use_id"].(string)
						toolResults = append(toolResults, map[string]interface{}{
							"role":         "tool",
							"tool_call_id": id,
							"content":      flattenText(part["content"]),
						})
					}
				}
				if text.Len() > 0 || len(toolCalls) > 0 {
					m := map[string]interface{}{"role": role}
					if text.Len() > 0 {
						m["content"] = text.String()
					} else {
						m["content"] = nil
					}
					if len(toolCalls) > 0 {
						m["tool_calls"] = toolCalls
					}
					messages = append(messages, m)
				}
				messages = append(messages, toolResults...)
			}
		}
	}
	out["messages"] = messages
	if tools := toList(req["tools"]); len(tools) > 0 {
		converted := make([]interface{}, 0, len(tools))
		for _, raw := range tools {
			tool, _ := raw.(map[string]interface{})
			if tool == nil {
				continue
			}
			params := map[string]interface{}{"type": "object"}
			if schema, ok := tool["input_schema"].(map[string]interface{}); ok {
				params = schema
			}
			fn := map[string]interface{}{"name": tool["name"], "parameters": params}
			if d, ok := tool["description"].(string); ok {
				fn["description"] = d
			}
			converted = append(converted, map[string]interface{}{"type": "function", "function": fn})
		}
		out["tools"] = converted
		out["tool_choice"] = "auto"
	}
	return out
}

// openAIToAnthropic builds the message the CLI expects back.
func openAIToAnthropic(resp map[string]interface{}, model string) map[string]interface{} {
	id, _ := resp["id"].(string)
	if id == "" {
		id = fmt.Sprintf("msg_%d", time.Now().UnixNano())
	} else if !strings.HasPrefix(id, "msg_") {
		id = "msg_" + id
	}
	var content []interface{}
	var calls []interface{}
	finish := ""
	if choices := toList(resp["choices"]); len(choices) > 0 {
		first, _ := choices[0].(map[string]interface{})
		finish, _ = first["finish_reason"].(string)
		msg, _ := first["message"].(map[string]interface{})
		if msg != nil {
			if text, _ := msg["content"].(string); strings.TrimSpace(text) != "" {
				content = append(content, map[string]interface{}{"type": "text", "text": text})
			}
			calls = toList(msg["tool_calls"])
		}
	}
	stop := "end_turn"
	switch {
	case len(calls) > 0:
		stop = "tool_use"
	case finish == "length":
		stop = "max_tokens"
	}
	for _, raw := range calls {
		call, _ := raw.(map[string]interface{})
		if call == nil {
			continue
		}
		fn, _ := call["function"].(map[string]interface{})
		if fn == nil {
			continue
		}
		input := map[string]interface{}{}
		if args, _ := fn["arguments"].(string); args != "" {
			_ = json.Unmarshal([]byte(args), &input)
		}
		callID, _ := call["id"].(string)
		if callID == "" {
			callID = fmt.Sprintf("tool_%d", time.Now().UnixNano())
		}
		name, _ := fn["name"].(string)
		content = append(content, map[string]interface{}{
			"type":  "tool_use",
			"id":    callID,
			"name":  name,
			"input": input,
		})
	}
	if content == nil {
		content = []interface{}{}
	}
	usage := map[string]interface{}{}
	if u, ok := resp["usage"].(map[string]interface{}); ok {
		if v, ok := intOf(u["prompt_tokens"]); ok {
			usage["input_tokens"] = v
		}
		if v, ok := intOf(u["completion_tokens"]); ok {
			usage["output_tokens"] = v
		}
	}
	if _, ok := usage["input_tokens"]; !ok {
		usage["input_tokens"] = 0
	}
	if _, ok := usage["output_tokens"]; !ok {
		usage["output_tokens"] = 0
	}
	if content == nil {
		content = []interface{}{}
	}
	return map[string]interface{}{
		"id":            id,
		"type":          "message",
		"role":          "assistant",
		"model":         model,
		"content":       content,
		"stop_reason":   stop,
		"stop_sequence": nil,
		"usage":         usage,
	}
}

// replayAsSSE tells the finished message back as Anthropic SSE events — one
// delta per content block. The CLI accepts any legal event sequence, so a
// whole answer arriving in one block streams fine for rendering, and keeping
// the upstream non-streaming means one request per turn and no partial-event
// bookkeeping on the cheapest socket we own.
func replayAsSSE(w http.ResponseWriter, msg map[string]interface{}) {
	w.Header().Set("Content-Type", "text/event-stream")
	w.Header().Set("Cache-Control", "no-cache")
	w.Header().Set("Connection", "close")
	w.WriteHeader(http.StatusOK)
	flusher, _ := w.(http.Flusher)
	send := func(event string, data map[string]interface{}) {
		b, _ := json.Marshal(data)
		fmt.Fprintf(w, "event: %s\ndata: %s\n\n", event, b)
		if flusher != nil {
			flusher.Flush()
		}
	}
	start := map[string]interface{}{}
	for k, v := range msg {
		if k == "content" || k == "stop_reason" {
			continue
		}
		start[k] = v
	}
	start["content"] = []interface{}{}
	start["stop_reason"] = nil
	send("message_start", map[string]interface{}{"type": "message_start", "message": start})
	for i, raw := range toList(msg["content"]) {
		block, _ := raw.(map[string]interface{})
		if block == nil {
			continue
		}
		kind, _ := block["type"].(string)
		var startBlock, delta map[string]interface{}
		if kind == "tool_use" {
			input := block["input"]
			b, _ := json.Marshal(input)
			startBlock = map[string]interface{}{
				"type": "tool_use", "id": block["id"], "name": block["name"], "input": map[string]interface{}{},
			}
			delta = map[string]interface{}{"type": "input_json_delta", "partial_json": string(b)}
		} else {
			startBlock = map[string]interface{}{"type": "text", "text": ""}
			text, _ := block["text"].(string)
			delta = map[string]interface{}{"type": "text_delta", "text": text}
		}
		send("content_block_start", map[string]interface{}{"type": "content_block_start", "index": i, "content_block": startBlock})
		send("content_block_delta", map[string]interface{}{"type": "content_block_delta", "index": i, "delta": delta})
		send("content_block_stop", map[string]interface{}{"type": "content_block_stop", "index": i})
	}
	send("message_delta", map[string]interface{}{
		"type":  "message_delta",
		"delta": map[string]interface{}{"stop_reason": msg["stop_reason"], "stop_sequence": nil},
		"usage": msg["usage"],
	})
	send("message_stop", map[string]interface{}{"type": "message_stop"})
}

// testProviderOpenAI probes an OpenAI-style provider exactly the way its
// gateway will call it later: one tiny chat/completions turn. Both spellings
// of the endpoint are tried (with and without /v1) because only the wrong
// guess answers 404, and a 404 is not a verdict about the model.
func testProviderOpenAI(ctx context.Context, p Provider, model string) ProviderTestResult {
	endpoints := chatCompletionEndpoints(p.BaseURL)
	res := ProviderTestResult{Model: model, URL: endpoints[0]}
	if model == "" {
		res.Error = "add a model id first (the test sends one short message to it)"
		return res
	}
	// a cold NVIDIA model takes ~36s to answer its first token, so a 30s
	// cap marks a working endpoint as broken; the spinner is the wait
	timeout := 60 * time.Second
	if p.TimeoutSec > 0 {
		timeout = time.Duration(min(p.TimeoutSec, 120)) * time.Second
	}
	ctx, cancel := context.WithTimeout(ctx, timeout)
	defer cancel()
	payload, _ := json.Marshal(map[string]interface{}{
		"model": model, "max_tokens": 32, "stream": false,
		"messages": []map[string]string{{"role": "user", "content": "Reply with the single word: ok"}},
	})
	client := &http.Client{}
	var lastStatus int
	var lastBody []byte
	for _, endpoint := range endpoints {
		req, err := http.NewRequestWithContext(ctx, http.MethodPost, endpoint, bytes.NewReader(payload))
		if err != nil {
			res.Error = err.Error()
			return res
		}
		req.Header.Set("Content-Type", "application/json")
		if p.AuthMode == authXAPIKey {
			req.Header.Set("x-api-key", p.APIKey)
		} else {
			req.Header.Set("Authorization", "Bearer "+p.APIKey)
		}
		for k, v := range p.Headers {
			req.Header.Set(k, v)
		}
		started := time.Now()
		resp, err := client.Do(req)
		res.LatencyMs = time.Since(started).Milliseconds()
		if err != nil {
			res.Error = err.Error()
			res.Hint = "Cannot reach the server: check the base URL and your connection."
			return res
		}
		body, _ := io.ReadAll(io.LimitReader(resp.Body, 1<<20))
		resp.Body.Close()
		res.URL = endpoint
		res.Status = resp.StatusCode
		if resp.StatusCode == http.StatusNotFound {
			lastStatus, lastBody = resp.StatusCode, body
			continue
		}
		var parsed struct {
			Choices []struct {
				Message struct {
					Content string `json:"content"`
				} `json:"message"`
			} `json:"choices"`
			Error struct {
				Message string `json:"message"`
			} `json:"error"`
		}
		_ = json.Unmarshal(body, &parsed)
		if resp.StatusCode/100 == 2 {
			if len(parsed.Choices) == 0 {
				res.Error = "HTTP 200 but not a chat/completions answer: " + strings.TrimSpace(string(body[:min(len(body), 160)]))
				return res
			}
			res.Reply = strings.TrimSpace(parsed.Choices[0].Message.Content)
			res.OK = true
			return res
		}
		msg := strings.TrimSpace(string(body[:min(len(body), 300)]))
		if parsed.Error.Message != "" {
			msg = parsed.Error.Message
		}
		res.Error = fmt.Sprintf("HTTP %d: %s", resp.StatusCode, msg)
		switch resp.StatusCode {
		case 401, 403:
			other := "x-api-key"
			if p.AuthMode == authXAPIKey {
				other = "Bearer token"
			}
			res.Hint = "The key was refused. Check the key, or try the other auth mode (" + other + ")."
		case 400, 422:
			res.Hint = "The provider rejected the request: check the model id."
		case 429:
			res.Hint = "Rate limited or out of credit."
		}
		return res
	}
	res.Status = lastStatus
	res.Error = fmt.Sprintf("HTTP 404: no chat/completions answer from %s", p.BaseURL)
	if len(lastBody) > 0 {
		res.Error += ": " + strings.TrimSpace(string(lastBody[:min(len(lastBody), 200)]))
	}
	// Both spellings of the URL answer 404, which means either a wrong base URL
	// or a model the account cannot reach. NVIDIA names them differently: a
	// decommissioned model answers a plain "404 page not found", an entitlement
	// miss answers a JSON "Not found for account", and /v1/models still lists
	// both, so the hint names the likely culprit instead of blaming the URL.
	res.Hint = "The test posts to " + strings.Join(endpoints, " and ") + ". " +
		"Some providers list models in /v1/models that are end of life or not deployed on this account (NVIDIA does): a 404 here usually means this model id, not a wrong base URL."
	return res
}

// upstreamError pulls the message out of an OpenAI-style error body.
func upstreamError(body []byte) string {
	var parsed struct {
		Error struct {
			Message string `json:"message"`
		} `json:"error"`
	}
	if json.Unmarshal(body, &parsed) == nil && parsed.Error.Message != "" {
		return parsed.Error.Message
	}
	s := strings.TrimSpace(string(body))
	if s == "" {
		return "the provider gave no reason"
	}
	return truncate(s, 500)
}

func writeAnthropicError(w http.ResponseWriter, status int, kind, message string) {
	writeJSONObject(w, status, map[string]interface{}{
		"type":  "error",
		"error": map[string]interface{}{"type": kind, "message": message},
	})
}

func writeJSONObject(w http.ResponseWriter, status int, body map[string]interface{}) {
	b, _ := json.Marshal(body)
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(status)
	_, _ = w.Write(b)
}

func toList(v interface{}) []interface{} {
	if l, ok := v.([]interface{}); ok {
		return l
	}
	return nil
}

// flattenText turns Anthropic content (a string or blocks) into plain text.
func flattenText(v interface{}) string {
	switch c := v.(type) {
	case string:
		return c
	case []interface{}:
		var b strings.Builder
		for _, raw := range c {
			part, _ := raw.(map[string]interface{})
			if part == nil {
				continue
			}
			if t, _ := part["type"].(string); t == "text" {
				if s, _ := part["text"].(string); s != "" {
					b.WriteString(s)
				}
			}
		}
		return b.String()
	case nil:
		return ""
	default:
		b, err := json.Marshal(v)
		if err != nil {
			return fmt.Sprintf("%v", v)
		}
		return string(b)
	}
}

func intOf(v interface{}) (int, bool) {
	switch n := v.(type) {
	case float64:
		return int(n), true
	case int:
		return n, true
	case json.Number:
		i, err := n.Int64()
		return int(i), err == nil
	}
	return 0, false
}

func truncate(s string, n int) string {
	if len(s) <= n {
		return s
	}
	return s[:n] + "…"
}
