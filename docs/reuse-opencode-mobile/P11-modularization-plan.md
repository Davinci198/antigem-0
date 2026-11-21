# P11 — Modularizarea antigem (blueprint)

Status: **PLAN** (fără modificări de build; necesită CI).

## Sursă de model
OpenCode Mobile își impune arhitectura prin teste de calitate:
- `native/build-logic/convention/src/main/kotlin/oc/buildlogic/ModuleRules.kt` — verifică lanțul de
  dependențe `core → protocol → engine → ui → feature → android`.
- `native/MODULE_OWNERS` — ownership per modul (propose, don't edit).
- `native/ui/kit*` — regula "kit only" (feature-urile doar aranjează piese din kit).

## Ținta pentru antigem
- Azi: monolit `:app` (~3.500 LOC doar în data/local, ~1740 LOC în GitSourceControlView).
- Ținta: extrageri pure-logic fără UI — pas cu pas, fără refactor big-bang:
  1. `:core-security` → `data/security` (deja creat în P2/P3).
  2. `:data-schedules` → clasa `ScheduleStore` + alarme (P7) — autocontinut.
  3. `:data-git` → `GitApiClient` + `GitHubDeviceFlow` (P9) — depinde doar de OkHttp/JSON.
  4. Mai târziu: `:protocol-agents` (backends) vs `:ui-chat`.
- Reguli propuse (copiate din ModuleRules.kt): UI nu depinde de agent intern; backends nu depind
  de Compose; datele trec prin `Port`-uri.

## Criterii de acceptare
- `./gradlew :app:assembleDebug` (CI) rămâne verde după fiecare extrage.
- Niciun modul UI nou nu importă clase de rețea direct.
