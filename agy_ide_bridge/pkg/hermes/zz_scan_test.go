package hermes

import (
	"net/http"
	"net/http/httptest"
	"sync/atomic"
	"testing"
	"time"
)

// The first probe of every model gets a 429, the retry gets a 200. Without the
// backoff loop the whole catalog reports as throttled.
func TestProbeRetriesOn429(t *testing.T) {
	var hits int32 = 0
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if atomic.AddInt32(&hits, 1) == 1 {
			w.WriteHeader(http.StatusTooManyRequests)
			return
		}
		w.WriteHeader(http.StatusOK)
	}))
	defer srv.Close()

	latency, errMsg := probeOpenAICompatible(srv.URL, "k", "m", 5*time.Second)
	if errMsg != "" {
		t.Fatalf("expected a retry to succeed, got %q", errMsg)
	}
	if latency <= 0 {
		t.Fatalf("expected a latency, got %d", latency)
	}
}

// Nous Portal delivers a credit refusal as ordinary assistant text and still
// completes the turn, so a probe that trusted message.complete alone reported a
// dead model as healthy. The refusal must become the verdict instead.
func TestProbeRefusalReadsTheAnswer(t *testing.T) {
	refusal := "Billing or credits exhausted: HTTP 404: Model 'anthropic/claude-haiku-4.5' " +
		"requires available credits. Your account balance is too low to use paid models — " +
		"add credits at https://portal.nousresearch.com or pick a free model.\n\nSome trailing detail."
	if got := probeRefusal(refusal); got == "" {
		t.Fatalf("a credit refusal must not pass as a healthy probe")
	}
	cases := map[string]string{
		"OK":                     "",
		"Here is the answer: 42": "",
		"insufficient_quota":     "insufficient_quota",
		"You exceeded your current quota, please check your plan": "You exceeded your current quota, please check your plan",
	}
	for text, want := range cases {
		if got := probeRefusal(text); (got == "") != (want == "") {
			t.Fatalf("probeRefusal(%q) = %q, want empty=%v", text, got, want == "")
		}
	}
}
