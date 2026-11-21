# P12 — Spike: AI Team engine (Rust) — fezabilitate

Status: **SPIKE REPORT** (fără integrare; necesită test pe dispozitiv).

## Ce există în OpenCode Mobile
- `engine/` — motor Rust „AI Team" (orchestrare multi-agent).
- Binare arm64 incluse ca jniLibs (native/android/linux/src/main/jniLibs/arm64-v8a/):
  `libaiteam_engine.so`, `libaiteam_sandbox.so`, `libaiteam_boundary_probe.so`.
- Host front în `tool/host/`; BYO host în `scripts/byo-host/`.

## Fezabilitate
- **Licență:** repo-ul e MIT; codul Rust e reutilizabil în GPLv3, cu păstrarea notelor de copyright.
- **Packaging:** `.so`-urile se pot include în APK-ul antigem (arm64-only, deja cazul) și se pot
  încărca cu `System.loadLibrary`.
- **Risc mare:** engine-ul comunică pe un contract propriu (host/gateway) cu cerințe de mediu
  (sandbox probing); fără documentație de API stabilă, integrarea e experimentală.
- **Alternativă mai ieftină:** folosim doar pattern-ul de orchestrare (multi-agent cu approval de
  la user) pe backends-urile existente — fără runtime Rust.

## Pași recomandați (spike, ~3 zile)
1. Eșantion: pune `.so`-urile în `app/src/main/jniLibs/arm64-v8a/` și verifică `System.loadLibrary` pe telefon (APK de test).
2. Rulează boundary probe standalone în guest (proot) pentru a vedea dacă sandbox-ul funcționează fără kernel special.
3. Decide go/no-go pe baza a două întrebări: (a) se încarcă pe arm64 proot?, (b) API-ul host e stabil?
