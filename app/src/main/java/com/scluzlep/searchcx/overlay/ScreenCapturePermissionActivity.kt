package com.scluzlep.searchcx.overlay

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat

/**
 * Translucent Activity purely used to request standard Android MediaProjection
 * screen-capture consent from the user and return the token to OverlayService.
 */
class ScreenCapturePermissionActivity : ComponentActivity() {

    private val captureLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val data = result.data
        if (result.resultCode == Activity.RESULT_OK && data != null) {
            val svcIntent = Intent(this, OverlayService::class.java).apply {
                action = OverlayService.ACTION_MEDIA_PROJECTION_GRANTED
                putExtra(OverlayService.EXTRA_RESULT_CODE, result.resultCode)
                putExtra(OverlayService.EXTRA_RESULT_DATA, data)
            }
            ContextCompat.startForegroundService(this, svcIntent)
        } else {
            val svcIntent = Intent(this, OverlayService::class.java).apply {
                action = OverlayService.ACTION_MEDIA_PROJECTION_DENIED
            }
            ContextCompat.startForegroundService(this, svcIntent)
        }
        finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val mgr = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        captureLauncher.launch(mgr.createScreenCaptureIntent())
    }
}
