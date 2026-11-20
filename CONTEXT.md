# CONTEXT.md — context comun pentru toți agenții

Acest fișier este sursa comună de context și prompt pentru toți agenții
care lucrează în acest repo (Claude Code, OpenCode, Hermes, agy).
Este referit din `AGENTS.md` și este citit la începutul oricărei sesiuni.

## Rolul proiectului
Antigem este o aplicație Android (bridge) care orchestrează mai mulți agenți
de codare (Claude Code, OpenCode, Hermes) printr-un hub local și un bridge Go.

## Reguli comune de lucru
- **Nu construi release-uri** (doar build Standard/Termux, la cerere).
- **Nu rula `gradle install` / `pkg install`** decât la cerere explicită.
- **Nu face git push/pull/commit/stash** fără acord explicit (git ops doar când cere utilizatorul).
- Schimbările se fac pe **branch nou**, niciodată direct pe `master` sau pe branchuri deja mergeate.
- Dacă nu ești 100% sigur de ceva, **întreabă** înainte de a modifica.
- Explică întotdeauna înainte de a edita; nu modifica în tăcere.

## Structură
- `workspace/` — directorul comun de lucru al agenților.
- `tmp/` — fișiere temporare comune (gitignored), e.g. artifacte intermediare, scratch.
- `~/dev/tmp` — directorul global temporar partajat între proiecte.
- `agy_ide_bridge/` — bridge-ul Go (backend).
- `app/` — aplicația Android (Kotlin/Compose).

## Conținut
- Folosește `workspace/` pentru munca efectivă.
- Pune fișierele temporare în `tmp/` (local) sau `~/dev/tmp` (global), nu în `~`/`/tmp`.
- Păstrează `CONTEXT.md` și `AGENTS.md` sincronizate când se schimbă regulile.
