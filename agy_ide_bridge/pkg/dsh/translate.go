package dsh

import (
	"encoding/json"
	"fmt"
	"net/http"
	"strings"
	"time"
)

// cleanBase normalizes a saved provider URL to the root the gateway appends
// paths to: "https://x/v1", "https://x/v1/messages" and "https://x/v1/chat/completions"
// all become "https://x".
func cleanBase(base string) string {
	base = strings.TrimSpace(base)
	if base == "" {
		return ""
	}
	base = strings.TrimRight(base, "/")
	for _, suffix := range []string{"/v1/messages", "/v1/chat/completions", "/chat/completions", "/v1/messages/count_tokens"} {
		if strings.HasSuffix(base, suffix) {
			base = strings.TrimSuffix(base, suffix)
			break
		}
	}
	base = strings.TrimSuffix(base, "/v1")
	return strings.TrimRight(base, "/")
}

// chatEndpoints lists the spellings of an OpenAI-style endpoint worth trying,
// so a base URL that disagrees about `/v1` still works.
func chatEndpoints(base string) []string {
	root := cleanBase(base)
	out := []string{root + "/v1/chat/completions", root + "/chat/completions"}
	if strings.HasSuffix(strings.TrimRight(base, "/"), "/v1") {
		out[0], out[1] = out[1], out[0]
	}
	return out
}

// anthropicEndpoints lists the spellings of an Anthropic messages endpoint.
func anthropicEndpoints(base string) []string {
	root := cleanBase(base)
	out := []string{root + "/v1/messages", root + "/messages"}
	if strings.HasSuffix(strings.TrimRight(base, "/"), "/v1") {
		out[0] = root + "/messages"
		out[1] = root + "/v1/messages"
	}
	return out
}

// sameConfig reports whether a running gateway already speaks for cfg.
func sameConfig(a, b Config) bool {
	if a.BaseURL != b.BaseURL || a.APIKey != b.APIKey || a.AuthMode != b.AuthMode || a.OpenAIStyle != b.OpenAIStyle {
		return false
	}
	if len(a.Models) != len(b.Models) || len(a.Headers) != len(b.Headers) {
		return false
	}
	for k, v := range a.Headers {
		if b.Headers[k] != v {
			return false
		}
	}
	for i := range a.Models {
		if a.Models[i] != b.Models[i] {
			return false
		}
	}
	return true
}

// openAIToAnthropic rewrites one chat/completions request as a messages request.
// Tool definitions are dropped on purpose: DSH's tools are answerers running in
// the guest, and a provider that cannot take them would fail every turn.
func openAIToAnthropic(req map[string]interface{}, model string) map[string]interface{} {
	var system []string
	messages := make([]interface{}, 0, 8)
	for _, raw := range listOf(req["messages"]) {
		m, ok := raw.(map[string]interface{})
		if !ok {
			continue
		}
		text := textOf(m["content"])
		role := stringOf(m["role"])
		switch role {
		case "system", "developer":
			if text != "" {
				system = append(system, text)
			}
		case "assistant":
			messages = append(messages, map[string]interface{}{"role": "assistant", "content": text})
		case "tool":
			// An Anthropic conversation carries tool output as a user turn.
			messages = append(messages, map[string]interface{}{
				"role":    "user",
				"content": "Tool result:\n" + text,
			})
		default:
			messages = append(messages, map[string]interface{}{"role": "user", "content": text})
		}
	}
	if len(messages) == 0 {
		messages = append(messages, map[string]interface{}{"role": "user", "content": ""})
	}
	out := map[string]interface{}{
		"model":      model,
		"messages":   messages,
		"max_tokens": maxTokensOf(req),
	}
	if len(system) > 0 {
		out["system"] = strings.Join(system, "\n\n")
	}
	if t, ok := req["temperature"]; ok {
		out["temperature"] = t
	}
	if p, ok := req["top_p"]; ok {
		out["top_p"] = p
	}
	// The gateway synthesizes the stream itself, so the upstream call is always
	// one plain answer.
	return out
}

// anthropicToCompletion rewrites one messages response as a chat.completion.
func anthropicToCompletion(resp map[string]interface{}, model string) map[string]interface{} {
	var text strings.Builder
	for _, raw := range listOf(resp["content"]) {
		block, ok := raw.(map[string]interface{})
		if !ok {
			continue
		}
		if stringOf(block["type"]) == "text" {
			text.WriteString(stringOf(block["text"]))
		}
	}
	promptTokens, outputTokens := 0, 0
	if usage, ok := resp["usage"].(map[string]interface{}); ok {
		promptTokens = intOf(usage["input_tokens"])
		outputTokens = intOf(usage["output_tokens"])
	}
	if model == "" {
		model = stringOf(resp["model"])
	}
	return map[string]interface{}{
		"id":      "chatcmpl-" + fmt.Sprint(time.Now().UnixNano()),
		"object":  "chat.completion",
		"created": time.Now().Unix(),
		"model":   model,
		"choices": []interface{}{
			map[string]interface{}{
				"index":         0,
				"message":       map[string]interface{}{"role": "assistant", "content": text.String()},
				"finish_reason": finishReasonOf(resp),
			},
		},
		"usage": map[string]interface{}{
			"prompt_tokens":     promptTokens,
			"completion_tokens": outputTokens,
			"total_tokens":      promptTokens + outputTokens,
		},
	}
}

// replayAsOpenAISSE plays a finished completion back as the chunked stream DSH
// asked for, so the SDK sees the same event shape for every provider.
func replayAsOpenAISSE(w http.ResponseWriter, completion map[string]interface{}) {
	w.Header().Set("Content-Type", "text/event-stream")
	w.Header().Set("Cache-Control", "no-cache")
	w.Header().Set("Connection", "keep-alive")
	w.WriteHeader(http.StatusOK)
	flush(w)

	id := stringOf(completion["id"])
	model := stringOf(completion["model"])
	created := completion["created"]

	text := ""
	finish := "stop"
	if choices := listOf(completion["choices"]); len(choices) > 0 {
		if choice, ok := choices[0].(map[string]interface{}); ok {
			finish = stringOf(choice["finish_reason"])
			if message, ok := choice["message"].(map[string]interface{}); ok {
				text = stringOf(message["content"])
			}
		}
	}

	writeChunk := func(delta map[string]interface{}, finishReason interface{}) {
		chunk := map[string]interface{}{
			"id":      id,
			"object":  "chat.completion.chunk",
			"created": created,
			"model":   model,
			"choices": []interface{}{
				map[string]interface{}{"index": 0, "delta": delta, "finish_reason": finishReason},
			},
		}
		payload, _ := json.Marshal(chunk)
		_, _ = fmt.Fprintf(w, "data: %s\n\n", payload)
		flush(w)
	}

	writeChunk(map[string]interface{}{"role": "assistant"}, nil)
	if text != "" {
		writeChunk(map[string]interface{}{"content": text}, nil)
	}
	if usage, ok := completion["usage"]; ok {
		chunk := map[string]interface{}{
			"id":      id,
			"object":  "chat.completion.chunk",
			"created": created,
			"model":   model,
			"choices": []interface{}{
				map[string]interface{}{"index": 0, "delta": map[string]interface{}{}, "finish_reason": finish},
			},
			"usage": usage,
		}
		payload, _ := json.Marshal(chunk)
		_, _ = fmt.Fprintf(w, "data: %s\n\n", payload)
		flush(w)
	} else {
		writeChunk(map[string]interface{}{}, finish)
	}
	_, _ = fmt.Fprint(w, "data: [DONE]\n\n")
	flush(w)
}

func finishReasonOf(resp map[string]interface{}) string {
	switch stringOf(resp["stop_reason"]) {
	case "max_tokens":
		return "length"
	default:
		return "stop"
	}
}

func maxTokensOf(req map[string]interface{}) int {
	candidates := []string{"max_completion_tokens", "max_tokens"}
	for _, key := range candidates {
		if n := intOf(req[key]); n > 0 {
			return n
		}
	}
	return 8192
}

// textOf flattens an OpenAI message content value: a plain string, or a list of
// `{type:"text", text:...}` parts.
func textOf(value interface{}) string {
	switch v := value.(type) {
	case string:
		return v
	case []interface{}:
		var b strings.Builder
		for _, raw := range v {
			part, ok := raw.(map[string]interface{})
			if !ok {
				continue
			}
			if stringOf(part["type"]) == "text" {
				b.WriteString(stringOf(part["text"]))
			}
		}
		return b.String()
	case nil:
		return ""
	default:
		return fmt.Sprint(v)
	}
}

func listOf(value interface{}) []interface{} {
	if v, ok := value.([]interface{}); ok {
		return v
	}
	return nil
}

func stringOf(value interface{}) string {
	switch v := value.(type) {
	case string:
		return v
	case nil:
		return ""
	default:
		return fmt.Sprint(v)
	}
}

func intOf(value interface{}) int {
	switch v := value.(type) {
	case float64:
		return int(v)
	case int:
		return v
	case int64:
		return int(v)
	case json.Number:
		n, _ := v.Int64()
		return int(n)
	case nil:
		return 0
	default:
		return 0
	}
}

// upstreamMessage digs the human sentence out of a provider error body.
func upstreamMessage(body []byte) string {
	var payload map[string]interface{}
	if err := json.Unmarshal(body, &payload); err == nil {
		if errObj, ok := payload["error"].(map[string]interface{}); ok {
			if message := stringOf(errObj["message"]); message != "" {
				return message
			}
		}
		if message := stringOf(payload["message"]); message != "" {
			return message
		}
	}
	trimmed := strings.TrimSpace(string(body))
	if trimmed == "" {
		return "the provider returned an empty error"
	}
	if len(trimmed) > 400 {
		trimmed = trimmed[:400]
	}
	return trimmed
}

func writeJSON(w http.ResponseWriter, status int, body map[string]interface{}) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(status)
	_ = json.NewEncoder(w).Encode(body)
}

// writeOpenAIError answers in OpenAI's error shape, which is what DSH's route
// parses; anything else lands in the session as an unreadable diagnostic.
func writeOpenAIError(w http.ResponseWriter, status int, kind, message string) {
	writeJSON(w, status, map[string]interface{}{
		"error": map[string]interface{}{
			"message": message,
			"type":    kind,
			"code":    status,
		},
	})
}
