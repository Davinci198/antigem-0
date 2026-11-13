package com.example.gemini.data.remote

/**
 * Streaming events shared by the app's chat backends. They used to live next to
 * AntigravityApiService, the app's own direct Google client; that client is gone because
 * every Antigravity request now goes through the agy CLI running in the container.
 */
sealed class StreamEvent {
    data class TextChunk(val text: String) : StreamEvent()
    data class ThoughtChunk(val thought: String) : StreamEvent()
    data class Completed(val tokenUsage: com.example.gemini.domain.model.TokenUsage?, val rawPayload: String? = null) : StreamEvent()
    data class Error(val message: String) : StreamEvent()
}
