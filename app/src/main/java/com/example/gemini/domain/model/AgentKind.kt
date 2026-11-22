package com.example.gemini.domain.model

/** Which coding agent owns a conversation. Fixed when the conversation is created. */
enum class AgentKind {
    AGY,
    CLAUDE,
    HERMES,
    OPENCODE,

    /** DeepSeek Harness (`dsh`), driven over its SDK profile. */
    DEEPSEEK_HARNESS
}
