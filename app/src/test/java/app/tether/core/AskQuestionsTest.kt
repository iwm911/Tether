package app.tether.core

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
    fun answerTextIsWhatClaudeCodeExpects() {
        val p = AskQuestions.parse(input)
        assertEquals("Blue", AskQuestions.answerText(p.questions[0], AskAnswer(listOf(1))))
        assertEquals("Cats, Fish", AskQuestions.answerText(p.questions[1], AskAnswer(listOf(2, 0))))
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
    fun placeholderWithoutQuestionsNeedsFetch() {
        assertTrue(AskQuestions.parse("""{"questions":[],"needsFetch":true}""").needsFetch)
        assertTrue(AskQuestions.parse("""{}""").needsFetch)
    }
}
