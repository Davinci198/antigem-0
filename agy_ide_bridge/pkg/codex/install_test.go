package codex

import (
	"archive/tar"
	"bytes"
	"compress/gzip"
	"io"
	"os"
	"path/filepath"
	"strings"
	"testing"
)

// tarGzWith builds a single-entry archive and returns its decompressed stream, which is what the installer hands to
// extractCodexBinary. The tar writer is not finalised on purpose: the header alone is enough for the size checks, and
// an entry that claims more bytes than it carries is exactly what those checks must survive.
func tarGzWith(t *testing.T, hdr tar.Header, body []byte) io.Reader {
	t.Helper()
	var buf bytes.Buffer
	gz := gzip.NewWriter(&buf)
	tw := tar.NewWriter(gz)
	if err := tw.WriteHeader(&hdr); err != nil {
		t.Fatalf("write header: %v", err)
	}
	if body != nil {
		if _, err := tw.Write(body); err != nil {
			t.Fatalf("write body: %v", err)
		}
	}
	_ = tw.Close()
	if err := gz.Close(); err != nil {
		t.Fatalf("close gzip: %v", err)
	}
	r, err := gzip.NewReader(&buf)
	if err != nil {
		t.Fatalf("open gzip: %v", err)
	}
	return r
}

func binaryHeader(size int64) tar.Header {
	return tar.Header{Name: "codex-x86_64-unknown-linux-musl/codex-x86_64-unknown-linux-musl", Size: size, Mode: 0755, Typeflag: tar.TypeReg}
}

func TestExtractCodexBinaryWritesTheEntry(t *testing.T) {
	body := []byte("#!/bin/sh\necho fake codex\n")
	tmp := filepath.Join(t.TempDir(), "codex.new")

	if err := extractCodexBinary(tarGzWith(t, binaryHeader(int64(len(body))), body), "x86_64-unknown-linux-musl", tmp); err != nil {
		t.Fatalf("extract: %v", err)
	}
	got, err := os.ReadFile(tmp)
	if err != nil {
		t.Fatalf("read extracted file: %v", err)
	}
	if !bytes.Equal(got, body) {
		t.Fatalf("extracted %q, want %q", got, body)
	}
	info, err := os.Stat(tmp)
	if err != nil {
		t.Fatalf("stat: %v", err)
	}
	// os.OpenFile masks the mode with the umask, so only the executable bit is asserted here
	if perm := info.Mode().Perm(); perm&0100 == 0 {
		t.Fatalf("mode %v, want the owner execute bit set", perm)
	}
}

func TestMaxBinarySizeFitsTheRealRelease(t *testing.T) {
	// the shipped codex binary is ~277 MB (290069784 bytes on the 0.167 release); a tighter cap breaks every install
	if maxBinarySize < 300<<20 {
		t.Fatalf("maxBinarySize = %d, too tight for the ~277 MB release binary", int64(maxBinarySize))
	}
}

func TestExtractCodexBinaryRefusesAnOversizedEntry(t *testing.T) {
	tmp := filepath.Join(t.TempDir(), "codex.new")

	// the header claims far more than maxBinarySize; nothing may be written and no file may be left behind
	hdr := binaryHeader(maxBinarySize + 1)
	err := extractCodexBinary(tarGzWith(t, hdr, nil), "x86_64-unknown-linux-musl", tmp)
	if err == nil || !strings.Contains(err.Error(), "limit") {
		t.Fatalf("err = %v, want a size-limit error", err)
	}
	if _, statErr := os.Stat(tmp); !os.IsNotExist(statErr) {
		t.Fatalf("an oversized entry left a file behind (stat err %v)", statErr)
	}
}

func TestExtractCodexBinaryRefusesATruncatedEntry(t *testing.T) {
	tmp := filepath.Join(t.TempDir(), "codex.new")

	// the header promises 4096 bytes, the archive carries 12: the copy must fail rather than install a stub
	hdr := binaryHeader(4096)
	if err := extractCodexBinary(tarGzWith(t, hdr, []byte("short binary")), "x86_64-unknown-linux-musl", tmp); err == nil {
		t.Fatal("a truncated entry was accepted")
	}
	if _, statErr := os.Stat(tmp); !os.IsNotExist(statErr) {
		t.Fatalf("a truncated entry left a file behind (stat err %v)", statErr)
	}
}

func TestExtractCodexBinaryRefusesAnArchiveWithoutTheBinary(t *testing.T) {
	tmp := filepath.Join(t.TempDir(), "codex.new")

	other := tar.Header{Name: "codex-aarch64-unknown-linux-musl/codex-aarch64-unknown-linux-musl", Size: 3, Mode: 0755, Typeflag: tar.TypeReg}
	err := extractCodexBinary(tarGzWith(t, other, []byte("abc")), "x86_64-unknown-linux-musl", tmp)
	if err == nil || !strings.Contains(err.Error(), "no codex binary") {
		t.Fatalf("err = %v, want a missing-binary error", err)
	}
}

func TestExtractCodexBinaryRefusesASymlink(t *testing.T) {
	tmp := filepath.Join(t.TempDir(), "codex.new")

	// a link entry would otherwise be copied as its target's bytes, or point outside the install directory
	link := binaryHeader(0)
	link.Typeflag = tar.TypeSymlink
	link.Linkname = "/etc/shadow"
	err := extractCodexBinary(tarGzWith(t, link, nil), "x86_64-unknown-linux-musl", tmp)
	if err == nil || !strings.Contains(err.Error(), "not a regular file") {
		t.Fatalf("err = %v, want a regular-file error", err)
	}
}
