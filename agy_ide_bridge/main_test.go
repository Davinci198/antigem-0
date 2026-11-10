package main

import (
	"net/http"
	"net/http/httptest"
	"testing"
)

// The logging wrapper must stay transparent to streaming handlers: the model
// scan asserts http.Flusher and used to panic (answering HTTP 500) because
// statusRecorder only embedded ResponseWriter.
func TestStatusRecorderIsFlusher(t *testing.T) {
	var _ http.Flusher = &statusRecorder{} // compile-time guarantee

	rec := httptest.NewRecorder()
	sr := &statusRecorder{ResponseWriter: rec, statusCode: http.StatusOK}
	sr.WriteHeader(http.StatusOK)
	if _, err := sr.Write([]byte("event\n")); err != nil {
		t.Fatal(err)
	}
	sr.Flush()

	if !rec.Flushed {
		t.Fatal("Flush did not reach the wrapped writer")
	}
	if rec.Body.String() != "event\n" {
		t.Fatalf("body = %q, want %q", rec.Body.String(), "event\n")
	}
	if sr.statusCode != http.StatusOK {
		t.Fatalf("statusCode = %d, want 200", sr.statusCode)
	}
}
