package com.scluzlep.searchcx.tiku

import org.json.JSONObject
import java.net.URLDecoder
import java.util.Base64

/**
 * Direct implementation of tikuAdapter's buguake (Baidu EasyLearn) provider.
 * The upstream payload is Base64/XOR encoded, then URL escaped.
 */
internal object BuguakeParser {
    private const val KEY_PREFIX = "34cab29ef956d78afd"
    private const val MIN_SIMILARITY = 0.8
    private val HTML_BR_REGEX = Regex("""<br\s*/?>""", RegexOption.IGNORE_CASE)
    private val HTML_TAG_REGEX = Regex("""<[^>]+>""")
    private val WHITESPACE_REGEX = Regex("""\s+""")

    fun parse(question: String, response: JSONObject): List<String> {
        val list = response.optJSONObject("data")?.optJSONArray("list") ?: return emptyList()
        val normalizedQuestion = TikuAnswerResolver.normalizeAnswerText(question)
        var bestScore = MIN_SIMILARITY
        var bestAnswers = emptyList<String>()
        for (i in 0 until list.length()) {
            val entry = list.optJSONObject(i) ?: continue
            val decoded = runCatching {
                JSONObject(decode(entry.getString("bdjson"), entry.getString("actk")))
            }.getOrNull() ?: continue

            val stem = extractBlocksText(decoded.optJSONArray("que_stem"))
            val normalizedStem = TikuAnswerResolver.normalizeAnswerText(stem)
            val score = maxOf(
                similarity(question, stem),
                similarity(normalizedQuestion, normalizedStem)
            )
            if (score < MIN_SIMILARITY) continue

            val candidate = ArrayList<String>()
            val options = decoded.optJSONArray("que_options")?.optJSONArray(0)
            if (options != null) {
                for (j in 0 until options.length()) {
                    val option = options.optJSONObject(j) ?: continue
                    if (!option.optBoolean("yes")) continue
                    val text = extractBlocksText(option.optJSONArray("ret"))
                    if (text.isNotEmpty()) candidate.add(text)
                }
            }
            if (candidate.isEmpty()) {
                val answerBlocks = decoded.optJSONArray("que_answer")
                if (answerBlocks != null) {
                    for (j in 0 until answerBlocks.length()) {
                        val blockText = extractSingleBlockText(answerBlocks.optJSONObject(j))
                        if (blockText.isNotEmpty()) candidate.add(blockText)
                    }
                }
            }

            // Each search result is an alternative candidate, not an additional
            // correct option. Do not merge answers from unrelated near matches.
            if (candidate.isNotEmpty() && (bestAnswers.isEmpty() || score > bestScore)) {
                bestAnswers = candidate.distinct()
                bestScore = score
            }
        }
        return bestAnswers
    }

    private fun extractBlocksText(blocks: org.json.JSONArray?): String {
        if (blocks == null || blocks.length() == 0) return ""
        val sb = StringBuilder()
        for (i in 0 until blocks.length()) {
            val part = extractSingleBlockText(blocks.optJSONObject(i))
            if (part.isNotEmpty()) {
                if (sb.isNotEmpty()) sb.append(' ')
                sb.append(part)
            }
        }
        return sb.toString().trim()
    }

    private fun extractSingleBlockText(block: JSONObject?): String {
        if (block == null) return ""
        val chunks = block.optJSONArray("c")
        if (chunks != null) {
            val sb = StringBuilder()
            for (k in 0 until chunks.length()) {
                val item = chunks.opt(k)
                val rawChunk = when (item) {
                    is JSONObject -> item.optString("c", "")
                    is String -> item
                    else -> ""
                }
                if (rawChunk.isNotEmpty()) sb.append(rawChunk)
            }
            return cleanRichText(sb.toString())
        }
        return cleanRichText(block.optString("c", ""))
    }

    private fun cleanRichText(raw: String): String {
        if (raw.isBlank()) return ""
        return raw
            .replace(HTML_BR_REGEX, " ")
            .replace(HTML_TAG_REGEX, "")
            .replace("&nbsp;", " ")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&amp;", "&")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace(WHITESPACE_REGEX, " ")
            .trim()
    }

    internal fun decode(bdjson: String, actk: String): String {
        val key = (KEY_PREFIX + actk).toByteArray(Charsets.UTF_8)
        val encoded = Base64.getDecoder().decode(bdjson)
        val xorBytes = ByteArray(encoded.size) { i ->
            (encoded[i].toInt() xor key[i % key.size].toInt()).toByte()
        }
        // Each pair of XOR-decoded bytes represents one URI-escaped character.
        val urlEncoded = buildString(xorBytes.size / 2) {
            for (i in 0 until xorBytes.size - 1 step 2) {
                append(((xorBytes[i].toInt() and 0xff) xor
                    (xorBytes[i + 1].toInt() and 0xff)).toChar())
            }
        }
        return URLDecoder.decode(urlEncoded, Charsets.UTF_8.name())
    }

    // strsim.Compare in the tikuAdapter implementation defaults to normalized Levenshtein.
    private fun similarity(left: String, right: String): Double {
        val a = left.trim().codePoints().toArray()
        val b = right.trim().codePoints().toArray()
        if (a.contentEquals(b)) return 1.0
        if (a.isEmpty() || b.isEmpty()) return 0.0

        var previous = IntArray(b.size + 1) { it }
        for (i in a.indices) {
            val current = IntArray(b.size + 1)
            current[0] = i + 1
            for (j in b.indices) {
                current[j + 1] = minOf(
                    current[j] + 1,
                    previous[j + 1] + 1,
                    previous[j] + if (a[i] == b[j]) 0 else 1
                )
            }
            previous = current
        }
        return 1.0 - previous[b.size].toDouble() / maxOf(a.size, b.size)
    }
}
