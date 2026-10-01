package app.tether.core

import kotlinx.serialization.json.Json

import kotlinx.serialization.json.JsonArray

import kotlinx.serialization.json.JsonObject

import kotlinx.serialization.json.JsonPrimitive

import kotlinx.serialization.json.booleanOrNull

import kotlinx.serialization.json.contentOrNull


/*
 * Claude's AskUserQuestion tool — the multiple-choice questions Claude Code shows as a picker.
 * The phone answers them through the helper's `ask` (it presses the keys in the session's TUI);
 * Claude receives "Label" | "A, B" | free text per question.
 */

const val ASK_USER_QUESTION = "AskUserQuestion"

data class AskOption(val label: String, val description: String = "")

data class AskQuestion(
    val question: String,
    val header: String = "",
    val options: List<AskOption>,
    val multiSelect: Boolean = false,
)

/** One answer: chosen option indices, and/or a typed answer ("Other"). */
data class AskAnswer(val choices: List<Int> = emptyList(), val other: String? = null)

data class AskPrompt(
    val questions: List<AskQuestion>,
    /** A question not yet read from the session's screen — fetch before showing. */
    val needsFetch: Boolean = false,
    /** False when multi-select flags are unknown (never shown to the user as a guess). */
    val multiSelectKnown: Boolean = true,
)

object AskQuestions {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun parse(inputJson: String?): AskPrompt {
        val o = runCatching { json.parseToJsonElement(inputJson ?: "{}") as? JsonObject }.getOrNull() ?: return AskPrompt(emptyList())
        val qs = (o["questions"] as? JsonArray).orEmpty().mapNotNull { e ->
            val q = e as? JsonObject ?: return@mapNotNull null
            val text = q.str("question") ?: return@mapNotNull null
            val opts = (q["options"] as? JsonArray).orEmpty().mapNotNull { oe ->
                val op = oe as? JsonObject ?: return@mapNotNull null
                AskOption(op.str("label") ?: return@mapNotNull null, op.str("description").orEmpty())
            }
            AskQuestion(text, q.str("header").orEmpty(), opts, (q["multiSelect"] as? JsonPrimitive)?.booleanOrNull ?: false)
        }
        return AskPrompt(
            questions = qs,
            needsFetch = (o["needsFetch"] as? JsonPrimitive)?.booleanOrNull == true || qs.isEmpty(),
            multiSelectKnown = (o["multiSelectKnown"] as? JsonPrimitive)?.booleanOrNull ?: true,
        )
    }

    /** The text Claude receives for one answer: labels joined by ", ", plus any typed answer. */
    fun answerText(q: AskQuestion, a: AskAnswer): String {
        val labels = a.choices.sorted().mapNotNull { q.options.getOrNull(it)?.label }
        val other = a.other?.trim()?.takeIf { it.isNotEmpty() }
        return (labels + listOfNotNull(other)).joinToString(", ")
    }

    fun isComplete(q: AskQuestion, a: AskAnswer?): Boolean {
        if (a == null) return false
        val hasOther = !a.other.isNullOrBlank()
        return if (q.multiSelect) a.choices.isNotEmpty() || hasOther else a.choices.size == 1 || hasOther
    }

    private fun JsonObject.str(k: String): String? = (this[k] as? JsonPrimitive)?.contentOrNull
}
