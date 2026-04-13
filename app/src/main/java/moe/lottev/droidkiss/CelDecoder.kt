package moe.lottev.droidkiss

import android.graphics.Bitmap

/** The KiSS cel decoder. This decodes all cel file to bitmaps. It checks for palette types, including no palette (CherryKiSS). */
class CelDecoder {
    private fun readShort(data: ByteArray, index: Int) =
        (data[index].toInt() and 0xFF) or ((data[index + 1].toInt() and 0xFF) shl 8)

    /** Cel-to-bitmap decoder */
    fun decodeToBitmap(data: ByteArray, palette: IntArray?): DecodeResult? {
        if (data.size < 4) return null

        val isGs = data[0].toInt().toChar() == 'K' && data[1].toInt().toChar() == 'i' &&
                data[2].toInt().toChar() == 'S' && data[3].toInt().toChar() == 'S'

        val width: Int
        val height: Int
        val offsetX: Int
        val offsetY: Int
        val dataOffset: Int
        val bitDepth: Int

        if (isGs) {
            bitDepth = data[5].toInt() and 0xFF //
            width = readShort(data, 8)
            height = readShort(data, 10)
            offsetX = readShort(data, 12).toShort().toInt()
            offsetY = readShort(data, 14).toShort().toInt()
            dataOffset = 32
        } else {
            bitDepth = 4 // Default for v0
            width = readShort(data, 1)
            height = readShort(data, 3)
            offsetX = 0
            offsetY = 0
            dataOffset = 4
        }

        val pixels = IntArray(width * height)
        var cursor = dataOffset

        when (bitDepth) {
            32 -> {
                // TRUE COLOR LOOP (RGBA / CherryKiSS)
                for (i in pixels.indices) {
                    if (cursor + 3 >= data.size) break
                    val b = data[cursor++].toInt() and 0xFF
                    val g = data[cursor++].toInt() and 0xFF
                    val r = data[cursor++].toInt() and 0xFF
                    val a = data[cursor++].toInt() and 0xFF
                    pixels[i] = (a shl 24) or (r shl 16) or (g shl 8) or b
                }
            }

            8 -> {
                // INDEXED 8-BIT LOOP
                if (palette == null) return null
                for (i in pixels.indices) {
                    if (cursor >= data.size) break
                    val index = data[cursor++].toInt() and 0xFF
                    pixels[i] = if (index == 0) 0 else palette.getOrElse(index) { 0 }
                }
            }

            4 -> {
                if (palette == null) return null
                // Calculate how many bytes are actually in ONE row of 4-bit data

                for (y in 0 until height) {
                    for (x in 0 until width step 2) {
                        if (cursor >= data.size) break
                        val byte = data[cursor++].toInt()

                        // Pixel 1 (High nibble)
                        val idx1 = (byte shr 4) and 0x0F
                        pixels[y * width + x] = if (idx1 == 0) 0 else palette.getOrElse(idx1) { 0 }

                        // Pixel 2 (Low nibble) - Only if we aren't past the width
                        if (x + 1 < width) {
                            val idx2 = byte and 0x0F
                            pixels[y * width + x + 1] =
                                if (idx2 == 0) 0 else palette.getOrElse(idx2) { 0 }
                        }
                    }
                }
            }
        }

        val bitmap = Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
        return DecodeResult(bitmap, data.copyOfRange(dataOffset, data.size), offsetX, offsetY)
    }
}