# P10 — ACP/Paseo protocol: port plan

Status: **PLAN / SCAFFOLD** (nu s-a scris cod compilat; necesita build CI + decizii).

## Obiectiv
Aducerea agenților ACP (Kilo, Cline, Goose, Qwen Code, Gemini CLI, Oh My Pi, fx) prin clientul
Paseo din OpenCode Mobile (MIT) — aceiași agenți pe care antigem îi susține azi doar prin
Antigravity/Claude/OpenCode/Hermes.

## Referință sursă
- Client Paseo (KMP pur, ~22.7k LOC): `~/dev/opencode-mobile-next/native/protocol/paseo/`
  (`gateway/`, `transport/`, `feed/`, `requests/`, `mappers/`, `work/`, `usage/`).
- Catalog de pins (versiuni + SHA) copiat deja ca asset de referință:
  `app/src/main/assets/reference/agent-pins.json` (schema 1; Paseo 0.15.4, Node v24.22.2,
  Claude Code testat 2.1.283–2.1.325; rute: `paseoNative` = Claude Code/Codex,
  `acpPaseo` = Antigravity/Gemini/Qwen/Kilo/Cline/Goose/Oh My Pi/fx).

## Dependențe de portat
1. `core/common` (Outcome, AppDispatchers, Clock, IDs) → modele locale antigem sau adaptoare mici.
2. `core/net` (Ktor) → OkHttp (deja în proiect) pentru transportul de rețea.
3. `core/security` (ExternalLinkPolicy) → deja portat în P2 (`data/security`).
4. `engine/requests` (permission/ask/forms) → deja există model de aprobări în chat (P6).

## Pași recomandați (când există build CI)
1. Copy `protocol/paseo/src/commonMain` → `app/src/main/java/com/example/gemini/data/agent/acp/`.
2. Adaptează importurile `io.github.eslamasabry.oc.core.*` → `com.example.gemini.data.*`.
3. Înlocuiește Ktor cu OkHttp în `transport/`; păstrează socket/kqueue în spatele unei interfețe.
4. Implementează `AgentProvisioner` recipes pentru agenții ACP folosind `agent-pins.json`
   (descărcare arm64 + verificare sha256, ca la P1).
5. Backend nou `AcpChatBackend` pe modelul `ClaudeChatBackend` (stream NDJSON + permissions).
6. UI: extinde `AgentsSetupUi` + picker de model cu lista ACP.

## Criterii de acceptare
- Cel puțin Kilo și Cline rulează end-to-end pe telefon (RPC + tool calls).
- Fiecare agent nou verificat cu sha256 din pins.
- Teste unitare pentru parserul de evenimente Paseo (feed/mappers).
