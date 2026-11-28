# dsh-antigem — the AntiGem bridge for DeepSeek Harness

A DSH bundle that connects DeepSeek Harness to the seven MCP servers the AntiGem Android app serves on its local bridge, so a session controls the app and the phone with `mcp__*` tools.

## Requirements

The AntiGem app must be running: its `AndroidLocalBridgeServer` is what answers these requests. Nothing in this bundle starts it.

The app must also pass three values into the environment of the DSH process it launches:

| Variable | Value |
| --- | --- |
| `ANTIGEM_MCP_PORT` | the port `AndroidLocalBridgeServer` actually bound |
| `ANTIGEM_DEVICE_ID` | `Settings.Secure.ANDROID_ID`, the value the bridge's `X-Device-Id` check compares against |
| `ANTIGEM_FULL_ACCESS` | `1` or `0` from the app's own switch; absent means "leave the profile alone" |

All three are read at boot through the loader's `!!js` expression tag, so nothing is hardcoded here and no secret is stored in the bundle. When `ANTIGEM_MCP_PORT` is absent every row stays disabled and DSH boots normally — that is what happens when you run `dsh` outside the app.

The port is not a constant even inside the app: the bridge retries `DEFAULT_PORT`, then `DEFAULT_PORT + n`, then an OS-assigned port, so the app has to report the port it ended up on rather than this bundle guessing it.

## Sandbox

The app writes `ANTIGEM_FULL_ACCESS` on every boot, `0` included, so a switch that is off cannot be mistaken for one that was never sent:

| `ANTIGEM_FULL_ACCESS` | `sandbox-policy.mode` |
| --- | --- |
| `1` | `danger-full-access` |
| `0` | `DSH_PERMISSION_MODE`, or `workspace-write` when that is unset |

`1` wins over `DSH_PERMISSION_MODE`: it is the user asking for full access on purpose. The workspace root is always `process.cwd()`, the directory the app opened the session in.

Approvals stay whatever the profile already had — this bundle does not change the `approval` plugin. The `danger-full-access-ask` preset exists only because `permission-presets` aborts boot when a sandbox and approval pair matches no preset; it is not selected for you.

## Sessions

The app opens every chat in one shared guest directory, so the harness files them together and a session started on either side is visible, and resumable, on the other. Chats saved elsewhere keep the directory they were filed under.

## Tools

| Prefix | Path | Capability |
| --- | --- | --- |
| `mcp__browser__*` | `/browser` | Webview tabs |
| `mcp__terminal__*` | `/terminal` | App terminal sessions |
| `mcp__google_flow__*` | `/google_flow` | Google Flow automation |
| `mcp__custom_plugin_developer__*` | `/extension_developer` | Webview extension developer |
| `mcp__android_automation__*` | `/android` | Device automation |
| `mcp__notes__*` | `/notes` | Notes store |
| `mcp__theme__*` | `/theme` | App theming |

## Limitations

- Every request must carry a valid `X-Device-Id`; a wrong or empty value is rejected by the bridge before the MCP handshake.
- Android automation answers with a `-32001` error until it is granted in the app's Utilities screen.
- The bridge speaks JSON-RPC over POST and offers no server-sent event stream. The transport tolerates the `405` the bridge returns for `GET`, so a session is request/response only.
- The tools run with the permissions the app already holds. Nothing here escalates them.
