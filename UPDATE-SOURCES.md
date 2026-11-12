# Sursele de update — ce vine de la cine

Notat 2026-11-13 pentru build-ul `standard` local (1.0.19 / 119, semnătură
`85:E1:CF:70…`). Tot ce urmează e citit din cod, nu presupus.

## Pe scurt

| Componentă | De unde se ia | Verificare | Când se actualizează |
|---|---|---|---|
| Aplicația AntiGem (APK) | `santoshkurmi/antigem` (upstream) | semnătură APK | la 12 h, dar **nu se poate instala** (vezi mai jos) |
| Antigravity CLI (`agy`) | manifest Google (`…us-central1.run.app`) | `sha512` | o singură dată, la prima instalare |
| Claude Code | npm registry + `downloads.claude.ai` | `sha256` | o singură dată, la prima instalare |
| OpenCode | npm registry (`@opencode/cli-linux-arm64`) | **niciuna** | o singură dată, la prima instalare |
| Hermes | `hermes-agent.nousresearch.com/install.sh` | — | o singură dată (rulează installer-ul în guest) |
| Rootfs Ubuntu | release `rootfs-v1` pe GitHub (upstream) | — | doar la reinstalare |

---

## 1. Aplicația AntiGem (APK)

Cod: `data/updater/AppUpdateManager.kt:20` și `:104`,
`data/updater/UpdateCheckWorker.kt:84`.

- metadate: `https://raw.githubusercontent.com/santoshkurmi/antigem/master/version.json`
- APK: `https://github.com/santoshkurmi/antigem/releases/download/v<versiune>/antiGem-<flavor>-v<versiune>-release.apk`
- verificare la fiecare **12 h** (`PeriodicWorkRequestBuilder<UpdateCheckWorker>(12, HOURS)`), plus un `HEAD` pe URL înainte de a oferi update-ul.

Ambele URL-uri sunt hardcodate către **upstream**, nu către fork
(`Davinci198/antigem-0`).

### De ce update-ul oficial nu poate ajunge pe acest build

1. **Asset-ul lipsește.** Upstream e la `1.2.0` / `129`, dar release-ul
   `v1.2.0` conține doar `antiGem-termux-v1.2.0-release.apk` și
   `antiGem-termux-shared-v1.2.0-release.apk`. Nu există varianta `standard`,
   deci `HEAD`-ul primește 404 și aplicația nu afișează nimic.
2. **Semnătura diferă.** `AntiGemApplication.isOfficialRelease()` compară
   certificatul cu `CCF2C681063BA941641519E4EAC7F024E92880CCD06EED5070DAA9717380F315`
   (cheia oficială upstream), iar `app/release.jks` (alias `antigem`) are
   `85:E1:CF:70:EA:08:4D:1C:5C:48:81:17:1A:00:E1:57:34:19:DB:45:63:32:13:4A:AA:E8:F0:98:49:82:BD:30`.
   Android refuză instalarea unui APK cu altă semnătură peste cel existent.

Consecință: **update-ul oficial de aplicație e deja imposibil pentru fork**,
indiferent ce modificăm în cod.

### Dacă vrem update-uri ale fork-ului

Ar fi nevoie de două schimbări, ambele intenționate:
1. CI să publice un GitHub Release pe `Davinci198/antigem-0` (acum doar
   încarcă artefacte, `.github/workflows/build-standard.yml`).
2. `DEFAULT_VERSION_URL` + baza URL-ului de download din `AppUpdateManager`
   să indică fork-ul.

Fără ele, updater-ul rămâne pe upstream și nu oferă nimic.

---

## 2. Antigravity CLI (`agy`)

Cod: `data/local/AgentProvisioner.kt:51-52` și `installAgy()` (`:168-216`).

- manifest: `https://antigravity-cli-auto-updater-974169037036.us-central1.run.app/manifests/linux_arm64.json`
  - la data notării: `version = 1.5.7`
- arhivă: `https://storage.googleapis.com/antigravity-public/antigravity-cli/1.5.7-<build>/linux-arm/cli_linux_arm64.tar.gz`
- `sha512` din manifest e verificat în proot cu `sha512sum -c` **înainte** de
  `install -m 0755`, apoi rulează `agy --version`.

### Când se actualizează

`installAgy()` iese imediat cu `"already installed"` dacă binarul există **și**
markerul `.antigem-agy-version` nu e gol. `provisionInBackground` e apelat doar
ne-forțat (`LocalServerManager.kt:354`), iar `provisionAll` doar la instalarea
rootfs (`LocalEnvironmentManager.kt:1753`). Deci:

- aplicația **nu** mai verifică versiunea `agy` după prima instalare;
- o versiune nouă de la Google se ia doar dacă ștergi binarul/markerul sau
  reinstalezi rootfs;
- e posibil ca CLI-ul însuși să se autoactualizeze (serviciul se numește
  „auto-updater"), dar asta nu se vede din codul aplicației.

Sursa fiind a Google, modificările din aplicație nu o pot „strica".

---

## 3. Claude Code

Cod: `AgentProvisioner.kt:53-54` (`CLAUDE_REGISTRY_URL`,
`CLAUDE_RELEASE_BASE`) și `installClaude()` (`:243-281`).

- versiune: `https://registry.npmjs.org/@anthropic-ai/claude-code/latest`
  (la data notării `2.1.323`)
- manifest: `https://downloads.claude.ai/claude-code-releases/<versiune>/manifest.json`
- binar: `https://downloads.claude.ai/claude-code-releases/<versiune>/linux-arm64/claude`
- `checksum` (sha256) din manifest, verificat în proot; apoi `claude --version`.

Înainte de descărcare rulează `ensureGuestLibs()` (apt în guest), fiindcă
binarul are nevoie de biblioteci.

Actualizare: tot o singură dată, prin markerul `.antigem-claude-version`.

---

## 4. OpenCode

Cod: `AgentProvisioner.kt:55` (`OPENCODE_PACKAGE_NAME`) și
`installOpencode()` (`:283-335`).

- versiune + tarball: `https://registry.npmjs.org/@opencode/cli-linux-arm64/latest`
  (la data notării `2.2.7`; tarball-ul vine din `dist.tarball`, tot pe
  `registry.npmjs.org`)
- se extrage `package/bin/opencode` și se instalează în `/root/.opencode/bin/opencode`.

**Atenție:** spre deosebire de `agy` și Claude Code, aici **nu se verifică niciun
checksum** — se instalează ce vine din tarball. Dacă vrem hardening, e un
candidat clar (npm publică `dist.integrity`, s-ar putea verifica).

Actualizare: tot o singură dată, prin markerul `.antigem-opencode-version`.

---

## 5. Hermes (pentru completitudine)

Cod: `AgentProvisioner.kt:56` și `installHermes()`.

- rulează installer-ul oficial din `https://hermes-agent.nousresearch.com/install.sh`
  **în interiorul guest-ului**, cu `apt-get` pentru git/curl/python/build tools
  și `UV_LINK_MODE=copy` (proot pe f2fs nu suportă hardlink).
- marker: `.antigem-hermes-version`.

Aici rețeaua e a guest-ului, nu a Android-ului, deci e singurul agent care
depinde de DNS-ul din proot.

---

## Ce înseamnă pentru modificările din aplicație

- Cele cinci fix-uri discutate (backup rules, log-uri de token, etc.) sunt în
  codul aplicației și **nu ating niciun URL de update și nicio cheie**.
- Update-ul oficial de aplicație era deja imposibil (asset lipsă + semnătură).
- Update-urile de `agy` / Claude / OpenCode vin din sursele lor oficiale și sunt
  citite de codul actual; ele nu depind de modificările noastre.
- Singura schimbare care ar opri update-urile ar fi schimbarea intenționată a
  URL-urilor de mai sus (sau pierderea cheii de semnare).

## Verificare rapidă a surselor

```bash
# manifest agy
python3 -c "import json,urllib.request;d=json.load(urllib.request.urlopen('https://antigravity-cli-auto-updater-974169037036.us-central1.run.app/manifests/linux_arm64.json'));print(d['version'],d['url'])"

# versiuni Claude / OpenCode
python3 -c "import json,urllib.request;print(json.load(urllib.request.urlopen('https://registry.npmjs.org/@anthropic-ai/claude-code/latest'))['version'])"
python3 -c "import json,urllib.request;print(json.load(urllib.request.urlopen('https://registry.npmjs.org/@opencode/cli-linux-arm64/latest'))['version'])"

# ce versiune de app oferă upstream
gh api repos/santoshkurmi/antigem/contents/version.json -H "Accept: application/vnd.github.raw" | head -3

# semnătura build-ului local
bash ~/Android/build-tools/36.1.0/apksigner verify --print-certs \
  app/build/outputs/apk/standard/release/antiGem-standard-release.apk | grep SHA-256
```
