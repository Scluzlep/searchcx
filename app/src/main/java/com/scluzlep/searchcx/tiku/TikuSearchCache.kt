package com.scluzlep.searchcx.tiku

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Thread-safe LRU + TTL cache for hit question-bank results with optional atomic JSON disk persistence.
 */
class TikuSearchCache(
    private val persistenceFile: File? = null,
    private val ttlMs: Long = DEFAULT_TTL_MS,
    private val maxEntries: Int = DEFAULT_MAX_ENTRIES,
    private val nowMs: () -> Long = System::currentTimeMillis
) {

    private data class CacheEntry(
        val cachedAtMs: Long,
        val result: TikuSearchResult
    )

    private val lock = Any()
    private val entries = LinkedHashMap<String, CacheEntry>(16, 0.75f, true)
    private var loaded = false

    fun get(key: String): TikuSearchResult? {
        if (key.isBlank()) return null
        synchronized(lock) {
            ensureLoadedLocked()
            if (maxEntries <= 0 || ttlMs <= 0L) return null
            val entry = entries[key] ?: return null
            val now = nowMs()
            if (isExpired(entry.cachedAtMs, now)) {
                entries.remove(key)
                persistLocked()
                return null
            }
            return entry.result
        }
    }

    fun put(key: String, result: TikuSearchResult) {
        if (key.isBlank() || !result.isHit()) return
        synchronized(lock) {
            if (maxEntries <= 0 || ttlMs <= 0L) return
            ensureLoadedLocked()
            val now = nowMs()
            pruneExpiredLocked(now)
            if (!entries.containsKey(key)) {
                while (entries.size >= maxEntries) {
                    val eldestKey = entries.entries.iterator().next().key
                    entries.remove(eldestKey)
                }
            }
            entries[key] = CacheEntry(cachedAtMs = now, result = result)
            persistLocked()
        }
    }

    fun clear() {
        synchronized(lock) {
            loaded = true
            entries.clear()
            val file = persistenceFile ?: return
            try {
                if (file.exists() && !file.delete()) {
                    persistLocked()
                }
            } catch (_: Throwable) {
                // Ignore persistence cleanup failures.
            }
        }
    }

    fun size(): Int {
        synchronized(lock) {
            ensureLoadedLocked()
            if (maxEntries <= 0 || ttlMs <= 0L) {
                entries.clear()
                return 0
            }
            val removed = pruneExpiredLocked(nowMs())
            if (removed) {
                persistLocked()
            }
            return entries.size
        }
    }

    private fun isExpired(cachedAtMs: Long, now: Long): Boolean {
        return ttlMs <= 0L || (now - cachedAtMs) >= ttlMs
    }

    private fun pruneExpiredLocked(now: Long): Boolean {
        var removed = false
        val iter = entries.entries.iterator()
        while (iter.hasNext()) {
            val item = iter.next()
            if (isExpired(item.value.cachedAtMs, now)) {
                iter.remove()
                removed = true
            }
        }
        return removed
    }

    private fun ensureLoadedLocked() {
        if (loaded) return
        loaded = true
        if (maxEntries <= 0 || ttlMs <= 0L) return
        val file = persistenceFile ?: return
        if (!file.exists() || !file.isFile) return

        try {
            val raw = file.readText(Charsets.UTF_8)
            if (raw.isBlank()) return
            val root = JSONObject(raw)
            val arr = root.optJSONArray("entries") ?: return
            val now = nowMs()
            val parsed = ArrayList<Pair<String, CacheEntry>>(arr.length())
            for (i in 0 until arr.length()) {
                val item = arr.optJSONObject(i) ?: continue
                val key = readOptString(item, "key")
                if (key.isBlank()) continue
                val cachedAtMs = item.optLong("cachedAtMs", Long.MIN_VALUE)
                if (cachedAtMs == Long.MIN_VALUE || isExpired(cachedAtMs, now)) continue
                val resObj = item.optJSONObject("result") ?: continue
                val result = resultFromJson(resObj) ?: continue
                if (!result.isHit()) continue
                parsed.add(key to CacheEntry(cachedAtMs, result))
            }
            val startIdx = if (parsed.size > maxEntries) parsed.size - maxEntries else 0
            for (i in startIdx until parsed.size) {
                val (k, entry) = parsed[i]
                entries[k] = entry
            }
        } catch (_: Throwable) {
            // Corrupt or unreadable cache files are safely ignored.
            entries.clear()
        }
    }

    private fun persistLocked() {
        val file = persistenceFile ?: return
        try {
            val absFile = file.absoluteFile
            val parent = absFile.parentFile
            if (parent != null && !parent.exists()) {
                parent.mkdirs()
            }
            val json = serializeEntriesLocked()
            val tmpPrefix = absFile.name.ifBlank { "tiku_cache" }
            val tmpFile = File(parent ?: File("."), "$tmpPrefix.${System.nanoTime()}.tmp")
            tmpFile.writeText(json, Charsets.UTF_8)
            var moved = false
            try {
                if (tmpFile.renameTo(absFile)) {
                    moved = true
                } else {
                    Files.move(
                        tmpFile.toPath(),
                        absFile.toPath(),
                        StandardCopyOption.REPLACE_EXISTING
                    )
                    moved = true
                }
            } catch (_: Throwable) {
                absFile.writeText(json, Charsets.UTF_8)
                moved = true
            } finally {
                if (!moved || tmpFile.exists()) {
                    tmpFile.delete()
                }
            }
        } catch (_: Throwable) {
            // Cache persistence failures must not break search operations.
        }
    }

    private fun serializeEntriesLocked(): String {
        val arr = JSONArray()
        for ((key, entry) in entries) {
            val item = JSONObject().apply {
                put("key", key)
                put("cachedAtMs", entry.cachedAtMs)
                put("result", resultToJson(entry.result))
            }
            arr.put(item)
        }
        return JSONObject().apply {
            put("version", CACHE_FORMAT_VERSION)
            put("entries", arr)
        }.toString()
    }

    private fun resultToJson(result: TikuSearchResult): JSONObject {
        return JSONObject().apply {
            put("question", result.question)
            put("options", toStringJsonArray(result.options))
            put("type", result.type)
            put("bestAnswer", toStringJsonArray(result.bestAnswer))
            put("answerKey", toStringJsonArray(result.answerKey))
            put("answerKeyText", result.answerKeyText)
            put("answerText", result.answerText)
            put("answerIndex", JSONArray().apply {
                for (idx in result.answerIndex) put(idx)
            })
            put("allAnswers", JSONArray().apply {
                for (group in result.allAnswers) {
                    put(toStringJsonArray(group))
                }
            })
            put("matchedProviders", toStringJsonArray(result.matchedProviders))
            if (result.error != null) {
                put("error", result.error)
            }
        }
    }

    private fun resultFromJson(obj: JSONObject): TikuSearchResult? {
        return runCatching {
            val question = readOptString(obj, "question")
            if (question.isBlank()) return null

            val allAnswersArr = obj.optJSONArray("allAnswers")
            val allAnswers = ArrayList<List<String>>(allAnswersArr?.length() ?: 0)
            if (allAnswersArr != null) {
                for (i in 0 until allAnswersArr.length()) {
                    val inner = allAnswersArr.optJSONArray(i) ?: continue
                    allAnswers.add(readStringList(inner))
                }
            }

            val idxArr = obj.optJSONArray("answerIndex")
            val answerIndex = ArrayList<Int>(idxArr?.length() ?: 0)
            if (idxArr != null) {
                for (i in 0 until idxArr.length()) {
                    answerIndex.add(idxArr.optInt(i))
                }
            }

            val error = if (obj.has("error") && !obj.isNull("error")) {
                obj.optString("error")
            } else {
                null
            }

            TikuSearchResult(
                question = question,
                options = readStringList(obj.optJSONArray("options")),
                type = obj.optInt("type", 0),
                bestAnswer = readStringList(obj.optJSONArray("bestAnswer")),
                answerKey = readStringList(obj.optJSONArray("answerKey")),
                answerKeyText = readOptString(obj, "answerKeyText"),
                answerText = readOptString(obj, "answerText"),
                answerIndex = answerIndex,
                allAnswers = allAnswers,
                matchedProviders = readStringList(obj.optJSONArray("matchedProviders")),
                error = error
            )
        }.getOrNull()
    }

    private fun toStringJsonArray(items: List<String>): JSONArray {
        return JSONArray().apply {
            for (item in items) put(item)
        }
    }

    private fun readStringList(arr: JSONArray?): List<String> {
        if (arr == null) return emptyList()
        val out = ArrayList<String>(arr.length())
        for (i in 0 until arr.length()) {
            if (arr.isNull(i)) continue
            out.add(arr.optString(i, ""))
        }
        return out
    }

    private fun readOptString(obj: JSONObject, key: String): String {
        if (!obj.has(key) || obj.isNull(key)) return ""
        return obj.optString(key, "")
    }

    companion object {
        private const val CACHE_FORMAT_VERSION = 1
        const val DEFAULT_TTL_MS = 24L * 60L * 60L * 1000L
        const val DEFAULT_MAX_ENTRIES = 200

        fun buildKey(req: TikuSearchRequest, config: TikuConfig): String {
            val modeName = config.mode.name
            val remotePart = if (config.mode == TikuMode.REMOTE_ADAPTER) {
                normalizeRemoteBaseUrl(config.remoteBaseUrl)
            } else {
                ""
            }
            val activeProviders = when (config.mode) {
                TikuMode.DIRECT_BUILTIN -> config.effectiveDirectProviders()
                TikuMode.REMOTE_ADAPTER -> config.effectiveRemoteProviders()
            }.map { it.id }.sorted().joinToString(",")

            val normalizedQuestion = OcsQuestionExtractor.normalizeQuestionText(req.question).lowercase()
            val normalizedOptions = req.options
                .map { OcsQuestionExtractor.stripOptionPrefix(it).lowercase() }
                .filter { it.isNotEmpty() }

            return JSONArray().apply {
                put(modeName)
                put(remotePart)
                put(activeProviders)
                put(req.type)
                put(normalizedQuestion)
                put(JSONArray().apply {
                    for (opt in normalizedOptions) put(opt)
                })
            }.toString()
        }

        private fun normalizeRemoteBaseUrl(rawUrl: String): String {
            val trimmed = rawUrl.trim().ifEmpty { TikuConfig.DEFAULT_REMOTE_URL }
            val withScheme = if (trimmed.startsWith("http://", ignoreCase = true) ||
                trimmed.startsWith("https://", ignoreCase = true)
            ) {
                trimmed
            } else {
                "http://$trimmed"
            }
            val parsed = withScheme.toHttpUrlOrNull() ?: return trimmed.trimEnd('/').lowercase()
            val builder = parsed.newBuilder()
                .query(null)
                .fragment(null)
            val segments = parsed.pathSegments
            val isRootOnly = segments.isEmpty() || (segments.size == 1 && segments[0].isEmpty())
            if (isRootOnly) {
                builder.addPathSegments("adapter-service/search")
            } else if (segments.last().isEmpty()) {
                builder.removePathSegment(segments.lastIndex)
            }
            return builder.build().toString().lowercase()
        }
    }
}
