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

object DomSnapshotBus {
    private val _events = MutableSharedFlow<CapturedDomEvent>(
        replay = 0,
        extraBufferCapacity = 8,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val events: SharedFlow<CapturedDomEvent> = _events.asSharedFlow()

    fun publish(requestId: String, snapshot: DomSnapshot) {
        _events.tryEmit(CapturedDomEvent(requestId, snapshot))
    }
}

class DomSnapshotReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        if (intent?.action != DomIpcContract.ACTION_DOM_SNAPSHOT) return
        val requestId = intent.getStringExtra(DomIpcContract.EXTRA_REQUEST_ID).orEmpty()
        val payload = intent.getStringExtra(DomIpcContract.EXTRA_PAYLOAD_JSON)
        if (payload.isNullOrBlank()) {
            DomSnapshotBus.publish(
                requestId,
                DomSnapshot.errorSnapshot("unknown", "收到空 DOM 广播载荷")
            )
            return
        }
        runCatching { DomSnapshot.fromJson(payload) }
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
