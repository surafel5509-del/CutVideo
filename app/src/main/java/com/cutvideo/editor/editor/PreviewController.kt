package com.cutvideo.editor.editor

import android.content.Context
import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import com.cutvideo.editor.domain.model.AspectRatio
import com.cutvideo.editor.domain.model.Clip
import com.cutvideo.editor.domain.model.ProjectDoc
import com.cutvideo.editor.domain.model.TrackType
import com.cutvideo.editor.editor.gl.CutVideoGlEffect
import com.cutvideo.editor.nativebridge.NativeEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.min

data class PreviewState(
    val isPlaying: Boolean = false,
    val positionUs: Long = 0L,
    val durationUs: Long = 0L,
    val activeClipId: String? = null,
    val speed: Float = 1f,
    val buffering: Boolean = false,
    val muteOriginal: Boolean = false,
    val error: String? = null
)

/**
 * Real preview engine.
 *
 * Video: a single [ExoPlayer] plays the main video track as a playlist of clipped [MediaItem]s. The
 * main video track is kept magnetic (gapless) by [com.cutvideo.editor.domain.timeline.TimelineManager],
 * so ordering the playlist is enough to get frame-accurate sequencing. Per-clip speed is applied with
 * `PlaybackParameters` (audio is time-stretched by ExoPlayer, keeping A/V sync), and the per-clip
 * colour pipeline runs as a GPU [CutVideoGlEffect] that always reads the *currently active* clip.
 *
 * Audio tracks / overlay tracks: dedicated follower players that are driven from the main player's
 * timeline position. They only hold the clip that is active right now, which makes gaps behave
 * correctly (silence) and keeps memory low.
 */
@UnstableApi
class PreviewController(
    context: Context,
    private val nativeEngine: NativeEngine
) {

    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var ticker: Job? = null

    private var doc: ProjectDoc? = null
    private var videoClips: List<Clip> = emptyList()

    /** Clip handed to the GPU pipeline. Mutated as playback crosses clip boundaries. */
    @Volatile
    private var activeClipForLook: Clip? = null
    private var aspect: AspectRatio = AspectRatio.RATIO_16_9

    private val _state = MutableStateFlow(PreviewState())
    val state: StateFlow<PreviewState> = _state.asStateFlow()

    val player: ExoPlayer = ExoPlayer.Builder(appContext)
        .build()
        .apply {
            // The colour pipeline always reads the clip that is active right now, so a single effect
            // instance covers the whole playlist. Must be set before prepare() — Media3 requires the
            // effects pipeline to exist before the first prepare.
            setVideoEffects(listOf(buildLookEffect()))
        }

    /** Aspect the GPU effect was last bound with (effects can be swapped mid-playback). */
    private var effectsAspect: AspectRatio = AspectRatio.RATIO_16_9

    private fun buildLookEffect(): CutVideoGlEffect = CutVideoGlEffect(
        clipProvider = {
            activeClipForLook ?: Clip(id = "placeholder", trackType = TrackType.VIDEO)
        },
        aspect = aspect,
        applyAnimation = false,
        // Media3 hands the effect media-timeline presentation time; convert to clip-local time so
        // mask/effect keyframes sample exactly where the playhead is (same math as the export).
        localTimeProvider = { presentationTimeUs ->
            val clip = activeClipForLook
            if (clip != null && clip.speed > 0f) {
                (((presentationTimeUs - clip.sourceInUs) / clip.speed).toLong()).coerceAtLeast(0L)
            } else {
                presentationTimeUs
            }
        }
    )

    private val musicPlayer: ExoPlayer = ExoPlayer.Builder(appContext).build()
    private val overlayPlayer: ExoPlayer = ExoPlayer.Builder(appContext).build()

    private var musicClipId: String? = null
    private var overlayClipId: String? = null
    private var muteOriginal = false
    private var lastVideoIndex = -1

    init {
        player.repeatMode = Player.REPEAT_MODE_OFF
        musicPlayer.repeatMode = Player.REPEAT_MODE_OFF
        overlayPlayer.repeatMode = Player.REPEAT_MODE_OFF
        musicPlayer.volume = 1f
        overlayPlayer.volume = 0f // overlay video: its original audio is mixed by the audio tracks

        player.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                _state.value = _state.value.copy(isPlaying = isPlaying)
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                _state.value = _state.value.copy(buffering = playbackState == Player.STATE_BUFFERING)
            }

            override fun onPlayerError(error: PlaybackException) {
                Log.e(TAG, "preview error", error)
                _state.value = _state.value.copy(error = error.errorCodeName)
            }

            override fun onPositionDiscontinuity(
                oldPosition: Player.PositionInfo,
                newPosition: Player.PositionInfo,
                reason: Int
            ) {
                syncFollowers(timelinePositionUs())
            }
        })
        startTicker()
    }

    // ---------------------------------------------------------------------------------------------
    // Document binding
    // ---------------------------------------------------------------------------------------------

    /**
     * Rebuilds the preview playlist. Called when clips are added, trimmed, reordered or the project is
     * (re)opened. [playheadUs] lets the UI keep the cursor where the user left it.
     */
    fun setDocument(document: ProjectDoc, playheadUs: Long = 0L, autoPlay: Boolean = false) {
        val previousPlaying = player.isPlaying
        doc = document
        aspect = document.aspect
        if (effectsAspect != aspect) {
            // The effect reads the project aspect for its uniforms; rebind so a portrait project
            // is graded with the right aspect ratio. Media3 allows changing effects mid-playback.
            effectsAspect = aspect
            runCatching { player.setVideoEffects(listOf(buildLookEffect())) }
        }
        videoClips = document.tracks
            .filter { it.type == TrackType.VIDEO }
            .flatMap { it.sortedClips }
            .filter { it.uri.isNotBlank() }

        val items = videoClips.map { clip -> mediaItemFor(clip) }
        if (items.isEmpty()) {
            player.clearMediaItems()
            activeClipForLook = null
            _state.value = _state.value.copy(
                durationUs = document.durationUs,
                positionUs = 0L,
                activeClipId = null
            )
            stopFollowers()
            return
        }

        player.setMediaItems(items, 0, 0L)
        player.prepare()
        nativeEngine.attach(document)
        lastVideoIndex = -1
        seekTo(playheadUs)
        if (previousPlaying || autoPlay) play() else pause()
        _state.value = _state.value.copy(durationUs = document.durationUs)
    }

    /** Refreshes a single clip's look without rebuilding the playlist (used by adjustment sliders). */
    fun refreshLook(clip: Clip) {
        doc?.let { nativeEngine.refreshClip(it, clip) }
        val index = videoClips.indexOfFirst { it.id == clip.id }
        if (index >= 0) {
            videoClips = videoClips.toMutableList().also { it[index] = clip }
        }
        if (activeClipForLook?.id == clip.id) activeClipForLook = clip
    }

    fun setMuteOriginal(muted: Boolean) {
        muteOriginal = muted
        player.volume = if (muted) 0f else 1f
        _state.value = _state.value.copy(muteOriginal = muted)
    }

    val isMuteOriginal: Boolean get() = muteOriginal

    // ---------------------------------------------------------------------------------------------
    // Transport
    // ---------------------------------------------------------------------------------------------

    fun play() {
        if (videoClips.isEmpty()) {
            _state.value = _state.value.copy(error = "No video clips to play")
            return
        }
        // A PlaybackException leaves the player in STATE_IDLE, where playWhenReady alone never
        // starts playback again — the play button would silently die. Re-prepare first.
        if (player.playbackState == Player.STATE_IDLE) {
            player.setMediaItems(videoClips.map { mediaItemFor(it) }, player.currentMediaItemIndex.coerceAtLeast(0), 0L)
            player.prepare()
        }
        player.play()
        if (musicClipId != null) musicPlayer.play()
        if (overlayClipId != null) overlayPlayer.play()
    }

    fun pause() {
        player.pause()
        musicPlayer.pause()
        overlayPlayer.pause()
    }

    fun togglePlayPause() {
        if (player.isPlaying) pause() else play()
    }

    fun seekTo(timelineUs: Long) {
        val target = timelineUs.coerceAtLeast(0L)
        if (videoClips.isEmpty()) {
            _state.value = _state.value.copy(positionUs = 0L)
            return
        }
        val index = videoClips.indexOfFirst { target >= it.startUs && target < it.endUs }
        if (index >= 0) {
            val clip = videoClips[index]
            val localUs = ((target - clip.startUs) * clip.speed).toLong()
            player.seekTo(index, (localUs / 1000L).coerceAtLeast(0L))
            applyActiveClip(clip)
        } else if (target < videoClips.first().startUs) {
            player.seekTo(0, 0L)
            applyActiveClip(videoClips.first())
        } else {
            val lastIndex = videoClips.lastIndex
            val last = videoClips[lastIndex]
            // Seeking past the end parks on the final frame.
            player.seekTo(lastIndex, (last.sourceSpanUs / 1000L).coerceAtLeast(1L))
            applyActiveClip(last)
        }
        _state.value = _state.value.copy(positionUs = target)
        syncFollowers(target)
    }

    fun stepFrame(frames: Int) {
        val fps = doc?.frameRate ?: 30
        val frameUs = 1_000_000L / fps.coerceAtLeast(1)
        pause()
        seekTo(timelinePositionUs() + frames * frameUs)
    }

    fun setPreviewSpeed(speed: Float) {
        player.playbackParameters = PlaybackParameters(speed.coerceIn(0.25f, 4f))
    }

    /** Current timeline position derived from the player's media position and the active clip speed. */
    fun timelinePositionUs(): Long {
        val clip = videoClips.getOrNull(player.currentMediaItemIndex) ?: return 0L
        val localUs = player.currentPosition * 1000L
        return clip.startUs + (localUs / clip.speed.coerceAtLeast(0.05f)).toLong()
    }

    // ---------------------------------------------------------------------------------------------
    // Internals
    // ---------------------------------------------------------------------------------------------

    private fun mediaItemFor(clip: Clip): MediaItem {
        val builder = MediaItem.Builder()
            .setUri(clip.uri)
            .setClippingConfiguration(
                MediaItem.ClippingConfiguration.Builder()
                    .setStartPositionUs(clip.sourceInUs.coerceAtLeast(0L))
                    .apply {
                        if (clip.sourceDurationUs > 0L) {
                            val end = min(clip.sourceInUs + clip.sourceSpanUs, clip.sourceDurationUs)
                            setEndPositionUs(end.coerceAtLeast(clip.sourceInUs + 10_000L))
                        }
                    }
                    .build()
            )
        return builder.build()
    }

    private fun applyActiveClip(clip: Clip) {
        activeClipForLook = clip
        player.playbackParameters = PlaybackParameters(clip.speed.coerceIn(0.25f, 4f))
        _state.value = _state.value.copy(activeClipId = clip.id, speed = clip.speed)
    }

    private fun startTicker() {
        ticker?.cancel()
        ticker = scope.launch {
            while (true) {
                delay(60)
                val document = doc ?: continue
                val index = player.currentMediaItemIndex
                if (index != lastVideoIndex) {
                    lastVideoIndex = index
                    videoClips.getOrNull(index)?.let { applyActiveClip(it) }
                }
                val position = if (player.isPlaying) timelinePositionUs() else _state.value.positionUs
                if (player.isPlaying) {
                    _state.value = _state.value.copy(
                        positionUs = position,
                        durationUs = document.durationUs
                    )
                    syncFollowers(position)
                }
                applyLiveKeyframes()
            }
        }
    }

    /**
     * Applies the clip-local SPEED and VOLUME keyframe curves to the players at the current
     * position. Speed ramps update `playbackParameters` continuously (the export applies the same
     * curve exactly through its `SpeedProvider`); volume keyframes scale the clip volume.
     */
    private fun applyLiveKeyframes() {
        val clip = videoClips.getOrNull(player.currentMediaItemIndex) ?: return
        val localUs = player.currentPosition * 1000L
        val speedTrack = clip.keyframeTrack(com.cutvideo.editor.domain.model.KeyframeProperty.SPEED)
        if (speedTrack != null) {
            val speed = com.cutvideo.editor.editor.KeyframeEngine
                .evaluate(speedTrack, localUs, clip.speed)
                .coerceIn(0.25f, 4f)
            if (abs(player.playbackParameters.speed - speed) > 0.01f) {
                player.playbackParameters = PlaybackParameters(speed)
            }
        }
        val volumeTrack = clip.keyframeTrack(com.cutvideo.editor.domain.model.KeyframeProperty.VOLUME)
        if (volumeTrack != null || player.volume != expectedVolume(clip, localUs)) {
            player.volume = expectedVolume(clip, localUs)
        }
    }

    private fun expectedVolume(clip: Clip, localUs: Long): Float {
        if (muteOriginal) return 0f
        val kf = com.cutvideo.editor.editor.KeyframeEngine.evaluate(
            clip.keyframeTrack(com.cutvideo.editor.domain.model.KeyframeProperty.VOLUME),
            localUs,
            1f
        )
        return (clip.effectiveVolume * kf).coerceIn(0f, 1f)
    }

    /** Keeps the audio and overlay followers glued to the main timeline. */
    private fun syncFollowers(timelineUs: Long) {
        val document = doc ?: return
        val playing = player.isPlaying

        val audioClip = document.tracks
            .filter { it.type == TrackType.AUDIO && !it.muted }
            .flatMap { track -> track.clips.map { track to it } }
            .firstOrNull { (_, clip) -> timelineUs >= clip.startUs && timelineUs < clip.endUs }
            ?.let { (_, clip) -> clip }

        syncFollower(musicPlayer, audioClip, timelineUs, playing, isOverlay = false)
        musicClipId = audioClip?.id

        val overlayClip = document.tracks
            .filter { it.type == TrackType.OVERLAY && !it.hidden }
            .flatMap { it.clips }
            .firstOrNull {
                it.pip.enabled && timelineUs >= it.startUs && timelineUs < it.endUs
            }
        syncFollower(overlayPlayer, overlayClip, timelineUs, playing, isOverlay = true)
        overlayClipId = overlayClip?.id

        nativeEngine.gainAt(timelineUs)
    }

    private fun syncFollower(
        follower: ExoPlayer,
        clip: Clip?,
        timelineUs: Long,
        playing: Boolean,
        isOverlay: Boolean
    ) {
        if (clip == null || clip.uri.isBlank()) {
            if (follower.mediaItemCount > 0) follower.stop()
            return
        }
        val expectedLocalUs = ((timelineUs - clip.startUs) * clip.speed).toLong().coerceAtLeast(0L)
        val currentId = if (isOverlay) overlayClipId else musicClipId

        if (currentId != clip.id) {
            follower.setMediaItem(mediaItemFor(clip))
            follower.prepare()
            follower.seekTo(expectedLocalUs / 1000L)
            follower.volume = if (isOverlay) 0f else clip.effectiveVolume.coerceIn(0f, 1f)
            follower.playbackParameters = PlaybackParameters(clip.speed.coerceIn(0.25f, 4f))
            if (playing) follower.play() else follower.pause()
            return
        }

        val driftUs = abs(follower.currentPosition * 1000L - expectedLocalUs)
        if (driftUs > 150_000L) {
            follower.seekTo(expectedLocalUs / 1000L)
        }
        follower.volume = if (isOverlay) 0f else clip.effectiveVolume.coerceIn(0f, 1f)
        if (playing && !follower.isPlaying) follower.play() else if (!playing && follower.isPlaying) follower.pause()
    }

    private fun stopFollowers() {
        musicPlayer.stop()
        overlayPlayer.stop()
        musicClipId = null
        overlayClipId = null
    }

    /** Overlay player surface handle for picture-in-picture preview. */
    val overlayPlayerInstance: ExoPlayer get() = overlayPlayer

    /** Clip currently under the playhead on overlay tracks (for the PiP surface). */
    fun activeOverlayClip(): Clip? {
        val document = doc ?: return null
        val position = _state.value.positionUs
        return document.tracks
            .filter { it.type == TrackType.OVERLAY && !it.hidden }
            .flatMap { it.clips }
            .firstOrNull { it.pip.enabled && position >= it.startUs && position < it.endUs }
    }

    fun release() {
        ticker?.cancel()
        runCatching { player.release() }
        runCatching { musicPlayer.release() }
        runCatching { overlayPlayer.release() }
        nativeEngine.detach()
    }

    private companion object {
        const val TAG = "PreviewController"
    }
}
