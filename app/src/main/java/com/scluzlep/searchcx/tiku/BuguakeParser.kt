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

    fun parse(question: String, response: JSONObject): List<String> {
        val list = response.optJSONObject("data")?.optJSONArray("list") ?: return emptyList()
        val answers = ArrayList<String>()
        for (i in 0 until list.length()) {
            val entry = list.optJSONObject(i) ?: continue
            val decoded = runCatching {
                JSONObject(decode(entry.getString("bdjson"), entry.getString("actk")))
            }.getOrNull() ?: continue

            val stem = decoded.optJSONArray("que_stem")
                ?.optJSONObject(0)?.optJSONArray("c")
                ?.optJSONObject(0)?.optString("c", "").orEmpty()
            if (similarity(question, stem) < MIN_SIMILARITY) continue

            val options = decoded.optJSONArray("que_options")?.optJSONArray(0)
            if (options != null) {
                for (j in 0 until options.length()) {
                    val option = options.optJSONObject(j) ?: continue
                    if (!option.optBoolean("yes")) continue
                    val text = option.optJSONArray("ret")?.optJSONObject(0)
                        ?.optJSONArray("c")?.optJSONObject(0)
                        ?.optString("c", "").orEmpty().trim()
                    if (text.isNotEmpty()) answers.add(text)
                }
            }
            if (answers.isNotEmpty()) continue

            val answer = decoded.optJSONArray("que_answer")?.optJSONObject(0)
                ?.optJSONArray("c")
            if (answer != null) {
                for (j in 0 until answer.length()) {
                    val text = answer.optJSONObject(j)?.optString("c", "").orEmpty().trim()
                    if (text.isNotEmpty()) answers.add(text)
                }
            }
        }
        return answers.distinct()
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
