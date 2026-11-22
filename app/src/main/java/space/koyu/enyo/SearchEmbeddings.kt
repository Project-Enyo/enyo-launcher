package space.koyu.enyo

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.ContentResolver
import android.graphics.ImageDecoder
import android.graphics.Rect
import org.json.JSONObject
import java.io.File
import java.nio.FloatBuffer
import java.nio.LongBuffer
import java.text.Normalizer
import java.util.Locale
import kotlin.math.roundToInt
import kotlin.math.sqrt

internal enum class EmbeddingKind { Document, Image }

internal class SearchEmbeddings(private val files: File) : AutoCloseable {
    private val environment = OrtEnvironment.getEnvironment()
    private val sessions = mutableMapOf<String, OrtSession>()
    private val wordPieces by lazy { WordPieces(File(files, "vocab.txt")) }
    private val imageTokens by lazy { ImageTokens(File(files, "vocab.json"), File(files, "merges.txt")) }

    private fun session(name: String) = sessions.getOrPut(name) {
        OrtSession.SessionOptions().use { options ->
            options.setIntraOpNumThreads(2)
            options.setInterOpNumThreads(1)
            environment.createSession(File(files, name).absolutePath, options)
        }
    }

    fun text(text: String, kind: EmbeddingKind): FloatArray {
        val document = kind == EmbeddingKind.Document
        val tokens = if (document) wordPieces.encode(text) else imageTokens.encode(text)
        val session = session(if (document) "doc.onnx" else "text.onnx")
        val shape = longArrayOf(1, tokens.size.toLong())
        val inputs = session.inputNames.associateWith { name ->
            val ids = when (name) {
                "input_ids" -> tokens
                "attention_mask" -> LongArray(tokens.size) { 1 }
                "token_type_ids" -> LongArray(tokens.size)
                else -> error("Unexpected input")
            }
            OnnxTensor.createTensor(environment, LongBuffer.wrap(ids), shape)
        }
        try {
            val output = if (document) "last_hidden_state" else "text_embeds"
            session.run(inputs, setOf(output)).use { result ->
                val buffer = (result.get(output).get() as OnnxTensor).floatBuffer
                if (!document) return FloatArray(buffer.remaining()).also { buffer.get(it) }.normalized()
                val pooled = FloatArray(buffer.remaining() / tokens.size)
                repeat(tokens.size) {
                    for (index in pooled.indices) pooled[index] += buffer.get()
                }
                return pooled.normalized()
            }
        } finally {
            inputs.values.forEach { it.close() }
        }
    }

    fun image(resolver: ContentResolver, file: SearchFile): FloatArray {
        val bitmap = ImageDecoder.decodeBitmap(ImageDecoder.createSource(resolver, file.uri)) { decoder, info, _ ->
            val width = info.size.width
            val height = info.size.height
            val factor = 224f / minOf(width, height)
            val scaledWidth = (width * factor).roundToInt()
            val scaledHeight = (height * factor).roundToInt()
            check(maxOf(scaledWidth, scaledHeight) <= 4096)
            val left = (scaledWidth - 224) / 2
            val top = (scaledHeight - 224) / 2
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            decoder.setTargetSize(scaledWidth, scaledHeight)
            decoder.crop = Rect(left, top, left + 224, top + 224)
        }
        val pixels = IntArray(224 * 224)
        try { bitmap.getPixels(pixels, 0, 224, 0, 0, 224, 224) }
        finally { bitmap.recycle() }
        val mean = floatArrayOf(0.48145466f, 0.4578275f, 0.40821073f)
        val deviation = floatArrayOf(0.26862954f, 0.26130258f, 0.27577711f)
        val values = FloatArray(pixels.size * 3)
        for (channel in 0..2) for (index in pixels.indices) {
            val rgb = (pixels[index] shr (16 - channel * 8)) and 255
            values[channel * pixels.size + index] = (rgb / 255f - mean[channel]) / deviation[channel]
        }
        return OnnxTensor.createTensor(environment, FloatBuffer.wrap(values), longArrayOf(1, 3, 224, 224)).use { input ->
            session("vision.onnx").run(mapOf("pixel_values" to input), setOf("image_embeds")).use { result ->
                val buffer = (result.get("image_embeds").get() as OnnxTensor).floatBuffer
                FloatArray(buffer.remaining()).also { buffer.get(it) }.normalized()
            }
        }
    }

    override fun close() {
        sessions.values.forEach { it.close() }
        sessions.clear()
    }
}

private fun FloatArray.normalized(): FloatArray {
    val norm = sqrt(sumOf { (it * it).toDouble() }).toFloat()
    check(norm.isFinite() && norm > 0)
    for (index in indices) this[index] /= norm
    return this
}

private class WordPieces(file: File) {
    private val vocabulary = file.readLines().withIndex().associate { it.value to it.index.toLong() }
    private val accents = Regex("\\p{Mn}")
    private val words = Regex("[\\p{L}\\p{N}]+|[^\\p{L}\\p{N}\\s]")

    fun encode(value: String): LongArray {
        val text = Normalizer.normalize(value.lowercase(Locale.ROOT), Normalizer.Form.NFD).replace(accents, "")
        return buildList {
            add(vocabulary.getValue("[CLS]"))
            for (match in words.findAll(text)) {
                val word = match.value
                val pieces = if (word.length <= 100) split(word) else emptyList()
                if (pieces.isEmpty()) add(vocabulary.getValue("[UNK]")) else addAll(pieces.take(127 - size))
                if (size >= 127) break
            }
            add(vocabulary.getValue("[SEP]"))
        }.toLongArray()
    }

    private fun split(word: String): List<Long> {
        val output = mutableListOf<Long>()
        var start = 0
        while (start < word.length) {
            var end = word.length
            var token: Long? = null
            while (end > start) {
                val piece = word.substring(start, end)
                token = vocabulary[if (start == 0) piece else "##" + piece]
                if (token != null) break
                end--
            }
            output.add(token ?: return emptyList())
            start = end
        }
        return output
    }
}

private class ImageTokens(vocab: File, merges: File) {
    private val vocabulary = JSONObject(vocab.readText())
    private val ranks = merges.useLines { lines ->
        lines.filter { it.isNotBlank() && !it.startsWith("#") }.withIndex()
            .associate { it.value to it.index }
    }
    private val bytes = CharArray(256).apply {
        val visible = (33..126) + (161..172) + (174..255)
        var extra = 256
        for (byte in indices) this[byte] = (if (byte in visible) byte else extra++).toChar()
    }
    private val words = Regex("'s|'t|'re|'ve|'m|'ll|'d|[\\p{L}]+|[\\p{N}]|[^\\s\\p{L}\\p{N}]+")

    fun encode(value: String): LongArray = buildList {
        add(vocabulary.getLong("<|startoftext|>"))
        for (match in words.findAll(value.lowercase(Locale.ROOT))) {
            val word = match.value.encodeToByteArray().map { bytes[it.toInt() and 255].toString() }.toMutableList()
            word[word.lastIndex] += "</w>"
            while (word.size > 1) {
                val best = word.zipWithNext { a, b -> "$a $b" }.filter { it in ranks }.minByOrNull { ranks.getValue(it) } ?: break
                val (left, right) = best.split(' ', limit = 2)
                var index = 0
                while (index < word.lastIndex) {
                    if (word[index] == left && word[index + 1] == right) {
                        word[index] = left + right
                        word.removeAt(index + 1)
                    }
                    index++
                }
            }
            addAll(word.take(76 - size).map { vocabulary.getLong(it) })
            if (size >= 76) break
        }
        add(vocabulary.getLong("<|endoftext|>"))
    }.toLongArray()
}
