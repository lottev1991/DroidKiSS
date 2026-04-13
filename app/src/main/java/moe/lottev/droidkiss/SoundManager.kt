package moe.lottev.droidkiss

import android.content.Context
import android.media.MediaPlayer
import android.media.SoundPool
import kotlinx.coroutines.Job
import java.io.File

/** FKiSS sound manager */
class SoundManager(private val context: Context) {
    val soundPool: SoundPool = SoundPool.Builder().setMaxStreams(8).build()
    val soundMap = mutableMapOf<String, Int>()
    val loadedIds = mutableSetOf<Int>()
    var isMusicSuspended = false
    var isSoundSuspended = false
    var musicJob: Job? = null
    var soundJob: Job? = null
    private val streamMap = mutableMapOf<String, Int>()      // Name -> StreamID (from play)

    init {
        soundPool.setOnLoadCompleteListener { _, sampleId, status ->
            if (status == 0) loadedIds.add(sampleId)
        }
    }

    fun loadSound(name: String, bytes: ByteArray) {
        try {
            // Run the conversion check
            val processedBytes = convert8BitTo16Bit(bytes)

            // Save the (possibly converted) bytes to the temp file
            val tempFile = File(context.cacheDir, name)
            tempFile.writeBytes(processedBytes)

            // Load into SoundPool
            val id = soundPool.load(tempFile.absolutePath, 1)
            soundMap[name.lowercase()] = id
        } catch (e: Exception) {

        }
    }

    fun play(name: String, loop: Boolean = false) {
        val soundName = name.lowercase()
        val soundId = soundMap[soundName] ?: return

        if (loadedIds.contains(soundId)) {
            val loopValue = if (loop) -1 else 0
            // Capture the Stream ID
            val streamId = soundPool.play(soundId, 1f, 1f, 1, loopValue, 1f)

            if (streamId != 0) {
                streamMap[soundName] = streamId
            }
        }
    }

    fun release() {
        soundPool.release()
        soundMap.clear()
        loadedIds.clear()
    }

    fun convert8BitTo16Bit(input: ByteArray): ByteArray {
        if (input.size < 44) return input

        // Check if it's 8-bit at the standard offset
        val bitsPerSample = input[34].toInt() and 0xFF
        if (bitsPerSample != 8) return input

        // Just grab the basic info from the standard offsets
        val channels = input[22].toInt() and 0xFF
        val sampleRate = (input[24].toInt() and 0xFF) or
                (input[25].toInt() and 0xFF shl 8) or
                (input[26].toInt() and 0xFF shl 16) or
                (input[27].toInt() and 0xFF shl 24)

        // We assume the data starts at 44 (Standard)
        // BUT we check if the word "data" is actually at 36.
        // If it's NOT, we skip the first 54 bytes (common in 'fact' chunk files)
        val dataStart = if (input[36] == 'd'.code.toByte()) 44 else 54

        if (input.size <= dataStart) return input

        val dataSize8 = input.size - dataStart
        val dataSize16 = dataSize8 * 2
        val output = ByteArray(44 + dataSize16)

        // BUILD THE PERFECT HEADER
        "RIFF".toByteArray().copyInto(output, 0)
        writeInt(output, 4, 36 + dataSize16)
        "WAVEfmt ".toByteArray().copyInto(output, 8)
        writeInt(output, 16, 16)
        writeShort(output, 20, 1) // PCM
        writeShort(output, 22, channels.toShort())
        writeInt(output, 24, sampleRate)
        writeInt(output, 28, sampleRate * channels * 2)
        writeShort(output, 32, (channels * 2).toShort())
        writeShort(output, 34, 16)
        "data".toByteArray().copyInto(output, 36)
        writeInt(output, 40, dataSize16)

        // CONVERT (The same math you had before, which was correct)
        for (i in 0 until dataSize8) {
            val sample8 = input[dataStart + i].toInt() and 0xFF
            val sample16 = (sample8 - 128) * 256
            output[44 + i * 2] = (sample16 and 0xFF).toByte()
            output[44 + i * 2 + 1] = (sample16 shr 8 and 0xFF).toByte()
        }

        return output
    }

    // Helper functions for Little Endian writing
    private fun writeInt(output: ByteArray, offset: Int, value: Int) {
        output[offset] = (value and 0xFF).toByte()
        output[offset + 1] = (value shr 8 and 0xFF).toByte()
        output[offset + 2] = (value shr 16 and 0xFF).toByte()
        output[offset + 3] = (value shr 24 and 0xFF).toByte()
    }

    private fun writeShort(output: ByteArray, offset: Int, value: Short) {
        output[offset] = (value.toInt() and 0xFF).toByte()
        output[offset + 1] = (value.toInt() shr 8 and 0xFF).toByte()
    }

    private var midiPlayer: MediaPlayer? = null

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

            midiPlayer = MediaPlayer().apply {
                setDataSource(tempMidi.absolutePath)
                isLooping = true
                prepare()
                start()
            }
        } catch (e: Exception) {

        }
    }

    fun pauseMusic() {
        isMusicSuspended = true
        midiPlayer?.pause()
    }

    /** Resumes music player */
    fun resumeMusic() {
        isMusicSuspended = false
        if (musicJob == null || musicJob?.isActive == false) {
            midiPlayer?.start()
        }
    }

    fun stopMusic() {
        musicJob?.cancel()
        midiPlayer?.stop()
        midiPlayer?.release()
        midiPlayer = null
    }

    fun pauseAll() {
        isSoundSuspended = true
        soundPool.autoPause()
        pauseMusic()
    }

    fun resumeAll() {
        isSoundSuspended = false
        soundPool.autoResume()
        resumeMusic()
        soundJob?.start()
    }

    fun stopAll() {
        stopMusic()
        soundJob?.cancel()
    }
}