package hermes

import (
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"sync/atomic"
	"testing"
	"time"
)

// The batch response is an NDJSON stream: one scan-probe event per model,
// flushed as the probe finishes, followed by scan-done. parseScanStream folds
// the probe events back into a verdict map for the assertions below.
type probeVerdict struct {
	OK  bool   `json:"ok"`
	Err string `json:"error"`
}

func parseScanStream(t *testing.T, body string) map[string]probeVerdict {
	t.Helper()
	out := make(map[string]probeVerdict)
	for _, line := range strings.Split(body, "\n") {
		if strings.TrimSpace(line) == "" {
			continue
		}
		var ev struct {
			Type  string `json:"type"`
			Model string `json:"model"`
			OK    bool   `json:"ok"`
			Err   string `json:"error"`
		}
		if err := json.Unmarshal([]byte(line), &ev); err != nil {
			t.Fatalf("bad event line %q: %v", line, err)
		}
		if ev.Type == "scan-probe" && ev.Model != "" {
			out[ev.Model] = probeVerdict{OK: ev.OK, Err: ev.Err}
		}
	}
	return out
}

// A batch must always answer with one verdict per requested model. An endpoint
// that never answers used to keep the HTTP request open until the app's client
// gave up, and a model the bridge skipped appeared in no answer at all — both
// left the picker showing "not checked yet" forever.
func TestScanBatchAnswersEveryModel(t *testing.T) {
	var hits int32
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		atomic.AddInt32(&hits, 1)
		w.WriteHeader(http.StatusOK)
	}))
	defer srv.Close()

	home := t.TempDir()
	writeAppProviders(t, home, srv.URL)

	m := &Manager{homeDir: home}
	rec := httptest.NewRecorder()
	body := `{"provider_id":"probe","timeout_ms":2000,"models":["m1","m2","m3"]}`
	m.handleModelScanBatch(rec, httptest.NewRequest(http.MethodPost, "/models/scan-batch", strings.NewReader(body)))

	if rec.Code != http.StatusOK {
		t.Fatalf("status = %d, body %s", rec.Code, rec.Body.String())
	}
	verdicts := parseScanStream(t, rec.Body.String())
	for _, model := range []string{"m1", "m2", "m3"} {
		got, ok := verdicts[model]
		if !ok {
			t.Errorf("no probe event for %q in %v", model, rec.Body.String())
			continue
		}
		if !got.OK {
			t.Errorf("%s = %+v, want ok", model, got)
		}
	}
}

// A probe that hangs must not hold the stream open past the batch budget;
// whatever was not reached is reported as a failed probe before scan-done, so
// the app records a reason instead of timing out with nothing.
func TestScanBatchReportsUnreachedModels(t *testing.T) {
	hang := make(chan struct{})
	defer close(hang)
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		<-hang // every probe hangs here
	}))
	// The probe client has no deadline by default, so we close the listener
	// ourselves once the batch has answered; otherwise httptest.Server.Close()
	// blocks on the open connections forever.
	t.Cleanup(func() { srv.Close() })

	home := t.TempDir()
	writeAppProviders(t, home, srv.URL)

	// Shrink the budget for the test so it does not wait ten minutes.
	restore := scanBatchBudget
	scanBatchBudget = 300 * time.Millisecond
	defer func() { scanBatchBudget = restore }()

	m := &Manager{homeDir: home}
	rec := httptest.NewRecorder()
	body := `{"provider_id":"probe","timeout_ms":60000,"models":["slow1","slow2"]}`
	started := time.Now()
	m.handleModelScanBatch(rec, httptest.NewRequest(http.MethodPost, "/models/scan-batch", strings.NewReader(body)))
	elapsed := time.Since(started)

	if elapsed > 10*time.Second {
		t.Fatalf("batch took %s, the budget did not bound it", elapsed)
	}
	verdicts := parseScanStream(t, rec.Body.String())
	for _, model := range []string{"slow1", "slow2"} {
		got, ok := verdicts[model]
		if !ok {
			t.Errorf("no probe event for %q in %v", model, rec.Body.String())
			continue
		}
		if got.OK || got.Err == "" {
			t.Errorf("%s = %+v, want ok=false with a reason", model, got)
		}
	}
}

func writeAppProviders(t *testing.T, home, baseURL string) {
	t.Helper()
	if err := os.MkdirAll(filepath.Join(home, ".claude"), 0o755); err != nil {
		t.Fatal(err)
	}
	store := `{"providers":[{"id":"probe","name":"Probe","base_url":"` + baseURL + `","api_key":"k","models":[{"id":"m1"}]}]}`
	if err := os.WriteFile(appProvidersPath(home), []byte(store), 0o600); err != nil {
		t.Fatal(err)
	}
}
