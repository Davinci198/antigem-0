package agyaccounts

import (
	"encoding/json"
	"fmt"
	"net/http"
	"strings"
	"time"
)

// probeAuth asks the just-restarted AGY hub whether the installed credential is
// accepted. It mirrors what the app does through gRPC: GetAuthStatus returns
// authResult.hasValidAuth. A network error means the hub did not come up, which is
// a different failure from "the token was rejected".
func probeAuth(port string) (bool, error) {
	if strings.TrimSpace(port) == "" {
		return false, fmt.Errorf("hub port unknown")
	}
	url := fmt.Sprintf("http://127.0.0.1:%s/exa.language_server_pb.LanguageServerService/GetAuthStatus", port)
	client := &http.Client{Timeout: 20 * time.Second}

	var lastErr error
	// The hub accepts connections slightly before it answers RPCs, so give it a few tries.
	for attempt := 0; attempt < 6; attempt++ {
		req, err := http.NewRequest(http.MethodPost, url, strings.NewReader(`{}`))
		if err != nil {
			return false, err
		}
		req.Header.Set("Content-Type", "application/json")

		resp, err := client.Do(req)
		if err != nil {
			lastErr = err
			time.Sleep(2 * time.Second)
			continue
		}
		var payload struct {
			AuthResult struct {
				HasValidAuth bool `json:"hasValidAuth"`
			} `json:"authResult"`
		}
		decodeErr := json.NewDecoder(resp.Body).Decode(&payload)
		_ = resp.Body.Close()
		if decodeErr != nil {
			lastErr = decodeErr
			time.Sleep(2 * time.Second)
			continue
		}
		return payload.AuthResult.HasValidAuth, nil
	}
	if lastErr == nil {
		lastErr = fmt.Errorf("no answer from the AGY hub")
	}
	return false, lastErr
}
