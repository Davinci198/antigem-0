package claude

import (
	"bufio"
	"context"
	"encoding/json"
	"io"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"
)

func TestAnthropicToOpenAIRequestShape(t *testing.T) {
	req := map[string]interface{}{
		"model":       "moonshotai/kimi-k2",
		"max_tokens":  float64(512),
		"temperature": 0.4,
		"system": []interface{}{
			map[string]interface{}{"type": "text", "text": "You are terse."},
		},
		"messages": []interface{}{
			map[string]interface{}{"role": "user", "content": "hi"},
			map[string]interface{}{"role": "assistant", "content": []interface{}{
				map[string]interface{}{"type": "text", "text": "let me look"},
				map[string]interface{}{"type": "tool_use", "id": "tu_1", "name": "read",
					"input": map[string]interface{}{"path": "a.go"}},
			}},
			map[string]interface{}{"role": "user", "content": []interface{}{
				map[string]interface{}{"type": "tool_result", "tool_use_id": "tu_1",
					"content": []interface{}{map[string]interface{}{"type": "text", "text": "file body"}}},
			}},
		},
		"tools": []interface{}{
			map[string]interface{}{"name": "read", "description": "read a file",
				"input_schema": map[string]interface{}{"type": "object"}},
		},
		"thinking": map[string]interface{}{"type": "enabled", "budget_tokens": 2000},
	}
	got := anthropicToOpenAI(req, Provider{})
	if got["model"] != "moonshotai/kimi-k2" || got["stream"] != false {
		t.Errorf("basics: %v", got)
	}
	if got["max_tokens"] != 512 || got["temperature"] != 0.4 {
		t.Errorf("limits: %v %v", got["max_tokens"], got["temperature"])
	}
	if _, leaked := got["thinking"]; leaked {
		t.Error("anthropic-only fields must not reach an OpenAI server")
	}
	msgs, _ := got["messages"].([]interface{})
	if len(msgs) != 4 {
		t.Fatalf("want system+3 messages, got %d: %v", len(msgs), msgs)
	}
	if m := msgs[0].(map[string]interface{}); m["role"] != "system" || m["content"] != "You are terse." {
		t.Errorf("system: %v", m)
	}
	if m := msgs[2].(map[string]interface{}); m["role"] != "assistant" {
		t.Errorf("assistant: %v", m)
	} else {
		calls, _ := m["tool_calls"].([]interface{})
		if len(calls) != 1 {
			t.Fatalf("tool_calls missing: %v", m)
		}
		fn := calls[0].(map[string]interface{})["function"].(map[string]interface{})
		if fn["name"] != "read" || fn["arguments"] != `{"path":"a.go"}` {
			t.Errorf("call fn: %v", fn)
		}
	}
	if m := msgs[3].(map[string]interface{}); m["role"] != "tool" || m["tool_call_id"] != "tu_1" || m["content"] != "file body" {
		t.Errorf("tool result: %v", m)
	}
	tools, _ := got["tools"].([]interface{})
	if len(tools) != 1 || got["tool_choice"] != "auto" {
		t.Fatalf("tools: %v", got["tools"])
	}
	fn := tools[0].(map[string]interface{})["function"].(map[string]interface{})
	if fn["parameters"].(map[string]interface{})["type"] != "object" {
		t.Errorf("input_schema not mapped: %v", fn)
	}
}

func TestOpenAIToAnthropicResponseShape(t *testing.T) {
	resp := map[string]interface{}{
		"id": "chatcmpl-9",
		"choices": []interface{}{map[string]interface{}{
			"finish_reason": "tool_calls",
			"message": map[string]interface{}{
				"content": "working",
				"tool_calls": []interface{}{map[string]interface{}{
					"id": "c1", "type": "function",
					"function": map[string]interface{}{"name": "bash", "arguments": `{"cmd":"ls"}`},
				}},
			},
		}},
		"usage": map[string]interface{}{"prompt_tokens": float64(10), "completion_tokens": float64(4)},
	}
	msg := openAIToAnthropic(resp, "minimax/m2")
	if msg["type"] != "message" || msg["role"] != "assistant" || msg["model"] != "minimax/m2" {
		t.Errorf("envelope: %v", msg)
	}
	if !strings.HasPrefix(msg["id"].(string), "msg_") {
		t.Errorf("id: %v", msg["id"])
	}
	if msg["stop_reason"] != "tool_use" {
		t.Errorf("stop: %v", msg["stop_reason"])
	}
	blocks := msg["content"].([]interface{})
	if len(blocks) != 2 {
		t.Fatalf("blocks: %v", blocks)
	}
	if b := blocks[0].(map[string]interface{}); b["type"] != "text" || b["text"] != "working" {
		t.Errorf("text block: %v", b)
	}
	b := blocks[1].(map[string]interface{})
	if b["type"] != "tool_use" || b["name"] != "bash" {
		t.Errorf("tool block: %v", b)
	}
	if input, ok := b["input"].(map[string]interface{}); !ok || input["cmd"] != "ls" {
		t.Errorf("tool input: %v", b["input"])
	}
	usage := msg["usage"].(map[string]interface{})
	if usage["input_tokens"] != 10 || usage["output_tokens"] != 4 {
		t.Errorf("usage: %v", usage)
	}
	// length must surface as max_tokens, empty answers still parse
	long := map[string]interface{}{"choices": []interface{}{map[string]interface{}{
		"finish_reason": "length", "message": map[string]interface{}{"content": "half"}}}}
	m2 := openAIToAnthropic(long, "x")
	if m2["stop_reason"] != "max_tokens" {
		t.Errorf("length stop_reason: %v", m2["stop_reason"])
	}
	empty := openAIToAnthropic(map[string]interface{}{}, "x")
	if empty["content"] == nil || len(empty["content"].([]interface{})) != 0 {
		t.Errorf("empty answer: %v", empty["content"])
	}
}

func TestChatCompletionEndpoints(t *testing.T) {
	if got := chatCompletionEndpoints("https://integrate.api.nvidia.com/v1"); len(got) != 1 || got[0] != "https://integrate.api.nvidia.com/v1/chat/completions" {
		t.Errorf("with /v1: %v", got)
	}
	got := chatCompletionEndpoints("https://integrate.api.nvidia.com/")
	if len(got) != 2 || got[0] != "https://integrate.api.nvidia.com/v1/chat/completions" || got[1] != "https://integrate.api.nvidia.com/chat/completions" {
		t.Errorf("without /v1: %v", got)
	}
}

// TestGatewayTranslatesAWholeTurn stands a fake OpenAI server up (checking
// auth and path like NVIDIA would), points a gateway at it, and drives one
// streaming and one plain /v1/messages request through the gateway exactly
// like Claude Code would.
func TestGatewayTranslatesAWholeTurn(t *testing.T) {
	var lastAuth, lastPath, lastBody string
	upstream := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		lastAuth = r.Header.Get("Authorization")
		lastPath = r.URL.Path
		b, _ := io.ReadAll(r.Body)
		lastBody = string(b)
		if r.URL.Path != "/v1/chat/completions" {
			http.NotFound(w, r)
			return
		}
		w.Header().Set("Content-Type", "application/json")
		_ = json.NewEncoder(w).Encode(map[string]interface{}{
			"id": "cmpl-1",
			"choices": []interface{}{map[string]interface{}{
				"finish_reason": "stop",
				"message":       map[string]interface{}{"content": "ok"},
			}},
			"usage": map[string]interface{}{"prompt_tokens": 5, "completion_tokens": 1},
		})
	}))
	defer upstream.Close()

	gw, err := newFormatGateway(Provider{ID: "nv", BaseURL: upstream.URL, APIKey: "nv-secret", AuthMode: authBearer})
	if err != nil {
		t.Fatal(err)
	}
	defer gw.Close()

	body := `{"model":"minimax/m2","max_tokens":8,"stream":true,"messages":[{"role":"user","content":"say ok"}]}`
	req, _ := http.NewRequest(http.MethodPost, gw.URL()+"/v1/messages", strings.NewReader(body))
	req.Header.Set("Content-Type", "application/json")
	req.Header.Set("Authorization", "Bearer "+loopbackKey)
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		t.Fatal(err)
	}
	defer resp.Body.Close()
	if ct := resp.Header.Get("Content-Type"); ct != "text/event-stream" {
		t.Fatalf("stream content-type: %s", ct)
	}
	var events []string
	sc := bufio.NewScanner(resp.Body)
	for sc.Scan() {
		if s := sc.Text(); strings.HasPrefix(s, "event: ") {
			events = append(events, strings.TrimPrefix(s, "event: "))
		}
	}
	want := []string{"message_start", "content_block_start", "content_block_delta", "content_block_stop", "message_delta", "message_stop"}
	if len(events) != len(want) {
		t.Fatalf("events: %v", events)
	}
	for i, e := range want {
		if events[i] != e {
			t.Fatalf("event %d: want %s got %s", i, e, events[i])
		}
	}
	if lastAuth != "Bearer nv-secret" {
		t.Errorf("gateway did not inject the real key: %q", lastAuth)
	}
	if lastPath != "/v1/chat/completions" {
		t.Errorf("path: %s", lastPath)
	}
	if !strings.Contains(lastBody, `"stream":false`) {
		t.Errorf("upstream must be asked non-streaming: %s", lastBody)
	}
	if !strings.Contains(lastBody, "say ok") {
		t.Errorf("user text lost: %s", lastBody)
	}

	// a non-streaming call gets a plain Anthropic JSON message back
	req2, _ := http.NewRequest(http.MethodPost, gw.URL()+"/v1/messages",
		strings.NewReader(`{"model":"minimax/m2","max_tokens":8,"messages":[{"role":"user","content":"say ok"}]}`))
	req2.Header.Set("Content-Type", "application/json")
	resp2, err := http.DefaultClient.Do(req2)
	if err != nil {
		t.Fatal(err)
	}
	defer resp2.Body.Close()
	var msg map[string]interface{}
	if err := json.NewDecoder(resp2.Body).Decode(&msg); err != nil {
		t.Fatal(err)
	}
	if msg["type"] != "message" || msg["stop_reason"] != "end_turn" {
		t.Errorf("plain answer: %v", msg)
	}
	blocks := msg["content"].([]interface{})
	if len(blocks) != 1 || blocks[0].(map[string]interface{})["text"] != "ok" {
		t.Errorf("blocks: %v", blocks)
	}

	// count_tokens is answered locally; the CLI watches it for context pressure
	req3, _ := http.NewRequest(http.MethodPost, gw.URL()+"/v1/messages/count_tokens", strings.NewReader(`{"messages":[]}`))
	req3.Header.Set("Content-Type", "application/json")
	resp3, err := http.DefaultClient.Do(req3)
	if err != nil {
		t.Fatal(err)
	}
	defer resp3.Body.Close()
	if resp3.StatusCode != 200 {
		t.Fatalf("count_tokens status: %d", resp3.StatusCode)
	}
	var ct map[string]interface{}
	_ = json.NewDecoder(resp3.Body).Decode(&ct)
	if v, _ := ct["input_tokens"].(float64); v < 1 {
		t.Errorf("count_tokens: %v", ct)
	}
}

// TestGatewayReportsUpstreamErrors proves a refusal travels back to the CLI in
// Anthropic shape, and a 429 keeps its retryable type.
func TestGatewayReportsUpstreamErrors(t *testing.T) {
	upstream := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(429)
		_, _ = w.Write([]byte(`{"error":{"message":"too many requests"}}`))
	}))
	defer upstream.Close()
	gw, err := newFormatGateway(Provider{ID: "e", BaseURL: upstream.URL, APIKey: "k"})
	if err != nil {
		t.Fatal(err)
	}
	defer gw.Close()
	resp, err := http.Post(gw.URL()+"/v1/messages", "application/json",
		strings.NewReader(`{"model":"m","max_tokens":8,"messages":[{"role":"user","content":"x"}]}`))
	if err != nil {
		t.Fatal(err)
	}
	defer resp.Body.Close()
	if resp.StatusCode != 429 {
		t.Fatalf("status: %d", resp.StatusCode)
	}
	var e struct {
		Type  string `json:"type"`
		Error struct {
			Type    string `json:"type"`
			Message string `json:"message"`
		} `json:"error"`
	}
	_ = json.NewDecoder(resp.Body).Decode(&e)
	if e.Type != "error" || e.Error.Type != "rate_limit_error" || e.Error.Message != "too many requests" {
		t.Errorf("error shape: %s %s %q", e.Type, e.Error.Type, e.Error.Message)
	}
}

// TestOpenAIStyleSettingsUseTheGateway checks the launch wiring: the CLI is
// pointed at the loopback translator with a placeholder key, never at the
// provider URL with the real one.
func TestOpenAIStyleSettingsUseTheGateway(t *testing.T) {
	m := &Manager{HomeDir: t.TempDir()}
	p := Provider{ID: "nv", BaseURL: "https://integrate.api.nvidia.com", APIKey: "super-secret", OpenAIStyle: true,
		Models: []ProviderModel{{ID: "minimax/m2"}}}
	path, err := m.providerSettingsFile(p, "minimax/m2")
	if err != nil {
		t.Fatal(err)
	}
	raw, err := os.ReadFile(path)
	if err != nil {
		t.Fatal(err)
	}
	if strings.Contains(string(raw), "super-secret") || strings.Contains(string(raw), "integrate.api.nvidia.com") {
		t.Errorf("settings leak the endpoint or key: %s", raw)
	}
	var f struct {
		Env map[string]string `json:"env"`
	}
	if err := json.Unmarshal(raw, &f); err != nil {
		t.Fatal(err)
	}
	if !strings.HasPrefix(f.Env["ANTHROPIC_BASE_URL"], "http://127.0.0.1:") {
		t.Errorf("base url: %q", f.Env["ANTHROPIC_BASE_URL"])
	}
	if f.Env["ANTHROPIC_AUTH_TOKEN"] != loopbackKey || f.Env["ANTHROPIC_API_KEY"] != "" {
		t.Errorf("key env: %q %q", f.Env["ANTHROPIC_AUTH_TOKEN"], f.Env["ANTHROPIC_API_KEY"])
	}
	if f.Env["ANTHROPIC_MODEL"] != "minimax/m2" {
		t.Errorf("model pin: %q", f.Env["ANTHROPIC_MODEL"])
	}

	// the plain Anthropic shape keeps writing the real endpoint and key
	normal := Provider{ID: "or", BaseURL: "https://openrouter.ai/api", APIKey: "real", AuthMode: authBearer}
	path2, err := m.providerSettingsFile(normal, "x/y")
	if err != nil {
		t.Fatal(err)
	}
	raw2, _ := os.ReadFile(filepath.Clean(path2))
	var f2 struct {
		Env map[string]string `json:"env"`
	}
	_ = json.Unmarshal(raw2, &f2)
	if f2.Env["ANTHROPIC_BASE_URL"] != "https://openrouter.ai/api" || f2.Env["ANTHROPIC_AUTH_TOKEN"] != "real" {
		t.Errorf("normal provider rewired: %v", f2.Env)
	}

	// a saved change of endpoint rebuilds the gateway and its port
	p2 := p
	p2.APIKey = "other"
	path3, err := m.providerSettingsFile(p2, "minimax/m2")
	if err != nil {
		t.Fatal(err)
	}
	raw3, _ := os.ReadFile(path3)
	if string(raw) == string(raw3) {
		t.Error("key change must rebuild the gateway")
	}
}

// TestTestProviderOpenAI checks the form's Test button against a fake endpoint.
func TestTestProviderOpenAI(t *testing.T) {
	upstream := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path != "/v1/chat/completions" {
			http.NotFound(w, r)
			return
		}
		if r.Header.Get("Authorization") != "Bearer k" {
			w.WriteHeader(401)
			return
		}
		_, _ = w.Write([]byte(`{"choices":[{"message":{"content":" ok "}}]}`))
	}))
	defer upstream.Close()
	res := testProvider(context.Background(), Provider{BaseURL: upstream.URL, APIKey: "k", OpenAIStyle: true}, "m")
	if !res.OK || res.Reply != "ok" {
		t.Errorf("test result: %+v", res)
	}
	// a wrong base: both candidates 404 and the error says where it looked
	res = testProvider(context.Background(), Provider{BaseURL: upstream.URL + "/nope", APIKey: "k", OpenAIStyle: true}, "m")
	if res.OK || !strings.Contains(res.Error, "404") {
		t.Errorf("404 case: %+v", res)
	}
	// key refused: the hint offers the other auth mode
	bad := testProvider(context.Background(), Provider{BaseURL: upstream.URL, APIKey: "wrong", OpenAIStyle: true}, "m")
	if bad.OK || bad.Status != 401 || !strings.Contains(bad.Hint, "x-api-key") {
		t.Errorf("401 case: %+v", bad)
	}
	time.Sleep(10 * time.Millisecond)
}
