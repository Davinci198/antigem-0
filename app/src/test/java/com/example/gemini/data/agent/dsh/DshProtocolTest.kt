package com.example.gemini.data.agent.dsh

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Locks the `dsh --profile sdk` wire contract: the three request frames we send
 * and how each streamed line is decoded. The frames here are the shapes the
 * guest emits (verified against dsh 0.2.0-rc.2), so a release that changes the
 * event vocabulary fails this test instead of failing silently in the UI.
 */
class DshProtocolTest {

    private val sessionId = "sess-1"
    private val parser = DshSdkParser(sessionId)

    @Test
    fun `initialize frame carries cwd, provider and model`() {
        val frame = DshProtocol.initializeFrame("/workspace/app", "deepseek-official", "deepseek-v4-flash")

        assertTrue(frame.contains("\"jsonrpc\":\"2.0\""))
        assertTrue(frame.contains("\"id\":1"))
        assertTrue(frame.contains("\"method\":\"initialize\""))
        assertTrue(frame.contains("\"cwd\":\"/workspace/app\""))
        assertTrue(frame.contains("\"provider\":\"deepseek-official\""))
        assertTrue(frame.contains("\"model\":\"deepseek-v4-flash\""))
    }

    @Test
    fun `prompt frame carries one text content block`() {
        val frame = DshProtocol.promptFrame(sessionId, "list the files")

        assertTrue(frame.contains("\"id\":2"))
        assertTrue(frame.contains("\"method\":\"session/prompt\""))
        assertTrue(frame.contains("\"sessionId\":\"$sessionId\""))
        assertTrue(frame.contains("\"type\":\"text\""))
        assertTrue(frame.contains("\"text\":\"list the files\""))
    }

    @Test
    fun `shutdown frame has no params`() {
        val frame = DshProtocol.shutdownFrame()

        assertTrue(frame.contains("\"method\":\"shutdown\""))
        assertFalse(frame.contains("params"))
    }

    @Test
    fun `answers are decoded by request id`() {
        assertEquals(DshEvent.Initialized, parser.parse("""{"jsonrpc":"2.0","id":1,"result":{}}"""))
        assertEquals(DshEvent.PromptAccepted, parser.parse("""{"jsonrpc":"2.0","id":2,"result":{}}"""))
        assertEquals(
            DshEvent.ShutdownAcknowledged,
            parser.parse("""{"jsonrpc":"2.0","id":3,"result":{}}"""),
        )
    }

    @Test
    fun `request error becomes a failure with its message`() {
        val event = parser.parse(
            """{"jsonrpc":"2.0","id":2,"error":{"code":-32000,"message":"model rejected the prompt"}}""",
        )

        assertEquals(DshEvent.Failed("model rejected the prompt"), event)
    }

    @Test
    fun `bare diagnostics fail the turn but warnings do not`() {
        assertEquals(DshEvent.Failed("AUTH_MISSING: no api key"), parser.parse("dsh: AUTH_MISSING: no api key"))
        assertEquals(DshEvent.Ignored, parser.parse("dsh: warning: sandbox degraded"))
        assertEquals(DshEvent.Ignored, parser.parse("unrelated stdout noise"))
    }

    @Test
    fun `text deltas stream and the completed message does not repeat them`() {
        val chunk = parser.parse(
            """{"jsonrpc":"2.0","method":"session.event","params":{"sessionId":"$sessionId",
               "event":{"type":"assistant/chunk","data":{"turn":0,"step":0,
               "chunk":{"type":"text-delta","index":0,"text":"Hello"}}}}}""",
        )
        assertEquals(DshEvent.Text("Hello"), chunk)

        // The completed message repeats the same text: it must not be delivered twice.
        val completed = parser.parse(
            """{"jsonrpc":"2.0","method":"session.event","params":{"sessionId":"$sessionId",
               "event":{"type":"assistant/message","data":{"turn":0,"step":0,
               "message":{"role":"assistant","content":[{"type":"text","text":"Hello"}]}}}}}""",
        )
        assertEquals(DshEvent.Ignored, completed)
    }

    @Test
    fun `compacted stream is replayed as one text delta`() {
        val event = parser.parse(
            """{"jsonrpc":"2.0","method":"session.event","params":{"sessionId":"$sessionId",
               "event":{"type":"assistant/message","data":{"turn":0,"step":0,
               "stream":[{"index":0,"type":"text-chunks","texts":["Hel","lo"]}],
               "message":{"role":"assistant","content":[{"type":"text","text":"Hello"}]}}}}}""",
        )

        assertEquals(DshEvent.Text("Hello"), event)
    }

    @Test
    fun `reasoning deltas are surfaced separately from text`() {
        val event = parser.parse(
            """{"jsonrpc":"2.0","method":"session.event","params":{"sessionId":"$sessionId",
               "event":{"type":"assistant/chunk","data":{"turn":0,"step":0,
               "chunk":{"type":"reasoning-delta","index":0,"text":"thinking"}}}}}""",
        )

        assertEquals(DshEvent.Reasoning("thinking"), event)
    }

    @Test
    fun `tool call and result are paired by call id`() {
        val started = parser.parse(
            """{"jsonrpc":"2.0","method":"session.event","params":{"sessionId":"$sessionId",
               "event":{"type":"tool/call","data":{"callId":"c1","name":"bash",
               "arguments":"{\"command\":\"ls -la\"}"}}}}""",
        )
        assertEquals(DshEvent.ToolStarted("c1", "Bash", "ls -la"), started)

        val completed = parser.parse(
            """{"jsonrpc":"2.0","method":"session.event","params":{"sessionId":"$sessionId",
               "event":{"type":"tool/result","data":{"callId":"c1",
               "message":{"toolCallId":"c1","content":[{"type":"tool-result",
               "content":[{"type":"text","text":"a.txt\nb.txt"}]}]}}}}}""",
        )
        assertEquals(DshEvent.ToolCompleted("c1", "Bash", "a.txt b.txt"), completed)
    }

    @Test
    fun `turn end decides completion, failure or cancellation`() {
        assertEquals(DshEvent.TurnCompleted, parseTurnEnd("""{"kind":"stop"}"""))
        assertEquals(
            DshEvent.Failed("hit the step budget"),
            parseTurnEnd("""{"kind":"error","error":{"message":"hit the step budget"}}"""),
        )
        assertEquals(
            DshEvent.Failed("DeepSeek Harness was blocked from completing the task"),
            parseTurnEnd("""{"kind":"blocked"}"""),
        )
        assertEquals(
            DshEvent.Failed("DeepSeek Harness cancelled the turn"),
            parseTurnEnd("""{"kind":"aborted"}"""),
        )
    }

    @Test
    fun `an unknown session is adopted once, then a stranger is ignored`() {
        // A resumed harness answers under its own id. Ignoring it dropped every event,
        // turn/end included: the first turn worked and the rest sat mute.
        val adopted = parser.parse(
            """{"jsonrpc":"2.0","method":"session.event","params":{"sessionId":"other",
               "event":{"type":"assistant/chunk","data":{"turn":0,"step":0,
               "chunk":{"type":"text-delta","index":0,"text":"mine"}}}}}""",
        )
        assertEquals(DshEvent.Text("mine"), adopted)

        val stranger = parser.parse(
            """{"jsonrpc":"2.0","method":"session.event","params":{"sessionId":"third",
               "event":{"type":"assistant/chunk","data":{"turn":0,"step":0,
               "chunk":{"type":"text-delta","index":0,"text":"nope"}}}}}""",
        )
        assertEquals(DshEvent.Ignored, stranger)
    }

    @Test
    fun `session status reports running`() {
        assertEquals(
            DshEvent.Status(true),
            parser.parse(
                """{"jsonrpc":"2.0","method":"session.status","params":{"sessionId":"$sessionId","status":"running"}}""",
            ),
        )
        assertEquals(
            DshEvent.Status(false),
            parser.parse(
                """{"jsonrpc":"2.0","method":"session.status","params":{"sessionId":"$sessionId","status":"idle"}}""",
            ),
        )
    }

    @Test
    fun `the session id the harness answers under is adopted once`() {
        // A resumed harness keeps its own session id. Filtering it out dropped every event,
        // turn/end included, and the turn looked mute: first message answered, the rest hung.
        val adopted = parser.parse(
            """{"jsonrpc":"2.0","method":"session.event","params":{"sessionId":"harness-own",
               "event":{"type":"turn/end","data":{"reason":null}}}}""",
        )
        assertEquals(DshEvent.TurnCompleted, adopted)

        // Only one adoption: a third session cannot steal the turn mid-stream.
        val stranger = parser.parse(
            """{"jsonrpc":"2.0","method":"session.event","params":{"sessionId":"someone-else",
               "event":{"type":"turn/end","data":{"reason":null}}}}""",
        )
        assertEquals(DshEvent.Ignored, stranger)
    }

    private fun parseTurnEnd(reason: String): DshEvent = parser.parse(
        """{"jsonrpc":"2.0","method":"session.event","params":{"sessionId":"$sessionId",
           "event":{"type":"turn/end","data":{"reason":$reason}}}}""",
    )
}
