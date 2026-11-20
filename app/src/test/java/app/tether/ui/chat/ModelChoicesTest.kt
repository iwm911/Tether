package app.tether.ui.chat

import app.tether.core.FallbackModels
import app.tether.core.ModelOption
import app.tether.remote.RemoteJson
import app.tether.remote.arr
import app.tether.remote.parseModelOptions
import app.tether.ui.newagent.newAgentModelChoices
import org.junit.Assert.assertEquals
import org.junit.Test

class ModelChoicesTest {

    /** What the helper's `models` returns for an account with Fable (Claude Code 2.1.329's initialize reply). */
    private val machine = parseModelOptions(
        RemoteJson.parseObject(
            """{"models":[
              {"value":"default","displayName":"Default (recommended)","description":"Opus 5.5","resolvedModel":"claude-opus-5-5"},
              {"value":"opus","displayName":"Opus 5.5","description":"For complex work","resolvedModel":"claude-opus-5-5"},
              {"value":"fable","displayName":"Fable 5.2","description":"For your toughest challenges","resolvedModel":"claude-fable-5-2"},
              {"value":"sonnet","displayName":"Sonnet 5.5","resolvedModel":"claude-sonnet-5-5"},
              {"value":"haiku","displayName":"Haiku 5.5","resolvedModel":"claude-haiku-5-5"},
              {"value":"claude-opus-4-8","displayName":"Opus 4.8","resolvedModel":"claude-opus-4-8"},
              {"value":"fable","displayName":"duplicate"},
              {"displayName":"no value"}
            ]}""",
        )?.arr("models"),
    )

    @Test
    fun parsesTheHelpersModelList() {
        assertEquals(listOf("default", "opus", "fable", "sonnet", "haiku", "claude-opus-4-8"), machine.map { it.value })
        assertEquals(ModelOption("fable", "Fable 5.2", "For your toughest challenges", "claude-fable-5-2"), machine[2])
    }

    @Test
    fun labelsASessionModelByTheAliasItResolvesFrom() {
        assertEquals("Fable 5.2", modelLabel("claude-fable-5-2", machine))
        assertEquals("Fable 5.2 1M", modelLabel("claude-fable-5-2[1m]", machine))
        assertEquals("Fable 5.2", modelLabel("fable", machine))
        // "default" resolves to Opus too, but a session on Opus is on Opus.
        assertEquals("Opus 5.5", modelLabel("claude-opus-5-5", machine))
        assertEquals("Opus 4.8", modelLabel("claude-opus-4-8", machine))
        assertEquals("Default", modelLabel(null, machine))
    }

    @Test
    fun labelsFableWithoutAMachineList() {
        assertEquals("Fable 5.2", modelLabel("claude-fable-5-2", FallbackModels))
        assertEquals("Opus 4.5", modelLabel("claude-opus-4-5-20251101", emptyList()))
    }

    @Test
    fun theSheetTicksTheOptionTheSessionIsOn() {
        assertEquals(2, selectedModelIndex(machine, "claude-fable-5-2[1m]"))
        assertEquals(1, selectedModelIndex(machine, "claude-opus-5-5"))
        assertEquals(5, selectedModelIndex(machine, "claude-opus-4-8"))
        assertEquals(0, selectedModelIndex(machine, null))
        assertEquals(-1, selectedModelIndex(machine, "claude-sonnet-4-6"))
        // The built-in list has no resolved ids: a family alias stands for its whole family.
        assertEquals(2, selectedModelIndex(FallbackModels, "claude-sonnet-5"))
    }

    @Test
    fun newSessionsOfferTheMachinesAliasesNotEveryPinnedVersion() {
        assertEquals(listOf("default", "opus", "fable", "sonnet", "haiku"), newAgentModelChoices(machine, "fable").map { it.value })
        assertEquals(
            listOf("default", "opus", "fable", "sonnet", "haiku", "claude-opus-4-8"),
            newAgentModelChoices(machine, "claude-opus-4-8").map { it.value },
        )
        assertEquals("Opus 4.8", newAgentModelChoices(machine, "claude-opus-4-8").last().displayName)
        assertEquals(FallbackModels, newAgentModelChoices(emptyList(), "opus"))
    }
}
