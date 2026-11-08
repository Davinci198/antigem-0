// Live end-to-end test against the real Hermes gateway on this machine.
// Opt-in only (it spends model tokens and needs a running Hermes install):
//
//	HERMES_LIVE_TEST=1 go test ./pkg/hermes/ -run TestLiveGateway -v
package hermes

import (
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"
)

func TestLiveGatewayTurn(t *testing.T) {
	if os.Getenv("HERMES_LIVE_TEST") != "1" {
		t.Skip("set HERMES_LIVE_TEST=1 to run against the real gateway")
	}
	home, _ := os.UserHomeDir()
	bin := filepath.Join(home, ".local", "bin", "hermes")
	if _, err := os.Stat(bin); err != nil {
		t.Fatalf("hermes CLI not found at %s: %v", bin, err)
	}
	m := NewManager(home, bin)

	// connect
	if err := m.gw.ensure(); err != nil {
		t.Fatalf("gateway connect: %v", err)
	}

	// the model picker answers
	models, err := m.gw.models("")
	if err != nil {
		t.Fatalf("model.options: %v", err)
	}
	if _, ok := models["providers"]; !ok {
		t.Logf("model.options keys: %v", models)
	}

	// a session, a turn, and the streamed answer
	s := m.session("livetest")
	s.mu.Lock()
	s.title = "live test"
	s.mu.Unlock()
	if err := m.gw.createSession(s); err != nil {
		t.Fatalf("session.create: %v", err)
	}
	if s.gwID == "" {
		t.Fatal("createSession left no gateway session id")
	}

	done := make(chan string, 1)
	go func() {
		deadline := time.Now().Add(180 * time.Second)
		for time.Now().Before(deadline) {
			s.mu.Lock()
			d, finished := s.streamDone, s.streamDone
			body := s.streamBuf.String()
			s.mu.Unlock()
			_ = d
			if finished {
				done <- body
				return
			}
			time.Sleep(500 * time.Millisecond)
		}
		done <- ""
	}()

	if err := m.gw.submit(s.gwID, "Reply with exactly: pong-gw"); err != nil {
		t.Fatalf("prompt.submit: %v", err)
	}

	select {
	case body := <-done:
		if !strings.Contains(body, "pong-gw") {
			t.Fatalf("reply did not contain pong-gw: %q", body)
		}
		t.Logf("reply: %q", body)
	case <-time.After(190 * time.Second):
		t.Fatal("turn never completed")
	}

	// history reflects the turn
	hist, err := m.gw.history(s.gwID)
	if err != nil {
		t.Fatalf("session.history: %v", err)
	}
	rows, _ := hist["messages"].([]interface{})
	if len(rows) == 0 {
		t.Fatal("history is empty after a completed turn")
	}

	// abort on an idle session must not blow up
	if err := m.gw.interrupt(s.gwID); err != nil {
		t.Logf("interrupt on idle session: %v (acceptable)", err)
	}

	m.gw.detach(s.gwID)
}
