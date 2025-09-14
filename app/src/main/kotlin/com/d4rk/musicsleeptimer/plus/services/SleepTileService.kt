package com.d4rk.musicsleeptimer.plus.services

import android.app.Notification
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Build.VERSION.SDK_INT
import android.os.Build.VERSION_CODES.Q
import android.provider.Settings
import android.service.quicksettings.Tile.STATE_ACTIVE
import android.service.quicksettings.Tile.STATE_INACTIVE
import android.service.quicksettings.TileService
import androidx.annotation.RequiresApi
import com.d4rk.musicsleeptimer.plus.R
import com.d4rk.musicsleeptimer.plus.notifications.SleepNotification.find
import com.d4rk.musicsleeptimer.plus.notifications.SleepNotification.handle
import com.d4rk.musicsleeptimer.plus.notifications.SleepNotification.notificationManager
import com.d4rk.musicsleeptimer.plus.notifications.SleepNotification.toggle
import java.text.DateFormat.SHORT
import java.text.DateFormat.getTimeInstance
import java.util.Date
import android.os.Build.VERSION_CODES.N as N_SDK_INT

@RequiresApi(N_SDK_INT)
class SleepTileService : TileService() {
    companion object {
        fun Context.requestTileUpdate() =
                requestListeningState(this , ComponentName(this , SleepTileService::class.java))
    }

    override fun onStartListening() = refreshTile()
    override fun onClick() = when (notificationManager()?.areNotificationsEnabled()) {
        true -> toggle().also { refreshTile() }
        else -> requestNotificationsPermission()
    }

    override fun onStartCommand(intent : Intent? , flags : Int , startId : Int) : Int {
        handle(intent)
        requestTileUpdate()
        stopSelfResult(startId)
        return START_NOT_STICKY
    }

    private fun refreshTile() = qsTile?.run {
        when (val notification : Notification? = find()) {
            null -> {
                state = STATE_INACTIVE
                if (SDK_INT >= Q) subtitle = resources.getText(R.string.tile_subtitle)
            }

            else -> {
                state = STATE_ACTIVE
                if (SDK_INT >= Q) subtitle =
                        getTimeInstance(SHORT).format(Date(notification.`when`))
            }
        }
        updateTile()
    } ?: Unit

    private fun requestNotificationsPermission() {
        when {
            SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE -> {
                val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
                }
                startActivity(intent)
            }

            SDK_INT >= Build.VERSION_CODES.O -> {
                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    putExtra(Settings.EXTRA_APP_PACKAGE , packageName)
                }.let(::startActivityAndCollapse) // FIXME: 'fun startActivityAndCollapse(intent: Intent!): Unit' is deprecated. Deprecated in Java.
            }

            else -> {
                val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    data = Uri.fromParts("package", packageName, null)
                }
                startActivity(intent)
            }
        }
    }

}