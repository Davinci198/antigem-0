package hermes

import (
	"fmt"
	"strings"
	"testing"
	"time"
)

// A model pick that arrives while a turn is finishing loses the revision race;
// the retry must re-read the snapshot and fence the mutate on the new revision
// instead of failing the pick.
func TestSetModelRetriesRevisionConflict(t *testing.T) {
	g := newGateway(nil)
	resumes, mutates := 0, 0
	call := func(method string, params obj, timeout time.Duration) (obj, error) {
		switch method {
		case "session.resume":
			resumes++
			return obj{"revision": float64(7 + resumes), "execution_generation": float64(2)}, nil
		case "session.mutate":
			mutates++
			if mutates < 3 {
				return nil, fmt.Errorf("session.mutate: revision_conflict")
			}
			want := 7 + resumes
			if got, _ := params["expected_revision"].(int); got != want {
				t.Fatalf("expected_revision = %v, want the fresh snapshot's %d", params["expected_revision"], want)
			}
			return obj{"session_id": "gw-1", "revision": float64(9)}, nil
		}
		t.Fatalf("unexpected method %s", method)
		return nil, nil
	}

	payload := obj{"model": "meituan/longcat-2.5:free", "provider": "nous"}
	res, err := g.setModelWith(call, "gw-1", payload)
	if err != nil {
		t.Fatalf("setModelWith: %v", err)
	}
	if mutates != 3 || resumes != 3 {
		t.Fatalf("mutates/resumes = %d/%d, want 3/3", mutates, resumes)
	}
	if res["revision"] != float64(9) {
		t.Fatalf("result = %v, want the winning mutate's result", res)
	}
}

// A real refusal is not a race: it must reach the caller on the first answer.
func TestSetModelStopsOnRealRefusal(t *testing.T) {
	g := newGateway(nil)
	mutates := 0
	call := func(method string, params obj, timeout time.Duration) (obj, error) {
		if method == "session.resume" {
			return obj{"revision": float64(4)}, nil
		}
		mutates++
		return nil, fmt.Errorf("session.mutate: unknown_model")
	}

	_, err := g.setModelWith(call, "gw-1", obj{"model": "nope", "provider": "nous"})
	if err == nil || !strings.Contains(err.Error(), "unknown_model") {
		t.Fatalf("err = %v, want the unknown_model refusal", err)
	}
	if mutates != 1 {
		t.Fatalf("mutates = %d, want 1 (no retry on a real refusal)", mutates)
	}
}

// The loop is bounded: a session that keeps refusing still returns.
func TestSetModelGivesUpAfterFourRaces(t *testing.T) {
	g := newGateway(nil)
	mutates := 0
	call := func(method string, params obj, timeout time.Duration) (obj, error) {
		if method == "session.resume" {
			return obj{"revision": float64(4)}, nil
		}
		mutates++
		return nil, fmt.Errorf("session.mutate: stale_generation")
	}

	_, err := g.setModelWith(call, "gw-1", obj{"model": "haiku", "provider": "nous"})
	if err == nil || !strings.Contains(err.Error(), "stale_generation") {
		t.Fatalf("err = %v, want the last race", err)
	}
	if mutates != 4 {
		t.Fatalf("mutates = %d, want 4 attempts", mutates)
	}
}

// A timed-out call is ambiguous — the switch may or may not have landed — so
// the pick retries it instead of giving up after one silent failure.
func TestSetModelRetriesATimedOutCall(t *testing.T) {
	g := newGateway(nil)
	resumes, mutates := 0, 0
	call := func(method string, params obj, timeout time.Duration) (obj, error) {
		switch method {
		case "session.resume":
			resumes++
			if resumes == 1 {
				return nil, fmt.Errorf("session.resume timed out after 35s")
			}
			return obj{"revision": float64(5), "execution_generation": float64(1)}, nil
		case "session.mutate":
			mutates++
			if mutates == 1 {
				return nil, fmt.Errorf("session.mutate timed out after 35s")
			}
			return obj{"session_id": "gw-1", "revision": float64(6)}, nil
		}
		t.Fatalf("unexpected method %s", method)
		return nil, nil
	}

	res, err := g.setModelWith(call, "gw-1", obj{"model": "meituan/longcat-2.5:free", "provider": "nous"})
	if err != nil {
		t.Fatalf("setModelWith: %v", err)
	}
	if resumes != 3 || mutates != 2 {
		t.Fatalf("resumes/mutates = %d/%d, want 3/2 (each retry re-reads the session)", resumes, mutates)
	}
	if res["revision"] != float64(6) {
		t.Fatalf("result = %v, want the winning mutate's result", res)
	}
}
