package com.mario.movies

import android.app.Activity
import android.app.PictureInPictureParams
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Rational
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.ViewGroup
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.mario.movies.ui.theme.MOVIESTheme
import kotlinx.coroutines.delay
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import android.content.Intent

class PlayerActivity : ComponentActivity() {
    private var isInPipMode = mutableStateOf(false)
    private var libVLC: LibVLC? = null
    private var mediaPlayer: MediaPlayer? = null
    private var videoUrlState = mutableStateOf("")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        enableEdgeToEdge()
        
        videoUrlState.value = intent.getStringExtra("VIDEO_URL") ?: ""
        if (videoUrlState.value.isEmpty()) {
            finish()
            return
        }

        libVLC = LibVLC(this, ArrayList<String>().apply {
            add("--no-drop-late-frames")
            add("--no-skip-frames")
            add("--rtsp-tcp")
            add("-vvv")
        })
        mediaPlayer = MediaPlayer(libVLC)

        setContent {
            MOVIESTheme {
                VLCPlayerScreen(
                    videoUrl = videoUrlState.value,
                    mediaPlayer = mediaPlayer!!,
                    onBack = { finish() },
                    isInPipMode = isInPipMode.value,
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val newUrl = intent.getStringExtra("VIDEO_URL") ?: ""
        if (newUrl.isNotEmpty()) {
            videoUrlState.value = newUrl
        }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        enterPipMode()
    }

    private fun enterPipMode() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val params = PictureInPictureParams.Builder()
                .setAspectRatio(Rational(16, 9))
                .build()
            enterPictureInPictureMode(params)
        }
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        isInPipMode.value = isInPictureInPictureMode
    }

    override fun onDestroy() {
        super.onDestroy()
        mediaPlayer?.stop()
        mediaPlayer?.release()
        libVLC?.release()
    }
}

@Composable
fun VLCPlayerScreen(
    videoUrl: String,
    mediaPlayer: MediaPlayer,
    onBack: () -> Unit,
    isInPipMode: Boolean,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val activity = context as? Activity
    val window = activity?.window

    var currentTime by remember { mutableLongStateOf(0L) }
    var totalTime by remember { mutableLongStateOf(0L) }
    var isPlaying by remember { mutableStateOf(false) }
    var showControls by remember { mutableStateOf(true) }
    var showAudioDialog by remember { mutableStateOf(false) }
    var showSubtitleDialog by remember { mutableStateOf(false) }

    BackHandler {
        onBack()
    }

    // Auto-hide controls
    LaunchedEffect(showControls, isPlaying) {
        if (showControls && isPlaying) {
            delay(3000)
            showControls = false
        }
    }

    // Handle new video URL
    LaunchedEffect(videoUrl) {
        if (videoUrl.isNotEmpty()) {
            mediaPlayer.stop()
            val media = Media(mediaPlayer.libVLC, Uri.parse(videoUrl))
            media.setHWDecoderEnabled(true, false)
            media.addOption(":network-caching=1500")
            mediaPlayer.media = media
            media.release()
            mediaPlayer.play()
        }
    }

    DisposableEffect(isInPipMode) {
        if (!isInPipMode) {
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            if (window != null) {
                val controller = WindowInsetsControllerCompat(window, window.decorView)
                controller.hide(WindowInsetsCompat.Type.systemBars())
                controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        }

        val listener = object : MediaPlayer.EventListener {
            override fun onEvent(event: MediaPlayer.Event) {
                when (event.type) {
                    MediaPlayer.Event.TimeChanged -> currentTime = event.timeChanged
                    MediaPlayer.Event.LengthChanged -> totalTime = event.lengthChanged
                    MediaPlayer.Event.Playing -> isPlaying = true
                    MediaPlayer.Event.Paused -> isPlaying = false
                    MediaPlayer.Event.Stopped -> isPlaying = false
                    MediaPlayer.Event.EndReached -> onBack()
                }
            }
        }
        mediaPlayer.setEventListener(listener)

        onDispose {
            mediaPlayer.setEventListener(null)
            if (!isInPipMode) {
                activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                if (window != null) {
                    val controller = WindowInsetsControllerCompat(window, window.decorView)
                    controller.show(WindowInsetsCompat.Type.systemBars())
                }
            }
        }
    }

    Box(modifier = modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(
            factory = { ctx ->
                SurfaceView(ctx).apply {
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                    holder.addCallback(object : SurfaceHolder.Callback {
                        override fun surfaceCreated(holder: SurfaceHolder) {
                            val vout = mediaPlayer.vlcVout
                            vout.setVideoView(this@apply)
                            vout.attachViews()
                            
                            // Media is handled by LaunchedEffect(videoUrl)
                        }
                        override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                            mediaPlayer.vlcVout.setWindowSize(width, height)
                        }
                        override fun surfaceDestroyed(holder: SurfaceHolder) {
                            mediaPlayer.vlcVout.detachViews()
                        }
                    })
                }
            },
            modifier = Modifier.fillMaxSize()
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null
                ) { showControls = !showControls }
        )

        AnimatedVisibility(
            visible = showControls && !isInPipMode,
            enter = fadeIn(),
            exit = fadeOut()
        ) {
            Box(modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.4f))) {
                // Top Bar
                Row(
                    modifier = Modifier.fillMaxWidth().padding(16.dp).align(Alignment.TopCenter),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = Color.White)
                    }
                    
                    Row {
                        IconButton(onClick = { showAudioDialog = true }) {
                            Icon(Icons.Default.Audiotrack, contentDescription = "Audio", tint = Color.White)
                        }
                        IconButton(onClick = { showSubtitleDialog = true }) {
                            Icon(Icons.Default.Subtitles, contentDescription = "Subtitles", tint = Color.White)
                        }
                    }
                }

                // Center Controls
                Row(
                    modifier = Modifier.align(Alignment.Center),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(32.dp)
                ) {
                    IconButton(onClick = { mediaPlayer.time = (mediaPlayer.time - 10000).coerceAtLeast(0) }) {
                        Icon(Icons.Default.Replay10, contentDescription = "Rewind", tint = Color.White, modifier = Modifier.size(48.dp))
                    }

                    IconButton(onClick = { 
                        if (isPlaying) mediaPlayer.pause() else mediaPlayer.play()
                    }) {
                        Icon(
                            if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                            contentDescription = "Play/Pause",
                            tint = Color.White,
                            modifier = Modifier.size(64.dp)
                        )
                    }

                    IconButton(onClick = { mediaPlayer.time = (mediaPlayer.time + 10000).coerceAtMost(totalTime) }) {
                        Icon(Icons.Default.Forward10, contentDescription = "Forward", tint = Color.White, modifier = Modifier.size(48.dp))
                    }
                }

                // Bottom Progress Bar
                Column(
                    modifier = Modifier.fillMaxWidth().align(Alignment.BottomCenter).padding(16.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(formatTime(currentTime), color = Color.White, fontSize = 12.sp)
                        Text(formatTime(totalTime), color = Color.White, fontSize = 12.sp)
                    }
                    Slider(
                        value = currentTime.toFloat(),
                        onValueChange = { mediaPlayer.time = it.toLong() },
                        valueRange = 0f..totalTime.toFloat().coerceAtLeast(1f),
                        colors = SliderDefaults.colors(
                            thumbColor = MaterialTheme.colorScheme.primary,
                            activeTrackColor = MaterialTheme.colorScheme.primary,
                            inactiveTrackColor = Color.White.copy(alpha = 0.3f)
                        )
                    )
                }
            }
        }
    }

    if (showAudioDialog) {
        val tracks = mediaPlayer.audioTracks
        val currentTrackId = mediaPlayer.audioTrack
        AlertDialog(
            onDismissRequest = { showAudioDialog = false },
            title = { Text("Select Audio Track") },
            text = {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    tracks?.forEach { track ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth().clickable {
                                mediaPlayer.audioTrack = track.id
                                showAudioDialog = false
                            }.padding(8.dp)
                        ) {
                            RadioButton(selected = track.id == currentTrackId, onClick = null)
                            Text(track.name ?: "Unknown", modifier = Modifier.padding(start = 8.dp))
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showAudioDialog = false }) { Text("Close") }
            }
        )
    }

    if (showSubtitleDialog) {
        val tracks = mediaPlayer.spuTracks
        val currentTrackId = mediaPlayer.spuTrack
        AlertDialog(
            onDismissRequest = { showSubtitleDialog = false },
            title = { Text("Select Subtitles") },
            text = {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    // Option to disable subtitles
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth().clickable {
                            mediaPlayer.spuTrack = -1
                            showSubtitleDialog = false
                        }.padding(8.dp)
                    ) {
                        RadioButton(selected = currentTrackId == -1, onClick = null)
                        Text("None", modifier = Modifier.padding(start = 8.dp))
                    }
                    
                    tracks?.forEach { track ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth().clickable {
                                mediaPlayer.spuTrack = track.id
                                showSubtitleDialog = false
                            }.padding(8.dp)
                        ) {
                            RadioButton(selected = track.id == currentTrackId, onClick = null)
                            Text(track.name ?: "Unknown", modifier = Modifier.padding(start = 8.dp))
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showSubtitleDialog = false }) { Text("Close") }
            }
        )
    }
}

private fun formatTime(millis: Long): String {
    val totalSeconds = millis / 1000
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) {
        String.format("%02d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format("%02d:%02d", minutes, seconds)
    }
}
