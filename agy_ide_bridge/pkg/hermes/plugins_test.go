package hermes

import (
	"os"
	"path/filepath"
	"strings"
	"testing"
	"unicode/utf8"
)

// A skill directory's SKILL.md frontmatter is the only source of the name and description shown in
// the app, so the parser is worth pinning: a broken parse silently lists nameless rows.
func TestReadSkillParsesFrontmatter(t *testing.T) {
	dir := t.TempDir()
	path := filepath.Join(dir, "SKILL.md")
	content := "---\nname: memory-keeper\ndescription: \"Keeps notes across sessions\"\nversion: 1.2.0\n---\n\n# Body\n"
	if err := os.WriteFile(path, []byte(content), 0o644); err != nil {
		t.Fatalf("write: %v", err)
	}

	skill, ok := readSkill(path, "productivity")
	if !ok {
		t.Fatal("readSkill returned !ok for a readable SKILL.md")
	}
	if skill.Name != "memory-keeper" {
		t.Errorf("name = %q, want memory-keeper", skill.Name)
	}
	if skill.Description != "Keeps notes across sessions" {
		t.Errorf("description = %q", skill.Description)
	}
	if skill.Category != "productivity" {
		t.Errorf("category = %q, want productivity", skill.Category)
	}
	if skill.Path != path {
		t.Errorf("path = %q, want %q", skill.Path, path)
	}
}

// Without frontmatter the directory name is the skill name, and the file must still be listed.
func TestReadSkillWithoutFrontmatter(t *testing.T) {
	dir := filepath.Join(t.TempDir(), "ad-hoc-skill")
	if err := os.MkdirAll(dir, 0o755); err != nil {
		t.Fatalf("mkdir: %v", err)
	}
	path := filepath.Join(dir, "SKILL.md")
	if err := os.WriteFile(path, []byte("# Just a heading\n"), 0o644); err != nil {
		t.Fatalf("write: %v", err)
	}

	skill, ok := readSkill(path, "local")
	if !ok {
		t.Fatal("readSkill returned !ok")
	}
	if skill.Name != "ad-hoc-skill" {
		t.Errorf("name = %q, want the directory name", skill.Name)
	}
	if skill.Description != "" {
		t.Errorf("description = %q, want empty", skill.Description)
	}
}

func TestReadSkillMissingFile(t *testing.T) {
	if _, ok := readSkill(filepath.Join(t.TempDir(), "SKILL.md"), "x"); ok {
		t.Fatal("readSkill reported ok for a missing file")
	}
}

// The CLI colours its output; the app must never receive escape sequences inside an error message.
func TestStripANSI(t *testing.T) {
	in := "\x1b[1m✓ Plugin\x1b[0m installed"
	if got, want := stripANSI(in), "✓ Plugin installed"; got != want {
		t.Errorf("stripANSI = %q, want %q", got, want)
	}
}

// The catalog trims long descriptions to what a card can show; the cut must not split a rune.
func TestClip(t *testing.T) {
	if got := clip("short", 10); got != "short" {
		t.Errorf("clip kept %q, want it unchanged", got)
	}
	// 8 bytes of "ă" headroom: the ellipsis must land on a rune boundary.
	got := clip("ăăăăă", 5)
	if !utf8.ValidString(got) {
		t.Errorf("clip produced invalid UTF-8: %q", got)
	}
	if !strings.HasSuffix(got, "…") {
		t.Errorf("clip = %q, want an ellipsis", got)
	}
}

// Names reach the CLI as argv elements (never through a shell), but they still have to be catalog
// names: anything with spaces, slashes into paths or shell-ish characters is refused up front.
func TestPluginNamePattern(t *testing.T) {
	valid := []string{"memory-wiki", "0bull", "owner/repo", "a.b_c-d", "Skill@1.0"}
	invalid := []string{"", " leading", "with space", "../etc/passwd", "a;rm -rf /", "-flag", "x\ny"}

	for _, name := range valid {
		if !pluginNamePattern.MatchString(name) {
			t.Errorf("%q should be accepted", name)
		}
	}
	for _, name := range invalid {
		if pluginNamePattern.MatchString(name) {
			t.Errorf("%q should be refused", name)
		}
	}
}
