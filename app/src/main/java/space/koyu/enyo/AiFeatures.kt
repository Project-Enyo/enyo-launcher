package space.koyu.enyo

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * The switch for every AI feature: Local AI and its memory, summaries, request interpretation,
 * spelling suggestions and semantic file search. Off until the person opts in; turning it off
 * keeps models, chats and memories so they come back when it is turned on again.
 */
private val aiFeaturesKey = booleanPreferencesKey("ai_features")

internal fun aiFeatures(context: Context) = context.searchPreferences.data.map { it[aiFeaturesKey] == true }

internal suspend fun aiFeaturesOn(context: Context) = aiFeatures(context).first()

internal suspend fun setAiFeatures(context: Context, on: Boolean) = context.searchPreferences.edit { it[aiFeaturesKey] = on }

@Composable
internal fun aiFeaturesEnabled(): Boolean {
    val context = LocalContext.current
    return remember(context) { aiFeatures(context) }.collectAsState(false).value
}
