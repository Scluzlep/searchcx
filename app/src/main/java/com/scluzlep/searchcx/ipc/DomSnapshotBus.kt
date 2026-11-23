package com.scluzlep.searchcx.ipc

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.scluzlep.searchcx.data.DomSnapshot
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

data class CapturedDomEvent(
    val requestId: String,
    val snapshot: DomSnapshot
)

/**
 * Splits oversized DOM snapshot JSON into Binder-safe chunks and reassembles
 * potentially out-of-order explicit broadcasts per requestId with TTL eviction.
 */
class DomChunkAssembler(
    private val ttlMs: Long = 15_000L,
    private val nowMs: () -> Long = System::currentTimeMillis
) {
    private class Bucket(
        val chunkTotal: Int,
        var updatedAtMs: Long
    ) {
        val parts: Array<String?> = arrayOfNulls(chunkTotal)
        var receivedCount: Int = 0
        var totalChars: Int = 0
    }

    private val lock = Any()
    private val buckets = LinkedHashMap<String, Bucket>()

    fun splitPayload(
        payload: String,
        maxChunkChars: Int = DomIpcContract.MAX_CHUNK_CHARS
    ): List<String> = Companion.splitPayload(payload, maxChunkChars)

    fun offer(
        requestId: String,
        chunkIndex: Int,
        chunkTotal: Int,
        payload: String
    ): String? {
        if (payload.length > DomIpcContract.MAX_ASSEMBLED_PAYLOAD_CHARS) {
            return null
        }
        if (chunkTotal <= 1 && chunkIndex <= 0) {
            return payload
        }
        if (requestId.isBlank()) return null
        if (chunkTotal !in 1..DomIpcContract.MAX_CHUNK_COUNT) return null
        if (chunkIndex !in 0 until chunkTotal) return null

        val now = nowMs()
        synchronized(lock) {
            evictExpiredLocked(now)

            val existing = buckets[requestId]
            val bucket = if (existing == null) {
                if (buckets.size >= MAX_ACTIVE_BUCKETS) {
                    val oldestKey = buckets.keys.firstOrNull()
                    if (oldestKey != null) buckets.remove(oldestKey)
                }
                Bucket(chunkTotal = chunkTotal, updatedAtMs = now).also {
                    buckets[requestId] = it
                }
            } else if (existing.chunkTotal != chunkTotal) {
                buckets.remove(requestId)
                return null
            } else {
                existing
            }

            val previous = bucket.parts[chunkIndex]
            val updatedTotalChars = if (previous == null) {
                bucket.totalChars + payload.length
            } else {
                bucket.totalChars - previous.length + payload.length
            }
            if (updatedTotalChars > DomIpcContract.MAX_ASSEMBLED_PAYLOAD_CHARS) {
                buckets.remove(requestId)
                return null
            }

            if (previous == null) {
                bucket.receivedCount++
            }
            bucket.parts[chunkIndex] = payload
            bucket.totalChars = updatedTotalChars
            bucket.updatedAtMs = now

            if (bucket.receivedCount < bucket.chunkTotal) {
                return null
            }

            buckets.remove(requestId)
            val sb = StringBuilder(bucket.totalChars)
            for (part in bucket.parts) {
                if (part == null) return null
                sb.append(part)
            }
            return sb.toString()
        }
    }

    private fun evictExpiredLocked(now: Long) {
        val iterator = buckets.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (now - entry.value.updatedAtMs > ttlMs) {
                iterator.remove()
            }
        }
    }

    companion object {
        private const val MAX_ACTIVE_BUCKETS = 32

        fun splitPayload(
            payload: String,
            maxChunkChars: Int = DomIpcContract.MAX_CHUNK_CHARS
        ): List<String> {
            require(maxChunkChars > 0) { "maxChunkChars must be positive" }
            if (payload.length <= maxChunkChars) {
                return listOf(payload)
            }
            val result = ArrayList<String>((payload.length + maxChunkChars - 1) / maxChunkChars)
            var offset = 0
            while (offset < payload.length) {
                val end = minOf(offset + maxChunkChars, payload.length)
                result.add(payload.substring(offset, end))
                offset = end
            }
            return result
        }
    }
}

object DomSnapshotBus {
    private val _events = MutableSharedFlow<CapturedDomEvent>(
        replay = 0,
        extraBufferCapacity = 8,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val events: SharedFlow<CapturedDomEvent> = _events.asSharedFlow()

    private val chunkAssembler = DomChunkAssembler()

    fun offerChunk(
        requestId: String,
        chunkIndex: Int,
        chunkTotal: Int,
        payload: String
    ): String? = chunkAssembler.offer(
        requestId = requestId,
        chunkIndex = chunkIndex,
        chunkTotal = chunkTotal,
        payload = payload
    )

    fun publish(requestId: String, snapshot: DomSnapshot) {
        _events.tryEmit(CapturedDomEvent(requestId, snapshot))
    }
}

class DomSnapshotReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        if (intent?.action != DomIpcContract.ACTION_DOM_SNAPSHOT) return
        val requestId = intent.getStringExtra(DomIpcContract.EXTRA_REQUEST_ID).orEmpty()
        val chunkIndex = intent.getIntExtra(DomIpcContract.EXTRA_CHUNK_INDEX, 0)
        val chunkTotal = intent.getIntExtra(DomIpcContract.EXTRA_CHUNK_TOTAL, 1)
        val payload = intent.getStringExtra(DomIpcContract.EXTRA_PAYLOAD_JSON)
        if (payload == null || (chunkTotal <= 1 && payload.isBlank())) {
            DomSnapshotBus.publish(
                requestId,
                DomSnapshot.errorSnapshot("unknown", "收到空 DOM 广播载荷")
            )
            return
        }
        val completePayload = DomSnapshotBus.offerChunk(
            requestId = requestId,
            chunkIndex = chunkIndex,
            chunkTotal = chunkTotal,
            payload = payload
        ) ?: return

        if (completePayload.isBlank()) {
            DomSnapshotBus.publish(
                requestId,
                DomSnapshot.errorSnapshot("unknown", "收到空 DOM 广播载荷")
            )
            return
        }
        runCatching { DomSnapshot.fromJson(completePayload) }
            .onSuccess { DomSnapshotBus.publish(requestId, it) }
            .onFailure { err ->
                Log.e("DomSnapshotReceiver", "Failed to parse DOM snapshot", err)
                DomSnapshotBus.publish(
                    requestId,
                    DomSnapshot.errorSnapshot("unknown", "DOM 快照解析失败: ${err.message}")
                )
            }
    }
}
