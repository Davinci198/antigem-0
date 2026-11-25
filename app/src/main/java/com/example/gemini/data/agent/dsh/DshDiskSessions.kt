package com.example.gemini.data.agent.dsh

import android.content.Context
import android.util.Log
import com.example.gemini.data.local.LocalEnvironmentManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Reads the chats DeepSeek Harness keeps on disk, so a conversation survives the app: the harness
 * stores one folder per session under `$DSH_HOME/sessions/<workspace>/<id>`, its title and counters in
 * `$DSH_HOME/storages/session_projcache/sessions/<id>.json`, and its log as a zstd-compressed JSONL
 * file next to it.
 *
 * Everything runs inside the container through a small Node script: the logs are zstd, which only Node
 * (and a Python 3.14 `compression.zstd`) can decode here, and only the guest has them at all.
 */
object DshDiskSessions {

    private const val TAG = "DshDiskSessions"
    private const val SCRIPT_PATH = "/root/.antigem/dsh-sessions.js"
    private const val RUN_TIMEOUT_MS = 25_000L

    /** One chat as the harness sees it. */
    data class DiskSession(
        val id: String,
        val title: String?,
        val updatedAt: Long,
        val turns: Int,
        val provider: String?,
        val model: String?,
    )

    /** One readable message of a stored transcript. */
    data class DiskMessage(val role: String, val text: String)

    suspend fun list(context: Context): List<DiskSession> = withContext(Dispatchers.IO) {
        val raw = run(context, "--list")
        val rows = runCatching { Json.parseToJsonElement(raw) as JsonArray }.getOrNull()
        if (rows == null) {
            Log.w(TAG, "the session list did not parse: ${raw.take(200)}")
            return@withContext emptyList()
        }
        rows.mapNotNull { element ->
            val row = element as? JsonObject ?: return@mapNotNull null
            val id = row.string("id") ?: return@mapNotNull null
            DiskSession(
                id = id,
                title = row.string("title")?.takeIf { it.isNotBlank() },
                updatedAt = row["updatedAt"]?.jsonPrimitive?.longOrNull ?: 0L,
                turns = row["turns"]?.jsonPrimitive?.intOrNull ?: 0,
                provider = row.string("provider")?.takeIf { it.isNotBlank() },
                model = row.string("model")?.takeIf { it.isNotBlank() },
            )
        }.sortedByDescending { it.updatedAt }
    }

    suspend fun transcript(context: Context, sessionId: String): List<DiskMessage> = withContext(Dispatchers.IO) {
        val raw = run(context, "--transcript", sessionId)
        val rows = runCatching { Json.parseToJsonElement(raw) as JsonArray }.getOrNull()
        if (rows == null) {
            Log.w(TAG, "the transcript of $sessionId did not parse: ${raw.take(200)}")
            return@withContext emptyList()
        }
        rows.mapNotNull { element ->
            val row = element as? JsonObject ?: return@mapNotNull null
            val text = row.string("text").orEmpty()
            if (text.isBlank()) null else DiskMessage(row.string("role").orEmpty(), text)
        }
    }

    private fun JsonObject.string(key: String): String? =
        (this[key] ?: return null).jsonPrimitive.contentOrNull

    /**
     * Runs the reader in the guest. The script is rewritten only when its text changes, so an updated
     * app never keeps running an old copy, and a missing container simply answers nothing.
     */
    private fun run(context: Context, vararg args: String): String {
        val root = LocalEnvironmentManager.getUbuntuRootDir(context)
        val installed = File(root, SCRIPT_PATH.removePrefix("/"))
        if (!installed.isFile || installed.length() != SCRIPT.length.toLong()) {
            runCatching {
                installed.parentFile?.mkdirs()
                installed.writeText(SCRIPT)
                Log.i(TAG, "session reader installed in the container")
            }.onFailure { Log.w(TAG, "could not install the session reader: ${it.message}") }
        }
        if (!installed.isFile) return "[]"
        val command = listOf(DshGuestPaths.NODE_BIN_ADDRESSED, SCRIPT_PATH) + args
        val process = runCatching {
            LocalEnvironmentManager.startGuestProcess(
                context = context,
                guestArgv = command,
                guestCwd = "/root",
                environment = mapOf("DSH_HOME" to DshGuestPaths.DSH_HOME),
            )
        }.getOrElse {
            Log.w(TAG, "could not start the session reader: ${it.message}")
            return "[]"
        }
        // `Process` is not Closeable, so it is torn down by hand whatever happens.
        val proc = process
        return try {
            val output = StringBuilder()
            val reader = Thread {
                runCatching {
                    proc.inputStream.bufferedReader().forEachLine { output.appendLine(it) }
                }
            }.apply { isDaemon = true; start() }
            val errors = StringBuilder()
            val errorReader = Thread {
                runCatching {
                    proc.errorStream.bufferedReader().forEachLine { line -> errors.appendLine(line) }
                }
            }.apply { isDaemon = true; start() }
            val finished = process.waitFor(RUN_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            if (!finished) {
                Log.w(TAG, "the session reader timed out")
                proc.destroyForcibly()
            }
            reader.join(2_000)
            errorReader.join(500)
            if (errors.isNotEmpty()) Log.i(TAG, "session reader said: ${errors.toString().trim().take(200)}")
            output.toString().trim().ifBlank { "[]" }
        } finally {
            proc.destroy()
        }
    }

    /**
     * The reader itself. `--list` prints the chats, `--transcript <id>` the readable messages of one:
     * titles and counters come from the projection cache, the messages from the compressed log.
     */
    private val SCRIPT = """
        const fs = require('fs'), path = require('path'), zlib = require('zlib');
        const HOME = process.env.DSH_HOME || '/root/.dsh';
        const mode = process.argv[2] || '--list';

        function textOf(content) {
          if (typeof content === 'string') return content;
          if (!Array.isArray(content)) return '';
          return content.filter(b => b && b.type === 'text').map(b => b.text || '').join('');
        }

        function sessions() {
          const out = [];
          let workspaces = [];
          try { workspaces = fs.readdirSync(path.join(HOME, 'sessions')); } catch (e) { return out; }
          for (const workspace of workspaces) {
            let ids = [];
            try { ids = fs.readdirSync(path.join(HOME, 'sessions', workspace)); } catch (e) { continue; }
            for (const id of ids) {
              if (id.startsWith('.')) continue;
              const dir = path.join(HOME, 'sessions', workspace, id);
              let stat;
              try { stat = fs.statSync(dir); } catch (e) { continue; }
              if (!stat.isDirectory()) continue;
              let log = null;
              try {
                const files = fs.readdirSync(dir).filter(f => /^session\..*\.jsonl(\.zstd)?$/.test(f)).sort();
                const last = files[files.length - 1];
                if (last) log = path.join(dir, last);
              } catch (e) {}
              out.push({ id: id, workspace: workspace, dir: dir, log: log, updatedAt: Math.round(stat.mtimeMs) });
            }
          }
          return out;
        }

        function projection(id) {
          try {
            const file = path.join(HOME, 'storages', 'session_projcache', 'sessions', id + '.json');
            return JSON.parse(fs.readFileSync(file, 'utf8')).record || null;
          } catch (e) { return null; }
        }

        function rows(id) {
          const record = projection(id);
          return (record && record.rows) || {};
        }

        if (mode === '--list') {
          const answer = sessions().filter(s => s.log).map(s => {
            const r = rows(s.id);
            const title = r.title && r.title.val;
            const stats = (r.sessionStats && r.sessionStats.val) || {};
            const selection = (r.modelSelection && r.modelSelection.val) || {};
            const used = selection.lastUsed || {};
            return {
              id: s.id,
              workspace: s.workspace,
              updatedAt: s.updatedAt,
              title: typeof title === 'string' ? title : null,
              turns: stats.turns || 0,
              provider: used.provider || null,
              model: used.model || null
            };
          });
          process.stdout.write(JSON.stringify(answer));
        } else if (mode === '--transcript') {
          const wanted = process.argv[3];
          const found = sessions().find(s => s.id === wanted);
          if (!found || !found.log) { process.stdout.write('[]'); }
          else {
            let buffer;
            try { buffer = fs.readFileSync(found.log); } catch (e) { buffer = Buffer.alloc(0); }
            if (found.log.endsWith('.zstd')) {
              try { buffer = zlib.zstdDecompressSync(buffer); } catch (e) { buffer = Buffer.alloc(0); }
            }
            const messages = [];
            for (const line of buffer.toString('utf8').split('\n')) {
              if (!line.trim()) continue;
              let entry;
              try { entry = JSON.parse(line); } catch (e) { continue; }
              const event = entry.event || entry;
              const type = event.type || event.kind;
              const data = event.data || {};
              if (type === 'user/message') {
                const text = textOf((data.message && data.message.content) || data.content);
                if (text.trim()) messages.push({ role: 'user', text: text });
              } else if (type === 'assistant/message') {
                const text = textOf((data.message && data.message.content) || data.content);
                if (text.trim()) messages.push({ role: 'assistant', text: text });
              }
            }
            process.stdout.write(JSON.stringify(messages));
          }
        }
    """.trimIndent()
}
