package com.wakequest.app.sound

import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.wakequest.app.Notifier
import com.wakequest.app.R
import com.wakequest.app.logic.NoiseGenerator
import com.wakequest.app.logic.SoundType
import com.wakequest.app.ui.MainActivity
import java.util.concurrent.CopyOnWriteArraySet
import kotlin.concurrent.thread

class SleepSoundService : Service() {
    companion object {
        private const val ACTION_PLAY = "com.wakequest.app.sound.PLAY"
        private const val ACTION_STOP = "com.wakequest.app.sound.STOP"
        private const val TAG = "WQSleepSound"

        @Volatile var playing: SoundType? = null
            private set
        @Volatile var endsAt: Long = 0L
            private set
        /** 0..1, may be changed while playing. */
        @Volatile var volume: Float = 0.6f

        private val listeners = CopyOnWriteArraySet<Runnable>()
        private val main by lazy { Handler(Looper.getMainLooper()) }
        fun addListener(r: Runnable) { listeners.add(r) }
        fun removeListener(r: Runnable) { listeners.remove(r) }
        private fun changed() = listeners.forEach { main.post(it) }

        /** [minutes] = 0 plays until stopped. */
        fun play(ctx: Context, type: SoundType, minutes: Int) {
            ContextCompat.startForegroundService(
                ctx,
                Intent(ctx, SleepSoundService::class.java)
                    .setAction(ACTION_PLAY)
                    .putExtra("type", type.name)
                    .putExtra("minutes", minutes),
            )
        }

        fun stop(ctx: Context) {
            try {
                ctx.startService(Intent(ctx, SleepSoundService::class.java).setAction(ACTION_STOP))
            } catch (e: Exception) {
                Log.w(TAG, "stop failed", e)
            }
        }
    }

    @Volatile private var generation = 0
    private var worker: Thread? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PLAY -> {
                val type = runCatching { SoundType.valueOf(intent.getStringExtra("type") ?: "") }.getOrDefault(SoundType.RAIN)
                val minutes = intent.getIntExtra("minutes", 30)
                goForeground(type)
                startPlayback(type, minutes)
            }
            else -> shutdown()
        }
        return START_NOT_STICKY
    }

    private fun goForeground(type: SoundType) {
        val open = PendingIntent.getActivity(
            this, 10,
            Intent(this, MainActivity::class.java).putExtra(MainActivity.EXTRA_TAB, 2)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this, 11, Intent(this, SleepSoundService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val n = NotificationCompat.Builder(this, Notifier.CH_SOUND)
            .setSmallIcon(R.drawable.ic_waves)
            .setContentTitle("${type.emoji} ${type.title}")
            .setContentText("Sleep sound playing")
            .setOngoing(true)
            .setContentIntent(open)
            .addAction(0, "Stop", stop)
            .build()
        val fgType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK else 0
        ServiceCompat.startForeground(this, Notifier.ID_SOUND, n, fgType)
    }

    private fun startPlayback(type: SoundType, minutes: Int) {
        val gen = ++generation
        worker?.interrupt()
        val start = System.currentTimeMillis()
        val end = if (minutes > 0) start + minutes * 60_000L else Long.MAX_VALUE
        playing = type
        endsAt = if (minutes > 0) end else 0L
        changed()
        worker = thread(name = "sleep-sound") {
            var track: AudioTrack? = null
            try {
                val sr = 44100
                val minBuf = AudioTrack.getMinBufferSize(sr, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
                track = AudioTrack.Builder()
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                            .build(),
                    )
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setSampleRate(sr)
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .build(),
                    )
                    .setBufferSizeInBytes(maxOf(minBuf, sr))
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build()
                track.setVolume(0f)
                track.play()
                val noise = NoiseGenerator(type, sr)
                val buf = ShortArray(2048)
                while (generation == gen) {
                    val now = System.currentTimeMillis()
                    if (now >= end) break
                    val fadeIn = ((now - start) / 3000f).coerceIn(0f, 1f)
                    val fadeOut = if (end == Long.MAX_VALUE) 1f else ((end - now) / 15000f).coerceIn(0f, 1f)
                    track.setVolume(volume.coerceIn(0f, 1f) * fadeIn * fadeOut)
                    noise.fill(buf)
                    if (track.write(buf, 0, buf.size) < 0) break
                }
            } catch (e: Exception) {
                Log.e(TAG, "playback failed", e)
            } finally {
                try { track?.stop() } catch (_: Exception) {}
                track?.release()
            }
            if (generation == gen) main.post { if (generation == gen) shutdown() }
        }
    }

    private fun shutdown() {
        generation++
        worker = null
        playing = null
        endsAt = 0L
        changed()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        generation++
        if (playing != null) {
            playing = null
            changed()
        }
        super.onDestroy()
    }
}
