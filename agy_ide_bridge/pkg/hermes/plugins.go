package hermes

import (
	"context"
	"encoding/json"
	"io/fs"
	"net/http"
	"os"
	"os/exec"
	"path/filepath"
	"regexp"
	"sort"
	"strings"
	"sync"
	"time"
	"unicode/utf8"
)

// Marketplace bridges: the app shows the same catalogs the `hermes` CLI already knows, so no
// scraping of the website is involved. Everything runs through the CLI with the manager's own
// binary and HOME, exactly like the gateway and the sessions do.

const (
	// The curated plugin catalog is large (over a thousand entries, a couple of megabytes), so it is
	// fetched once and reused; the screen filters it per keystroke, which must not hit the CLI.
	pluginCatalogTTL = 10 * time.Minute
	// How many catalog entries a query returns. The screen shows a scrollable list, not the whole thing.
	pluginCatalogLimit = 200
	// Read-only CLI calls (list, search, check-updates) are quick on paper but pay Python's start-up
	// and git's checks inside proot: measured 6–16 s even on the host, so a tight timeout would turn
	// a slow phone into a marketplace that looks broken.
	hermesReadTimeout = 90 * time.Second
	// Installing a plugin clones a repository: this is a slow, user-initiated step.
	hermesInstallTimeout = 4 * time.Minute
	// Catalog descriptions run to a thousand characters each; the card shows four lines, and two
	// hundred of them per answer would be a megabyte over the app's socket.
	pluginDescriptionLimit = 500
	// A marketplace name arrives from the app and is used as an argv element, never through a shell.
	pluginNameRe = `^[A-Za-z0-9][A-Za-z0-9._/@-]{0,119}$`
)

var pluginNamePattern = regexp.MustCompile(pluginNameRe)

// pluginEntry is one catalog row, trimmed to what the screen shows (the CLI also returns readmes,
// screenshots and other fields that would multiply the payload).
type pluginEntry struct {
	Name           string   `json:"name"`
	Title          string   `json:"title,omitempty"`
	Description    string   `json:"description,omitempty"`
	Category       string   `json:"category,omitempty"`
	Maintainer     string   `json:"maintainer,omitempty"`
	Version        string   `json:"version,omitempty"`
	Tier           string   `json:"tier,omitempty"`
	Repo           string   `json:"repo,omitempty"`
	Capabilities   []string `json:"capabilities,omitempty"`
	RequiresHermes string   `json:"requires_hermes,omitempty"`
}

// skillEntry is an installed skill, read from the skills tree on disk.
type skillEntry struct {
	Name        string `json:"name"`
	Description string `json:"description,omitempty"`
	Category    string `json:"category,omitempty"`
	Path        string `json:"path"`
}

// pluginCategory is one filter chip: the catalog's own category name and how many entries it holds.
type pluginCategory struct {
	Name  string `json:"name"`
	Count int    `json:"count"`
}

type pluginCatalogCache struct {
	at         time.Time
	entries    []pluginEntry
	categories []pluginCategory
}

// One catalog per bridge process; it does not depend on a chat or a session.
var (
	catalogMu    sync.Mutex
	catalogCache pluginCatalogCache
)

// handlePlugins serves /api/hermes/plugins/{catalog,installed,updates,install,remove,toggle,update}.
func (m *Manager) handlePlugins(w http.ResponseWriter, r *http.Request, sub string) {
	switch {
	case sub == "catalog" && r.Method == http.MethodGet:
		m.handlePluginCatalog(w, r)
	case sub == "installed" && r.Method == http.MethodGet:
		m.runHermesJSON(w, hermesReadTimeout, "plugins", "list", "--json")
	case sub == "updates" && r.Method == http.MethodGet:
		m.runHermesJSON(w, hermesReadTimeout, "plugins", "check-updates", "--json")
	case sub == "install" && r.Method == http.MethodPost:
		m.handlePluginAction(w, r, hermesInstallTimeout, "plugins", "install")
	case sub == "remove" && r.Method == http.MethodPost:
		m.handlePluginAction(w, r, hermesReadTimeout, "plugins", "remove")
	case sub == "update" && r.Method == http.MethodPost:
		m.handlePluginAction(w, r, hermesInstallTimeout, "plugins", "update")
	case sub == "toggle" && r.Method == http.MethodPost:
		m.handlePluginToggle(w, r)
	default:
		writeJSON(w, http.StatusNotFound, obj{"error": "unknown plugin endpoint"})
	}
}

// handlePluginCatalog returns the curated catalog, filtered by q (name/title/description) and category.
func (m *Manager) handlePluginCatalog(w http.ResponseWriter, r *http.Request) {
	entries, categories, err := m.pluginCatalog()
	if err != nil {
		writeJSON(w, http.StatusBadGateway, obj{"error": err.Error()})
		return
	}
	query := strings.ToLower(strings.TrimSpace(r.URL.Query().Get("q")))
	category := strings.ToLower(strings.TrimSpace(r.URL.Query().Get("category")))
	out := make([]pluginEntry, 0, pluginCatalogLimit)
	matched := 0
	for _, e := range entries {
		if category != "" && !strings.EqualFold(e.Category, category) {
			continue
		}
		if query != "" && !strings.Contains(strings.ToLower(e.Name+" "+e.Title+" "+e.Description), query) {
			continue
		}
		matched++
		if len(out) < pluginCatalogLimit {
			out = append(out, e)
		}
	}
	writeJSON(w, http.StatusOK, obj{
		"entries":    out,
		"matched":    matched,
		"truncated":  matched > len(out),
		"categories": categories,
	})
}

// pluginCatalog fetches `hermes plugins search --json` once and caches the trimmed rows.
func (m *Manager) pluginCatalog() ([]pluginEntry, []pluginCategory, error) {
	catalogMu.Lock()
	defer catalogMu.Unlock()
	if catalogCache.entries != nil && time.Since(catalogCache.at) < pluginCatalogTTL {
		return catalogCache.entries, catalogCache.categories, nil
	}
	raw, err := m.runHermes(hermesReadTimeout, "plugins", "search", "--json")
	if err != nil {
		return nil, nil, err
	}
	var doc struct {
		Results []pluginEntry `json:"results"`
	}
	if err := json.Unmarshal([]byte(raw), &doc); err != nil {
		return nil, nil, err
	}
	entries := doc.Results
	for i := range entries {
		entries[i].Description = clip(entries[i].Description, pluginDescriptionLimit)
	}
	sort.Slice(entries, func(i, j int) bool { return entries[i].Name < entries[j].Name })

	// The chips come from the whole catalog, not from the filtered page: a category must stay
	// clickable even when the current query happens to match nothing in it.
	counts := map[string]int{}
	for _, e := range entries {
		if e.Category != "" {
			counts[e.Category]++
		}
	}
	categories := make([]pluginCategory, 0, len(counts))
	for name, count := range counts {
		categories = append(categories, pluginCategory{Name: name, Count: count})
	}
	sort.Slice(categories, func(i, j int) bool { return categories[i].Count > categories[j].Count })

	catalogCache = pluginCatalogCache{at: time.Now(), entries: entries, categories: categories}
	return entries, categories, nil
}

// handlePluginAction runs one mutating plugin command for the name in the body.
func (m *Manager) handlePluginAction(w http.ResponseWriter, r *http.Request, timeout time.Duration, args ...string) {
	name, ok := marketplaceName(w, r)
	if !ok {
		return
	}
	out, err := m.runHermes(timeout, append(args, name)...)
	if err != nil {
		writeJSON(w, http.StatusBadGateway, obj{"success": false, "error": err.Error(), "output": out})
		return
	}
	writeJSON(w, http.StatusOK, obj{"success": true, "output": out})
}

// handlePluginToggle enables or disables an installed plugin.
func (m *Manager) handlePluginToggle(w http.ResponseWriter, r *http.Request) {
	var body struct {
		Name    string `json:"name"`
		Enabled *bool  `json:"enabled"`
	}
	if err := json.NewDecoder(r.Body).Decode(&body); err != nil {
		writeJSON(w, http.StatusBadRequest, obj{"error": err.Error()})
		return
	}
	if body.Enabled == nil {
		writeJSON(w, http.StatusBadRequest, obj{"error": "enabled is required"})
		return
	}
	if !pluginNamePattern.MatchString(strings.TrimSpace(body.Name)) {
		writeJSON(w, http.StatusBadRequest, obj{"error": "invalid plugin name"})
		return
	}
	verb := "disable"
	if *body.Enabled {
		verb = "enable"
	}
	out, err := m.runHermes(hermesReadTimeout, "plugins", verb, body.Name)
	if err != nil {
		writeJSON(w, http.StatusBadGateway, obj{"success": false, "error": err.Error(), "output": out})
		return
	}
	writeJSON(w, http.StatusOK, obj{"success": true, "output": out})
}

// handleSkills serves /api/hermes/skills/{search,installed,install,uninstall}.
func (m *Manager) handleSkills(w http.ResponseWriter, r *http.Request, sub string) {
	switch {
	case sub == "search" && r.Method == http.MethodGet:
		m.handleSkillSearch(w, r)
	case sub == "installed" && r.Method == http.MethodGet:
		m.handleSkillInstalled(w)
	case sub == "install" && r.Method == http.MethodPost:
		m.handleSkillAction(w, r, "install")
	case sub == "uninstall" && r.Method == http.MethodPost:
		m.handleSkillAction(w, r, "uninstall")
	default:
		writeJSON(w, http.StatusNotFound, obj{"error": "unknown skill endpoint"})
	}
}

// handleSkillSearch searches the skill registries; the CLI needs a query (browsing has no JSON output).
func (m *Manager) handleSkillSearch(w http.ResponseWriter, r *http.Request) {
	query := strings.TrimSpace(r.URL.Query().Get("q"))
	if query == "" {
		writeJSON(w, http.StatusBadRequest, obj{"error": "q is required"})
		return
	}
	args := []string{"skills", "search", query, "--json"}
	if source := strings.TrimSpace(r.URL.Query().Get("source")); source != "" {
		if !regexp.MustCompile(`^[a-z0-9-]{1,32}$`).MatchString(source) {
			writeJSON(w, http.StatusBadRequest, obj{"error": "invalid source"})
			return
		}
		args = append(args, "--source", source)
	}
	if limit := strings.TrimSpace(r.URL.Query().Get("limit")); limit != "" {
		if !regexp.MustCompile(`^[0-9]{1,3}$`).MatchString(limit) {
			writeJSON(w, http.StatusBadRequest, obj{"error": "invalid limit"})
			return
		}
		args = append(args, "--limit", limit)
	}
	m.runHermesJSON(w, hermesReadTimeout, args...)
}

// handleSkillInstalled lists the skills that are present on disk, with their frontmatter description.
func (m *Manager) handleSkillInstalled(w http.ResponseWriter) {
	skills := m.installedSkills()
	writeJSON(w, http.StatusOK, obj{"skills": skills, "count": len(skills)})
}

// handleSkillAction installs (identifier) or uninstalls (name) one skill.
func (m *Manager) handleSkillAction(w http.ResponseWriter, r *http.Request, verb string) {
	var body struct {
		Identifier string `json:"identifier"`
		Name       string `json:"name"`
	}
	if err := json.NewDecoder(r.Body).Decode(&body); err != nil {
		writeJSON(w, http.StatusBadRequest, obj{"error": err.Error()})
		return
	}
	// install takes a registry identifier (owner/skills/name or a URL), uninstall takes the skill name.
	target := strings.TrimSpace(body.Identifier)
	if verb == "uninstall" {
		target = strings.TrimSpace(body.Name)
	}
	if target == "" {
		writeJSON(w, http.StatusBadRequest, obj{"error": "target is required"})
		return
	}
	if !pluginNamePattern.MatchString(target) {
		writeJSON(w, http.StatusBadRequest, obj{"error": "invalid skill identifier"})
		return
	}
	args := []string{"skills", verb, target, "--yes"}
	out, err := m.runHermes(hermesInstallTimeout, args...)
	if err != nil {
		writeJSON(w, http.StatusBadGateway, obj{"success": false, "error": err.Error(), "output": out})
		return
	}
	writeJSON(w, http.StatusOK, obj{"success": true, "output": out})
}

// installedSkills walks ~/.hermes/skills and reads every SKILL.md it finds, whatever the nesting:
// categories group skills, but a bundle may also keep its own skills/ folder underneath.
func (m *Manager) installedSkills() []skillEntry {
	root := filepath.Join(m.homeDir, ".hermes", "skills")
	out := []skillEntry{}
	if !isDir(root) {
		return out
	}
	rootDepth := strings.Count(root, string(os.PathSeparator))
	_ = filepath.WalkDir(root, func(path string, entry fs.DirEntry, err error) error {
		if err != nil {
			return nil
		}
		// Locks, caches and manifests live in dot entries and are not skills.
		if path != root && strings.HasPrefix(entry.Name(), ".") {
			if entry.IsDir() {
				return fs.SkipDir
			}
			return nil
		}
		if entry.IsDir() {
			// Enough for category/group/bundle/skills/name, shallow enough to never crawl a whole tree.
			if strings.Count(path, string(os.PathSeparator))-rootDepth > 5 {
				return fs.SkipDir
			}
			return nil
		}
		if entry.Name() != "SKILL.md" {
			return nil
		}
		// The category is the first directory under skills/, when there is one.
		category := "local"
		if rel, relErr := filepath.Rel(root, path); relErr == nil {
			if parts := strings.Split(rel, string(os.PathSeparator)); len(parts) >= 3 {
				category = parts[0]
			}
		}
		if skill, ok := readSkill(path, category); ok {
			out = append(out, skill)
		}
		return nil
	})
	sort.Slice(out, func(i, j int) bool { return out[i].Name < out[j].Name })
	return out
}

// readSkill parses the tiny YAML frontmatter of a SKILL.md (name and description).
func readSkill(path, category string) (skillEntry, bool) {
	data, err := os.ReadFile(path)
	if err != nil {
		return skillEntry{}, false
	}
	text := string(data)
	skill := skillEntry{Name: filepath.Base(filepath.Dir(path)), Category: category, Path: path}
	if strings.HasPrefix(text, "---") {
		for _, line := range strings.Split(strings.TrimPrefix(text, "---"), "\n") {
			trimmed := strings.TrimSpace(line)
			if trimmed == "---" {
				break
			}
			key, value, found := strings.Cut(trimmed, ":")
			if !found {
				continue
			}
			value = strings.Trim(strings.TrimSpace(value), `"'`)
			switch strings.TrimSpace(key) {
			case "name":
				if value != "" {
					skill.Name = value
				}
			case "description":
				skill.Description = value
			}
		}
	}
	return skill, true
}

// isDir follows symlinks, so skills linked into the tree are listed too.
func isDir(path string) bool {
	info, err := os.Stat(path)
	return err == nil && info.IsDir()
}

// marketplaceName decodes a {name} body and rejects anything that is not a plain catalog name.
func marketplaceName(w http.ResponseWriter, r *http.Request) (string, bool) {
	var body struct {
		Name string `json:"name"`
	}
	if err := json.NewDecoder(r.Body).Decode(&body); err != nil {
		writeJSON(w, http.StatusBadRequest, obj{"error": err.Error()})
		return "", false
	}
	name := strings.TrimSpace(body.Name)
	if !pluginNamePattern.MatchString(name) {
		writeJSON(w, http.StatusBadRequest, obj{"error": "invalid name"})
		return "", false
	}
	return name, true
}

// runHermes runs the CLI with the manager's HOME and returns its combined output.
func (m *Manager) runHermes(timeout time.Duration, args ...string) (string, error) {
	ctx, cancel := context.WithTimeout(context.Background(), timeout)
	defer cancel()
	cmd := exec.CommandContext(ctx, m.hermesBin, args...)
	cmd.Env = append(os.Environ(), "HOME="+m.homeDir)
	out, err := cmd.CombinedOutput()
	text := stripANSI(string(out))
	if err != nil {
		if ctx.Err() == context.DeadlineExceeded {
			return text, context.DeadlineExceeded
		}
		if text == "" {
			text = err.Error()
		}
		return text, err
	}
	return text, nil
}

// runHermesJSON runs a CLI call whose stdout is JSON and passes it through, so the app parses it.
func (m *Manager) runHermesJSON(w http.ResponseWriter, timeout time.Duration, args ...string) {
	out, err := m.runHermes(timeout, args...)
	if err != nil {
		writeJSON(w, http.StatusBadGateway, obj{"error": err.Error(), "output": out})
		return
	}
	w.Header().Set("Content-Type", "application/json")
	_, _ = w.Write([]byte(out))
}

var ansiPattern = regexp.MustCompile(`\x1b\[[0-9;]*[A-Za-z]`)

// stripANSI removes terminal colouring from CLI output before it reaches the app.
func stripANSI(s string) string {
	return ansiPattern.ReplaceAllString(s, "")
}

// clip shortens s to at most limit bytes without splitting a rune, appending an ellipsis when cut.
func clip(s string, limit int) string {
	if len(s) <= limit {
		return s
	}
	cut := limit
	for cut > 0 && !utf8.RuneStart(s[cut]) {
		cut--
	}
	return strings.TrimSpace(s[:cut]) + "…"
}
