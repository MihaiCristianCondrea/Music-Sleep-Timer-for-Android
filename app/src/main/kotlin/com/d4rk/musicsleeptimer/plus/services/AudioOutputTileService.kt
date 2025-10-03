package com.d4rk.musicsleeptimer.plus.services

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.util.Log
import android.widget.Toast
import androidx.annotation.RequiresApi
import com.d4rk.musicsleeptimer.plus.R

/**
 * Quick Settings tile that opens the native Android audio output picker dialog.
 *
 * The tile interacts with SystemUI by sending a broadcast that triggers the
 * system dialog used to switch between audio routes such as headphones,
 * speakers, and Bluetooth devices.
 */
@RequiresApi(Build.VERSION_CODES.N)
class AudioOutputTileService : TileService() {
    companion object {
        private const val TAG = "AudioOutputTileService"
        private const val ACTION_MEDIA_OUTPUT =
            "com.android.systemui.action.LAUNCH_SYSTEM_MEDIA_OUTPUT_DIALOG"
        private const val PACKAGE_SYSTEMUI = "com.android.systemui"
        private const val RECEIVER_CLASS =
            "com.android.systemui.media.dialog.MediaOutputDialogReceiver"
    }

    override fun onStartListening() {
        super.onStartListening()
        updateTile()
    }

    override fun onClick() {
        super.onClick()
        openMediaOutputDialog()
    }

    private fun openMediaOutputDialog() {
        try {
            val intent = Intent(ACTION_MEDIA_OUTPUT).apply {
                component = ComponentName(PACKAGE_SYSTEMUI, RECEIVER_CLASS)
            }

            val pendingIntent = PendingIntent.getBroadcast(
                this, 0, intent, PendingIntent.FLAG_IMMUTABLE
            )

            pendingIntent.send()
        } catch (exception: Exception) {
            val errorMessage =
                "Failed to open audio output dialog: ${exception.javaClass.simpleName}: ${exception.message}"
            Log.e(TAG, errorMessage, exception)
            showToast(errorMessage)
        }
    }

    private fun updateTile() {
        val tile = qsTile ?: return
        tile.label = getString(R.string.audio_output)
        tile.icon = Icon.createWithResource(this, R.drawable.ic_audio_output)
        tile.state = Tile.STATE_ACTIVE
        tile.updateTile()
    }

    private fun showToast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }
}
