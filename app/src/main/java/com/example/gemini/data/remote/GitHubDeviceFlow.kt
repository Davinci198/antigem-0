package com.example.gemini.data.remote

import kotlinx.coroutines.delay
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

/**
 * GitHub OAuth Device Flow (P9): obtain a user token with a device code, create a repo, and
 * produce the authenticated remote URL used for the push.
 *
 * Requires a registered GitHub OAuth App `client_id` (AuthPreferences.githubClientId). Without it
 * the flow returns [Result.failure] with a human-readable message.
 */
object GitHubDeviceFlow {

    private const val DEVICE_CODE_URL = "https://github.com/login/device/code"
    private const val ACCESS_TOKEN_URL = "https://github.com/login/oauth/access_token"
    private const val CREATE_REPO_URL = "https://api.github.com/user/repos"

    private val jsonMedia = "application/json".toMediaType()
    private val http = OkHttpClient.Builder()
        .connectTimeout(20, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
        .build()

    data class DeviceCode(
        val deviceCode: String,
        val userCode: String,
        val verificationUri: String,
        val intervalSeconds: Int,
        val expiresInSeconds: Int,
    )

    data class TokenResult(
        val accessToken: String,
        val owner: String,
    )

    suspend fun requestDeviceCode(clientId: String): Result<DeviceCode> {
        if (clientId.isBlank()) {
            return Result.failure(IllegalStateException("No GitHub OAuth client id configured"))
        }
        return runCatching {
            val body = "client_id=$clientId&scope=repo".toRequestBody(jsonMedia)
            val req = Request.Builder()
                .url(DEVICE_CODE_URL)
                .header("Accept", "application/json")
                .post(body)
                .build()
            http.newCall(req).execute().use { resp ->
                val json = JSONObject(resp.body?.string() ?: "{}")
                DeviceCode(
                    deviceCode = json.getString("device_code"),
                    userCode = json.getString("user_code"),
                    verificationUri = json.getString("verification_uri"),
                    intervalSeconds = json.optInt("interval", 5).coerceAtLeast(1),
                    expiresInSeconds = json.optInt("expires_in", 900),
                )
            }
        }
    }

    /** Polls until the user authorizes; returns the token + the account login (owner). */
    suspend fun pollAccessToken(clientId: String, deviceCode: DeviceCode): Result<TokenResult> {
        var attempts = 0
        val maxAttempts = deviceCode.expiresInSeconds / deviceCode.intervalSeconds
        while (attempts < maxAttempts) {
            val result = tryPollOnce(clientId, deviceCode.deviceCode)
            if (result != null) return result
            delay(deviceCode.intervalSeconds * 1000L)
            attempts++
        }
        return Result.failure(IllegalStateException("Device flow timed out — try again"))
    }

    private suspend fun tryPollOnce(clientId: String, deviceCode: String): Result<TokenResult>? {
        val body = buildString {
            append("client_id=").append(clientId)
            append("&device_code=").append(deviceCode)
            append("&grant_type=urn:ietf:params:oauth:grant-type:device_code")
        }.toRequestBody(jsonMedia)
        val req = Request.Builder()
            .url(ACCESS_TOKEN_URL)
            .header("Accept", "application/json")
            .post(body)
            .build()
        return runCatching {
            http.newCall(req).execute().use { resp ->
                val json = JSONObject(resp.body?.string() ?: "{}")
                val token = json.optString("access_token")
                val error = json.optString("error")
                when {
                    token.isNotBlank() -> {
                        val owner = fetchOwner(token)
                        Result.success(TokenResult(token, owner))
                    }
                    error == "authorization_pending" || error == "slow_down" -> null
                    else -> Result.failure(IllegalStateException("GitHub device flow error: ${error.ifBlank { "unknown" }}"))
                }
            }
        }.getOrElse { Result.failure(it) }
    }

    private suspend fun fetchOwner(token: String): String {
        val req = Request.Builder()
            .url("https://api.github.com/user")
            .header("Authorization", "Bearer $token")
            .header("Accept", "application/vnd.github+json")
            .build()
        http.newCall(req).execute().use { resp ->
            val json = JSONObject(resp.body?.string() ?: "{}")
            return json.optString("login", "")
        }
    }

    suspend fun createRepository(token: String, name: String, description: String, isPrivate: Boolean): Result<Pair<String, String>> {
        val body = JSONObject().apply {
            put("name", name)
            put("description", description)
            put("private", isPrivate)
            put("auto_init", false)
        }.toString().toRequestBody(jsonMedia)
        val req = Request.Builder()
            .url(CREATE_REPO_URL)
            .header("Authorization", "Bearer $token")
            .header("Accept", "application/vnd.github+json")
            .post(body)
            .build()
        return runCatching {
            http.newCall(req).execute().use { resp ->
                val json = JSONObject(resp.body?.string() ?: "{}")
                if (!resp.isSuccessful) {
                    throw IllegalStateException("GitHub create repo failed: ${json.optString("message", "HTTP ${resp.code}")}")
                }
                val fullName = json.optString("full_name", "")
                val owner = fullName.substringBefore('/')
                val repoName = fullName.substringAfter('/')
                owner to repoName
            }
        }
    }

    /** Token-bearing HTTPS remote URL for git push (x-access-token user). */
    fun authenticatedRemoteUrl(token: String, owner: String, repoName: String): String =
        "https://x-access-token:$token@github.com/$owner/$repoName.git"
}
