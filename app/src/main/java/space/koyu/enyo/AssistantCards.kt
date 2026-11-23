package space.koyu.enyo

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.json.JSONArray
import org.json.JSONObject

/** What Local AI looked at for an answer, shown as cards under it and saved with the chat. */
internal sealed class AssistantCard {
    abstract fun toJson(): JSONObject

    data class Clock(val date: String, val time: String, val zone: String) : AssistantCard() {
        override fun toJson() = JSONObject().put("type", "clock").put("date", date).put("time", time).put("zone", zone)
    }

    data class Weather(val celsius: Int, val conditions: String) : AssistantCard() {
        override fun toJson() = JSONObject().put("type", "weather").put("celsius", celsius).put("conditions", conditions)
    }

    data class Battery(val percent: Int, val charging: Boolean) : AssistantCard() {
        override fun toJson() = JSONObject().put("type", "battery").put("percent", percent).put("charging", charging)
    }

    /** Events as "when: title" lines; empty when nothing is coming up. */
    data class Calendar(val events: List<String>) : AssistantCard() {
        override fun toJson() = JSONObject().put("type", "calendar").put("events", JSONArray(events))
    }

    data class Network(val lines: List<String>) : AssistantCard() {
        override fun toJson() = JSONObject().put("type", "network").put("lines", JSONArray(lines))
    }

    data class Chat(val thread: ThreadRef, val name: String, val preview: String?) : AssistantCard() {
        override fun toJson() = JSONObject().put("type", "chat").put("name", name).putOpt("preview", preview).apply {
            when (thread) {
                is ThreadRef.Person -> put("person", thread.id)
                is ThreadRef.Group -> put("group", thread.conversationId)
            }
        }
    }

    companion object {
        fun from(json: JSONObject): AssistantCard? = runCatching {
            fun strings(name: String) = json.optJSONArray(name)?.let { array -> List(array.length()) { array.optString(it) } }.orEmpty()
            when (json.optString("type")) {
                "clock" -> Clock(json.getString("date"), json.getString("time"), json.optString("zone"))
                "weather" -> Weather(json.getInt("celsius"), json.getString("conditions"))
                "battery" -> Battery(json.getInt("percent"), json.optBoolean("charging"))
                "calendar" -> Calendar(strings("events"))
                "network" -> Network(strings("lines"))
                "chat" -> Chat(
                    if (json.has("person")) ThreadRef.Person(json.getLong("person")) else ThreadRef.Group(json.getLong("group")),
                    json.getString("name"), json.optString("preview").ifEmpty { null }
                )
                else -> null
            }
        }.getOrNull()
    }
}

/** The cards under a Local AI reply. [openChat] shows a chat a card refers to. */
@Composable
internal fun AssistantCards(cards: List<AssistantCard>, openChat: (ThreadRef) -> Unit) {
    if (cards.isEmpty()) return
    val tokens = LocalTokens.current
    Column(Modifier.widthIn(max = tokens.bubbleMaxWidth), verticalArrangement = Arrangement.spacedBy(tokens.gap / 2)) {
        cards.forEach { card ->
            when (card) {
                is AssistantCard.Clock -> CardRow(Glyph.Clock, card.time, listOf(card.date, card.zone))
                is AssistantCard.Weather -> CardRow(Glyph.Today, "${card.celsius} °C", listOf(card.conditions))
                is AssistantCard.Battery -> CardRow(Glyph.Battery, "${card.percent} %",
                    listOf(stringResource(if (card.charging) R.string.local_ai_card_charging else R.string.local_ai_card_not_charging)),
                    progress = card.percent / 100f)
                is AssistantCard.Calendar -> CardRow(Glyph.Calendar, stringResource(R.string.local_ai_card_calendar),
                    card.events.ifEmpty { listOf(stringResource(R.string.local_ai_card_no_events)) })
                is AssistantCard.Network -> CardRow(Glyph.Wifi, stringResource(R.string.local_ai_card_network), card.lines)
                is AssistantCard.Chat -> CardRow(Glyph.Messages, card.name, listOfNotNull(card.preview), action = { openChat(card.thread) })
            }
        }
    }
}

@Composable
private fun CardRow(glyph: Glyph, title: String, lines: List<String>, progress: Float? = null, action: (() -> Unit)? = null) {
    val tokens = LocalTokens.current
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(tokens.radius / 2)).background(tokens.widgetSurface)
            .then(if (action != null) Modifier.appPress(action) else Modifier).padding(tokens.gap),
        horizontalArrangement = Arrangement.spacedBy(tokens.gap), verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(tokens.listAvatar).clip(CircleShape).background(tokens.accentContainer), contentAlignment = Alignment.Center) {
            EnyoGlyph(glyph, color = tokens.onAccentContainer)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = tokens.rowTitle, maxLines = 1, overflow = TextOverflow.Ellipsis)
            lines.forEach { Text(it, style = tokens.rowSummary, maxLines = 2, overflow = TextOverflow.Ellipsis) }
            if (progress != null) Box(Modifier.fillMaxWidth().height(4.dp).clip(CircleShape).background(tokens.widgetTrack)) {
                Box(Modifier.fillMaxWidth(progress.coerceIn(0f, 1f)).fillMaxHeight().background(tokens.accent))
            }
        }
        if (action != null) EnyoGlyph(Glyph.Forward, color = tokens.quiet)
    }
}

