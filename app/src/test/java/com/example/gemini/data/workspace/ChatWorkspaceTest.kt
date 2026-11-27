package com.example.gemini.data.workspace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The path rules matter because the folder is what an agent uses as its working directory: if two chats map to the
 * same one, one chat's files appear in the other, and if the harness root leaked in here, every chat would share it.
 */
class ChatWorkspaceTest {

    @Test
    fun `the guest path is per conversation`() {
        assertEquals("/root/chats/abc", ChatWorkspace.guestPath("abc"))
    }

    @Test
    fun `the uri form strips back to the same guest path`() {
        assertEquals("/root/chats/abc", ChatWorkspace.uri("abc").removePrefix("file://"))
    }

    @Test
    fun `a chat is recognised from its workspace path`() {
        assertEquals("abc", ChatWorkspace.conversationOf("/root/chats/abc"))
        assertEquals("abc", ChatWorkspace.conversationOf("file:///root/chats/abc/"))
    }

    @Test
    fun `the harness root is not a chat workspace`() {
        assertNull(ChatWorkspace.conversationOf("/root/chat"))
        assertNull(ChatWorkspace.conversationOf("/root/workspace/chats/abc"))
        assertNull(ChatWorkspace.conversationOf(""))
        assertNull(ChatWorkspace.conversationOf(null))
    }

    @Test
    fun `a project folder the user opened is not a chat workspace`() {
        assertNull(ChatWorkspace.conversationOf("/root/AndroidProjects/notes"))
    }
}
