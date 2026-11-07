# Suricode findings — Davinci198/antigem

Scan run `39221847-f645-4d67-951d-098c5ea29fda` (branch `master`, commit `f084832`,
finished 2026-11-07 15:29 UTC): 14 findings — 4 to fix, 9 ignored, 0 rotate, 0 policy.

## Fixes

1. **[fix/code_change] Add size limits when restoring uploaded ZIP archives** — medium
   - File: `agy_ide_bridge/pkg/handlers/handlers.go` (RestoreConversationHandler)
   - Reason: uploaded archives were copied to disk and each ZIP entry extracted with
     unbounded `io.Copy`; a crafted archive could exhaust storage or resources.
   - Fix: cap the upload at 256 MiB (`http.MaxBytesReader` + `io.LimitReader`), cap each
     entry at 256 MiB and the total extraction at 1 GiB, reject archives with more than
     10 000 entries, and bound the `manifest.json` decode to 1 MiB. Over-limit requests
     answer `413` and the partially written entry is removed.

2. **[fix/config] Disable credential persistence in the release workflow checkout step** — medium
   - File: `.github/workflows/release.yml`
   - Reason: `actions/checkout@v4` persists the workflow token into local Git config;
     the job has `contents: write` and publishes releases.
   - Fix: `persist-credentials: false` on the checkout step (also applied in
     `build-standard.yml`, which restores the release keystore secret).

3. **[fix/config] Pin GitHub Actions to full commit SHAs** — high/medium
   - Files: `.github/workflows/release.yml`, `.github/workflows/build-standard.yml`
   - Reason: mutable tags (`@v4`, `@v5`, `@v2`) can be silently repointed upstream.
   - Fix: every `uses:` pinned to the commit SHA its tag currently resolves to:
     - `actions/checkout@11d5960a326750d5838078e36cf38b85af677262` (v4)
     - `actions/setup-java@cf277c60eb25467037889841efdb72551f06f6c3` (v4)
     - `actions/setup-go@40f1582b2485089dde7abd97c1529aa768e1baff` (v5)
     - `gradle/actions/setup-gradle@ed408507eac070d1f99cc633dbcf757c94c7933a` (v4)
     - `actions/upload-artifact@ea165f8d65b6e75b540449e92b4886f43607fa02` (v4)
     - `softprops/action-gh-release@3bb12739c298aeb8a4eeaf626c5b8d85266b0e65` (v2)

4. **[fix/code_change] Validate README URLs before fetching plugin documentation** — high
   - File: `app/src/main/java/com/example/gemini/ui/settings/CascadeMcpCatalogDialog.kt`
   - Reason: `plugin.readme` comes from remote catalog data and was fetched with
     `URL(readmeUrl).openConnection()` and no validation — SSRF / local-resource access.
   - Fix: README fetch restricted to `https` on public hosts (loopback, link-local,
     site-local, CGNAT, this-network and IPv6 unique-local addresses rejected), redirects
     followed manually with every hop re-validated (max 3), response capped at 2 MiB.

## Status

- Fixes implemented on branch `fix/suricode-findings`.
- A fix counts as confirmed only when a later scan stops reporting it; the scan runs on
  `master`, so confirmation requires these commits to land there.
