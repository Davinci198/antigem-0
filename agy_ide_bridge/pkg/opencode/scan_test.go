package opencode

import (
	"encoding/json"
	"io"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"
)

func TestProbeRequestURLs(t *testing.T) {
	cases := []struct {
		kind, base, want string
	}{
		{KindOpenAI, "http://h/v1", "http://h/v1/chat/completions"},
		{KindOpenAI, "http://h/v1/", "http://h/v1/chat/completions"},
		{KindOpenAI, "http://host:11434", "http://host:11434/v1/chat/completions"},
		{KindOpenAI, "http://h/v1/chat/completions", "http://h/v1/chat/completions"},
		{KindAnthropic, "https://a/v1", "https://a/v1/messages"},
		{KindAnthropic, "https://a", "https://a/v1/messages"},
	}
	for _, c := range cases {
		p := &CustomProvider{Kind: c.kind, BaseURL: c.base}
		url, _, _, err := p.probeRequest("m")
		if err != nil {
			t.Fatalf("%s %s: %v", c.kind, c.base, err)
		}
		if url != c.want {
			t.Errorf("kind %s base %s: got %s want %s", c.kind, c.base, url, c.want)
		}
	}
	// the key rides as Bearer only when set
	p := &CustomProvider{BaseURL: "http://h/v1", APIKey: "sk-9"}
	_, _, headers, _ := p.probeRequest("m")
	if headers["Authorization"] != "Bearer sk-9" {
		t.Errorf("auth header: %v", headers["Authorization"])
	}
}

func TestCustomProviderScanStream(t *testing.T) {
	home := t.TempDir()
	m := NewManager(home, home, "")

	var gotAuth string
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		gotAuth = r.Header.Get("Authorization")
		var body struct {
			Model string `json:"model"`
		}
		_ = json.NewDecoder(r.Body).Decode(&body)
		if body.Model == "dead" {
			w.WriteHeader(http.StatusInternalServerError)
			io.WriteString(w, "boom")
			return
		}
		io.WriteString(w, `{"choices":[]}`)
	}))
	defer srv.Close()

	res := doJSON(t, m, http.MethodPost, "/api/opencode/custom-providers",
		`{"name":"Probe Box","base_url":"`+srv.URL+`/v1","api_key":"sk-test","models":[{"id":"live"},{"id":"dead"}]}`)
	if res["success"] != true {
		t.Fatalf("add failed: %v", res)
	}
	id := res["provider"].(map[string]interface{})["id"].(string)

	req := httptest.NewRequest(http.MethodPost, "/api/opencode/custom-providers/scan",
		strings.NewReader(`{"provider_id":"`+id+`","timeout_ms":5000}`))
	rec := httptest.NewRecorder()
	m.ServeHTTP(rec, req)

	events := []map[string]interface{}{}
	for _, line := range strings.Split(rec.Body.String(), "\n") {
		if strings.TrimSpace(line) == "" {
			continue
		}
		var ev map[string]interface{}
		if err := json.Unmarshal([]byte(line), &ev); err != nil {
			t.Fatalf("not NDJSON: %q", line)
		}
		events = append(events, ev)
	}
	if len(events) != 4 {
		t.Fatalf("want scan-start + 2 probes + scan-done, got %d events: %v", len(events), events)
	}
	if events[0]["type"] != "scan-start" || events[0]["models"] != float64(2) {
		t.Errorf("start: %v", events[0])
	}
	ok := map[string]bool{}
	for _, ev := range events[1:3] {
		if ev["type"] != "scan-probe" {
			t.Errorf("bad event: %v", ev)
		}
		truthy, _ := ev["ok"].(bool) // "ok" is omitted when false
		model := ev["model"].(string)
		if model == "live" {
			ok[model] = truthy && ev["latency_ms"] != nil && gotAuth == "Bearer sk-test"
		} else {
			ok[model] = !truthy && strings.HasPrefix(ev["error"].(string), "HTTP 500")
		}
	}
	if !ok["live"] || !ok["dead"] {
		t.Errorf("verdicts wrong: %v (%v)", ok, events)
	}
	if events[3]["type"] != "scan-done" {
		t.Errorf("done: %v", events[3])
	}
}

func TestCustomProviderScanUnknownProvider(t *testing.T) {
	home := t.TempDir()
	m := NewManager(home, home, "")
	res := doJSON(t, m, http.MethodPost, "/api/opencode/custom-providers/scan", `{"provider_id":"ghost"}`)
	if res["success"] != false || !strings.Contains(res["error"].(string), "ghost") {
		t.Fatalf("unknown provider: %v", res)
	}
}

func TestScanBatchBudgetIsSane(t *testing.T) {
	// The app gives up on a scan after its read timeout; the bridge must close
	// the stream itself before that or the app sees a silent cut.
	if scanBatchBudget > 15*time.Minute {
		t.Errorf("scan budget %s exceeds what the app waits", scanBatchBudget)
	}
}
