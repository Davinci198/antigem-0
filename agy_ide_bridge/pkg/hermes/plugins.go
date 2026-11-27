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
// screenshots, capabilities and other fields that would multiply the payload). Only string fields
// belong here: `capabilities` is a nested object in the real catalog, and one wrong type makes the
// whole decode fail — the endpoint answered 502 until this field was dropped.
type pluginEntry struct {
	Name           string `json:"name"`
	Title          string `json:"title,omitempty"`
	Description    string `json:"description,omitempty"`
	Category       string `json:"category,omitempty"`
	Maintainer     string `json:"maintainer,omitempty"`
	Version        string `json:"version,omitempty"`
	Tier           string `json:"tier,omitempty"`
	Repo           string `json:"repo,omitempty"`
	RequiresHermes string `json:"requires_hermes,omitempty"`
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
		m.handlePluginInstalled(w)
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

// installedPlugin is one row of `hermes plugins list --json`. The CLI omits the key it resolves
// enable/disable by, and names are not unique (the bundled image and video backends are both
// "deepinfra"), so the bridge adds the key: it is what lets the app act on exactly one of them.
type installedPlugin struct {
	Name        string          `json:"name"`
	Status      string          `json:"status,omitempty"`
	Version     string          `json:"version,omitempty"`
	Description string          `json:"description,omitempty"`
	Source      string          `json:"source,omitempty"`
	Removed     json.RawMessage `json:"removed,omitempty"`
	Key         string          `json:"key,omitempty"`
}

// pluginKey is one plugin the CLI's discovery sees: the manifest name shown in the list, and the
// key the mutating verbs resolve (the directory path for nested plugins, the name at top level).
type pluginKey struct {
	Name string
	Key  string
}

// Categories whose plugins load through dedicated paths; the CLI leaves them out of `plugins list`.
var bundledPluginSkipDirs = map[string]bool{
	"memory": true, "context_engine": true, "computer_use": true, "model-providers": true,
	"config_backends": true,
}

// handlePluginInstalled lists installed plugins and adds the canonical key to each row.
func (m *Manager) handlePluginInstalled(w http.ResponseWriter) {
	out, err := m.runHermes(hermesReadTimeout, "plugins", "list", "--json")
	if err != nil {
		writeJSON(w, http.StatusBadGateway, obj{"error": err.Error(), "output": out})
		return
	}
	var rows []installedPlugin
	if err := json.Unmarshal([]byte(out), &rows); err != nil {
		writeJSON(w, http.StatusBadGateway, obj{"error": "cannot read the plugin list: " + err.Error(), "output": out})
		return
	}
	attachPluginKeys(rows, m.discoverPluginKeys())
	writeJSON(w, http.StatusOK, rows)
}

// discoverPluginKeys mirrors the CLI's discovery order (bundled tree first, then user plugins),
// because `plugins list --json` prints the rows in that order and drops the key. Pairing by
// position keeps two same-named plugins apart, which is exactly what the app needs.
func (m *Manager) discoverPluginKeys() []pluginKey {
	bundled := strings.TrimSpace(os.Getenv("HERMES_BUNDLED_PLUGINS"))
	if bundled == "" {
		bundled = filepath.Join(m.hermesHome(), "hermes-agent", "plugins")
	}
	var out []pluginKey
	seen := map[string]int{}
	scanPluginLevel(bundled, "bundled", "", 0, bundledPluginSkipDirs, seen, &out)
	scanPluginLevel(filepath.Join(m.hermesHome(), "plugins"), "user", "", 0, nil, seen, &out)
	return out
}

func (m *Manager) hermesHome() string {
	if v := strings.TrimSpace(os.Getenv("HERMES_HOME")); v != "" {
		return v
	}
	return filepath.Join(m.homeDir, ".hermes")
}

// scanPluginLevel follows the CLI's scan: a directory with a manifest is a plugin (top-level ones
// are keyed by manifest name, nested ones by their path), and a directory without one is a category
// holding plugins, scanned exactly one level deeper.
func scanPluginLevel(base, source, prefix string, depth int, skip map[string]bool, seen map[string]int, out *[]pluginKey) {
	entries, err := os.ReadDir(base)
	if err != nil {
		return
	}
	for _, entry := range entries {
		if depth == 0 && skip[entry.Name()] {
			continue
		}
		dir := filepath.Join(base, entry.Name())
		if info, err := os.Stat(dir); err != nil || !info.IsDir() {
			continue
		}
		name, ok := readPluginManifestName(dir)
		if !ok {
			if depth == 0 {
				next := entry.Name()
				if prefix != "" {
					next = prefix + "/" + entry.Name()
				}
				scanPluginLevel(dir, source, next, 1, nil, seen, out)
			}
			continue
		}
		key := name
		if prefix != "" {
			key = prefix + "/" + entry.Name()
		}
		if index, duplicate := seen[key]; duplicate {
			// The CLI keeps the first bundled plugin for a key and lets a user plugin replace it.
			if source == "bundled" {
				continue
			}
			(*out)[index] = pluginKey{Name: name, Key: key}
			continue
		}
		seen[key] = len(*out)
		*out = append(*out, pluginKey{Name: name, Key: key})
	}
}

// readPluginManifestName returns the name the CLI would list for dir, and whether dir is a plugin at
// all: plugin.yaml (or .yml) wins, then a portable plugin.json. An unreadable manifest degrades to
// the directory name, like the CLI does.
func readPluginManifestName(dir string) (string, bool) {
	for _, file := range []string{"plugin.yaml", "plugin.yml"} {
		path := filepath.Join(dir, file)
		info, err := os.Stat(path)
		if err != nil || info.IsDir() {
			continue
		}
		if data, err := os.ReadFile(path); err == nil {
			if name := yamlTopLevelValue(string(data), "name"); name != "" {
				return name, true
			}
		}
		return filepath.Base(dir), true
	}
	if _, err := os.Lstat(filepath.Join(dir, "plugin.json")); err == nil {
		if data, err := os.ReadFile(filepath.Join(dir, "plugin.json")); err == nil {
			var doc struct {
				Name string `json:"name"`
			}
			if json.Unmarshal(data, &doc) == nil && strings.TrimSpace(doc.Name) != "" {
				return strings.TrimSpace(doc.Name), true
			}
		}
		return filepath.Base(dir), true
	}
	return "", false
}

// yamlTopLevelValue reads one scalar field from a plugin.yaml without a YAML dependency: the fields
// we need are plain top-level scalars in every real manifest, so the first unindented `key:` line is
// enough.
func yamlTopLevelValue(text, field string) string {
	for _, line := range strings.Split(text, "\n") {
		line = strings.TrimSuffix(line, "\r")
		if line == "" || line[0] == ' ' || line[0] == '\t' || line[0] == '#' {
			continue
		}
		key, value, found := strings.Cut(line, ":")
		if !found || strings.TrimSpace(key) != field {
			continue
		}
		value = strings.TrimSpace(value)
		if value == "" || value == "|" || value == ">" {
			return ""
		}
		if cut := strings.Index(value, " #"); cut >= 0 {
			value = strings.TrimSpace(value[:cut])
		}
		return strings.Trim(value, `"'`)
	}
	return ""
}

// attachPluginKeys pairs each CLI row with the next discovered key of the same name, in order.
func attachPluginKeys(rows []installedPlugin, keys []pluginKey) {
	queues := make(map[string][]string, len(keys))
	for _, entry := range keys {
		queues[entry.Name] = append(queues[entry.Name], entry.Key)
	}
	for i := range rows {
		queue := queues[rows[i].Name]
		if len(queue) == 0 {
			continue
		}
		rows[i].Key = queue[0]
		queues[rows[i].Name] = queue[1:]
	}
}

// handlePluginAction runs one mutating plugin command for the target in the body.

func (m *Manager) handlePluginAction(w http.ResponseWriter, r *http.Request, timeout time.Duration, args ...string) {
	target, ok := marketplaceTarget(w, r)
	if !ok {
		return
	}
	out, err := m.runHermes(timeout, append(args, target)...)
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
		Key     string `json:"key"`
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
	target := strings.TrimSpace(body.Key)
	if target == "" {
		target = strings.TrimSpace(body.Name)
	}
	if !validPluginTarget(target) {
		writeJSON(w, http.StatusBadRequest, obj{"error": "invalid plugin name"})
		return
	}
	verb := "disable"
	if *body.Enabled {
		verb = "enable"
	}
	out, err := m.runHermes(hermesReadTimeout, "plugins", verb, target)
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

// marketplaceTarget decodes {name,key} and prefers key: a key names exactly one plugin
// (image_gen/deepinfra), while a bare name can belong to two. Name stays for installs and for
// callers that predate keys.
func marketplaceTarget(w http.ResponseWriter, r *http.Request) (string, bool) {
	var body struct {
		Name string `json:"name"`
		Key  string `json:"key"`
	}
	if err := json.NewDecoder(r.Body).Decode(&body); err != nil {
		writeJSON(w, http.StatusBadRequest, obj{"error": err.Error()})
		return "", false
	}
	target := strings.TrimSpace(body.Key)
	if target == "" {
		target = strings.TrimSpace(body.Name)
	}
	if !validPluginTarget(target) {
		writeJSON(w, http.StatusBadRequest, obj{"error": "invalid name"})
		return "", false
	}
	return target, true
}

// validPluginTarget accepts a manifest name or a nested CLI key, never a path traversal.
func validPluginTarget(target string) bool {
	if !pluginNamePattern.MatchString(target) {
		return false
	}
	for _, part := range strings.Split(target, "/") {
		if part == "." || part == ".." {
			return false
		}
	}
	return true
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
