package com.d4rk.musicsleeptimer.plus.receivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.d4rk.musicsleeptimer.plus.notifications.SleepNotification

class SleepNotificationReceiver : BroadcastReceiver() {
    override fun onReceive(context : Context , intent : Intent?) {
        with(SleepNotification) {
            context.handle(intent)
        }
    }
}
