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
