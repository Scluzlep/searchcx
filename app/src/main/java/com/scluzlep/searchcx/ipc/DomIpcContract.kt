package com.scluzlep.searchcx.ipc

object DomIpcContract {
    const val ASSISTANT_PACKAGE = "com.scluzlep.searchcx"
    const val ACTION_CAPTURE_DOM = "com.scluzlep.searchcx.ACTION_CAPTURE_DOM"
    const val ACTION_DOM_SNAPSHOT = "com.scluzlep.searchcx.ACTION_DOM_SNAPSHOT"
    const val EXTRA_REQUEST_ID = "request_id"
    const val EXTRA_PAYLOAD_JSON = "payload_json"
    const val EXTRA_CHUNK_INDEX = "chunk_index"
    const val EXTRA_CHUNK_TOTAL = "chunk_total"
    const val MAX_BODY_CHARS = 12_000
    const val MAX_CHUNK_CHARS = 160_000
    const val MAX_CHUNK_COUNT = 24
    const val MAX_ASSEMBLED_PAYLOAD_CHARS = 3_840_000
}
