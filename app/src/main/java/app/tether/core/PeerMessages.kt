package app.tether.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Claude Code's tool for messaging another session (one-session model). */
const val SEND_MESSAGE = "SendMessage"

/** What a `SendMessage` call sends, read from its tool input. */
data class SendMessageInput(
    /** Recipient address as Claude wrote it (`uds:…`, a name, a session id). */
    val to: String?,
    /** Recipient display name, when the input carries one. */
    val toName: String?,
    /** The message itself; null when the input has no text (yet). */
    val text: String?,
) {
    /** Who it goes to, for people: the name, else a readable address. */
    val recipient: String get() = peerDisplayName(toName, to) ?: "another session"
}

private val PeerJson = Json { ignoreUnknownKeys = true; isLenient = true }

/** Parses a `SendMessage` input; unknown or partial JSON gives empty fields, never throws. */
fun parseSendMessage(inputJson: String): SendMessageInput {
    val obj = runCatching { PeerJson.parseToJsonElement(inputJson) as? JsonObject }.getOrNull()
        ?: return SendMessageInput(null, null, null)
    fun str(k: String) = (obj[k] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotBlank() }
    return SendMessageInput(
        to = str("to") ?: str("recipient"),
        toName = str("toName") ?: str("name"),
        text = str("message") ?: str("content") ?: str("text") ?: str("summary"),
    )
}

/** A peer's name, else the last part of its address (`uds:/tmp/x/abc.sock` → `abc.sock`); null when neither is known. */
fun peerDisplayName(name: String?, address: String?): String? =
    name?.trim()?.takeIf { it.isNotEmpty() }
        ?: address?.trim()?.removePrefix("uds:")?.substringAfterLast('/')?.takeIf { it.isNotEmpty() }
