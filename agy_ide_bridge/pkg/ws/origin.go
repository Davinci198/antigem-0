package ws

import "net/url"

// AllowedOrigin decides whether a WebSocket handshake may proceed.
//
// The bridge listens on loopback only. Native clients (the Android app, the terminal emulator)
// perform the handshake without an Origin header, so an absent Origin stays allowed. When a
// browser does send one, it must name a loopback host: anything else is a page trying to talk to
// the local app from the internet or from another site (CSRF against an authenticated session).
func AllowedOrigin(origin string) bool {
	if origin == "" {
		return true
	}
	u, err := url.Parse(origin)
	if err != nil {
		return false
	}
	switch u.Scheme {
	case "http", "https", "ws", "wss":
	default:
		return false
	}
	switch u.Hostname() {
	case "localhost", "127.0.0.1", "::1":
		return true
	}
	return false
}
