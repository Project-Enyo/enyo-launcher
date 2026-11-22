package space.koyu.enyo

import android.content.Intent
import android.net.Uri
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import org.commonmark.ext.gfm.strikethrough.Strikethrough
import org.commonmark.ext.gfm.strikethrough.StrikethroughExtension
import org.commonmark.ext.gfm.tables.TableCell
import org.commonmark.ext.gfm.tables.TableRow
import org.commonmark.ext.gfm.tables.TablesExtension
import org.commonmark.node.BlockQuote
import org.commonmark.node.BulletList
import org.commonmark.node.Code
import org.commonmark.node.Emphasis
import org.commonmark.node.FencedCodeBlock
import org.commonmark.node.HardLineBreak
import org.commonmark.node.Heading
import org.commonmark.node.HtmlBlock
import org.commonmark.node.HtmlInline
import org.commonmark.node.Image
import org.commonmark.node.IndentedCodeBlock
import org.commonmark.node.Link
import org.commonmark.node.LinkReferenceDefinition
import org.commonmark.node.Node
import org.commonmark.node.OrderedList
import org.commonmark.node.SoftLineBreak
import org.commonmark.node.StrongEmphasis
import org.commonmark.node.Text as MarkdownLiteral
import org.commonmark.node.ThematicBreak
import org.commonmark.parser.Parser

private val chatMarkdown = Parser.builder()
    .extensions(listOf(StrikethroughExtension.create(), TablesExtension.create()))
    .build()

@Composable
internal fun ChatMarkdown(text: String, style: TextStyle, maxLines: Int = Int.MAX_VALUE) {
    val tokens = LocalTokens.current
    val context = LocalContext.current
    val rendered = remember(text, style.fontSize, tokens.accent, tokens.widgetTrack, context) {
        val code = SpanStyle(fontFamily = FontFamily.Monospace, background = tokens.widgetTrack)
        val linkStyles = TextLinkStyles(SpanStyle(color = tokens.accent, textDecoration = TextDecoration.Underline))
        val document = chatMarkdown.parse(text)
        buildAnnotatedString {
            fun render(node: Node, depth: Int = 0): Unit {
                if (depth > 40) return
                fun children(separator: String = "") {
                    node.children().forEachIndexed { index, child ->
                        if (index > 0) append(separator)
                        render(child, depth + 1)
                    }
                }
                when (node) {
                    is MarkdownLiteral -> append(node.literal)
                    is Heading -> withStyle(SpanStyle(fontWeight = FontWeight.Bold,
                        fontSize = style.fontSize * if (node.level <= 2) 1.22f else 1.08f)) { children() }
                    is StrongEmphasis -> withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { children() }
                    is Emphasis -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { children() }
                    is Strikethrough -> withStyle(SpanStyle(textDecoration = TextDecoration.LineThrough)) { children() }
                    is Code -> withStyle(code) { append(node.literal) }
                    is FencedCodeBlock -> withStyle(code) { append(node.literal.trimEnd('\n')) }
                    is IndentedCodeBlock -> withStyle(code) { append(node.literal.trimEnd('\n')) }
                    is HardLineBreak, is SoftLineBreak -> append('\n')
                    is BlockQuote -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
                        append("› ")
                        children("\n› ")
                    }
                    is OrderedList, is BulletList -> {
                        val start = (node as? OrderedList)?.markerStartNumber ?: 1
                        node.children().forEachIndexed { index, item ->
                            if (index > 0) append('\n')
                            append(if (node is OrderedList) "${start + index}. " else "• ")
                            item.children().forEachIndexed { part, child ->
                                if (part > 0) append("\n  ")
                                render(child, depth + 1)
                            }
                        }
                    }
                    is Link -> {
                        val uri = Uri.parse(node.destination)
                        if (uri.scheme in listOf("https", "http") && !uri.host.isNullOrBlank()) {
                            withLink(LinkAnnotation.Url(node.destination, linkStyles) {
                                context.launchSafely(Intent(Intent.ACTION_VIEW, uri))
                            }) { children() }
                        } else children()
                    }
                    is Image -> children()
                    is HtmlInline -> append(node.literal)
                    is HtmlBlock -> append(node.literal.trimEnd('\n'))
                    is ThematicBreak -> append("────")
                    is LinkReferenceDefinition -> Unit
                    is TableRow -> children("  │  ")
                    is TableCell -> if (node.isHeader) withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { children() }
                        else children()
                    is org.commonmark.ext.gfm.tables.TableBlock -> children("\n")
                    is org.commonmark.ext.gfm.tables.TableBody -> children("\n")
                    else -> children()
                }
            }
            document.children().filterNot { it is LinkReferenceDefinition }.forEachIndexed { index, node ->
                if (index > 0) append("\n\n")
                render(node)
            }
        }.takeIf { it.isNotBlank() } ?: AnnotatedString(text)
    }
    Text(rendered, style = style, maxLines = maxLines, overflow = TextOverflow.Ellipsis)
}

private fun Node.children(): Sequence<Node> = generateSequence(firstChild) { it.next }
