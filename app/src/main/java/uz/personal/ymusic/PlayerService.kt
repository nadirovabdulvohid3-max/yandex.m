package uz.personal.ymusic

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.Icon
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.MediaPlayer
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import java.net.HttpURLConnection
import java.net.URL

/**
 * Bitta MediaPlayer. Navbat (queue) mantiqi JavaScript tomonda,
 * bu yerda faqat ijro, audio fokus va bildirishnoma.
 */
object Player {

    private val main = Handler(Looper.getMainLooper())
    private var app: Context? = null
    private var mp: MediaPlayer? = null
    private var ready = false
    private var gen = 0
    private var focusReq: AudioFocusRequest? = null
    private var resumeOnFocus = false

    @Volatile var title = ""
    @Volatile var artist = ""
    @Volatile var cover: Bitmap? = null
    @Volatile var onEvent: ((String) -> Unit)? = null

    private val attrs: AudioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
        .build()

    val isPlaying: Boolean
        get() = try {
            ready && mp?.isPlaying == true
        } catch (e: Exception) {
            false
        }

    val position: Int
        get() = try {
            if (ready) (mp?.currentPosition ?: 0) else 0
        } catch (e: Exception) {
            0
        }

    val duration: Int
        get() = try {
            if (ready) (mp?.duration ?: 0) else 0
        } catch (e: Exception) {
            0
        }

    fun init(ctx: Context) {
        app = ctx.applicationContext
    }

    fun emit(ev: String) {
        main.post { onEvent?.invoke(ev) }
    }

    private fun refresh() {
        PlayerService.instance?.update()
    }

    fun play(url: String, t: String, a: String, coverUrl: String) {
        main.post {
            val ctx = app
            if (ctx != null) {
                title = t
                artist = a
                cover = null
                gen += 1
                val my = gen
                releasePlayer()
                requestFocus(ctx)
                val m = MediaPlayer()
                m.setAudioAttributes(attrs)
                m.setWakeMode(ctx, PowerManager.PARTIAL_WAKE_LOCK)
                m.setOnPreparedListener { p ->
                    if (my == gen) {
                        ready = true
                        p.start()
                        refresh()
                        emit("state")
                    }
                }
                m.setOnCompletionListener {
                    if (my == gen) emit("ended")
                }
                m.setOnErrorListener { _, _, _ ->
                    if (my == gen) emit("error")
                    true
                }
                mp = m
                ready = false
                try {
                    m.setDataSource(url)
                    m.prepareAsync()
                } catch (e: Exception) {
                    emit("error")
                }
                if (PlayerService.instance == null) {
                    ctx.startForegroundService(Intent(ctx, PlayerService::class.java))
                } else {
                    refresh()
                }
                loadCover(coverUrl, my)
            }
        }
    }

    fun toggle() {
        main.post { if (isPlaying) pauseNow() else resumeNow() }
    }

    fun pause() {
        main.post { pauseNow() }
    }

    fun resume() {
        main.post { resumeNow() }
    }

    fun seek(ms: Int) {
        main.post {
            try {
                if (ready) mp?.seekTo(ms.toLong(), MediaPlayer.SEEK_CLOSEST)
            } catch (e: Exception) {
            }
            refresh()
        }
    }

    fun stop() {
        main.post {
            releasePlayer()
            abandonFocus()
            refresh()
        }
    }

    fun shutdown() {
        releasePlayer()
        abandonFocus()
        val ctx = app
        if (ctx != null) ctx.stopService(Intent(ctx, PlayerService::class.java))
    }

    private fun pauseNow() {
        try {
            if (ready) mp?.pause()
        } catch (e: Exception) {
        }
        refresh()
        emit("state")
    }

    private fun resumeNow() {
        val ctx = app
        if (ready && ctx != null) {
            requestFocus(ctx)
            try {
                mp?.start()
            } catch (e: Exception) {
            }
        }
        refresh()
        emit("state")
    }

    private fun releasePlayer() {
        try {
            mp?.release()
        } catch (e: Exception) {
        }
        mp = null
        ready = false
    }

    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        when (change) {
            AudioManager.AUDIOFOCUS_LOSS -> {
                resumeOnFocus = false
                pauseNow()
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                if (isPlaying) {
                    resumeOnFocus = true
                    pauseNow()
                }
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                try {
                    mp?.setVolume(0.25f, 0.25f)
                } catch (e: Exception) {
                }
            }
            AudioManager.AUDIOFOCUS_GAIN -> {
                try {
                    mp?.setVolume(1f, 1f)
                } catch (e: Exception) {
                }
                if (resumeOnFocus) {
                    resumeOnFocus = false
                    resumeNow()
                }
            }
        }
    }

    private fun requestFocus(ctx: Context) {
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        var r = focusReq
        if (r == null) {
            r = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(attrs)
                .setOnAudioFocusChangeListener(focusListener, main)
                .build()
            focusReq = r
        }
        am.requestAudioFocus(r)
    }

    private fun abandonFocus() {
        val ctx = app ?: return
        val r = focusReq ?: return
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        am.abandonAudioFocusRequest(r)
        focusReq = null
    }

    private fun loadCover(url: String, my: Int) {
        if (url.isEmpty()) return
        Thread {
            try {
                val c = URL(url).openConnection() as HttpURLConnection
                c.connectTimeout = 8000
                c.readTimeout = 8000
                val bmp = c.inputStream.use { BitmapFactory.decodeStream(it) }
                main.post {
                    if (my == gen && bmp != null) {
                        cover = bmp
                        refresh()
                    }
                }
            } catch (e: Exception) {
            }
        }.start()
    }
}

class PlayerService : Service() {

    companion object {
        @Volatile var instance: PlayerService? = null
        const val CH = "playback"
        const val ID = 17
        const val A_TOGGLE = "uz.personal.ymusic.TOGGLE"
        const val A_NEXT = "uz.personal.ymusic.NEXT"
        const val A_PREV = "uz.personal.ymusic.PREV"
    }

    private lateinit var session: MediaSession
    private var started = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH, "Ijro", NotificationManager.IMPORTANCE_LOW))
        session = MediaSession(this, "ymusic")
        session.setCallback(object : MediaSession.Callback() {
            override fun onPlay() {
                Player.resume()
            }

            override fun onPause() {
                Player.pause()
            }

            override fun onSkipToNext() {
                Player.emit("next")
            }

            override fun onSkipToPrevious() {
                Player.emit("prev")
            }

            override fun onSeekTo(pos: Long) {
                Player.seek(pos.toInt())
            }
        })
        session.isActive = true
        instance = this
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            A_TOGGLE -> Player.toggle()
            A_NEXT -> Player.emit("next")
            A_PREV -> Player.emit("prev")
        }
        update()
        return START_NOT_STICKY
    }

    fun update() {
        val playing = Player.isPlaying
        val md = MediaMetadata.Builder()
            .putString(MediaMetadata.METADATA_KEY_TITLE, Player.title)
            .putString(MediaMetadata.METADATA_KEY_ARTIST, Player.artist)
            .putLong(MediaMetadata.METADATA_KEY_DURATION, Player.duration.toLong())
        val bmp = Player.cover
        if (bmp != null) md.putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, bmp)
        session.setMetadata(md.build())

        val actions = PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or
            PlaybackState.ACTION_PLAY_PAUSE or PlaybackState.ACTION_SKIP_TO_NEXT or
            PlaybackState.ACTION_SKIP_TO_PREVIOUS or PlaybackState.ACTION_SEEK_TO
        val st = if (playing) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED
        session.setPlaybackState(
            PlaybackState.Builder()
                .setActions(actions)
                .setState(st, Player.position.toLong(), 1f)
                .build()
        )

        val n = buildNotification(playing)
        if (!started) {
            started = true
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
            } else {
                startForeground(ID, n)
            }
        } else {
            getSystemService(NotificationManager::class.java).notify(ID, n)
        }
    }

    private fun buildNotification(playing: Boolean): Notification {
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE
        )
        val title = if (Player.title.isEmpty()) "Mening musiqam" else Player.title
        val b = Notification.Builder(this, CH)
            .setSmallIcon(R.drawable.ic_notif)
            .setContentTitle(title)
            .setContentText(Player.artist)
            .setContentIntent(open)
            .setOngoing(playing)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .addAction(act(R.drawable.ic_prev, "Oldingi", A_PREV))
            .addAction(
                act(
                    if (playing) R.drawable.ic_pause else R.drawable.ic_play,
                    if (playing) "Pauza" else "Ijro",
                    A_TOGGLE
                )
            )
            .addAction(act(R.drawable.ic_next, "Keyingi", A_NEXT))
            .setStyle(
                Notification.MediaStyle()
                    .setMediaSession(session.sessionToken)
                    .setShowActionsInCompactView(0, 1, 2)
            )
        val bmp = Player.cover
        if (bmp != null) b.setLargeIcon(bmp)
        return b.build()
    }

    private fun act(icon: Int, label: String, action: String): Notification.Action {
        val pi = PendingIntent.getService(
            this, action.hashCode(),
            Intent(this, PlayerService::class.java).setAction(action),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return Notification.Action.Builder(Icon.createWithResource(this, icon), label, pi).build()
    }

    override fun onDestroy() {
        instance = null
        try {
            session.release()
        } catch (e: Exception) {
        }
        super.onDestroy()
    }
}
