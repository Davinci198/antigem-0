package androiddns

import (
	"bufio"
	"context"
	"net"
	"os"
	"path/filepath"
	"strings"
	"sync/atomic"
	"time"
)

// Termux's Go runtime reads the resolver config from the Termux prefix even
// when GOOS=android (net/dnsclient_android.go hardcodes this path). Inside the
// app's proot that file does not exist while the guest's /etc/resolv.conf
// does, so the runtime falls back to [::1]:53 and every outbound lookup fails.
const termuxResolvConf = "/data/data/com.termux/files/usr/etc/resolv.conf"

// init installs a resolver that does not depend on the Go runtime finding the
// config at its compiled-in path. Checking only /etc/resolv.conf (as this
// package used to) missed the proot case: the file exists, so it returned
// early, but Termux's Go never reads it.
func init() {
	// The Termux-Go path exists, so the runtime can read it by itself.
	if _, err := os.Stat(termuxResolvConf); err == nil {
		return
	}
	servers := readNameservers("/etc/resolv.conf")
	if len(servers) == 0 {
		if prefix := os.Getenv("PREFIX"); prefix != "" {
			servers = readNameservers(filepath.Join(prefix, "etc", "resolv.conf"))
		}
	}
	if len(servers) == 0 {
		servers = readNameservers(termuxResolvConf)
	}
	if len(servers) == 0 {
		servers = []string{"8.8.8.8:53", "1.1.1.1:53"}
	}
	installResolver(servers)
}

// installResolver points the default resolver at explicit DNS servers. The
// name list and search domains still come from the runtime's config; only the
// server address is overridden, which is what the proot case needs.
func installResolver(servers []string) {
	var next uint32
	net.DefaultResolver = &net.Resolver{
		PreferGo: true,
		Dial: func(ctx context.Context, network, _ string) (net.Conn, error) {
			d := net.Dialer{Timeout: 5 * time.Second}
			start := int(atomic.AddUint32(&next, 1))
			var lastErr error
			for i := range servers {
				c, err := d.DialContext(ctx, network, servers[(start+i)%len(servers)])
				if err == nil {
					return c, nil
				}
				lastErr = err
			}
			return nil, lastErr
		},
	}
}

func readNameservers(path string) []string {
	f, err := os.Open(path)
	if err != nil {
		return nil
	}
	defer f.Close()
	var out []string
	sc := bufio.NewScanner(f)
	for sc.Scan() {
		fields := strings.Fields(sc.Text())
		if len(fields) >= 2 && fields[0] == "nameserver" && net.ParseIP(fields[1]) != nil {
			out = append(out, net.JoinHostPort(fields[1], "53"))
		}
	}
	return out
}
