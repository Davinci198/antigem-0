package com.example.gemini.data.remote

import android.util.Log
import com.example.gemini.data.daemon.IdeApiClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/** A saved AGY account: the credential copy lives in the guest, this is what the UI shows. */
data class AgyAccount(
    val id: String,
    val email: String,
    val label: String = "",
    val savedAt: Long = 0L,
    val promptCredits: Long? = null,
    val flowCredits: Long? = null,
    val plan: String = "",
    val needsRelogin: Boolean = false
) {
    val displayName: String get() = label.ifBlank { email }
}

/** Snapshot of the account store plus the (possibly in-flight) swap. */
data class AgyAccountsState(
    val accounts: List<AgyAccount> = emptyList(),
    val activeId: String = "",
    val activeEmail: String = "",
    val switchPhase: String = "idle",
    val switchMessage: String = "",
    val switchTargetId: String = "",
    val switchTargetEmail: String = ""
) {
    fun isActive(account: AgyAccount): Boolean =
        (activeId.isNotEmpty() && account.id == activeId) ||
            (activeEmail.isNotEmpty() && account.email.equals(activeEmail, ignoreCase = true))
}

/**
 * Client for the bridge's AGY account endpoints. AGY keeps exactly one Google account on disk, so a
 * "saved account" is a copy of its credential file; switching writes a copy back and restarts the hub,
 * which takes tens of seconds and is therefore polled instead of awaited in one request.
 */
object AgyAccountStore {
    private const val TAG = "AgyAccountStore"
    private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

    private val client get() = IdeApiClient.okHttpClient
    private val baseUrl get() = IdeApiClient.baseUrl

    private fun parseAccount(obj: JSONObject?): AgyAccount? {
        obj ?: return null
        val id = obj.optString("id")
        val email = obj.optString("email")
        if (id.isBlank() || email.isBlank()) return null
        return AgyAccount(
            id = id,
            email = email,
            label = obj.optString("label"),
            savedAt = parseTimestamp(obj.optString("savedAt")),
            promptCredits = obj.optLongOrNull("lastKnownPromptCredits"),
            flowCredits = obj.optLongOrNull("lastKnownFlowCredits"),
            plan = obj.optString("lastKnownPlan"),
            needsRelogin = obj.optBoolean("needsRelogin", false)
        )
    }

    private fun JSONObject.optLongOrNull(key: String): Long? =
        if (has(key) && !isNull(key)) optLong(key) else null

    private fun parseTimestamp(raw: String): Long {
        if (raw.isBlank()) return 0L
        return runCatching {
            val fmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).apply {
                timeZone = TimeZone.getTimeZone("UTC")
            }
            // Go emits RFC3339 with a variable number of fractional digits; trim them before parsing.
            val trimmed = raw.substringBefore('.').substringBefore('Z').substringBefore('+')
            fmt.parse(trimmed)?.time ?: 0L
        }.getOrDefault(0L)
    }

    private fun parseState(body: String): AgyAccountsState {
        val root = JSONObject(body)
        val arr = root.optJSONArray("accounts")
        val accounts = buildList {
            if (arr != null) {
                for (i in 0 until arr.length()) {
                    parseAccount(arr.optJSONObject(i))?.let { add(it) }
                }
            }
        }
        val sw = root.optJSONObject("switch")
        return AgyAccountsState(
            accounts = accounts,
            activeId = root.optString("activeId"),
            activeEmail = root.optString("activeEmail"),
            switchPhase = sw?.optString("phase").orEmpty().ifBlank { "idle" },
            switchMessage = sw?.optString("message").orEmpty(),
            switchTargetId = sw?.optString("targetId").orEmpty(),
            switchTargetEmail = sw?.optString("targetEmail").orEmpty()
        )
    }

    suspend fun list(): Result<AgyAccountsState> = withContext(Dispatchers.IO) {
        runCatching {
            val request = Request.Builder().url("$baseUrl/api/agy/accounts").get().build()
            client.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) error("HTTP ${response.code}: ${extractError(body)}")
                parseState(body)
            }
        }.onFailure { Log.w(TAG, "list failed: ${it.message}") }
    }

    /** Saves the account AGY is signed into right now. Credits are hints for the list only. */
    suspend fun saveCurrent(
        label: String = "",
        promptCredits: Long? = null,
        flowCredits: Long? = null,
        plan: String = ""
    ): Result<AgyAccount> = withContext(Dispatchers.IO) {
        runCatching {
            val payload = JSONObject().apply {
                put("label", label)
                promptCredits?.let { put("promptCredits", it) }
                flowCredits?.let { put("flowCredits", it) }
                if (plan.isNotBlank()) put("plan", plan)
            }.toString()
            val request = Request.Builder()
                .url("$baseUrl/api/agy/accounts/save")
                .post(payload.toRequestBody(JSON_MEDIA_TYPE))
                .build()
            client.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) error(extractError(body))
                parseAccount(JSONObject(body).optJSONObject("account"))
                    ?: error("the bridge returned no account")
            }
        }.onFailure { Log.w(TAG, "saveCurrent failed: ${it.message}") }
    }

    /**
     * Starts a swap. The call returns as soon as the credential is installed; use [status] to follow
     * the hub restart and verification.
     */
    suspend fun switchTo(
        id: String,
        promptCredits: Long? = null,
        flowCredits: Long? = null,
        plan: String = ""
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val payload = JSONObject().apply {
                put("id", id)
                promptCredits?.let { put("promptCredits", it) }
                flowCredits?.let { put("flowCredits", it) }
                if (plan.isNotBlank()) put("plan", plan)
            }.toString()
            val request = Request.Builder()
                .url("$baseUrl/api/agy/accounts/switch")
                .post(payload.toRequestBody(JSON_MEDIA_TYPE))
                .build()
            client.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) error(extractError(body))
            }
        }.onFailure { Log.w(TAG, "switchTo failed: ${it.message}") }
    }

    suspend fun status(): Result<AgyAccountsState> = withContext(Dispatchers.IO) {
        runCatching {
            val request = Request.Builder().url("$baseUrl/api/agy/accounts/status").get().build()
            client.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) error(extractError(body))
                parseState(body)
            }
        }.onFailure { Log.w(TAG, "status failed: ${it.message}") }
    }

    suspend fun delete(id: String): Result<Unit> = withContext(Dispatchers.IO) {
        postID("/api/agy/accounts/delete", id, null)
    }

    suspend fun label(id: String, label: String): Result<Unit> = withContext(Dispatchers.IO) {
        postID("/api/agy/accounts/label", id, label)
    }

    private suspend fun postID(path: String, id: String, label: String?): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                val payload = JSONObject().apply {
                    put("id", id)
                    label?.let { put("label", it) }
                }.toString()
                val request = Request.Builder()
                    .url("$baseUrl$path")
                    .post(payload.toRequestBody(JSON_MEDIA_TYPE))
                    .build()
                client.newCall(request).execute().use { response ->
                    val body = response.body?.string().orEmpty()
                    if (!response.isSuccessful) error(extractError(body))
                }
            }.onFailure { Log.w(TAG, "request $path failed: ${it.message}") }
        }

    private fun extractError(body: String): String = runCatching {
        JSONObject(body).optString("error").ifBlank { body.take(200) }
    }.getOrDefault(body.take(200))
}
