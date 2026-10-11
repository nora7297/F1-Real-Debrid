package com.f1debrid.app.player

import android.graphics.Color
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.annotation.OptIn
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.DefaultTimeBar
import androidx.media3.ui.PlayerView
import com.f1debrid.app.F1DebridApp

/** Full-screen ExoPlayer with D-pad friendly controls; remembers the playback position per file. */
@OptIn(UnstableApi::class)
class PlayerActivity : ComponentActivity() {

    companion object {
        const val EXTRA_URL = "url"
        const val EXTRA_TITLE = "title"
        const val EXTRA_RESUME_KEY = "resume_key"

        /** How far one left/right press moves when scrubbing with the seek bar. */
        const val SCRUB_STEP_MS = 2 * 60_000L
    }

    private lateinit var playerView: PlayerView
    private var player: ExoPlayer? = null
    private var url: String = ""
    private var title: String = ""
    private var resumeKey: String = ""

    private val prefs get() = (application as F1DebridApp).graph.prefs

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        url = intent.getStringExtra(EXTRA_URL).orEmpty()
        if (url.isBlank()) {
            finish()
            return
        }
        title = intent.getStringExtra(EXTRA_TITLE).orEmpty()
        resumeKey = intent.getStringExtra(EXTRA_RESUME_KEY) ?: url

        playerView = PlayerView(this).apply {
            useController = true
            controllerAutoShow = true
            controllerShowTimeoutMs = 5000
            setShowNextButton(false)
            setShowPreviousButton(false)
            setBackgroundColor(Color.BLACK)
            keepScreenOn = true
        }
        setContentView(playerView)
        // By default each D-pad press on the seek bar jumps 1/20 of the video (about 10 minutes on a
        // full race broadcast). Use a fixed step instead.
        (playerView.findViewById<View>(androidx.media3.ui.R.id.exo_progress) as? DefaultTimeBar)
            ?.setKeyTimeIncrement(SCRUB_STEP_MS)
        hideSystemUi()

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (playerView.isControllerFullyVisible) playerView.hideController() else finish()
            }
        })
    }

    override fun onStart() {
        super.onStart()
        initPlayer()
    }

    override fun onStop() {
        savePosition()
        releasePlayer()
        super.onStop()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (::playerView.isInitialized && playerView.dispatchKeyEvent(event)) return true
        return super.dispatchKeyEvent(event)
    }

    private fun initPlayer() {
        if (player != null) return
        val httpFactory = DefaultHttpDataSource.Factory()
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(20_000)
            .setReadTimeoutMs(30_000)
        val mediaSourceFactory = DefaultMediaSourceFactory(this)
            .setDataSourceFactory(DefaultDataSource.Factory(this, httpFactory))
        val exo = ExoPlayer.Builder(this)
            .setMediaSourceFactory(mediaSourceFactory)
            .setSeekBackIncrementMs(10_000)
            .setSeekForwardIncrementMs(30_000)
            .build()
        player = exo
        playerView.player = exo

        val item = MediaItem.Builder()
            .setUri(url)
            .setMediaMetadata(MediaMetadata.Builder().setTitle(title).build())
            .build()
        exo.setMediaItem(item)
        val resume = prefs.resumePosition(resumeKey)
        if (resume > 0) exo.seekTo(resume)
        exo.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                Toast.makeText(this@PlayerActivity, "Playback error: ${error.errorCodeName}", Toast.LENGTH_LONG).show()
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED) {
                    prefs.saveResumePosition(resumeKey, 0)
                    finish()
                }
            }
        })
        exo.playWhenReady = true
        exo.prepare()
    }

    private fun savePosition() {
        val p = player ?: return
        val duration = p.duration
        val position = p.currentPosition
        // Forget the position when within the last two minutes so the next play starts from the top.
        val remember = duration > 0 && duration - position > 120_000 && position > 15_000
        prefs.saveResumePosition(resumeKey, if (remember) position else 0)
    }

    private fun releasePlayer() {
        player?.release()
        player = null
        if (::playerView.isInitialized) playerView.player = null
    }

    private fun hideSystemUi() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, playerView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }
}
