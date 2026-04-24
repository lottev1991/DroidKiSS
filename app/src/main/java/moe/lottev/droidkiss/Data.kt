package moe.lottev.droidkiss

import android.graphics.Bitmap
import android.graphics.Rect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color

/** Represents the parsed state of a KiSS doll. */
data class KissDoll(
    val name: String,
    val layers: List<KissLayer>,
    var envWidth: Int,  // e.g., 784
    var envHeight: Int, // e.g., 500
    var activeGlobalBank: Int = 0,
    val themeColor: Color,
    val bgColor: Color,
    val config: ConfigParser,
    val palettes: List<KissPalette> = emptyList(),
    val activePaletteIndex: Int = 0,
    val allFiles: Map<String, ByteArray>,
)

/** KCF palette. */
data class KissPalette(
    val id: Int,
    val colors: List<Color> // Color is from androidx.compose.ui.graphics
)

data class KissInTrigger(
    val sourceId: Int,
    val targetId: Int,
    val actions: List<KissAction>
)

/** Descriptor for a single image layer found in the CNF */
data class KissLayerDescriptor(
    val fileName: String,
    var x: Int,
    var y: Int,
    val depth: Int = 0,
    val setID: Int = 0,
    var paletteMarker: Char = 'a',
    var paletteIndex: Int = 0,
    var bankIndex: Int = 0,
    var objectId: Int,
    val initialUnmapped: Boolean = false,
    var isInitiallyFixed: Boolean = false,
    var initialFixValue: Int,
    val allowedSets: List<Int> = emptyList(),
    val celOffsetX: Int = 0,
    val celOffsetY: Int = 0,
    val initialAlpha: Int = 0,
    var currentDragOffset: Offset = Offset.Zero,
) {
    var isUnmapped by mutableStateOf(initialUnmapped)
    var isFixed by mutableStateOf(isInitiallyFixed)
    var isGhosted by mutableStateOf(false)
}

data class DecodeResult(
    val bitmap: Bitmap,
    val rawIndices: ByteArray,
    val offsetX: Int,
    val offsetY: Int,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as DecodeResult

        if (offsetX != other.offsetX) return false
        if (offsetY != other.offsetY) return false
        if (bitmap != other.bitmap) return false
        if (!rawIndices.contentEquals(other.rawIndices)) return false

        return true
    }

    override fun hashCode(): Int {
        var result = offsetX
        result = 31 * result + offsetY
        result = 31 * result + bitmap.hashCode()
        result = 31 * result + rawIndices.contentHashCode()
        return result
    }
}

data class PendingTrigger(
    val type: String,     // "collide", "apart", "in"
    val srcRaw: String,   // Could be "216" or "ssword.cel"
    val dstRaw: String,   // Could be "356" or "shield.cel"
    val rawPayload: String // <--- Add this
)

/** Snap-to rules. */
data class SnapRule(
    val triggerObj: Int,
    val targetObj: Int,
    val snapObj: Int,
    val xValue: Int,
    val yValue: Int,
    val isRelative: Boolean,
    val isRelativeFromSelf: Boolean,
    val disabled: Boolean
)

/** Represents individual CEL files. */
data class KissLayer(
    val descriptor: KissLayerDescriptor,
    var bitmap: Bitmap,
    val rawIndices: ByteArray,
    var x: Int,
    var y: Int,
    val fileName: String,
    val width: Int,
    val height: Int,
    var alpha: Float = 1f,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as KissLayer

        if (x != other.x) return false
        if (y != other.y) return false
        if (descriptor != other.descriptor) return false
        if (bitmap != other.bitmap) return false
        if (!rawIndices.contentEquals(other.rawIndices)) return false
        if (fileName != other.fileName) return false

        return true
    }

    override fun hashCode(): Int {
        var result = x
        result = 31 * result + y
        result = 31 * result + descriptor.hashCode()
        result = 31 * result + bitmap.hashCode()
        result = 31 * result + rawIndices.contentHashCode()
        result = 31 * result + fileName.hashCode()
        return result
    }

    fun getBounds(offset: Offset): Rect {
        val left = this.x + offset.x
        val top = this.y + offset.y
        return Rect(
            left.toInt(),
            top.toInt(),
            (left + this.width).toInt(),
            (top + this.height).toInt()
        )
    }
}

/** `alarm()` action */
class KissAlarm(val id: Int) {
    var delay: Int = -1      // Total wait time (ms). -1 = inactive.
    var time: Long = 0       // Current progress (ms)
    var enabled: Boolean = false // Must be false until the triggering event finishes
}

/** Represents a simplified KiSS Action (e.g., showing/hiding a specific CEL) */
data class KissAction(
    val target: String,      // "#123" or "dress.cel"
    val type: ActionType,
    val valueStr: String = "", // Holds "255", "-50", "100,200", etc.
    val extraValue: String
)

/** `shell()` events for web URLS and email addresses only. */
sealed class ShellEvent {
    data class OpenLink(val url: String) : ShellEvent()
}

/** FKiSS action type list. TODO: Implement currently unused actions. */
@Suppress("unused")
enum class ActionType {
    COL, CHANGECOL, UNFIX, SETFIX, IFFIXED, IFNOTFIXED, TRANSPARENT, MAP, UNMAP, ALTMAP, IFMAPPED, IFNOTMAPPED, TIMER, ALARM, MOVE, MOVEBYX, MOVEBYY, MOVETO, RANDOM_TIMER, PRESS, RELEASE, CATCH, DROP, FIXCATCH, FIXDROP, SET, CHANGESET, IN, OUT, COLLIDE, APART, SOUND, MUSIC, NOTIFY, DETACHED, KEY, MOUSEIN, MOUSEOUT, STILLIN, STILLOUT, ADD, ATTACH, DETACH, IF, ELSE, ENDIF, GHOST, EXITEVENT, EXITLOOP, GLUE, GOSUB, GOSUBRANDOM, GOTO, GOTORANDOM, LABEL, NOP, VIEWPORT, SHELL, DEBUG, QUIT, END;
}