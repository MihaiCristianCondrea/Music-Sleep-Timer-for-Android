package com.d4rk.musicsleeptimer.plus.notifications

import android.app.Notification
import android.app.Notification.CATEGORY_EVENT
import android.app.Notification.VISIBILITY_PUBLIC
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.NotificationManager.IMPORTANCE_LOW
import android.app.PendingIntent
import android.app.PendingIntent.FLAG_IMMUTABLE
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import com.d4rk.musicsleeptimer.plus.R
import com.d4rk.musicsleeptimer.plus.notifications.SleepNotification.Action.CANCEL
import com.d4rk.musicsleeptimer.plus.notifications.SleepNotification.Action.DECREMENT
import com.d4rk.musicsleeptimer.plus.notifications.SleepNotification.Action.INCREMENT
import com.d4rk.musicsleeptimer.plus.workers.SleepAudioWorker
import androidx.annotation.RequiresApi
import com.d4rk.musicsleeptimer.plus.receivers.SleepNotificationReceiver
import com.d4rk.musicsleeptimer.plus.services.SleepTileService
import java.lang.System.currentTimeMillis
import java.text.DateFormat
import java.text.DateFormat.SHORT
import java.util.Date
import java.util.concurrent.TimeUnit.MILLISECONDS
import java.util.concurrent.TimeUnit.MINUTES

object SleepNotification {
    private val TIMEOUT_INITIAL_MILLIS : Long = MINUTES.toMillis(30)
    private val TIMEOUT_INCREMENT_MILLIS : Long = MINUTES.toMillis(10)
    private val TIMEOUT_DECREMENT_MILLIS : Long = MINUTES.toMillis(10)

    private enum class Action(private val value : String) {
        CANCEL("com.d4rk.musicsleeptimer.plus.notifications.SleepNotification.Action.CANCEL") {
            override fun title(context : Context) = context.getText(android.R.string.cancel)
        } ,
        INCREMENT("com.d4rk.musicsleeptimer.plus.notifications.SleepNotification.Action.INCREMENT") {
            override fun title(context : Context) = context.getString(
                R.string.notification_action_increment , MILLISECONDS.toMinutes(TIMEOUT_INCREMENT_MILLIS)
            )
        } ,
        DECREMENT("com.d4rk.musicsleeptimer.plus.notifications.SleepNotification.Action.DECREMENT") {
            override fun title(context : Context) = context.getString(
                R.string.notification_action_decrement , MILLISECONDS.toMinutes(TIMEOUT_DECREMENT_MILLIS)
            )
        } , ;

        companion object {
            fun parse(value : String?) : Action? = entries.firstOrNull { it.value == value }
        }

        fun intent(context : Context) : Intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            serviceIntent(context)
        } else {
            broadcastIntent(context)
        }

        @RequiresApi(Build.VERSION_CODES.N)
        private fun serviceIntent(context : Context) : Intent =
            Intent(context , SleepTileService::class.java).setAction(value)

        private fun broadcastIntent(context : Context) : Intent =
            Intent(context , SleepNotificationReceiver::class.java).setAction(value)

        fun pendingIntent(context : Context , cancel : Boolean = false) : PendingIntent? {
            val requestCode = value.hashCode()
            val intent = intent(context)
            val pendingIntent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                PendingIntent.getService(context , requestCode , intent , FLAG_IMMUTABLE)
            } else {
                PendingIntent.getBroadcast(context , requestCode , intent , FLAG_IMMUTABLE)
            }
            return pendingIntent.apply { if (cancel) cancel() }
        }

        fun action(context : Context , cancel : Boolean = false) : Notification.Action.Builder = Notification.Action.Builder(
            Icon.createWithResource(context , 0) , title(context) , pendingIntent(context , cancel)
        )

        abstract fun title(context : Context) : CharSequence?
    }

    fun Context.notificationManager() : NotificationManager? = getSystemService(NotificationManager::class.java)

    fun Context.find() = notificationManager()?.activeNotifications?.firstOrNull { it.id == R.id.notification_id }?.notification

    fun Context.handle(intent : Intent?) = when (Action.parse(intent?.action)) {
        INCREMENT -> update(TIMEOUT_INCREMENT_MILLIS)
        DECREMENT -> update(- TIMEOUT_DECREMENT_MILLIS)
        CANCEL -> cancel()
        null -> Unit
    }

    fun Context.toggle() = if (find() == null) show() else cancel()
    private fun Context.cancel() = notificationManager()?.cancel(R.id.notification_id) ?: Unit
    private fun Context.update(timeout : Long) = find()?.let {
        it.`when` - currentTimeMillis()
    }?.let {
        if (it > - timeout) it + timeout else it
    }?.let {
        show(it)
    }

    private fun Context.show(timeout : Long = TIMEOUT_INITIAL_MILLIS) {
        require(timeout > 0)
        val eta : Long = currentTimeMillis() + timeout
        val builder : Notification.Builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, getString(R.string.notification_channel_id))
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }

        val notification : Notification = builder
            .setCategory(CATEGORY_EVENT)
            .setVisibility(VISIBILITY_PUBLIC)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setSmallIcon(R.drawable.ic_music_off)
            .setSubText(DateFormat.getTimeInstance(SHORT).format(Date(eta)))
            .setShowWhen(true)
            .setWhen(eta)
            .setUsesChronometer(true).apply {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    setChronometerCountDown(true)
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    setTimeoutAfter(timeout)
                }
            }
            .apply {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    setDeleteIntent(SleepAudioWorker.pendingIntent(this@show))
                }
            }
            .addAction(INCREMENT.action(this).build())
            .addAction(
                DECREMENT.action(this, cancel = timeout <= TIMEOUT_DECREMENT_MILLIS).build()
            )
            .addAction(CANCEL.action(this).build())
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            createNotificationChannel()
        }
        notificationManager()?.notify(R.id.notification_id, notification)
    }

    private fun Context.createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val id : String = getString(R.string.notification_channel_id)
            val name : CharSequence = getString(R.string.app_name)
            val channel : NotificationChannel = NotificationChannel(id, name, IMPORTANCE_LOW).apply {
                setBypassDnd(true)
                lockscreenVisibility = VISIBILITY_PUBLIC
            }
            notificationManager()?.createNotificationChannel(channel)
        }
    }
}