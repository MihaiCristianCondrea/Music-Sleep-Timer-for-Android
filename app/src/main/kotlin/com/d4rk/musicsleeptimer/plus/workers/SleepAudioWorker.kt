package com.d4rk.musicsleeptimer.plus.workers

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.AudioPlaybackConfiguration
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.annotation.RequiresApi
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.d4rk.musicsleeptimer.plus.receivers.SleepAudioReceiver
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RequiresApi(Build.VERSION_CODES.O)
class SleepAudioWorker(
    context : Context ,
    workerParams : WorkerParameters ,
) : Worker(context , workerParams) {

    companion object {
        private val FADE_STEP_MILLIS : Long = TimeUnit.SECONDS.toMillis(1)
        private val RESTORE_VOLUME_MILLIS : Long = TimeUnit.SECONDS.toMillis(2)
        private val WAIT_FOR_PLAYBACK_STOP_MILLIS : Long = TimeUnit.SECONDS.toMillis(4)
        private const val MAX_FADE_STEPS : Int = 20
        private const val UNIQUE_WORK_NAME : String = "sleep_audio_work"
        const val ACTION_SLEEP_AUDIO : String = "com.d4rk.musicsleeptimer.plus.action.SLEEP_AUDIO"

        fun pendingIntent(context : Context) : PendingIntent? {
            val intent : Intent = Intent(context , SleepAudioReceiver::class.java).apply {
                action = ACTION_SLEEP_AUDIO
            }
            return PendingIntent.getBroadcast(
                context , 0 , intent , PendingIntent.FLAG_IMMUTABLE
            )
        }

        internal fun startWork(context : Context) {
            val workRequest : OneTimeWorkRequest = OneTimeWorkRequestBuilder<SleepAudioWorker>().build()

            WorkManager.getInstance(context).enqueueUniqueWork(
                UNIQUE_WORK_NAME , ExistingWorkPolicy.REPLACE , workRequest
            )
        }
    }

    override fun doWork(): Result {
        val audioManager = applicationContext.getSystemService(AudioManager::class.java)
            ?: return Result.failure()

        if (!audioManager.hasControllableOutputDevice() || !audioManager.isMusicActive) {
            return Result.success()
        }

        val attributes: AudioAttributes = mediaAudioAttributes()
        val initialVolume: Int = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        val canAdjustVolume: Boolean = !audioManager.isVolumeFixed && initialVolume > 0

        val playbackStoppedLatch = CountDownLatch(1)
        val focusRequest: AudioFocusRequest = buildFocusRequest(
            attributes = attributes,
            playbackStoppedLatch = playbackStoppedLatch
        )

        var playbackCallback: AudioManager.AudioPlaybackCallback? = null
        var focusGranted = false
        var playbackStopped = false

        return try {
            playbackCallback = audioManager.registerPlaybackStopCallback(playbackStoppedLatch)

            focusGranted = audioManager.requestAudioFocus(focusRequest) ==
                AudioManager.AUDIOFOCUS_REQUEST_GRANTED

            if (canAdjustVolume) {
                audioManager.fadeOutPlayback()
            }

            if (focusGranted) {
                if (!audioManager.isMusicActive) {
                    playbackStoppedLatch.countDown()
                }

                playbackStopped = playbackStoppedLatch.await(
                    WAIT_FOR_PLAYBACK_STOP_MILLIS,
                    TimeUnit.MILLISECONDS
                )
                if (!playbackStopped) {
                    playbackStopped = !audioManager.isMusicActive
                }
            } else {
                playbackStopped = !audioManager.isMusicActive
            }

            sleepFor(RESTORE_VOLUME_MILLIS)
            playbackStopped = playbackStopped || !audioManager.isMusicActive

            Result.success()
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            Result.failure()
        } catch (_: Throwable) {
            Result.failure()
        } finally {
            playbackCallback?.let(audioManager::unregisterAudioPlaybackCallback)

            if (canAdjustVolume && playbackStopped &&
                audioManager.getStreamVolume(AudioManager.STREAM_MUSIC) != initialVolume
            ) {
                audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, initialVolume, 0)
            }

            if (focusGranted) {
                audioManager.abandonAudioFocusRequest(focusRequest)
            }
        }
    }

    private fun mediaAudioAttributes(): AudioAttributes {
        return AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .build()
    }

    private fun buildFocusRequest(
        attributes: AudioAttributes,
        playbackStoppedLatch: CountDownLatch
    ): AudioFocusRequest {
        return AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
            .setAudioAttributes(attributes)
            .setAcceptsDelayedFocusGain(false)
            .setWillPauseWhenDucked(true)
            .setOnAudioFocusChangeListener(
                { focusChange: Int ->
                    if (focusChange == AudioManager.AUDIOFOCUS_LOSS ||
                        focusChange == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT
                    ) {
                        playbackStoppedLatch.countDown()
                    }
                },
                Handler(Looper.getMainLooper())
            )
            .build()
    }

    private fun AudioManager.registerPlaybackStopCallback(
        playbackStoppedLatch: CountDownLatch
    ): AudioManager.AudioPlaybackCallback? {
        val callback = object : AudioManager.AudioPlaybackCallback() {
            override fun onPlaybackConfigChanged(configs: MutableList<AudioPlaybackConfiguration>) {
                if (configs.none(::isRelevantPlaybackActive)) {
                    playbackStoppedLatch.countDown()
                }
            }
        }

        registerAudioPlaybackCallback(callback, Handler(Looper.getMainLooper()))
        return callback
    }

    private fun isRelevantPlaybackActive(config: AudioPlaybackConfiguration): Boolean {
        if (!config.isPlaybackActiveCompat()) {
            return false
        }

        return when (config.audioAttributes?.usage) {
            AudioAttributes.USAGE_MEDIA,
            AudioAttributes.USAGE_GAME,
            AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE,
            AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY -> true
            else -> false
        }
    }

    private fun AudioPlaybackConfiguration.isPlaybackActiveCompat(): Boolean {
        val configurationClass = AudioPlaybackConfiguration::class.java

        val directState = runCatching {
            configurationClass.getMethod("isActive").invoke(this) as? Boolean
        }.getOrNull()
        if (directState != null) {
            return directState
        }

        val playerState = runCatching {
            configurationClass.getMethod("getPlayerState").invoke(this) as? Int
        }.getOrNull()
        if (playerState == null) {
            return true
        }

        val activeStates = listOfNotNull(
            runCatching { configurationClass.getField("PLAYER_STATE_STARTED").getInt(null) }
                .getOrNull(),
            runCatching { configurationClass.getField("PLAYER_STATE_PLAYING").getInt(null) }
                .getOrNull()
        )

        if (activeStates.isEmpty()) {
            return playerState != 0
        }

        return activeStates.any { it == playerState }
    }

    private fun AudioManager.fadeOutPlayback() {
        if (isVolumeFixed || getStreamVolume(AudioManager.STREAM_MUSIC) == 0) {
            return
        }

        var steps = 0
        while (getStreamVolume(AudioManager.STREAM_MUSIC) > 0 &&
            steps < MAX_FADE_STEPS &&
            !Thread.currentThread().isInterrupted
        ) {
            lowerVolumeStep()
            steps++
            this@SleepAudioWorker.sleepFor(FADE_STEP_MILLIS)
        }
    }

    private fun AudioManager.lowerVolumeStep() {
        adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_LOWER, 0)
    }

    private fun AudioManager.hasControllableOutputDevice(): Boolean {
        return if (Build.VERSION.SDK_INT >= 35) {
            hasSupportedFutureOutputDevice()
        } else {
            getDevices(AudioManager.GET_DEVICES_OUTPUTS)
                .any { it.type != AudioDeviceInfo.TYPE_UNKNOWN }
        }
    }

    @RequiresApi(35)
    private fun AudioManager.hasSupportedFutureOutputDevice(): Boolean {
        return runCatching {
            getSupportedDeviceTypes(AudioManager.GET_DEVICES_OUTPUTS)
                .any { it != AudioDeviceInfo.TYPE_UNKNOWN }
        }.getOrDefault(false)
    }

    @Throws(InterruptedException::class)
    private fun sleepFor(durationMillis: Long) {
        if (durationMillis <= 0L) {
            return
        }

        TimeUnit.MILLISECONDS.sleep(durationMillis)
    }
}
