package ai.argvid.gen0.today

import android.content.Context
import android.net.Uri
import android.view.ViewGroup
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class PlayerEvent {
    FirstFrameRendered,
}

interface MomentPlayer {
    val events: Flow<PlayerEvent>
    fun play(uri: String)
    fun release()
}

class Media3MomentPlayer(context: Context) : MomentPlayer {
    private val appContext = context.applicationContext
    private var exoPlayer: ExoPlayer? = null
    private val mutablePlayer = MutableStateFlow<Player?>(null)
    internal val currentPlayer = mutablePlayer.asStateFlow()
    private val mutableEvents = MutableSharedFlow<PlayerEvent>(extraBufferCapacity = 1)
    private var ready = false
    private var rendered = false
    private var emitted = false
    override val events: Flow<PlayerEvent> = mutableEvents

    private fun player(): ExoPlayer = exoPlayer ?: ExoPlayer.Builder(appContext).build().also { created ->
        created.addListener(
            object : Player.Listener {
                override fun onPlaybackStateChanged(playbackState: Int) {
                    ready = playbackState == Player.STATE_READY
                    emitFirstFrameWhenReady()
                }

                override fun onRenderedFirstFrame() {
                    rendered = true
                    emitFirstFrameWhenReady()
                }
            },
        )
        exoPlayer = created
        mutablePlayer.value = created
    }

    override fun play(uri: String) {
        ready = false
        rendered = false
        emitted = false
        player().run {
            setMediaItem(MediaItem.fromUri(Uri.parse(uri)))
            prepare()
            playWhenReady = true
        }
    }

    override fun release() {
        mutablePlayer.value = null
        exoPlayer?.release()
        exoPlayer = null
        ready = false
        rendered = false
        emitted = false
    }

    private fun emitFirstFrameWhenReady() {
        if (ready && rendered && !emitted) {
            emitted = true
            mutableEvents.tryEmit(PlayerEvent.FirstFrameRendered)
        }
    }
}

@Composable
fun MomentPlayerSurface(
    player: Media3MomentPlayer,
    modifier: Modifier = Modifier,
) {
    val currentPlayer by player.currentPlayer.collectAsState()
    AndroidView(
        modifier = modifier,
        factory = { context ->
            PlayerView(context).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                )
            }
        },
        update = { view -> view.player = currentPlayer },
        onRelease = { view -> view.player = null },
    )
}
