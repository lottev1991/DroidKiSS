package moe.lottev.droidkiss

import android.content.Context
import kotlinx.coroutines.Job
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import java.io.File

class SoundManager(private val context: Context) {

    // Initialize LibVLC with standard options
    private val libVLC: LibVLC by lazy {
        val args = arrayListOf(
            "-vvv",
            "--aout=opensles",
        )

        // MIDI soundfont path
        val sfPath = getSoundFontPath("Jnsgm2.sf2")
        if (sfPath != null) {
            // The argument VLC expects for MIDI synthesis
            args.add("--soundfont=$sfPath")
        }

        LibVLC(context, args)
    }
    private var musicPlayer: MediaPlayer? = null

    // Store SFX players to manage overlapping sounds
    private val sfxPlayers = mutableMapOf<String, MediaPlayer>()

    var isMusicSuspended = false
    var isSoundSuspended = false
    var musicJob: Job? = null
    var soundJob: Job? = null

    val soundMap = mutableMapOf<String, String>()

    /**
     * Loads sound bytes into a temp file for VLC to read.
     */
    fun loadSound(name: String, bytes: ByteArray) {
        try {
            val soundName = name.lowercase()
            val tempFile = File(context.cacheDir, soundName)
            tempFile.writeBytes(bytes)
            soundMap[soundName] = tempFile.absolutePath
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun play(name: String, loop: Boolean = false) {
        if (isSoundSuspended) return

        val soundName = name.lowercase()
        val path = soundMap[soundName] ?: return

        try {
            // Clean up existing player for this specific sound
            sfxPlayers[soundName]?.let {
                it.stop()
                it.release()
            }

            val player = MediaPlayer(libVLC)
            val media = Media(libVLC, path)

            // Set looping if required
            if (loop) {
                media.addOption(":input-repeat=65535")
            }

            player.media = media
            media.release() // The player retains the media

            player.play()

            // Cleanup for non-looping sounds
            if (!loop) {
                player.setEventListener { event ->
                    if (event.type == MediaPlayer.Event.EndReached) {
                        player.release()
                        sfxPlayers.remove(soundName)
                    }
                }
            }

            sfxPlayers[soundName] = player
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun handleMusicAction(filename: String, allFiles: Map<String, ByteArray>) {
        val cleanName = filename.lowercase().trim()
        val entry = allFiles.entries.find {
            val key = it.key.lowercase()
            key == cleanName || key.endsWith("/$cleanName") || key.startsWith(cleanName)
        }

        if (entry != null) {
            playMidi(entry.value)
        }
    }

    private fun playMidi(bytes: ByteArray) {
        try {
            stopMusic()
            val tempMidi = File(context.cacheDir, "current_music.mid")
            tempMidi.writeBytes(bytes)

            musicPlayer = MediaPlayer(libVLC).apply {
                val media = Media(libVLC, tempMidi.absolutePath)
                media.addOption(":input-repeat=65535") // High repeat count for looping
                this.media = media
                media.release()
                play()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun pauseMusic() {
        isMusicSuspended = true
        if (musicPlayer?.isPlaying == true) {
            musicPlayer?.pause()
        }
    }

    fun resumeMusic() {
        isMusicSuspended = false
        musicPlayer?.play()
    }

    fun stopMusic() {
        musicJob?.cancel()
        musicPlayer?.apply {
            stop()
            release()
        }
        musicPlayer = null
    }

    fun pauseAll() {
        isSoundSuspended = true
        sfxPlayers.values.forEach { if (it.isPlaying) it.pause() }
        pauseMusic()
    }

    fun resumeAll() {
        isSoundSuspended = false
        sfxPlayers.values.forEach { it.play() }
        resumeMusic()
    }

    fun stopAll() {
        stopMusic()
        sfxPlayers.values.forEach {
            it.stop()
            it.release()
        }
        sfxPlayers.clear()
        soundJob?.cancel()
    }

    fun release() {
        stopAll()
        soundMap.clear()
        libVLC.release() // Crucial to release the native LibVLC instance
    }

    private fun getSoundFontPath(fileName: String): String? {
        val destinationFile = File(context.cacheDir, fileName)

        // Only copy if it doesn't exist, or always copy during dev to ensure updates
        try {
            context.assets.open(fileName).use { inputStream ->
                destinationFile.outputStream().use { outputStream ->
                    inputStream.copyTo(outputStream)
                }
            }
            return destinationFile.absolutePath
        } catch (e: Exception) {
            e.printStackTrace()
            return null
        }
    }
}