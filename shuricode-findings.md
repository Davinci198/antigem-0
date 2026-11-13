# Suricode findings — Davinci198/antigem-0

Routine source: https://mcp.suricode.ai/mcp · repository `antigem-0` (id `e01a6d6f-2db1-45aa-a32d-5076b0af62c3`).
A fix counts as confirmed only when a **later scan stops reporting it**. Never self-declared false positives.

---

## Scan `ee59bedf` — commit `8444343` (master after PR #40, the Codex port)
fix=1 · rotate=1 · policy=0 · ignore=8. The Codex port added one real finding; the register
otherwise did not move — ① (history-only OAuth secret) is still the only rotate item.

| Verdict | Finding | Where |
|---|---|---|
| fix | Bound extracted tar entry size when installing Codex | `agy_ide_bridge/pkg/codex/install.go` |
| rotate | Google OAuth client secret embedded in shipped Android code | ① below (history only) |

The Codex install path downloads the official release tarball and extracts one entry with
`io.Copy(f, tr)`; a tar header can claim any size, so a malformed archive could fill the disk.
Fixed in this change: `hdr.Size` is now checked against a 256 MB cap before anything is
written, the entry must be a regular file (no symlink), the copy is `io.CopyN` bounded to the
declared size, and a short entry is an error instead of a half-written binary. The GitHub
download and its sha256 verification are untouched.

`pkg/codex/install_test.go` covers the four paths: a normal entry, an oversized header, a
truncated entry, a missing binary and a symlink entry. `go build ./...`, `go vet` and
`go test ./pkg/codex/` are green.

## Scan `fa470923` — commit `b680f2d` (master after PR #38)
fix=0 · rotate=1 · ignore=8. The backup-rules change is clean: no new findings, and the
register did not move — ① (history-only OAuth secret) is still the only open item.

The shipped APK was checked rather than only the sources: `apkanalyzer manifest print` shows
`fullBackupContent` and `dataExtractionRules` on `<application>`, and the two compiled
resource files inside the APK (`res/Qq.xml`, `res/4j.xml`) are the backup rules with the
excludes. `usesCleartextTraffic` stays `true` by decision; the manifest comment gives the reason.

## PR #38 — backup and cleartext hardening (merged as `b680f2d`)
Backup and cleartext hardening, the last two items of the security list.

**Auto Backup never applied its rules.** `res/xml/backup_rules.xml` and
`res/xml/data_extraction_rules.xml` existed but the manifest never referenced them, so
`allowBackup="true"` backed up the whole app data dir: the DataStore holding
`security_token` and `termux_ssh_pass`, its SharedPreferences mirror, and the entire Linux
container (rootfs plus the agy, Claude Code and OpenCode credentials inside it — far past
the 25 MB quota, so the backup would also fail).

Both files now carry real `<exclude>` entries and the manifest points at them
(`android:fullBackupContent`, `android:dataExtractionRules`), for cloud backup and device
transfer alike:

| Excluded | Why |
|---|---|
| `file`: `usr`, `home`, `ubuntu`, `bin`, `lib`, `sysdata`, `tmp`, `shm` | container rootfs, agent homes, their credential files, SSH keys |
| `file`: `extensions`, `browser-extensions` | downloaded browser runtime, no user data |
| `file`: `datastore` | `security_token` and `termux_ssh_pass`, both plain text |
| `sharedpref`: `anti_gem_sync_prefs.xml` | mirror of the same DataStore, repeats the token |

Kept on purpose: `file/notes` (user notes) and the ordinary preference files
(`agent_prefs`, `claude_code_prefs`, `opencode_prefs`, `termux_ide_prefs`,
`anti_gem_terminal_prefs`, `chat_uploads`, `hermes_model_health`, `telemetry_device_prefs`).
A restore therefore brings notes and settings, never credentials; every agent signs in
again on the new device. MCP server headers and env vars (API keys) live in the
container's `mcp_config.json`, so the same exclusion covers them.

**`usesCleartextTraffic` stays `true` — a decision, not an oversight.** The hub and the
bridge are plain HTTP servers on `127.0.0.1`, `Servers & Connectivity` builds
`http://host:port` for a remote host by design, and the in-app browser tab loads plain
http sites on request. Blocking cleartext would cut the app off from its own backend. What
authenticates the local API is `security_token`, and that token is now out of backups.
The manifest carries a comment saying so, so a future reader does not flip the flag
blindly.

**No OAuth scopes and no token logging left.** `grep` for `drive.file`, `userinfo.email`,
`oauth2` and Google endpoints returns nothing outside deleted files. The one remaining
credential in logcat — the CSRF token's first 8 characters, printed on every update in
`AgyCsrfManager.setCachedToken` — now logs the token length instead.

## Scan `d0b3f2de` and `db7ae8dd` — commit `9d11db1` (master after PR #36)
fix=0 · rotate=1 · ignore=8. ① is still reported, but the code it names no longer exists:

- `app/src/main/java/com/example/gemini/data/remote/GoogleOAuthManager.kt` returns 404 from the contents API at `9d11db1`, and the recursive tree for that commit has no such path.
- The APK built by CI on the same commit (`antigem-standard-signed`, run `43400956030`) contains **no** `GOCSPX`, no client id, no `accounts.google.com`, no `oauth2.googleapis.com` and no `cloudcode-pa.googleapis.com` in `classes*.dex`.

So the shipped app cannot leak that secret any more. The remaining report points at a file that exists only in history (6 commits before this merge). It stays open until a scan stops reporting it or the secret is rotated at Google — rotation is not ours to do, it is upstream's OAuth client.

## Change awaiting scan — `fix/single-login-agy` (on top of `a9dd599`)
The app's own Google OAuth is gone: `GoogleOAuthManager` (PKCE + loopback callback + embedded client id/secret), `AntigravityApiService` (direct `cloudcode-pa` client) and `ContextCompactor` (its only caller) were deleted, together with the access/refresh token, project and tier state in `AuthPreferences`. Login is now only the agy CLI's own flow, driven through the hub's `Login` RPC, with the credential inside the container — the Mobile Harness model.

① is **not closed by this entry**: it closes only when a scan of the merged commit stops reporting it.

## Scan `baf5ae11-9cac-4033-ba1e-c26391048fc9` — commit `a04af8c`, finished 2026-11-10 21:11Z
8 findings: 1 rotate · 2 fix · 7 ignore. (Earlier scan `ef0761d7` on the same commit reported the same set.)

### ① rotate — `354c8d15` — Google OAuth client secret embedded in shipped Android code
- File: `app/src/main/java/com/example/gemini/data/remote/GoogleOAuthManager.kt` (lines 23–33)
- Severity: high · class: `secret_rotation`
- Suricode: treat the secret as exposed; rotate at the provider; move the mobile flow to a public-client/PKCE setup with no embedded secret.
- Status: **BLOCKED — user decision required.** The secret belongs to a Google-issued *installed app* OAuth client (`1071006060591-…apps.googleusercontent.com`) and is the only AGY sign-in path. We cannot rotate it ourselves (it is Google's console), and removing it breaks sign-in. Options presented to owner: keep documented as-is / ask upstream Santoshkurmi to rotate + move to PKCE / replace the flow. Still reported by scans `989c06e7`, `67d4ce63`, `670d9019`, `6cdc45d8`, `6a241e3b` and every scan since (`08b465f1`, `01439769`, `9373013f`, `39a92150`) — nothing rotated yet; it is the only finding left.

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

## Scan `670d9019-500d-4739-a5bd-cb230835e280` — commit `83f8bf1`
fix=0 · rotate=1 · ignore=8. Confirms ② and ③ stay gone after PRs #12–#17 (SSE identity-encoding + OpenCode event-stream start). Only ① remains.

## Scan `6cdc45d8-c7a0-4b46-9fe9-d494a9af8479` — commit `6a6141b` (master after PR #18 Hermes turn-duration)
fix=0 · rotate=1 · ignore=8. PR #18 introduced no new findings; register unchanged — still only ① open.

## Scan `6a241e3b-0b75-4867-a86d-4441818ec4a0` — commit `52593a9` (master after docs PR #19)
fix=0 · rotate=1 · ignore=8. Docs-only merge, identical set — no drift. Further scans of docs-only commits would re-report the same set, so the next scan runs after the next code push.

## Scan `ab451785-27b4-4e76-8eb9-7a545eccb73d` — commit `f3b0f7c` (master before PR #21)
fix=0 · rotate=1 · ignore=8. Triggered by the push of `fix/hermes-session-persistence` (PR #21); Suricode scans the default branch, so this confirms master itself still has no drift — only ① open. The scan that validates PR #21's code runs after it merges.

## Scan `ad5b7afe-bf73-4476-85e8-726dc2befb5f` — commit `11bcaa6` (master after PR #24)
fix=0 · rotate=1 · ignore=8. The OpenCode model scan (PR #24) introduced no new findings. Only ① remains, re-reported under a fresh id (`08b465f1`): the same embedded Google OAuth secret, still awaiting the user's own OAuth client.

## Scan `d6064cbc-7b8d-4b51-9907-b9d28113564d` — commit `1e02a21` (master after PR #25)
fix=0 · rotate=1 · ignore=8. The Claude OpenAI-compatible gateway (PR #25) introduced no new findings either — the new Go translator files are clean. Only ① remains, now as `01439769`.

## Scan `dc064717-367d-421f-a0a8-58382284752a` — commit `9f88e45` (master after PR #28)
fix=0 · rotate=1 · ignore=8. The drawer filter row + "Continue in another agent" change (PR #28) added no findings: the new `HermesChatBackend.loadMessages` and `ChatViewModel.continueConversationIn` code is clean. Only ① remains, still the embedded Google OAuth secret (`01439769`).

## Scan `4061b1fe-80bb-4aac-bd75-13e07d75bd22` — commit `11d3b6f` (master after PR #30)
fix=0 · rotate=1 · ignore=8. The compact start-screen picker (PR #30) added no findings — `AgentPicker.kt` only changed sizing constants. Only ① remains.

## Scan `34b4dffc-f8f8-4e40-86c3-2f1d4f38b087` — commit `bb3da1e` (master after PR #32)
fix=0 · rotate=1 · ignore=8. The model-visibility change (PR #32) added no findings: `AgyModelService.getAvailableModels`, `AgyChatBackend.recomputeEnabledModels` and the `ChatScreen` picker wiring are clean. Only ① remains, re-reported under a fresh id (`9373013f`): the same embedded Google OAuth client secret, still waiting on the user's own OAuth client (see ① below).

## Scan `4cb6b4b0-9448-4cd8-8231-b0bc62304540` — commit `128ebe4` (master after PR #34)
fix=0 · rotate=1 · ignore=8. The model-naming change (PR #34) added no findings: `ModelSelectorBottomSheet` only groups by model key and sorts names. Only ① remains, re-reported as `39a92150` — same embedded Google OAuth client secret, unchanged.

---

## Previous scan `ef0761d7` — commit `a04af8c`
2 `fix` + 1 `rotate` + 7 `ignore` — identical set to `baf5ae11` (no drift). Superseded by the entries above.
