package app.tether.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WorkflowMetaTest {

    private fun input(json: String): JsonObject = Json.parseToJsonElement(json).jsonObject

    private fun scriptInput(script: String): JsonObject =
        JsonObject(mapOf("script" to kotlinx.serialization.json.JsonPrimitive(script)))

    @Test
    fun readsTheInlineScriptsMetaLiteral() {
        val m = WorkflowMeta.of(
            scriptInput(
                """
                export const meta = {
                  name: 'review-changes',
                  description: "Review the diff, then verify each finding",
                  phases: [
                    { title: 'Review', detail: 'one agent per dimension' },
                    { title: 'Verify', description: 'not the workflow description' },
                  ],
                }
                const name = 'not this'
                phase('Review')
                """.trimIndent(),
            ),
        )
        assertEquals("review-changes", m.name)
        assertEquals("Review the diff, then verify each finding", m.description)
        assertEquals(listOf("Review", "Verify"), m.phases)
        assertEquals("Review the diff, then verify each finding", m.title)
    }

    @Test
    fun phasesBeforeTheDescriptionDoNotLeakIntoIt() {
        val m = WorkflowMeta.of(
            scriptInput("export const meta = { phases: [{ title: 'A', description: 'inner' }], name: 'x', description: 'outer \\'quoted\\'' }"),
        )
        assertEquals("outer 'quoted'", m.description)
        assertEquals(listOf("A"), m.phases)
    }

    @Test
    fun savedScriptsUseTheInputsOwnFields() {
        val m = WorkflowMeta.of(input("""{"scriptPath":"/home/u/.claude/workflows/scripts/audit-wf_1.js","resumeFromRunId":"wf_1","description":"Re-run the audit"}"""))
        assertNull(m.name)
        assertEquals("Re-run the audit", m.title)
        assertEquals("wf_1", m.resumeFrom)
        assertEquals("audit-wf_1", WorkflowMeta.of(input("""{"scriptPath":"/a/audit-wf_1.js"}""")).title)
        assertEquals("deploy", WorkflowMeta.of(input("""{"name":"deploy"}""")).title)
        assertNull(WorkflowMeta.of(null).title)
    }
}
