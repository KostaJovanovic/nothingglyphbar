package com.kosta.glyphbar

import android.app.Application
import android.media.MediaPlayer
import android.net.Uri
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Holds app state across configuration changes.
 *
 * Rotating the phone previously tore down the Glyph session and re-ran the
 * async service handshake, so the bar dropped and every control greyed out for
 * a moment. Owning the controller here keeps the session alive through it.
 */
class GlyphViewModel(app: Application) : AndroidViewModel(app) {

    val glyph = GlyphController(app.applicationContext, viewModelScope)
    val audio = AudioReactive()
    val motion = MotionDetector(app.applicationContext)
    val pov = PovController(glyph, motion, viewModelScope)
    private val store = AnimationStore(app.applicationContext)

    private val _custom = MutableStateFlow(store.load())
    val custom: StateFlow<List<CustomAnimation>> = _custom.asStateFlow()

    /** Frames being edited. Starts with a single blank frame. */
    private val _editorFrames = MutableStateFlow(listOf(emptyFrame()))
    val editorFrames: StateFlow<List<Frame>> = _editorFrames.asStateFlow()

    private val _editorIndex = MutableStateFlow(0)
    val editorIndex: StateFlow<Int> = _editorIndex.asStateFlow()

    private val _editorStepMs = MutableStateFlow(120)
    val editorStepMs: StateFlow<Int> = _editorStepMs.asStateFlow()

    private val _audioError = MutableStateFlow<String?>(null)
    val audioError: StateFlow<String?> = _audioError.asStateFlow()

    /** Imported Glyph Composer compositions, this session only. */
    private val _tones = MutableStateFlow<List<Glyphtone.Parsed>>(emptyList())
    val tones: StateFlow<List<Glyphtone.Parsed>> = _tones.asStateFlow()

    private val _toneError = MutableStateFlow<String?>(null)
    val toneError: StateFlow<String?> = _toneError.asStateFlow()

    private var player: MediaPlayer? = null

    init {
        glyph.connect()
    }

    // ------------------------------------------------------------ glyphtones

    fun importTone(uri: Uri, displayName: String) {
        _toneError.value = null
        val app = getApplication<Application>()
        val result = try {
            app.contentResolver.openInputStream(uri).use { stream ->
                if (stream == null) {
                    Glyphtone.Result.Err("Couldn't open that file.")
                } else {
                    Glyphtone.parse(stream, displayName)
                }
            }
        } catch (e: Exception) {
            Log.e("GlyphVM", "import failed", e)
            Glyphtone.Result.Err("Import failed: ${e.message}")
        }

        when (result) {
            is Glyphtone.Result.Ok -> {
                _tones.value = _tones.value.filterNot { it.title == result.parsed.title } + result.parsed
                _toneError.value = result.parsed.notes.takeIf { it.isNotEmpty() }?.joinToString(" ")
            }
            is Glyphtone.Result.Err -> _toneError.value = result.message
        }
    }

    /** Play a composition's lights, with its audio if a source uri is known. */
    fun playTone(tone: Glyphtone.Parsed, uri: Uri?) {
        stopTonePlayer()
        uri?.let {
            player = try {
                MediaPlayer().apply {
                    setDataSource(getApplication(), it)
                    prepare()
                    start()
                }
            } catch (e: Exception) {
                // Lights still work without audio — don't fail the whole action.
                Log.w("GlyphVM", "audio playback failed", e)
                null
            }
        }
        glyph.play(tone.title, Glyphtone.toKeyframes(tone))
    }

    /**
     * Play ANY audio file and generate its pattern live from the audio.
     *
     * Analysis runs off our own MediaPlayer's audio session. That's the part
     * that makes this work at all: Android blocks third-party apps from
     * analysing the global output mix, but our own session is fair game.
     */
    fun playSoundFile(uri: Uri, name: String, mode: AudioReactive.Mode) {
        stopTonePlayer()
        audio.stop()
        _audioError.value = null

        val mp = try {
            MediaPlayer().apply {
                setDataSource(getApplication(), uri)
                prepare()
                isLooping = true
            }
        } catch (e: Exception) {
            Log.e("GlyphVM", "media prepare failed", e)
            _audioError.value = "Couldn't play that file: ${e.javaClass.simpleName}. " +
                "Try an mp3, m4a, wav, ogg or flac."
            return
        }

        player = mp
        audio.mode = mode
        val err = audio.startSession(mp.audioSessionId)
        if (err != null) {
            _audioError.value = err
            stopTonePlayer()
            return
        }
        mp.start()
        glyph.playSource("$name · ${mode.name}") { audio.frame() }
    }

    /** React to the room: microphone in, pattern out. Works with any app's music. */
    fun startMic(mode: AudioReactive.Mode) {
        stopTonePlayer()
        audio.mode = mode
        val err = audio.startMic()
        _audioError.value = err
        if (err == null) glyph.playSource("Mic · ${mode.name}") { audio.frame() }
    }

    fun loadToneIntoEditor(tone: Glyphtone.Parsed) {
        _editorFrames.value = tone.frames.map { it.copyFrame() }
        _editorIndex.value = 0
        _editorStepMs.value = 17
        glyph.setFrame(tone.frames.first().copyFrame())
    }

    fun clearToneError() {
        _toneError.value = null
    }

    private fun stopTonePlayer() {
        runCatching {
            player?.stop()
            player?.release()
        }
        player = null
    }

    // ------------------------------------------------------------ editor

    private fun currentFrame(): Frame = _editorFrames.value[_editorIndex.value]

    fun editorSelect(index: Int) {
        _editorIndex.value = index.coerceIn(0, _editorFrames.value.lastIndex)
        glyph.setFrame(currentFrame().copyFrame())
    }

    fun editorSetZone(zone: Int, level: Int) {
        val frames = _editorFrames.value.toMutableList()
        val f = frames[_editorIndex.value].copyFrame()
        f[zone] = level.coerceIn(0, MAX_LIGHT)
        frames[_editorIndex.value] = f
        _editorFrames.value = frames
        // Live-preview the edit on the real bar.
        glyph.setFrame(f.copyFrame())
    }

    fun editorToggleZone(zone: Int) {
        val f = currentFrame()
        editorSetZone(zone, if (f[zone] > 0) 0 else MAX_LIGHT)
    }

    fun editorAddFrame(copyCurrent: Boolean) {
        val frames = _editorFrames.value.toMutableList()
        val new = if (copyCurrent) currentFrame().copyFrame() else emptyFrame()
        frames.add(_editorIndex.value + 1, new)
        _editorFrames.value = frames
        _editorIndex.value = _editorIndex.value + 1
        glyph.setFrame(new.copyFrame())
    }

    fun editorDeleteFrame() {
        if (_editorFrames.value.size <= 1) {
            // Never leave the editor with zero frames — clear instead.
            _editorFrames.value = listOf(emptyFrame())
            _editorIndex.value = 0
            glyph.setFrame(emptyFrame())
            return
        }
        val frames = _editorFrames.value.toMutableList()
        frames.removeAt(_editorIndex.value)
        _editorFrames.value = frames
        _editorIndex.value = _editorIndex.value.coerceAtMost(frames.lastIndex)
        glyph.setFrame(currentFrame().copyFrame())
    }

    fun editorClear() {
        _editorFrames.value = listOf(emptyFrame())
        _editorIndex.value = 0
        glyph.setFrame(emptyFrame())
    }

    fun editorSetStep(ms: Int) {
        _editorStepMs.value = ms.coerceIn(20, 1000)
    }

    fun editorKeys(): Keyframes = Keyframes(
        frames = _editorFrames.value.map { it.copyFrame() },
        stepMs = _editorStepMs.value,
        loop = true,
    )

    fun editorPreview() = glyph.play("Preview", editorKeys())

    /** Load a built-in into the editor by sampling it — a starting point to tweak. */
    fun editorLoadFrom(entry: Animations.Entry, frameCount: Int = 24) {
        val step = _editorStepMs.value
        val frames = (0 until frameCount).map { i ->
            entry.animation.frameAt(i * step / 1000f)
        }
        _editorFrames.value = frames
        _editorIndex.value = 0
        glyph.setFrame(frames.first().copyFrame())
    }

    fun editorLoadCustom(anim: CustomAnimation) {
        _editorFrames.value = anim.keys.frames.map { it.copyFrame() }
        _editorIndex.value = 0
        _editorStepMs.value = anim.keys.stepMs
        glyph.setFrame(anim.keys.frames.first().copyFrame())
    }

    fun saveCustom(name: String) {
        val trimmed = name.trim().ifEmpty { "Untitled" }
        _custom.value = store.add(CustomAnimation(trimmed, editorKeys()))
    }

    fun deleteCustom(name: String) {
        _custom.value = store.delete(name)
    }

    // ------------------------------------------------------------ audio

    fun stopAudio() {
        audio.stop()
        stopTonePlayer()
        _audioError.value = null
        glyph.stop()
    }

    fun clearAudioError() {
        _audioError.value = null
    }

    /** Called from the Activity's onStop. */
    fun onBackgrounded() {
        audio.stop()
        motion.stop()
        stopTonePlayer()
        glyph.stop()
    }

    override fun onCleared() {
        audio.stop()
        motion.stop()
        stopTonePlayer()
        glyph.release()
        super.onCleared()
    }
}
