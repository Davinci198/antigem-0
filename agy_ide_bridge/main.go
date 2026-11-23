package main

import (
	"bufio"
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"io"
	"log"
	"net"
	"net/http"
	"os"
	"os/signal"
	"runtime/debug"
	"strconv"
	"strings"
	"syscall"
	"time"

	// DNS for Android (no /etc/resolv.conf there); a package so `go build main.go` includes it too
	_ "gemini-server/pkg/androiddns"

	"gemini-server/pkg/agyaccounts"
	"gemini-server/pkg/claude"
	"gemini-server/pkg/config"
	"gemini-server/pkg/dsh"
	"gemini-server/pkg/handlers"
	"gemini-server/pkg/hermes"
	"gemini-server/pkg/hub"
	"gemini-server/pkg/opencode"
	"gemini-server/pkg/plugins"
	"gemini-server/pkg/runlog"
	"gemini-server/pkg/security"
	"gemini-server/pkg/ws"
)

func printUsage() {
	fmt.Println(`antiGem Go IDE Server & AGY Hub Supervisor

Usage:
  go run main.go [flags]

Flags:
  -u, --unpatch             Unpatch AGY binary back to standard header and exit (do not start server)
  --bin <path>              Custom path to AGY binary (for --unpatch or custom setups)
  --claude-bin <path>       Custom path to the Claude Code CLI (default: claude on PATH)
  -t, --token <token>       12-character security token for API & AGY CSRF obfuscation
  -f, --force, --f          Force start AGY Hub automatically without prompting
  -p, --port <port>         Port for the Go IDE Server (default: 1234)
  --bind <address>          Address the IDE Server listens on (default: 127.0.0.1; use 0.0.0.0 only to reach it directly from other machines)
  --hub-port <port>         Port for the AGY Hub RPC server (default: 1235)
  --tz-offset <seconds>     Timezone offset in seconds (e.g. 20700 for UTC+05:45)
  --tz <location>           Timezone location name (e.g. Asia/Kathmandu)
  --no-hub                  Skip launching AGY Hub (run IDE server only)
  --no-claude               Disable Claude Code (no /api/claude endpoints, never runs the claude CLI)
  --opencode-bin <path>     Custom path to the OpenCode CLI (default: opencode on PATH or ~/.opencode/bin)
  --no-opencode             Disable OpenCode (no /api/opencode endpoints, never runs the opencode CLI)
  --hermes-bin <path>       Custom path to the Hermes Agent CLI (default: hermes on PATH)
  --no-hermes               Disable Hermes Agent (no /api/hermes endpoints, never runs the hermes CLI)
  --no-dsh                  Disable DeepSeek Harness (no /api/dsh endpoints, never runs the dsh CLI)
  -d, --dir <path>          Custom workspace directory
  -h, --help                Show help documentation`)
}

// statusRecorder intercepts HTTP status code and response body for logging and error reporting
type statusRecorder struct {
	http.ResponseWriter
	statusCode   int
	responseBody bytes.Buffer
	wroteHeader  bool
}

func (r *statusRecorder) WriteHeader(code int) {
	if !r.wroteHeader {
		r.statusCode = code
		r.wroteHeader = true
		r.ResponseWriter.WriteHeader(code)
	}
}

func (r *statusRecorder) Write(b []byte) (int, error) {
	if !r.wroteHeader {
		r.WriteHeader(http.StatusOK)
	}
	if r.responseBody.Len() < 4096 {
		r.responseBody.Write(b)
	}
	return r.ResponseWriter.Write(b)
}

// Flush forwards to the wrapped writer. Streaming handlers (the model scan
// NDJSON stream) type-assert their ResponseWriter to http.Flusher; without
// this method the logging wrapper hides the underlying Flusher, the assertion
// panics and every scan batch answers 500.
func (r *statusRecorder) Flush() {
	if f, ok := r.ResponseWriter.(http.Flusher); ok {
		f.Flush()
	}
}

// formatHumanCrash converts raw runtime panics and stacks into human-readable diagnostics
func formatHumanCrash(p interface{}, rawStack []byte) (reason string, location string) {
	reason = fmt.Sprintf("%v", p)
	switch {
	case strings.Contains(reason, "invalid memory address") || strings.Contains(reason, "nil pointer"):
		reason = "Nil Pointer / Missing Object (tried to access data that was not initialized)"
	case strings.Contains(reason, "index out of range"):
		reason = "Array/Slice Index Out Of Range (tried to read beyond array length)"
	case strings.Contains(reason, "slice bounds out of range"):
		reason = "Slice Bounds Out Of Range"
	case strings.Contains(reason, "concurrent map"):
		reason = "Concurrent Map Access Collision"
	}

	lines := strings.Split(string(rawStack), "\n")
	for i := 0; i < len(lines); i++ {
		line := strings.TrimSpace(lines[i])
		if (strings.Contains(line, "gemini-server/pkg/") || strings.Contains(line, "gemini-server/main.go")) && !strings.Contains(line, "recoveryHandler") {
			if i+1 < len(lines) && strings.Contains(lines[i+1], ".go:") {
				fileLoc := strings.TrimSpace(lines[i+1])
				if idx := strings.Index(fileLoc, " +0x"); idx != -1 {
					fileLoc = fileLoc[:idx]
				}
				funcName := line
				if idx := strings.LastIndex(funcName, "/"); idx != -1 {
					funcName = funcName[idx+1:]
				}
				return reason, fmt.Sprintf("%s (in %s)", fileLoc, funcName)
			}
			return reason, line
		}
	}
	return reason, "Unknown internal code location"
}

func main() {
	cfg := config.LoadConfig()
	config.EnsureWorkspaceDirs(cfg.HomeDir)

	var unpatchOnly bool
	var customAgyBin string
	var customClaudeBin string
	var noClaude bool
	var customOpenCodeBin string
	var noOpenCode bool
	var customHermesBin string
	var noHermes bool
	// noDsh: the app passes --no-dsh while the agent is turned off. The route
	// itself is not served yet, so a disabled harness and a build without the
	// /api/dsh package behave the same: the app falls back to its own host.
	var noDsh bool
	var forceStart bool
	var skipHub bool
	var cliToken string
	var tzOffsetSec int
	var tzName string
	hubPort := "1235"

	// Parse command line arguments
	for i := 1; i < len(os.Args); i++ {
		arg := os.Args[i]
		switch {
		case arg == "-u" || arg == "--unpatch":
			unpatchOnly = true
		case strings.HasPrefix(arg, "--bin="):
			customAgyBin = strings.TrimPrefix(arg, "--bin=")
		case arg == "--bin" || arg == "--agy-bin":
			if i+1 < len(os.Args) {
				customAgyBin = os.Args[i+1]
				i++
			}
		case strings.HasPrefix(arg, "--claude-bin="):
			customClaudeBin = strings.TrimPrefix(arg, "--claude-bin=")
		case arg == "--claude-bin":
			if i+1 < len(os.Args) {
				customClaudeBin = os.Args[i+1]
				i++
			}
		case strings.HasPrefix(arg, "--token="):
			cliToken = strings.TrimPrefix(arg, "--token=")
		case arg == "-t" || arg == "--token":
			if i+1 < len(os.Args) {
				cliToken = os.Args[i+1]
				i++
			}
		case arg == "-f" || arg == "--f" || arg == "--force" || arg == "-force":
			forceStart = true
		case arg == "--no-hub" || arg == "-n" || arg == "--skip-hub":
			skipHub = true
		case arg == "--no-claude":
			noClaude = true
		case strings.HasPrefix(arg, "--opencode-bin="):
			customOpenCodeBin = strings.TrimPrefix(arg, "--opencode-bin=")
		case arg == "--opencode-bin":
			if i+1 < len(os.Args) {
				customOpenCodeBin = os.Args[i+1]
				i++
			}
		case arg == "--no-opencode":
			noOpenCode = true
		case strings.HasPrefix(arg, "--hermes-bin="):
			customHermesBin = strings.TrimPrefix(arg, "--hermes-bin=")
		case arg == "--hermes-bin":
			if i+1 < len(os.Args) {
				customHermesBin = os.Args[i+1]
				i++
			}
		case arg == "--no-hermes":
			noHermes = true
		case arg == "--no-dsh":
			noDsh = true
		case strings.HasPrefix(arg, "--port="):
			cfg.Port = strings.TrimPrefix(arg, "--port=")
		case arg == "-p" || arg == "--port":
			if i+1 < len(os.Args) {
				cfg.Port = os.Args[i+1]
				i++
			}
		case strings.HasPrefix(arg, "--bind="):
			cfg.Bind = strings.TrimPrefix(arg, "--bind=")
		case arg == "--bind":
			if i+1 < len(os.Args) {
				cfg.Bind = os.Args[i+1]
				i++
			}
		case strings.HasPrefix(arg, "--hub-port="):
			hubPort = strings.TrimPrefix(arg, "--hub-port=")
		case arg == "--hub-port":
			if i+1 < len(os.Args) {
				hubPort = os.Args[i+1]
				i++
			}
		case strings.HasPrefix(arg, "--tz-offset="):
			if val, err := strconv.Atoi(strings.TrimPrefix(arg, "--tz-offset=")); err == nil {
				tzOffsetSec = val
			}
		case arg == "--tz-offset":
			if i+1 < len(os.Args) {
				if val, err := strconv.Atoi(os.Args[i+1]); err == nil {
					tzOffsetSec = val
				}
				i++
			}
		case strings.HasPrefix(arg, "--tz="):
			tzName = strings.TrimPrefix(arg, "--tz=")
		case arg == "--tz":
			if i+1 < len(os.Args) {
				tzName = os.Args[i+1]
				i++
			}
		case strings.HasPrefix(arg, "--dir="):
			cfg.WorkspaceDir = strings.TrimPrefix(arg, "--dir=")
		case arg == "-d" || arg == "--dir" || arg == "--workspace":
			if i+1 < len(os.Args) {
				cfg.WorkspaceDir = os.Args[i+1]
				i++
			}
		case arg == "-h" || arg == "--help":
			printUsage()
			os.Exit(0)
		}
	}

	if unpatchOnly {
		resolvedBin, err := hub.ResolveAgyBinaryInteractive(customAgyBin, true)
		if err != nil {
			fmt.Printf("  \033[1;31m✗ Unpatch Failed:\033[0m %v\n", err)
			os.Exit(1)
		}
		patchTarget := hub.ResolvePatchTarget(resolvedBin)
		fmt.Println("\033[1;36m============================================================\033[0m")
		fmt.Println("\033[1;33m  ⚡ antiGem AGY Binary Header Unpatcher\033[0m")
		fmt.Println("\033[1;36m============================================================\033[0m")
		fmt.Printf("  \033[1m• Target Binary:\033[0m  %s\n", patchTarget)

		restoredHeader, elapsed, err := security.UnpatchAgyHeader(patchTarget)
		if err != nil {
			fmt.Printf("  \033[1;31m✗ Unpatch Failed:\033[0m %v\n", err)
			os.Exit(1)
		}
		fmt.Printf("  \033[1;32m✓ Unpatch Succeeded:\033[0m Header restored to standard '%s' in %v\n", restoredHeader, elapsed)
		fmt.Println("\033[1;36m============================================================\033[0m")
		os.Exit(0)
	}

	// Configure local timezone if offset or name is specified (or in environment)
	if tzOffsetSec == 0 {
		if envOff := os.Getenv("TZ_OFFSET"); envOff != "" {
			if val, err := strconv.Atoi(envOff); err == nil {
				tzOffsetSec = val
			}
		}
	}
	if tzOffsetSec != 0 {
		time.Local = time.FixedZone("Local", tzOffsetSec)
	} else if tzName != "" {
		if loc, err := time.LoadLocation(tzName); err == nil {
			time.Local = loc
		}
	}

	secToken := security.ResolveToken(cliToken)
	framedHeader := security.BuildFramedHeader(secToken)

	resolvedAgyBin, err := hub.ResolveAgyBinaryInteractive(customAgyBin, forceStart || skipHub)
	if customAgyBin != "" && err != nil {
		fmt.Printf(" \033[1;31m✗ Fatal Error:\033[0m %v\n", err)
		os.Exit(1)
	}
	if err != nil && !skipHub {
		fmt.Printf(" \033[31m[!] AGY Discovery Warning:\033[0m %v\n", err)
	}

	// everything printed from here on also goes to ~/logs/agy-ide-bridge_<date>_<time>.log (one file per start)
	runLogFile := runlog.Start(cfg.HomeDir)

	// Stylized Banner
	fmt.Println("\033[1;36m============================================================\033[0m")
	fmt.Println("\033[1;32m  ⚡ antiGem Go IDE Server & AGY Hub Supervisor\033[0m")
	fmt.Println("\033[1;36m============================================================\033[0m")
	fmt.Printf("  \033[1m• IDE Server Port:\033[0m  http://%s:%s\n", cfg.Bind, cfg.Port)
	fmt.Printf("  \033[1m• Projects Dir:\033[0m     %s\n", cfg.ProjectsBaseDir)
	fmt.Printf("  \033[1m• Target Hub Port:\033[0m  %s\n", hubPort)
	if resolvedAgyBin != "" {
		fmt.Printf("  \033[1m• AGY Binary:\033[0m       %s\n", resolvedAgyBin)
	}
	// The token and the framed header are the API secret (the header name embeds the token and its presence alone
	// authenticates). The banner is copied into the run log (PR #9), so only masked forms are printed.
	fmt.Printf("  \033[1;33m• Security Token:\033[0m   %s\n", security.MaskToken(secToken))
	fmt.Printf("  \033[1;36m• Framed Header:\033[0m    %s\n", security.MaskFramedHeader(framedHeader))
	fmt.Printf("  \033[1m• Local Time:\033[0m       %s\n", time.Now().Format("2006-01-02 15:04:05 MST"))
	if runLogFile != nil {
		fmt.Printf("  \033[1m• Log File:\033[0m         %s\n", runLogFile.Path)
	}
	fmt.Println("\033[1;36m============================================================\033[0m")

	var hubMgr *hub.HubManager

	wsHub := ws.NewHub(cfg)
	h := handlers.NewHandler(cfg, hubMgr)
	h.SetHub(wsHub)

	mux := http.NewServeMux()

	// Seed the shared Kilo Free endpoint into the Claude and OpenCode stores
	// once (marker files make it one-shot). Hermes reads
	// ~/.claude/agy_providers.json directly for its model picker, so this runs
	// even when a manager below is flag-gated off.
	claude.SeedKiloFree(cfg.HomeDir)
	opencode.SeedKiloFree(cfg.HomeDir)

	// Claude Code CLI sessions (process manager + NDJSON WebSocket relay); off with --no-claude
	var claudeMgr *claude.Manager
	if noClaude {
		mux.HandleFunc("/api/claude/", func(w http.ResponseWriter, r *http.Request) {
			w.Header().Set("Content-Type", "application/json")
			w.WriteHeader(http.StatusServiceUnavailable)
			_, _ = w.Write([]byte(`{"success":false,"disabled":true,"error":"Claude Code is turned off in the app"}`))
		})
	} else {
		claudeMgr = claude.NewManager(cfg.HomeDir, cfg.WorkspaceDir, customClaudeBin)
		mux.Handle("/api/claude/", claudeMgr)
	}

	// OpenCode: one `opencode serve` for all its chats, relayed under /api/opencode/x/. Off with --no-opencode,
// which is also what the app passes when the OpenCode agent is turned off in settings, so the CLI is never run.
	var openCodeMgr *opencode.Manager
	if noOpenCode {
		mux.HandleFunc("/api/opencode/", func(w http.ResponseWriter, r *http.Request) {
			w.Header().Set("Content-Type", "application/json")
			w.WriteHeader(http.StatusServiceUnavailable)
			_, _ = w.Write([]byte(`{"success":false,"disabled":true,"error":"OpenCode is turned off in the app"}`))
		})
	} else {
		openCodeMgr = opencode.NewManager(cfg.HomeDir, cfg.WorkspaceDir, customOpenCodeBin)
		mux.Handle("/api/opencode/", openCodeMgr)
	}

	// Hermes Agent CLI sessions; off with --no-hermes
	var hermesMgr *hermes.Manager
	if noHermes {
		mux.HandleFunc("/api/hermes/", func(w http.ResponseWriter, r *http.Request) {
			w.Header().Set("Content-Type", "application/json")
			w.WriteHeader(http.StatusServiceUnavailable)
			_, _ = w.Write([]byte(`{"success":false,"disabled":true,"error":"Hermes Agent is turned off in the app"}`))
		})
	} else {
		hermesMgr = hermes.NewManager(cfg.HomeDir, customHermesBin)
		mux.Handle("/api/hermes/", hermesMgr)
	}

	// DeepSeek Harness model gateway: DSH always authenticates as a provider
	// route, and the app already holds the user's credentials — so the app points
	// a DSH custom route (`api: openai-completions`) at this loopback endpoint,
	// which applies the real key upstream. A keyless endpoint is therefore just a
	// provider with no key, and no secret ever enters the container.
	var dshGateways dsh.Cache
	defer dshGateways.Close()
	mux.HandleFunc("/api/dsh/gateway", func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		if noDsh {
			w.WriteHeader(http.StatusServiceUnavailable)
			_, _ = w.Write([]byte(`{"success":false,"disabled":true,"error":"DeepSeek Harness is turned off in the app"}`))
			return
		}
		id := r.URL.Query().Get("provider")
		if claudeMgr == nil || id == "" {
			w.WriteHeader(http.StatusBadRequest)
			_, _ = w.Write([]byte(`{"success":false,"error":"provider is required, and Claude Code must be enabled"}`))
			return
		}
		p, ok := claudeMgr.SavedProvider(id)
		if !ok {
			w.WriteHeader(http.StatusNotFound)
			_, _ = w.Write([]byte(`{"success":false,"error":"no such provider"}`))
			return
		}
		listed := make([]string, 0, len(p.Models))
		for _, m := range p.Models {
			if m.ID != "" {
				listed = append(listed, m.ID)
			}
		}
		models := dsh.ChatModels(listed)
		gw, err := dshGateways.For(dsh.Config{
			ID:          p.ID,
			BaseURL:     p.BaseURL,
			APIKey:      p.APIKey,
			AuthMode:    p.AuthMode,
			Headers:     p.Headers,
			OpenAIStyle: p.OpenAIStyle,
			Models:      models,
		})
		if err != nil {
			w.WriteHeader(http.StatusBadGateway)
			_, _ = fmt.Fprintf(w, `{"success":false,"error":%q}`, err.Error())
			return
		}
		answer := map[string]interface{}{
			"success":  true,
			"provider": p.ID,
			"base_url": gw.URL(),
			"api":      "openai-completions",
			"models":   models,
		}
		if def := dsh.DefaultModel(models); def != "" {
			answer["model"] = def
		}
		_ = json.NewEncoder(w).Encode(answer)
	})

	// Session host: not served yet, so the app runs the session through its own
	// in-app PRoot host.
	mux.HandleFunc("/api/dsh/", func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(http.StatusServiceUnavailable)
		if noDsh {
			_, _ = w.Write([]byte(`{"success":false,"disabled":true,"error":"DeepSeek Harness is turned off in the app"}`))
			return
		}
		_, _ = w.Write([]byte(`{"success":false,"unsupported":true,"error":"This bridge build runs DSH sessions in the app, not here"}`))
	})

	// Custom AntiGem plugins on disk: list, install (ZIP or GitHub repository), update, remove, export
	mux.Handle("/api/custom-plugins/", plugins.NewManager(cfg.HomeDir))

	// REST Endpoints
	mux.HandleFunc("/api/health", h.HealthHandler)
	mux.HandleFunc("/api/status", h.StatusHandler)
	mux.HandleFunc("/api/shutdown", h.ShutdownHandler)
	mux.HandleFunc("/api/auth/login-url", h.GetLoginURLHandler)
	mux.HandleFunc("/api/auth/start-login", h.StartLoginHandler)
	// Multi-account AGY: saved credential snapshots and the swap between them
	mux.HandleFunc("/api/agy/accounts", h.AgyAccountsHandler)
	mux.HandleFunc("/api/agy/accounts/save", h.AgyAccountsSaveHandler)
	mux.HandleFunc("/api/agy/accounts/switch", h.AgyAccountsSwitchHandler)
	mux.HandleFunc("/api/agy/accounts/status", h.AgyAccountsStatusHandler)
	mux.HandleFunc("/api/agy/accounts/delete", h.AgyAccountsDeleteHandler)
	mux.HandleFunc("/api/agy/accounts/label", h.AgyAccountsLabelHandler)
	mux.HandleFunc("/api/models", h.ModelsHandler)
	mux.HandleFunc("/api/models/refresh", h.ModelsHandler)
	mux.HandleFunc("/api/quotas", h.QuotasHandler)
	mux.HandleFunc("/api/quotas/refresh", h.QuotasHandler)
	mux.HandleFunc("/api/instances", h.InstancesHandler)
	mux.HandleFunc("/api/abort", h.AbortHandler)
	mux.HandleFunc("/api/conversations", h.ConversationsHandler)
	mux.HandleFunc("/api/system-prompt", h.SystemPromptHandler)
	mux.HandleFunc("/api/projects", h.ProjectsHandler)
	mux.HandleFunc("/api/projects/add", h.ProjectsAddHandler)
	mux.HandleFunc("/api/projects/remove", h.ProjectsRemoveHandler)
	mux.HandleFunc("/api/projects/create", h.CreateProjectHandler)
	mux.HandleFunc("/api/fs/browse", h.FsBrowseHandler)
	mux.HandleFunc("/api/fs/mkdir", h.FsMkdirHandler)
	mux.HandleFunc("/api/tree", h.FileTreeHandler)
	mux.HandleFunc("/api/file/read", h.FileReadHandler)
	mux.HandleFunc("/api/file/save", h.FileSaveHandler)
	mux.HandleFunc("/api/file/patch", h.FilePatchHandler)
	mux.HandleFunc("/api/file/create", h.FileCreateHandler)
	mux.HandleFunc("/api/file/delete", h.FileDeleteHandler)
	mux.HandleFunc("/api/file/rename", h.FileRenameHandler)
	mux.HandleFunc("/api/file/copy", h.FileCopyHandler)
	mux.HandleFunc("/api/search", h.FileSearchHandler)
	mux.HandleFunc("/api/upload", h.UploadHandler)
	mux.HandleFunc("/api/mcp/config", h.McpConfigHandler)

	// Git Source Control Endpoints
	mux.HandleFunc("/api/git/status", h.GitStatusHandler)
	mux.HandleFunc("/api/git/init", h.GitInitHandler)
	mux.HandleFunc("/api/git/config", func(w http.ResponseWriter, r *http.Request) {
		if r.Method == http.MethodPost {
			h.GitSetConfigHandler(w, r)
		} else {
			h.GitGetConfigHandler(w, r)
		}
	})
	mux.HandleFunc("/api/git/branches", h.GitBranchesHandler)
	mux.HandleFunc("/api/git/checkout", h.GitCheckoutHandler)
	mux.HandleFunc("/api/git/branch/create", h.GitCheckoutHandler)
	mux.HandleFunc("/api/git/stage", h.GitStageHandler)
	mux.HandleFunc("/api/git/unstage", h.GitUnstageHandler)
	mux.HandleFunc("/api/git/discard", h.GitDiscardHandler)
	mux.HandleFunc("/api/git/commit", h.GitCommitHandler)
	mux.HandleFunc("/api/git/push", h.GitPushHandler)
	mux.HandleFunc("/api/git/pull", h.GitPullHandler)
	mux.HandleFunc("/api/git/stash", h.GitStashHandler)
	mux.HandleFunc("/api/git/stash/pop", h.GitStashPopHandler)
	mux.HandleFunc("/api/git/diff", h.GitDiffHandler)
	mux.HandleFunc("/api/git/log", h.GitLogHandler)
	mux.HandleFunc("/api/git/commit/details", h.GitCommitDetailsHandler)
	mux.HandleFunc("/api/git/commit/diff", h.GitCommitFileDiffHandler)
	mux.HandleFunc("/api/git/commit/content", h.GitCommitFileContentHandler)

	// Sub-resource endpoints
	mux.HandleFunc("/api/instances/", func(w http.ResponseWriter, r *http.Request) {
		if strings.HasSuffix(r.URL.Path, "/terminate") {
			h.TerminateInstanceHandler(w, r)
		} else {
			h.InstancesHandler(w, r)
		}
	})

	mux.HandleFunc("/api/conversations/restore", h.RestoreConversationHandler)
	mux.HandleFunc("/api/conversations/import", h.RestoreConversationHandler)

	mux.HandleFunc("/api/conversations/", func(w http.ResponseWriter, r *http.Request) {
		path := r.URL.Path
		if strings.HasSuffix(path, "/system-prompt") {
			h.SystemPromptHandler(w, r)
		} else if strings.HasSuffix(path, "/title") {
			h.UpdateConversationTitleHandler(w, r)
		} else if strings.HasSuffix(path, "/warm") {
			h.PrewarmConversationHandler(w, r)
		} else if strings.HasSuffix(path, "/export") || strings.HasSuffix(path, "/share") {
			h.ExportConversationArchiveHandler(w, r)
		} else {
			switch r.Method {
			case http.MethodGet:
				h.ConversationMessagesHandler(w, r)
			case http.MethodPatch, http.MethodPost:
				h.UpdateConversationTitleHandler(w, r)
			case http.MethodDelete:
				h.DeleteConversationHandler(w, r)
			default:
				http.Error(w, "Method not allowed", http.StatusMethodNotAllowed)
			}
		}
	})

	// Static File Serving
	mux.Handle("/artifacts/", h.ArtifactsFileServer())
	mux.Handle("/attachments/", h.AttachmentsFileServer())

	// WebSocket Endpoint
	mux.HandleFunc("/ws", wsHub.ServeWS)

	mux.HandleFunc("/", func(w http.ResponseWriter, r *http.Request) {
		if strings.ToLower(r.Header.Get("Upgrade")) == "websocket" {
			wsHub.ServeWS(w, r)
			return
		}
		if r.URL.Path == "/" {
			w.Header().Set("Content-Type", "text/plain")
			_, _ = w.Write([]byte(fmt.Sprintf("antiGem IDE Server running on :%s\n", cfg.Port)))
			return
		}
		http.NotFound(w, r)
	})

	corsHandler := http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Access-Control-Allow-Origin", "*")
		w.Header().Set("Access-Control-Allow-Methods", "GET, POST, PATCH, DELETE, OPTIONS")
		// Never echo the framed header here: its name embeds the token, and this response is readable by
		// any origin (Allow-Origin: *). "*" keeps browser clients working without leaking the secret.
		w.Header().Set("Access-Control-Allow-Headers", "Content-Type, Authorization, *")
		if r.Method == http.MethodOptions {
			w.WriteHeader(http.StatusOK)
			return
		}
		security.AuthMiddleware(secToken, mux).ServeHTTP(w, r)
	})

	// Global Panic Recovery & Error Logging Middleware:
	// 1. Prevents any API handler panic or crash from bringing down the Go server
	// 2. Formats and prints truncated request payload, query params, and error details to stdout/log stream
	recoveryHandler := http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		// Bypass recorder for WebSocket upgrade requests so Gorilla websocket can hijack directly
		if strings.ToLower(r.Header.Get("Upgrade")) == "websocket" || r.URL.Path == "/ws" {
			defer func() {
				if p := recover(); p != nil {
					log.Printf("\033[1;31m[WS CRASH / PANIC RECOVERED] %s: %v\033[0m\n", r.URL.Path, p)
					debug.PrintStack()
				}
			}()
			corsHandler.ServeHTTP(w, r)
			return
		}

		var reqPayload string
		if r.Body != nil && !strings.HasPrefix(r.Header.Get("Content-Type"), "multipart/form-data") {
			bodyBytes, err := io.ReadAll(r.Body)
			if err == nil && len(bodyBytes) > 0 {
				r.Body = io.NopCloser(bytes.NewReader(bodyBytes))
				trimmed := strings.TrimSpace(string(bodyBytes))
				if len(trimmed) > 300 {
					reqPayload = trimmed[:300] + "... (truncated)"
				} else {
					reqPayload = trimmed
				}
			}
		}

		rec := &statusRecorder{
			ResponseWriter: w,
			statusCode:     http.StatusOK,
		}

		defer func() {
			if p := recover(); p != nil {
				rawStack := debug.Stack()
				reason, codeLoc := formatHumanCrash(p, rawStack)

				log.Printf("\033[1;31m═══════════════════════════════════════════════════════════════\033[0m\n")
				log.Printf("\033[1;31m[API CRASH RECOVERED SAFELY] %s %s\033[0m\n", r.Method, r.URL.Path)
				if r.URL.RawQuery != "" {
					log.Printf("  \033[1;33m• Query:\033[0m    %s\n", security.RedactQuery(r.URL.RawQuery))
				}
				if reqPayload != "" {
					log.Printf("  \033[1;33m• Payload:\033[0m  %s\n", reqPayload)
				}
				log.Printf("  \033[1;31m• Reason:\033[0m   %s\n", reason)
				log.Printf("  \033[1;36m• Code Line:\033[0m %s\n", codeLoc)
				log.Printf("\033[1;31m═══════════════════════════════════════════════════════════════\033[0m\n")

				w.Header().Set("Content-Type", "application/json")
				w.WriteHeader(http.StatusInternalServerError)
				_ = json.NewEncoder(w).Encode(map[string]interface{}{
					"error":    fmt.Sprintf("Internal Server Error: %s", reason),
					"location": codeLoc,
					"success":  false,
				})
			}
		}()

		corsHandler.ServeHTTP(rec, r)

		// Log API errors (status >= 400 or response payload containing "error" / "success":false)
		respStr := rec.responseBody.String()
		isErrorResp := rec.statusCode >= 400 || (strings.Contains(respStr, `"error"`) && strings.Contains(respStr, `"success":false`))
		if isErrorResp {
			log.Printf("\033[1;33m[API ERROR %d] %s %s\033[0m\n", rec.statusCode, r.Method, r.URL.Path)
			if r.URL.RawQuery != "" {
				log.Printf("  \033[33m• Query:\033[0m   %s\n", security.RedactQuery(r.URL.RawQuery))
			}
			if reqPayload != "" {
				log.Printf("  \033[33m• Payload:\033[0m %s\n", reqPayload)
			}
			if len(respStr) > 0 {
				respSnippet := strings.TrimSpace(respStr)
				if len(respSnippet) > 300 {
					respSnippet = respSnippet[:300] + "... (truncated)"
				}
				log.Printf("  \033[31m• Error Response:\033[0m %s\n", respSnippet)
			}
		}
	})

	// WriteTimeout covers the whole response, not each write. A model-scan batch
	// streams NDJSON for as long as its budget (12 min) and one NVIDIA probe takes
	// 82-92 s, so 60 s cut long scans off with an empty reply. 15 min matches the
	// app's scan read timeout and stays above the bridge's own budget; handlers
	// keep their per-request deadlines.
	server := &http.Server{
		Addr:         net.JoinHostPort(cfg.Bind, cfg.Port),
		Handler:      security.GuardResponse(secToken, recoveryHandler),
		ReadTimeout:  60 * time.Second,
		WriteTimeout: 15 * time.Minute,
	}

	stopChan := make(chan os.Signal, 1)
	signal.Notify(stopChan, os.Interrupt, syscall.SIGTERM)

	h.OnShutdown = func() {
		log.Println("🛑 Received /api/shutdown request. Initiating graceful shutdown...")
		select {
		case stopChan <- syscall.SIGTERM:
		default:
		}
	}

	go func() {
		if err := server.ListenAndServe(); err != nil && err != http.ErrServerClosed {
			log.Fatalf("Server error: %v", err)
		}
	}()

	fmt.Printf(" \033[32m🚀 antiGem IDE Server running at http://%s:%s\033[0m\n\n", cfg.Bind, cfg.Port)

	// Determine if AGY Hub should be started
	shouldStartHub := false
	if !skipHub {
		if forceStart {
			fmt.Println(" \033[33m⚡ Force flag (-f) detected: auto-starting AGY Hub...\033[0m")
			shouldStartHub = true
		} else {
			// Interactive user prompt
			fmt.Print(" \033[1;33m? Do you want to start AGY Hub server (port " + hubPort + ")? [Y/n]: \033[0m")
			reader := bufio.NewReader(os.Stdin)
			input, err := reader.ReadString('\n')
			if err == nil {
				trimmed := strings.ToLower(strings.TrimSpace(input))
				if trimmed == "" || trimmed == "y" || trimmed == "yes" {
					shouldStartHub = true
				} else {
					fmt.Println(" \033[90mℹ Skipping AGY Hub. Running IDE Server only.\033[0m")
				}
			} else {
				// Non-interactive fallback (e.g. piped or redirected stdin) -> default to starting hub
				shouldStartHub = true
			}
		}
	}

	// Always initialize HubManager (the handlers use it); with --no-hub it is disabled: no monitoring, no probes
	hubMgr = hub.NewHubManager(hubPort, cfg.WorkspaceDir, cfg.AppDataDir, secToken, resolvedAgyBin)
	if skipHub {
		hubMgr.Disabled = true
		hubMgr.SetStopped()
	}
	h.HubManager = hubMgr
	h.Accounts = agyaccounts.NewManager(cfg.HomeDir, hubMgr)
	wsHub.StatusProv = hubMgr
	wsHub.HubPort = hubPort
	hubMgr.OnLoginURL = h.HandleLoginURL
	hubMgr.OnStatusChange = func(status string, csrfToken string, errorMsg string, logs []string) {
		wsHub.BroadcastHubStatus(status, hubPort, csrfToken, errorMsg, logs)
	}
	if !skipHub {
		hubMgr.StartContinuousMonitor()
	}

	if shouldStartHub {
		if err := hubMgr.Start(); err != nil {
			fmt.Printf(" \033[31m[!] Warning starting AGY Hub:\033[0m %v\n", err)
		}
	}

	<-stopChan
	fmt.Println("\n🛑 Shutting down antiGem server gracefully...")

	// 1. Close all active WebSocket client connections immediately
	wsHub.Close()

	// 2. Stop Claude Code and Hermes sessions, then the AGY Hub process group and continuous background monitor
	if claudeMgr != nil {
		claudeMgr.StopAll()
	}
	if openCodeMgr != nil {
		openCodeMgr.StopAll()
	}
	if hermesMgr != nil {
		hermesMgr.StopAll()
	}
	if hubMgr != nil {
		hubMgr.Stop()
	}

	// 3. Close the HTTP listener without hanging on lingering connections
	ctx, cancel := context.WithTimeout(context.Background(), 1*time.Second)
	defer cancel()
	_ = server.Shutdown(ctx)
	_ = server.Close()

	fmt.Println("✅ antiGem Go server stopped.")
	runLogFile.Close()
	os.Exit(0)
}
