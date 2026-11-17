package hermes

import (
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"testing"
)

// these tests exercise the marketplace routes end to end with a stub in place of the hermes CLI:
// routing, filtering, the category chips and the name validation are all ours, and the real CLI's
// answers are already covered by its own output shape (asserted below in the fixtures).

const stubCatalog = `{"query":"","results":[
 {"name":"zeta","title":"Zeta","description":"a memory tool","category":"memory","maintainer":"someone","version":"1.0.0"},
 {"name":"alpha","title":"Alpha","description":"a testing tool","category":"tools","maintainer":"someone","version":"2.0.0"},
 {"name":"beta","title":"Beta","description":"another memory tool","category":"memory","maintainer":"someone","version":"1.0.0"}
]}`

// newStubManager returns a Manager whose CLI is a shell script printing out and recording its argv.
func newStubManager(t *testing.T, out string) (*Manager, string) {
	t.Helper()
	dir := t.TempDir()
	log := filepath.Join(dir, "argv.log")
	script := filepath.Join(dir, "hermes")
	body := "#!/bin/sh\nprintf '%s\\n' \"$*\" >> " + log + "\ncat <<'EOF'\n" + out + "\nEOF\n"
	if err := os.WriteFile(script, []byte(body), 0o755); err != nil {
		t.Fatalf("write stub: %v", err)
	}
	// The catalog is cached for the whole process: a test must not see another test's answer.
	catalogMu.Lock()
	catalogCache = pluginCatalogCache{}
	catalogMu.Unlock()
	return &Manager{hermesBin: script, homeDir: dir}, log
}

func doGET(t *testing.T, m *Manager, target string) *httptest.ResponseRecorder {
	t.Helper()
	rec := httptest.NewRecorder()
	m.ServeHTTP(rec, httptest.NewRequest(http.MethodGet, target, nil))
	return rec
}

func doPOST(t *testing.T, m *Manager, target, body string) *httptest.ResponseRecorder {
	t.Helper()
	rec := httptest.NewRecorder()
	m.ServeHTTP(rec, httptest.NewRequest(http.MethodPost, target, strings.NewReader(body)))
	return rec
}

// The whole catalog comes back once, with the chips computed from every entry, not from the page.
func TestPluginCatalogRoutesAndChips(t *testing.T) {
	m, _ := newStubManager(t, stubCatalog)
	rec := doGET(t, m, "/api/hermes/plugins/catalog")
	if rec.Code != http.StatusOK {
		t.Fatalf("status = %d, body = %s", rec.Code, rec.Body.String())
	}
	var page struct {
		Entries    []pluginEntry    `json:"entries"`
		Matched    int              `json:"matched"`
		Categories []pluginCategory `json:"categories"`
	}
	if err := json.Unmarshal(rec.Body.Bytes(), &page); err != nil {
		t.Fatalf("decode: %v", err)
	}
	if len(page.Entries) != 3 || page.Matched != 3 {
		t.Fatalf("entries = %d, matched = %d, want 3/3", len(page.Entries), page.Matched)
	}
	// Sorted by name so the screen's list order is stable across refreshes.
	if names := []string{page.Entries[0].Name, page.Entries[1].Name, page.Entries[2].Name}; names[0] != "alpha" || names[2] != "zeta" {
		t.Errorf("order = %v, want alphabetical", names)
	}
	// memory (2) must lead: the chips are ordered by how much they hold.
	if len(page.Categories) != 2 || page.Categories[0].Name != "memory" || page.Categories[0].Count != 2 {
		t.Errorf("categories = %+v, want memory first with 2", page.Categories)
	}
}

func TestPluginCatalogFilters(t *testing.T) {
	m, _ := newStubManager(t, stubCatalog)

	rec := doGET(t, m, "/api/hermes/plugins/catalog?q=TESTING")
	if rec.Code != http.StatusOK {
		t.Fatalf("status = %d", rec.Code)
	}
	var page struct {
		Entries []pluginEntry `json:"entries"`
		Matched int           `json:"matched"`
	}
	_ = json.Unmarshal(rec.Body.Bytes(), &page)
	if page.Matched != 1 || len(page.Entries) != 1 || page.Entries[0].Name != "alpha" {
		t.Errorf("query filter = %+v, want only alpha", page.Entries)
	}

	rec = doGET(t, m, "/api/hermes/plugins/catalog?category=memory")
	page.Entries, page.Matched = nil, 0
	_ = json.Unmarshal(rec.Body.Bytes(), &page)
	if page.Matched != 2 {
		t.Errorf("category filter matched %d, want 2", page.Matched)
	}
}

// An installed plugin is enabled or disabled through the CLI, and the verb follows the boolean.
func TestPluginToggleUsesTheRightVerb(t *testing.T) {
	m, log := newStubManager(t, `{"success":true}`)

	rec := doPOST(t, m, "/api/hermes/plugins/toggle", `{"name":"memory-wiki","enabled":true}`)
	if rec.Code != http.StatusOK {
		t.Fatalf("status = %d, body = %s", rec.Code, rec.Body.String())
	}
	rec = doPOST(t, m, "/api/hermes/plugins/toggle", `{"name":"memory-wiki","enabled":false}`)
	if rec.Code != http.StatusOK {
		t.Fatalf("status = %d", rec.Code)
	}

	calls, err := os.ReadFile(log)
	if err != nil {
		t.Fatalf("stub log: %v", err)
	}
	if got := strings.Split(strings.TrimSpace(string(calls)), "\n"); len(got) != 2 ||
		got[0] != "plugins enable memory-wiki" || got[1] != "plugins disable memory-wiki" {
		t.Errorf("CLI calls = %q", got)
	}
}

// A name from the app is an argv element: anything shell-shaped or path-shaped never reaches the CLI.
func TestPluginActionsRejectUnsafeNames(t *testing.T) {
	m, log := newStubManager(t, `{"success":true}`)

	for _, body := range []string{
		`{"name":"../../etc/passwd"}`,
		`{"name":"x; rm -rf /"}`,
		`{"name":""}`,
		`{"name":"-flag"}`,
	} {
		if rec := doPOST(t, m, "/api/hermes/plugins/install", body); rec.Code != http.StatusBadRequest {
			t.Errorf("%s: status = %d, want 400", body, rec.Code)
		}
	}
	if _, err := os.Stat(log); err == nil {
		t.Error("the CLI ran for a rejected name")
	}
}

// A missing name in the toggle body is a client bug, not a silent disable of everything.
func TestPluginToggleRequiresEnabled(t *testing.T) {
	m, _ := newStubManager(t, `{"success":true}`)
	if rec := doPOST(t, m, "/api/hermes/plugins/toggle", `{"name":"memory-wiki"}`); rec.Code != http.StatusBadRequest {
		t.Errorf("status = %d, want 400", rec.Code)
	}
}

// The catalog is fetched once and then served from memory: the screen queries it per keystroke.
func TestPluginCatalogIsCached(t *testing.T) {
	m, log := newStubManager(t, stubCatalog)
	doGET(t, m, "/api/hermes/plugins/catalog")
	doGET(t, m, "/api/hermes/plugins/catalog?q=zeta")
	doGET(t, m, "/api/hermes/plugins/catalog?category=memory")

	calls, err := os.ReadFile(log)
	if err != nil {
		t.Fatalf("stub log: %v", err)
	}
	if n := len(strings.Split(strings.TrimSpace(string(calls)), "\n")); n != 1 {
		t.Errorf("CLI ran %d times, want once", n)
	}
}

// Skills have no JSON list, so the installed tab is read from the skills tree.
func TestInstalledSkillsWalk(t *testing.T) {
	m, _ := newStubManager(t, "")
	skillDir := filepath.Join(m.homeDir, ".hermes", "skills", "productivity", "notes")
	if err := os.MkdirAll(skillDir, 0o755); err != nil {
		t.Fatalf("mkdir: %v", err)
	}
	content := "---\nname: notes\ndescription: Take notes\n---\n"
	if err := os.WriteFile(filepath.Join(skillDir, "SKILL.md"), []byte(content), 0o644); err != nil {
		t.Fatalf("write: %v", err)
	}
	// Dot directories (locks, caches) are not skills and must not appear.
	if err := os.MkdirAll(filepath.Join(m.homeDir, ".hermes", "skills", ".locks"), 0o755); err != nil {
		t.Fatalf("mkdir: %v", err)
	}

	rec := doGET(t, m, "/api/hermes/skills/installed")
	if rec.Code != http.StatusOK {
		t.Fatalf("status = %d", rec.Code)
	}
	var payload struct {
		Skills []skillEntry `json:"skills"`
		Count  int          `json:"count"`
	}
	if err := json.Unmarshal(rec.Body.Bytes(), &payload); err != nil {
		t.Fatalf("decode: %v", err)
	}
	if payload.Count != 1 || len(payload.Skills) != 1 {
		t.Fatalf("skills = %+v, want exactly one", payload.Skills)
	}
	if payload.Skills[0].Name != "notes" || payload.Skills[0].Description != "Take notes" {
		t.Errorf("skill = %+v", payload.Skills[0])
	}
}

// Browsing for an empty term would only return the registry's noise; the CLI requires a query anyway.
func TestSkillSearchNeedsAQuery(t *testing.T) {
	m, _ := newStubManager(t, "[]")
	if rec := doGET(t, m, "/api/hermes/skills/search"); rec.Code != http.StatusBadRequest {
		t.Errorf("status = %d, want 400", rec.Code)
	}
	if rec := doGET(t, m, "/api/hermes/skills/search?q=memory&source=..%2F..%2Fetc"); rec.Code != http.StatusBadRequest {
		t.Errorf("invalid source: status = %d, want 400", rec.Code)
	}
}

// Unknown endpoints under our prefixes answer 404 instead of falling into the session router.
func TestMarketplaceUnknownRoutes(t *testing.T) {
	m, _ := newStubManager(t, "")
	for _, target := range []string{"/api/hermes/plugins/nope", "/api/hermes/skills/nope"} {
		if rec := doGET(t, m, target); rec.Code != http.StatusNotFound {
			t.Errorf("%s: status = %d, want 404", target, rec.Code)
		}
	}
}
