package app.tether.ui.chat

import app.tether.core.SlashCommand
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SlashCommandsTest {
    private val cmds = listOf("compact", "/context", "code-review", "review", "context").map { SlashCommand(it, "") }

    @Test
    fun prefixMatchesComeFirstShortestFirstThenSubstrings() {
        assertEquals(listOf("compact", "context", "code-review"), matchSlashCommands("/co", cmds).map { it.name })
        assertEquals(listOf("review", "code-review"), matchSlashCommands("/rev", cmds).map { it.name })
    }

    @Test
    fun onlyWhileTypingABareCommand() {
        assertEquals(4, matchSlashCommands("/", cmds).size)
        assertTrue(matchSlashCommands("/compact now", cmds).isEmpty())
        assertTrue(matchSlashCommands("compact", cmds).isEmpty())
        assertTrue(matchSlashCommands("", cmds).isEmpty())
    }
}
