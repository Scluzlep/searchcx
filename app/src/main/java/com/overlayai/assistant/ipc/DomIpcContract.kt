package com.overlayai.assistant.ipc

object DomIpcContract {
    const val ASSISTANT_PACKAGE = "com.overlayai.assistant"
    const val ACTION_CAPTURE_DOM = "com.overlayai.assistant.ACTION_CAPTURE_DOM"
    const val ACTION_DOM_SNAPSHOT = "com.overlayai.assistant.ACTION_DOM_SNAPSHOT"
    const val EXTRA_REQUEST_ID = "request_id"
    const val EXTRA_PAYLOAD_JSON = "payload_json"
    const val MAX_BODY_CHARS = 12_000
}
