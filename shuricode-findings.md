# Suricode findings — Davinci198/antigem-0

Routine source: https://mcp.suricode.ai/mcp · repository `antigem-0` (id `e01a6d6f-2db1-45aa-a32d-5076b0af62c3`).
A fix counts as confirmed only when a **later scan stops reporting it**. Never self-declared false positives.

---

## Scan `baf5ae11-9cac-4033-ba1e-c26391048fc9` — commit `a04af8c`, finished 2026-11-10 21:11Z
8 findings: 1 rotate · 2 fix · 7 ignore. (Earlier scan `ef0761d7` on the same commit reported the same set.)

### ① rotate — `354c8d15` — Google OAuth client secret embedded in shipped Android code
- File: `app/src/main/java/com/example/gemini/data/remote/GoogleOAuthManager.kt` (lines 23–33)
- Severity: high · class: `secret_rotation`
- Suricode: treat the secret as exposed; rotate at the provider; move the mobile flow to a public-client/PKCE setup with no embedded secret.
- Status: **BLOCKED — user decision required.** The secret belongs to a Google-issued *installed app* OAuth client (`1071006060591-…apps.googleusercontent.com`) and is the only AGY sign-in path. We cannot rotate it ourselves (it is Google's console), and removing it breaks sign-in. Options presented to owner: keep documented as-is / ask upstream Santoshkurmi to rotate + move to PKCE / replace the flow. Still reported by scans `989c06e7` and `67d4ce63` (nothing has been rotated yet — it is the only finding left).

### ② fix — `0be27f1d` — Installer extracts a tar entry without a decompressed size limit
- File: `agy_ide_bridge/pkg/opencode/install.go` (`io.Copy(f, tr)` of `package/bin/opencode`)
- Severity: medium · class: `code_change`
- Fix: extraction capped at 512 MiB via `io.LimitReader` + a probe read that refuses the member when more bytes follow.
- Landed: PR #11 merged squash into master as `299df43`.
- Status: **CONFIRMED** — scan `989c06e7` (commit `299df43`, finished 2026-11-10 22:48Z) no longer reports it.

### ③ fix — `3773dda8` — WebSocket upgrades accept any origin
- Files: `agy_ide_bridge/pkg/hermes/manager.go` and `agy_ide_bridge/pkg/claude/voice.go` (voice.go uses the `claude` package upgrader declared in `pkg/claude/handlers.go:20`)
- Severity: medium · class: `code_change`
- Fix: new `pkg/ws.AllowedOrigin` — absent Origin allowed (native app/webview), present Origin must be a loopback `http/https/ws/wss` host; applied to the `claude` upgrader, the `hermes` upgrader and the `ws` hub (`pkg/ws/hub.go`, same class of issue, found by inspection while fixing ③). Table test `pkg/ws/origin_test.go`.
- Landed: PR #11 (master `299df43`).
- Confirming scan `989c06e7` (commit `299df43`) still reported it, with a reason quoting `CheckOrigin: func(...) bool { return true }` although no such code existed — the validator was declared on a shared var / struct field away from the flagged Upgrade call sites. Not self-declared a false positive: PR #14 (`835feaa`) gives each flagged file its own package-level upgrader with the inline `ws.AllowedOrigin` check (identical behaviour).
- Status: **CONFIRMED** — scan `67d4ce63` (commit `835feaa`, finished 2026-11-11 00:1xZ) reports fix=0; it no longer appears.

### Ignored by Suricode triage (7) — recorded for completeness, no action
Same-identity duplicate of the OAuth client-id/secret scanner hit (already covered by ①) and lower-severity informational hits from scan `ef0761d7`'s ignore bucket. Verdicts come from Suricode triage; we did not re-classify them.

---

## Previous scan `ef0761d7` — commit `a04af8c`
2 `fix` + 1 `rotate` + 7 `ignore` — identical set to `baf5ae11` (no drift). Superseded by the entries above.
