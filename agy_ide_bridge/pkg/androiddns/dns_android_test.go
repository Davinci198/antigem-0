package androiddns

import (
	"os"
	"path/filepath"
	"reflect"
	"testing"
)

func TestReadNameservers(t *testing.T) {
	dir := t.TempDir()
	path := filepath.Join(dir, "resolv.conf")
	content := "# comment\nnameserver 8.8.8.8\nsearch lan\n" +
		"nameserver 2001:4860:4860::8888\nnameserver not-an-ip\n"
	if err := os.WriteFile(path, []byte(content), 0o644); err != nil {
		t.Fatal(err)
	}
	got := readNameservers(path)
	want := []string{"8.8.8.8:53", "[2001:4860:4860::8888]:53"}
	if !reflect.DeepEqual(got, want) {
		t.Fatalf("got %v, want %v", got, want)
	}
}

func TestReadNameserversMissing(t *testing.T) {
	if got := readNameservers(filepath.Join(t.TempDir(), "missing")); got != nil {
		t.Fatalf("got %v, want nil", got)
	}
}
