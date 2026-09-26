package app.tether.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AskQuestionsTest {
    // Shape captured from Claude Code 2.1.283's can_use_tool request.
    private val input = """{"questions":[
        {"question":"Which color do you prefer?","header":"Color","options":[{"label":"Red","description":"warm"},{"label":"Blue","description":"cool"}],"multiSelect":false},
        {"question":"Which pets do you like?","header":"Pets","options":[{"label":"Cats"},{"label":"Dogs"},{"label":"Fish"}],"multiSelect":true}]}"""

    @Test
    fun parsesQuestionsOptionsAndFlags() {
        val p = AskQuestions.parse(input)
        assertEquals(2, p.questions.size)
        assertFalse(p.needsFetch)
        assertEquals("Color", p.questions[0].header)
        assertEquals(listOf("Red", "Blue"), p.questions[0].options.map { it.label })
        assertTrue(p.questions[1].multiSelect)
    }

    @Test
    fun updatedInputCarriesAnswersExactlyLikeClaudeCodeExpects() {
        val out = AskQuestions.updatedInput(input, listOf(AskAnswer(listOf(1)), AskAnswer(listOf(2, 0))))
        val o = Json.parseToJsonElement(out).jsonObject
        val answers = o["answers"]!!.jsonObject
        assertEquals("Blue", answers["Which color do you prefer?"]!!.jsonPrimitive.content)
        assertEquals("Cats, Fish", answers["Which pets do you like?"]!!.jsonPrimitive.content)
        assertTrue(o["questions"] != null) // original input kept
    }

    @Test
    fun typedAnswerAndCompleteness() {
        val p = AskQuestions.parse(input)
        assertEquals("My own", AskQuestions.answerText(p.questions[0], AskAnswer(other = "My own")))
        assertTrue(AskQuestions.isComplete(p.questions[0], AskAnswer(other = "x")))
        assertFalse(AskQuestions.isComplete(p.questions[1], AskAnswer()))
        assertTrue(AskQuestions.isComplete(p.questions[1], AskAnswer(listOf(0, 1))))
    }

    @Test
    fun placeholderFromBackgroundAgentNeedsFetch() {
        val p = AskQuestions.parse("""{"questions":[],"needsFetch":true}""")
        assertTrue(p.needsFetch)
        val clean = Json.parseToJsonElement(AskQuestions.updatedInput("""{"questions":[],"needsFetch":true}""", emptyList())) as JsonObject
        assertFalse(clean.containsKey("needsFetch"))
    }
}
