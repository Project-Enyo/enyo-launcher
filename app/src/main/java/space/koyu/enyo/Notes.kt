package space.koyu.enyo

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.clickable
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.selection.toggleable
import androidx.sqlite.db.SupportSQLiteDatabase
import java.util.UUID

internal enum class NoteKind { Note, Todo }

internal data class NoteItem(
    val uid: String,
    val kind: NoteKind,
    val text: String,
    val done: Boolean,
    val pinned: Boolean,
    val position: Long,
    val updatedAt: Long
)

/** Notes and to-dos, kept in the encrypted archive database next to the messages. */
internal object Notes {
    fun createSchema(db: SupportSQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS notes (
                id INTEGER PRIMARY KEY,
                uid TEXT NOT NULL UNIQUE,
                kind TEXT NOT NULL,
                text TEXT NOT NULL,
                done INTEGER NOT NULL DEFAULT 0,
                pinned INTEGER NOT NULL DEFAULT 0,
                position INTEGER NOT NULL DEFAULT 0,
                created_at INTEGER NOT NULL,
                updated_at INTEGER NOT NULL
            )"""
        )
    }

    fun all(context: Context): List<NoteItem> = MessageArchive.get(context).read { db ->
        db.query("SELECT uid, kind, text, done, pinned, position, updated_at FROM notes ORDER BY pinned DESC, position DESC, updated_at DESC").use { cursor ->
            buildList {
                while (cursor.moveToNext()) add(NoteItem(
                    cursor.getString(0), if (cursor.getString(1) == "todo") NoteKind.Todo else NoteKind.Note,
                    cursor.getString(2), cursor.getInt(3) == 1, cursor.getInt(4) == 1, cursor.getLong(5), cursor.getLong(6)
                ))
            }
        }
    }.orEmpty()

    fun add(context: Context, kind: NoteKind, text: String) {
        val now = System.currentTimeMillis()
        MessageArchive.get(context).write { db ->
            db.execSQL(
                "INSERT INTO notes(uid, kind, text, position, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?)",
                arrayOf(UUID.randomUUID().toString(), if (kind == NoteKind.Todo) "todo" else "note", text, now, now, now)
            )
        }
    }

    fun update(context: Context, uid: String, text: String? = null, done: Boolean? = null, pinned: Boolean? = null) {
        val now = System.currentTimeMillis()
        MessageArchive.get(context).write { db ->
            text?.let { db.execSQL("UPDATE notes SET text = ?, updated_at = ? WHERE uid = ?", arrayOf(it, now, uid)) }
            done?.let { db.execSQL("UPDATE notes SET done = ?, updated_at = ? WHERE uid = ?", arrayOf(if (it) 1 else 0, now, uid)) }
            pinned?.let { db.execSQL("UPDATE notes SET pinned = ?, updated_at = ? WHERE uid = ?", arrayOf(if (it) 1 else 0, now, uid)) }
        }
    }

    fun delete(context: Context, uid: String) = MessageArchive.get(context).write { db ->
        db.execSQL("DELETE FROM notes WHERE uid = ?", arrayOf(uid))
    }

    fun clearDone(context: Context) = MessageArchive.get(context).write { db ->
        db.execSQL("DELETE FROM notes WHERE kind = 'todo' AND done = 1")
    }
}

/** The Inbox section below the messages: open to-dos with a quick add field, then notes. */
@Composable
internal fun NotesSection() {
    val context = LocalContext.current
    val tokens = LocalTokens.current
    val items = rememberArchive(emptyList<NoteItem>()) { Notes.all(it) }
    val todos = items.filter { it.kind == NoteKind.Todo }
    val open = todos.filterNot { it.done }
    val done = todos.filter { it.done }
    val notes = items.filter { it.kind == NoteKind.Note }
    var showDone by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<NoteItem?>(null) }
    var creating by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(tokens.gap)) {
        SettingsPanel(stringResource(R.string.notes_todos)) {
            QuickTodoField { Notes.add(context, NoteKind.Todo, it) }
            open.forEach { item -> key(item.uid) { TodoRow(item) { editing = item } } }
            if (done.isNotEmpty()) {
                SettingsAction(
                    stringResource(if (showDone) R.string.notes_hide_done else R.string.notes_show_done, done.size),
                    Glyph.Checked
                ) { showDone = !showDone }
                if (showDone) {
                    done.forEach { item -> key(item.uid) { TodoRow(item) { editing = item } } }
                    SettingsAction(stringResource(R.string.notes_clear_done), Glyph.Trash) { Notes.clearDone(context) }
                }
            }
        }
        SettingsPanel(stringResource(R.string.notes_notes)) {
            notes.forEach { note ->
                key(note.uid) {
                    val lines = note.text.trim().lines()
                    ListRow(
                        lines.firstOrNull().orEmpty().ifBlank { stringResource(R.string.notes_untitled) },
                        Modifier.appPress({ editing = note }),
                        summary = lines.drop(1).joinToString(" ").trim().takeIf { it.isNotEmpty() }, summaryLines = 2,
                        leading = { EnyoGlyph(if (note.pinned) Glyph.Pin else Glyph.Note, color = tokens.accent) }
                    )
                }
            }
            SettingsAction(stringResource(R.string.notes_new), Glyph.Plus) { creating = true }
        }
    }
    if (creating) NoteEditor(null) { creating = false }
    editing?.let { item -> NoteEditor(item) { editing = null } }
}

@Composable
private fun QuickTodoField(add: (String) -> Unit) {
    val tokens = LocalTokens.current
    var text by remember { mutableStateOf("") }
    val hint = stringResource(R.string.notes_add_todo)
    fun submit() { if (text.isNotBlank()) { add(text.trim()); text = "" } }
    Row(
        Modifier.fillMaxWidth().heightIn(min = tokens.listRowHeight).padding(horizontal = tokens.padding, vertical = tokens.listRowPadding),
        horizontalArrangement = Arrangement.spacedBy(tokens.padding),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(tokens.listLeading), contentAlignment = Alignment.Center) { EnyoGlyph(Glyph.Plus, color = tokens.quiet) }
        BasicTextField(
            text, { text = it },
            Modifier.weight(1f).semantics { contentDescription = hint },
            singleLine = true,
            textStyle = tokens.body,
            cursorBrush = SolidColor(tokens.accent),
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { submit() }),
            decorationBox = { field ->
                Box(contentAlignment = Alignment.CenterStart) {
                    if (text.isEmpty()) Text(hint, style = tokens.body.copy(color = tokens.quiet))
                    field()
                }
            }
        )
        if (text.isNotBlank()) PillButton(stringResource(R.string.notes_add), filled = true) { submit() }
    }
}

@Composable
private fun TodoRow(item: NoteItem, edit: () -> Unit) {
    val context = LocalContext.current
    val tokens = LocalTokens.current
    Row(
        Modifier.fillMaxWidth().heightIn(min = tokens.listRowHeight).padding(horizontal = tokens.padding, vertical = tokens.listRowPadding),
        horizontalArrangement = Arrangement.spacedBy(tokens.padding),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier.size(tokens.listLeading).clip(RoundedCornerShape(tokens.radius / 2))
                .toggleable(item.done, role = Role.Checkbox) { Notes.update(context, item.uid, done = it) }
                .semantics { contentDescription = item.text },
            contentAlignment = Alignment.Center
        ) { EnyoGlyph(if (item.done) Glyph.Checked else Glyph.Unchecked, color = if (item.done) tokens.accent else tokens.quiet) }
        Text(
            item.text, Modifier.weight(1f).clickable(onClick = edit),
            style = tokens.body.copy(
                color = if (item.done) tokens.quiet else tokens.onAccentContainer,
                textDecoration = if (item.done) TextDecoration.LineThrough else null
            ),
            maxLines = 3, overflow = TextOverflow.Ellipsis
        )
    }
}

/** Writes a new note, or edits a note or to-do, saving when the sheet closes. */
@Composable
private fun NoteEditor(item: NoteItem?, close: () -> Unit) {
    val context = LocalContext.current
    val tokens = LocalTokens.current
    var text by remember(item?.uid) { mutableStateOf(item?.text.orEmpty()) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    fun save() {
        val clean = text.trim()
        when {
            item == null && clean.isNotEmpty() -> Notes.add(context, NoteKind.Note, clean)
            item != null && clean.isEmpty() -> Notes.delete(context, item.uid)
            item != null && clean != item.text -> Notes.update(context, item.uid, text = clean)
        }
        close()
    }
    val title = when {
        item == null -> stringResource(R.string.notes_new)
        item.kind == NoteKind.Todo -> stringResource(R.string.notes_edit_todo)
        else -> stringResource(R.string.notes_edit)
    }
    EnyoSheet(title, ::save) {
        BasicTextField(
            text, { text = it },
            Modifier.fillMaxWidth().heightIn(min = if (item?.kind == NoteKind.Todo) tokens.touch else tokens.noteEditorHeight)
                .clip(RoundedCornerShape(tokens.radius)).background(tokens.widgetTrack)
                .padding(tokens.padding).focusRequester(focus),
            textStyle = tokens.body,
            cursorBrush = SolidColor(tokens.accent),
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences)
        )
        Row(horizontalArrangement = Arrangement.spacedBy(tokens.gap)) {
            if (item != null) PillButton(stringResource(R.string.notes_delete)) { Notes.delete(context, item.uid); close() }
            if (item?.kind == NoteKind.Note) PillButton(stringResource(if (item.pinned) R.string.notes_unpin else R.string.notes_pin)) {
                Notes.update(context, item.uid, pinned = !item.pinned)
            }
            Spacer(Modifier.weight(1f))
            PillButton(stringResource(R.string.notes_done), filled = true) { save() }
        }
    }
}
