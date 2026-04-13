package moe.lottev.droidkiss

/** KiSS palette (KCF) decoder. */
class PaletteDecoder {
    /** Decodes all palettes in a doll. */
    fun decodeAllBanks(bytes: ByteArray): List<IntArray> {
        val isVersion1 =
            bytes.size > 4 && bytes[0].toInt() == 'K'.code && bytes[1].toInt() == 'i'.code
        val bits = if (isVersion1) (bytes[5].toInt() and 0xFF) else 12
        val offset = if (isVersion1) 32 else 0
        val bytesPerColor = if (bits == 12) 2 else 3

        val allBanks = mutableListOf<IntArray>()
        var cursor = offset

        while (cursor + (16 * bytesPerColor) <= bytes.size) {
            val remainingBytes = bytes.size - cursor

            // Determine if this specific bank is 16 or 256 colors
            val colorsInThisBank = when {
                bits == 12 -> 16
                // If there's enough room for a 256-color bank (768 bytes), assume 256.
                // Otherwise, it must be a 16-color bank (48 bytes).
                remainingBytes >= (256 * bytesPerColor) -> 256
                else -> 16
            }

            val palette = IntArray(256)
            for (c in 0 until colorsInThisBank) {
                if (cursor + bytesPerColor > bytes.size) break

                val r8: Int
                val g8: Int
                val b8: Int
                if (bits == 12) {
                    val b1 = bytes[cursor].toInt() and 0xFF
                    val b2 = bytes[cursor + 1].toInt() and 0xFF
                    cursor += 2
                    val r4 = (b1 ushr 4) and 0x0F
                    val b4 = b1 and 0x0F
                    val g4 = b2 and 0x0F
                    r8 = r4 or (r4 shl 4)
                    b8 = b4 or (b4 shl 4)
                    g8 = g4 or (g4 shl 4)
                } else {
                    r8 = bytes[cursor].toInt() and 0xFF
                    g8 = bytes[cursor + 1].toInt() and 0xFF
                    b8 = bytes[cursor + 2].toInt() and 0xFF
                    cursor += 3
                }
                // Index 0 is transparent, 1-255 are opaque
                palette[c] = (if (c == 0) 0 else 0xFF shl 24) or (r8 shl 16) or (g8 shl 8) or b8
            }
            allBanks.add(palette)
        }
        return allBanks
    }
}