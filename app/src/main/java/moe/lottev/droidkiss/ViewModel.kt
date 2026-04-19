package moe.lottev.droidkiss

import android.app.Application
import androidx.compose.runtime.*
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.launch
import android.content.Context
import android.graphics.Rect
import androidx.compose.ui.graphics.Color
import android.net.Uri
import androidx.compose.ui.geometry.Offset
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlin.math.pow
import kotlin.math.sqrt
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.SnapshotStateMap
import kotlin.collections.set
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.lifecycle.AndroidViewModel
import kotlinx.coroutines.withContext
import kotlin.text.contains

/** This is the viewmodel. A viewmodel bridges backend functionality with the frontend (see MainActivity.kt). */
class ViewModel(application: Application) :
    AndroidViewModel(application) {

    /** Disable drag/press and enable scrolling in the playfield. */
    var isScrollMode by mutableStateOf(false)

    /** Timer job caller */
    private var heartbeatJob: Job? = null

    /** The current state of the UI. */
    var uiState by mutableStateOf<KissUiState>(KissUiState.Empty)
        private set

    private val immediateQueue = mutableListOf<Int>()

    /** The zoom level of the doll. */
    var zoomLevel by mutableDoubleStateOf(1.0)

    val soundManager by lazy { SoundManager(getApplication()) }

    val engine by lazy {
        DollEngine(
            context = getApplication(),
            onMappingChanged = { target, unmapped ->
                val doll = currentDoll ?: return@DollEngine
                val layer = doll.layers.find { it.descriptor.fileName == target }
                layer?.descriptor?.isUnmapped = unmapped
            },
            onSoundDetected = { name, bytes ->
                soundManager.loadSound(name, bytes)
            }
        )
    }

    @Suppress("unused")
    sealed class KissUiState {
        object Empty : KissUiState()
        object Loading : KissUiState()
        data class Loaded(val doll: KissDoll) : KissUiState()
        data class Error(val message: String) : KissUiState()
    }

    var currentDoll: KissDoll? = null

    private val setOffsets = mutableStateMapOf<Int, SnapshotStateMap<Int, Offset>>()

    val currentOffsets: MutableMap<Int, Offset>
        get() = setOffsets.getOrPut(activeSet) { mutableStateMapOf() }

    var configParser = ConfigParser(
        onMappingChanged = { target, isUnmapped ->
            // This ensures the Real VM's setMapping is called
            this.setMapping(target, isUnmapped)
        },
    )

    /** The active set (0-9) */
    var activeSet by mutableIntStateOf(0)

    /** Grabs the border color from the current doll's palette. */
    val dollBorderColor: Color
        get() {
            val doll = currentDoll ?: return Color.Transparent
            return Color(doll.themeColor.value)
        }

    var fullCnfText: String = ""

    private val alarms = mutableMapOf<Int, KissAlarm>()
    private var timerJob: Job? = null

    private var isTimerSuspended = false

    /** Toggle between "pan" and "drag" modes. "Drag" is the default. Users will still be able to pan from the sides of the doll screen, even in "drag" mode. "Pan" mode was implemented for larger dolls/zoom instances. */
    fun toggleScrollMode() {
        isScrollMode = !isScrollMode
    }

    /** Suspend `timer()` events */
    fun suspendTimer() {
        isTimerSuspended = true
    }

    /** Resume `timer()` events */
    fun resumeTimer() {
        isTimerSuspended = false

        if (timerJob == null || timerJob?.isActive == false) {
            startHeartbeat()
        }
    }

    /** Stops `timer()` events */
    fun stopTimer() {
        timerJob?.cancel()
        alarms.clear()
    }

    /** Starts heartbeat (for `timer()`, `randomtimer()`, etc.) */
    private fun startHeartbeat() {
        heartbeatJob?.cancel()
        heartbeatJob = viewModelScope.launch(Dispatchers.Default) {

            while (isActive) {
                val toFire = mutableListOf<Int>()

                val currentAlarms = synchronized(alarms) {
                    alarms.values.toList()
                }

                if (currentAlarms.isEmpty()) {
                    delay(100)
                    continue
                }

                currentAlarms.forEach { alarm ->
                    if (alarm.enabled) {
                        alarm.time += 16
                        if (alarm.time >= alarm.delay && alarm.delay > 0) { // Disable alarm if the delay time is 0
                            toFire.add(alarm.id)
                        }
                    }
                }

                toFire.forEach { alarmId ->
                    synchronized(alarms) {
                        alarms[alarmId]?.apply {
                            enabled = false
                            time = 0
                        }
                    }
                    withContext(Dispatchers.Main) {
                        executeEvent("alarm($alarmId)")
                    }
                }
                delay(16)
            }
        }
    }

    /** Handles the cleanup and @end events before a doll is swapped or closed. */
    suspend fun shutdownCurrentDoll() {
        // Execute any `end()` events
        executeEvent("end")

        // Stop all sounds
        soundManager.stopAll()

        // Stop timer
        stopTimer()

        // Add a delay
        delay(500)
    }

    var availableCnfs by mutableStateOf<List<String>>(emptyList())
    val masterFileCache = mutableMapOf<String, ByteArray>()

    /** Closes current LZH, returning to the main screen */
    fun closeLzh() {
        viewModelScope.launch {
            uiState = KissUiState.Loading
            shutdownCurrentDoll()
            uiState = KissUiState.Empty
        }
    }

    @Suppress("unused")
            /** Load LZH into the viewmodel */
    fun loadLzh(context: Context, uri: Uri, specificCnf: String? = null) {
        uiState = KissUiState.Loading
        viewModelScope.launch {
            shutdownCurrentDoll()
            isExpansionSet = false

            // Clear master cache
            masterFileCache.clear()
            availableCnfs = emptyList() // Reset CNF list for the new LZH

            try {
                // Extract all files from the LZH into our Master Cache
                val newFiles = engine.extractAllFiles(uri)
                masterFileCache.putAll(newFiles)

                // Update the available CNFs list for the UI selector
                availableCnfs = masterFileCache.keys
                    .filter { it.endsWith(".cnf", ignoreCase = true) }

                // Initialize the doll using the combined cache
                val resultDoll = engine.initializeFromCache(masterFileCache, specificCnf)

                // Update ViewModel State
                currentDoll = resultDoll
                this@ViewModel.configParser = resultDoll.config

                this@ViewModel.fullCnfText = engine.rawCnfString

                onDollLoaded(resultDoll)
                uiState = KissUiState.Loaded(resultDoll)

                // Start timers
                startHeartbeat()

                // Start special events
                executeEvent("eventhandler")
                executeEvent("initialize")
                executeEvent("begin")
                changeSet(0)
                zoomLevel = 1.0
            } catch (e: Exception) {
                uiState = KissUiState.Error(e.message ?: e.toString())
                e.printStackTrace()
            }
        }
    }

    /** Load expansion set into the viewmodel */
    @Suppress("unused")
    fun loadExpansionSet(context: Context, uri: Uri, specificCnf: String? = null) {

        availableCnfs = emptyList()

        viewModelScope.launch {
            try {
                // Extract and merge
                val expansionFiles = engine.extractAllFiles(uri)
                masterFileCache.putAll(expansionFiles)

                // pdate the available CNFs list for the UI selector
                availableCnfs = masterFileCache.keys
                    .filter { it.endsWith(".cnf", ignoreCase = true) }

                // Update currently loaded doll
                val updatedDoll = engine.initializeFromCache(masterFileCache, specificCnf)

                this@ViewModel.configParser = updatedDoll.config
                this@ViewModel.fullCnfText = engine.rawCnfString

                // Update the doll and UI
                currentDoll = updatedDoll
                onDollLoaded(updatedDoll) // This re-finalizes triggers for new layers

                // Execute special events
                executeEvent("begin")
                executeEvent("eventhandler")
                changeSet(0)
            } catch (e: Exception) {
                uiState = KissUiState.Error(e.message ?: e.toString())
                e.printStackTrace()
            }
        }
    }

    var showCnfSelector by mutableStateOf(false)
    var pendingCnfOptions by mutableStateOf<List<String>>(emptyList())
    var selectedUri by mutableStateOf<Uri?>(null)

    fun prepareLzh(context: Context, uri: Uri) {
        viewModelScope.launch {
            // Use the engine to see how many CNFs are inside
            val cnfs = engine.discoverCnfs(uri)

            if (cnfs.size > 1) {
                selectedUri = uri
                pendingCnfOptions = cnfs
                showCnfSelector = true // This triggers the dialog in the UI
            } else {
                // If only one, just load it immediately
                loadLzh(context, uri, cnfs.firstOrNull())
            }
        }
    }

    var isExpansionSet: Boolean = false
    fun prepareExpansion(context: Context, uri: Uri) {
        viewModelScope.launch {
            // Use the engine to see how many CNFs are inside
            val cnfs = engine.discoverCnfs(uri)

            if (cnfs.size > 1) {
                selectedUri = uri
                pendingCnfOptions = cnfs
                showCnfSelector = true // This triggers the dialog in the UI!
            } else {
                // If only one, just load it immediately
                loadExpansionSet(context, uri, cnfs.firstOrNull())
            }
        }
    }

    override fun onCleared() {
        heartbeatJob?.cancel() // This kills the loop when the app/viewmodel is destroyed
        super.onCleared()
        soundManager.release()
    }

    /** General refresh trigger */
    var refreshTrigger by mutableIntStateOf(0)

    /** Set switching logic */
    fun changeSet(newSet: Int) {
        val doll = currentDoll ?: return
        val content = this.fullCnfText

        // SWITCH THE ACTIVE SET FIRST
        activeSet = newSet

        // RE-PARSE BASE COORDS FOR THE NEW SET
        // This fills configParser.objectCoords with the $0 defaults
        configParser.parseGlobalCoords(content, newSet)

        // RESET LIVE LAYER POSITIONS
        doll.layers.forEach { layer ->
            val id = layer.descriptor.objectId
            val base = configParser.objectCoords[id] ?: Pair(0, 0)

            // Snap the layer back to its CNF base for this specific set
            layer.x = base.first + layer.descriptor.celOffsetX
            layer.y = base.second + layer.descriptor.celOffsetY
        }

        // FKISS Logic (Context-Aware)
        val allLines = content.split(Regex("\\r?\\n"))

        var currentActiveContext: Int? = null

        allLines.forEach { line ->
            val trimmed = line.trim().lowercase()

            // If the line is NOT a KiSS command line, KILL the context.
            if (!trimmed.startsWith("@") && !trimmed.startsWith(";@")) {
                currentActiveContext = null
                return@forEach
            }

            // Update context ONLY if 'set()' is found
            val setMatch = Regex("set\\s*\\(\\s*(\\d+)\\s*\\)").find(trimmed)
            if (setMatch != null) {
                currentActiveContext = setMatch.groupValues[1].toIntOrNull()
            }

            // EXECUTION: Only run if it's the target set
            if (currentActiveContext == newSet || currentActiveContext == 0) {
                // Only run actions, ignore the triggers themselves (press)
                // Blacklisting "altmap" is important, otherwise it will think "altmap" = "map"
                if (!trimmed.contains("press(") && !trimmed.contains("altmap(") && !trimmed.contains(
                        "release("
                    )
                ) {
                    processActionLine(trimmed)
                }
            }
        }
        executeSetActions(newSet.toString())
        currentOffsets.clear()
        // This forces the Compose UI to redraw with the new coordinate/offset context
        refreshTrigger++
    }

    fun isSetDefined(setNum: Int): Boolean {
        // If the parser found this number in any object's mapping list, it's valid
        return configParser.definedSetIndices.contains(setNum)
    }

    fun getAvailableSets(): List<Int> {
        // Return a sorted list of indices that actually have cels
        // Standard KiSS behavior: if no sets are defined, default to showing '0'
        return configParser.definedSetIndices.toList().sorted()
    }

    /** Processes FKiSS action lines */
    fun processActionLine(line: String) {
        // This Regex is the "Space Killer."
        // It captures: 1. The Command, 2. The Target/Params
        val actionRegex = Regex("([a-zA-Z]+)\\s*\\(([^)]*)\\)")

        val actions = actionRegex.findAll(line)

        actions.forEach { match ->
            val command = match.groupValues[1].lowercase()
            val rawContent = match.groupValues[2]

            // Split by comma for commands like transparent(target, value)
            // Then strip quotes and extra whitespace from each piece
            val params = rawContent.split(",").map { it.replace("\"", "").trim() }
            val target = params.getOrNull(0) ?: ""

            when (command) {
                "unmap" -> setMapping(target, true)
                "map" -> setMapping(target, false)
                "altmap" -> altMapping(target)
                "timer" -> executeEvent("timer($target)")
                // Add other commands as needed (TODO)
            }

        }
    }

    /** Drop even handler */
    fun handleDrop(draggedId: Int) {
        val doll = currentDoll ?: return

        // Find rules where the object being dropped is the one that needs to snap
        val rules = configParser.snapRules.filter { it.snapObj == draggedId }

        rules.forEach { rule ->
            val snapLayer =
                doll.layers.find { it.descriptor.objectId == rule.snapObj } ?: return@forEach


            val layers = doll.layers

            if (rule.isRelative) {
                val anchorLayer = doll.layers.find { it.descriptor.objectId == rule.triggerObj }
                    ?: return@forEach
                val anchorOffset = currentOffsets[rule.triggerObj] ?: Offset.Zero

                // Find the Anchor's VISUAL origin (Top-left of pixels)
                val anchorVisualX =
                    anchorLayer.x - anchorLayer.descriptor.celOffsetX + anchorOffset.x
                val anchorVisualY =
                    anchorLayer.y - anchorLayer.descriptor.celOffsetY + anchorOffset.y

                // The Ideal VISUAL position for the snapped object
                val targetVisualX = anchorVisualX + rule.xValue
                val targetVisualY = anchorVisualY + rule.yValue

                // The current VISUAL position of the dragged object
                val currentVisualX =
                    snapLayer.x - snapLayer.descriptor.celOffsetX + (currentOffsets[draggedId]?.x
                        ?: 0f)
                val currentVisualY =
                    snapLayer.y - snapLayer.descriptor.celOffsetY + (currentOffsets[draggedId]?.y
                        ?: 0f)

                val bitmapWidth = anchorLayer.bitmap.width
                val bitmapHeight = anchorLayer.bitmap.height

                val distanceX = sqrt(currentVisualX - anchorVisualX).toDouble().pow(2.0)
                val distanceY = sqrt(currentVisualY - anchorVisualY).toDouble().pow(2.0)

                if (distanceX < bitmapWidth && distanceY < bitmapHeight) {
                    //  Convert Visual Target back to Compose Offset
                    // Compose Offset = (Visual Target + Internal Offset) - Layer Base
                    val finalX = (targetVisualX + snapLayer.descriptor.celOffsetX) - snapLayer.x
                    val finalY = (targetVisualY + snapLayer.descriptor.celOffsetY) - snapLayer.y

                    currentOffsets[rule.snapObj] = Offset(finalX, finalY)
                }
            } else if (rule.isRelativeFromSelf) {
                val targetLayer = doll.layers.find { it.descriptor.objectId == rule.snapObj }
                    ?: return@forEach
                val targetOffset = currentOffsets[rule.snapObj] ?: Offset.Zero

                // Find the Anchor's VISUAL origin (Top-left of pixels)
                val targetVisualX =
                    targetLayer.x - targetLayer.descriptor.celOffsetX + targetOffset.x
                val targetVisualY =
                    targetLayer.y - targetLayer.descriptor.celOffsetY + targetOffset.y

                // The current VISUAL position of the dragged object
                val currentVisualX =
                    snapLayer.x - snapLayer.descriptor.celOffsetX + (currentOffsets[draggedId]?.x
                        ?: 0f)
                val currentVisualY =
                    snapLayer.y - snapLayer.descriptor.celOffsetY + (currentOffsets[draggedId]?.y
                        ?: 0f)

                val bitmapWidth = targetLayer.bitmap.width
                val bitmapHeight = targetLayer.bitmap.height

                val distanceX = sqrt(currentVisualX - targetVisualX).toDouble().pow(2.0)
                val distanceY = sqrt(currentVisualY - targetVisualY).toDouble().pow(2.0)


                if (distanceX < bitmapWidth && distanceY < bitmapHeight) {
                    //  Convert Visual Target back to Compose Offset
                    // Compose Offset = (Visual Target + Internal Offset) - Layer Base
                    val finalX = (targetVisualX + snapLayer.descriptor.celOffsetX) - snapLayer.x
                    val finalY = (targetVisualY + snapLayer.descriptor.celOffsetY) - snapLayer.y

                    currentOffsets[rule.snapObj] = Offset(finalX, finalY)
                }


            } else {
                val anchorLayer = doll.layers.find { it.descriptor.objectId == rule.triggerObj }
                    ?: return@forEach
                val anchorOffset = currentOffsets[rule.triggerObj] ?: Offset.Zero

                // Find the Anchor's VISUAL origin (Top-left of pixels)
                val anchorVisualX =
                    anchorLayer.x - anchorLayer.descriptor.celOffsetX + anchorOffset.x
                val anchorVisualY =
                    anchorLayer.y - anchorLayer.descriptor.celOffsetY + anchorOffset.y

                // The current VISUAL position of the dragged object
                val currentVisualX =
                    snapLayer.x - snapLayer.descriptor.celOffsetX + (currentOffsets[draggedId]?.x
                        ?: 0f)
                val currentVisualY =
                    snapLayer.y - snapLayer.descriptor.celOffsetY + (currentOffsets[draggedId]?.y
                        ?: 0f)

                // --- ABSOLUTE SNAP (moveto) ---
                val idealX = rule.xValue.toFloat()
                val idealY = rule.yValue.toFloat()

                val bitmapWidth = anchorLayer.bitmap.width
                val bitmapHeight = anchorLayer.bitmap.height

                val distanceX = sqrt(currentVisualX - anchorVisualX).toDouble().pow(2.0)
                val distanceY = sqrt(currentVisualY - anchorVisualY).toDouble().pow(2.0)

                if (distanceX < bitmapWidth && distanceY < bitmapHeight) {
                    // Target Screen Coord - Base Layer Coord
                    val finalOffsetX = idealX - snapLayer.x
                    val finalOffsetY = idealY - snapLayer.y
                    currentOffsets[rule.snapObj] = Offset(finalOffsetX, finalOffsetY)
                }
                configParser.finalizeTriggers(layers)
            }
        }
        refreshTrigger++
    }

    /** General event executions */
    fun executeEvent(eventName: String) {
        val cleanTrigger = eventName.lowercase().replace(" ", "")
        val actions = configParser.eventActions[cleanTrigger] ?: return

        // Regex for event IDs
        val eventId = Regex("""#(\d+)""").find(eventName)?.groupValues?.get(1)?.toIntOrNull() ?: -1

        actions.forEach { action -> performAction(action) }

        if (eventId != -1) {
            configParser.snapRules.forEach { rule ->
                if (rule.triggerObj == eventId) applySnap(rule)
            }
        }
        refreshTrigger++
    }

    /**
     * Consolidated action executor.
     */
    private fun performAction(action: KissAction) {
        try {
            when (action.type) {
                ActionType.UNMAP -> setMappingStrict(action.target, true)
                ActionType.MAP -> setMappingStrict(action.target, false)
                ActionType.ALTMAP -> altMappingStrict(action.target)
                ActionType.TIMER -> fireTimer(action)
                ActionType.RANDOM_TIMER -> fireRandomTimer(action)
                ActionType.PRESS -> {
                    val target = action.target.replace("#", "").lowercase()
                    val targetId = target.toIntOrNull()
                    val targetLayer = currentDoll?.layers?.find { layer ->
                        val layerName = layer.descriptor.fileName.lowercase().substringBefore(".")
                        if (targetId != null) layer.descriptor.objectId == targetId else layerName == target
                    }
                    targetLayer?.let { executePressActions(it) }
                }

                ActionType.RELEASE -> {
                    val target = action.target.replace("#", "").lowercase()
                    val targetId = target.toIntOrNull()
                    val targetLayer = currentDoll?.layers?.find { layer ->
                        val layerName = layer.descriptor.fileName.lowercase().substringBefore(".")
                        if (targetId != null) layer.descriptor.objectId == targetId else layerName == target
                    }
                    targetLayer?.let { executeReleaseActions(it) }
                }
                ActionType.ALARM -> {
                    val id = action.target.trim().toIntOrNull() ?: return
                    synchronized(immediateQueue) {
                        immediateQueue.add(id)
                    }
                }
                ActionType.CHANGESET -> changeSet(action.target.toIntOrNull() ?: 0)

                ActionType.SOUND -> {
                    val target = action.target.replace("\"", "").lowercase()
                    soundManager.play(target, false)
                }

                ActionType.MUSIC -> {
                    val doll = currentDoll ?: return
                    val cleanTarget =
                        action.target.replace("\"", "").replace("null", "").trim().lowercase()
                    if (cleanTarget.isNotEmpty()) {
                        soundManager.handleMusicAction(cleanTarget, doll.allFiles)
                    } else {
                        soundManager.stopMusic()
                    }
                }

                ActionType.NOTIFY -> {
                    val target = action.target.trim()
                        .removePrefix("(").removeSuffix(")")
                        .trim()
                        .removeSurrounding("\"")
                        .removeSurrounding("'")
                    showNotify(target)
                }

                ActionType.MOVETO -> {
                    val targetId = action.target.replace("#", "").toIntOrNull() ?: return
                    val coords = action.valueStr.split(",")
                    if (coords.size >= 2) {
                        val tx = coords[0].trim().toIntOrNull() ?: 0
                        val ty = coords[1].trim().toIntOrNull() ?: 0
                        val layer = currentDoll?.layers?.find { it.descriptor.objectId == targetId }
                        if (layer != null) {
                            val finalX = tx - layer.x
                            val finalY = ty - layer.y
                            currentOffsets[targetId] = Offset(finalX.toFloat(), finalY.toFloat())
                        }
                    }
                }

                ActionType.MOVE -> moveRelativeFromSelf(action)
                ActionType.MOVEBYX, ActionType.MOVEBYY -> moveRelative(action)

                ActionType.UNFIX -> {
                    val target = action.target.replace("#", "").lowercase()
                    val targetId = target.toIntOrNull()
                    val targetLayer = currentDoll?.layers?.find { layer ->
                        val layerName = layer.descriptor.fileName.lowercase().substringBefore(".")
                        if (targetId != null && !target.contains(".")) {
                            layer.descriptor.objectId == targetId
                        } else {
                            layerName == target
                        }
                    }
                    if (targetLayer != null || (targetId != null && targetId == 0)) targetLayer?.descriptor?.isFixed = false
                }

                ActionType.SETFIX -> {
                    val target = action.target.replace("#", "").lowercase()
                    val targetId = target.toIntOrNull()
                    val targetLayer = currentDoll?.layers?.find { layer ->
                        val layerName = layer.descriptor.fileName.lowercase().substringBefore(".")
                        if (targetId != null && !target.contains(".")) {
                            layer.descriptor.objectId == targetId
                        } else {
                            layerName == target
                        }
                    }
                    val value = action.valueStr.toIntOrNull()

                    if (targetLayer != null && value != null) {
                        targetLayer.descriptor.isFixed = value > 0
                    }
                }

                ActionType.GOSUB -> {
                    val target = action.target
                    executeLabelActions(target)
                }

                ActionType.QUIT -> {
                    if (uiState is KissUiState.Loaded) {
                        uiState = KissUiState.Empty
                    }
                }
                else -> {}
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun isPixelCollisionWithOffset(
        layer1: KissLayer,
        offset1: Offset,
        layer2: KissLayer
    ): Boolean {
        // We use the provided offset for the dragged item
        val rect1 = layer1.getBounds(offset1)

        // We use the current position for the target (usually static)
        val offset2 = currentOffsets[layer2.descriptor.objectId] ?: Offset.Zero
        val rect2 = layer2.getBounds(offset2)

        val intersect = Rect()
        if (!intersect.setIntersect(rect1, rect2)) return false

        val width = intersect.width()
        val height = intersect.height()
        val pixels1 = IntArray(width * height)
        val pixels2 = IntArray(width * height)

        // Pull the intersection area into memory all at once
        layer1.bitmap.getPixels(
            pixels1,
            0,
            width,
            intersect.left - rect1.left,
            intersect.top - rect1.top,
            width,
            height
        )
        layer2.bitmap.getPixels(
            pixels2,
            0,
            width,
            intersect.left - rect2.left,
            intersect.top - rect2.top,
            width,
            height
        )

        for (i in pixels1.indices) {
            // Check if both pixels have alpha > 0
            if ((pixels1[i] ushr 24) > 0 && (pixels2[i] ushr 24) > 0) {
                return true
            }
        }
        return false
    }

    // Tracks pairs currently touching: "350-0"
    private val activeCollisionPairs = mutableSetOf<String>()

    /** `collide()` and `apart()` FKiSS events */
    fun checkCollideApart(draggedId: Int, dropOffset: Offset? = null) {
        val doll = currentDoll ?: return
        val srcLayer =
            doll.layers.find { it.descriptor.objectId == draggedId && !it.descriptor.isUnmapped }
                ?: return

        val draggedName = srcLayer.descriptor.fileName.lowercase()
        val offsetToCheck = dropOffset ?: currentOffsets[draggedId] ?: Offset.Zero

        configParser.eventActions.forEach { (eventKey) ->
            val parts = eventKey.split("_")
            if (parts.size < 3) return@forEach

            val type = parts[0]
            val triggerSrcRaw = parts[1].lowercase()
            val triggerDstRaw = parts[2].lowercase()

            // --- THE UNIVERSAL IDENTITY CHECK ---
            // Matches if the dragging object is the Source OR Destination
            val isMatch = (draggedId.toString() == triggerSrcRaw || draggedName == triggerSrcRaw) ||
                    (draggedId.toString() == triggerDstRaw || draggedName == triggerDstRaw)

            if (!isMatch) return@forEach

            // --- FIND THE TARGET OBJECT ---
            val isDraggedTheSource =
                (draggedId.toString() == triggerSrcRaw || draggedName == triggerSrcRaw)
            val targetIdentifier = if (isDraggedTheSource) triggerDstRaw else triggerSrcRaw

            val dstLayer = doll.layers.find {
                val idMatch = it.descriptor.objectId.toString() == targetIdentifier
                val nameMatch = it.descriptor.fileName.equals(targetIdentifier, ignoreCase = true)
                (idMatch || nameMatch) && !it.descriptor.isUnmapped
            } ?: return@forEach

            // --- MATH ---
            val r1 = srcLayer.getBounds(offsetToCheck)
            val r2 = dstLayer.getBounds(currentOffsets[dstLayer.descriptor.objectId] ?: Offset.Zero)
            val isRectOverlapping = Rect.intersects(r1, r2)

            val isOverlapping = if (type == "collide" || type == "apart") {
                isPixelCollisionWithOffset(srcLayer, offsetToCheck, dstLayer)
            } else {
                isRectOverlapping
            }

            val stateKey = "active_$eventKey"
            val isCurrentlyTouching = activeCollisionPairs.contains(stateKey)

            // --- UNIFIED EXECUTION LOGIC ---
            when (type) {
                "in" -> {
                    // Only fire if it's a NEW overlap
                    if (isRectOverlapping) {
                        activeCollisionPairs.add(stateKey)
                        executeEvent(eventKey)
                    }
                }

                "out" -> {
                    // Only fire if it WAS overlapping but now it's NOT
                    if (!isRectOverlapping) {
                        activeCollisionPairs.remove(stateKey)
                        executeEvent(eventKey)
                    }
                }

                "collide" -> {
                    if (isOverlapping && !isCurrentlyTouching) {
                        activeCollisionPairs.add(stateKey)
                        executeEvent(eventKey) // FIRING HERE
                    } else if (!isOverlapping && isCurrentlyTouching) {
                        activeCollisionPairs.remove(stateKey)
                    }
                }

                "apart" -> {
                    if (!isOverlapping && isCurrentlyTouching) {
                        activeCollisionPairs.remove(stateKey)
                        executeEvent(eventKey) // FIRING HERE
                    } else if (isOverlapping && !isCurrentlyTouching) {
                        activeCollisionPairs.add(stateKey)
                    }
                }
            }

            // Cleanup the latch
            if (!isRectOverlapping && (type == "in" || type == "out")) activeCollisionPairs.remove(
                stateKey
            )
            if (!isOverlapping && (type == "collide" || type == "apart")) activeCollisionPairs.remove(
                stateKey
            )
        }
    }

    /**
    `movebyx()`/`movebyy()` FKiSS events (relative move from anchor)
     */
    fun moveRelative(action: KissAction) {
        val movingId = action.target.replace("#", "").toIntOrNull() ?: return
        val parts = action.valueStr.split(",").map { it.trim().replace("#", "") }
        val anchorId = parts[0].toIntOrNull() ?: 0
        val offsetVal = if (parts.size > 1) parts[1].toFloatOrNull() ?: 0f else 0f

        val doll = currentDoll ?: return
        val anchorLayer =
            doll.layers.find { it.descriptor.objectId == anchorId } ?: return
        val movingLayer =
            doll.layers.find { it.descriptor.objectId == movingId } ?: return

        val currentOffset = currentOffsets[movingId] ?: Offset.Zero

        val celCorrectionX =
            (anchorLayer.descriptor.celOffsetX - movingLayer.descriptor.celOffsetX)
        val celCorrectionY =
            (anchorLayer.descriptor.celOffsetY - movingLayer.descriptor.celOffsetY)

        if (action.type == ActionType.MOVEBYX) {
            val anchorAbsX = anchorLayer.x + (currentOffsets[anchorId]?.x ?: 0f)
            val targetAbsX = (anchorAbsX + offsetVal) - celCorrectionX
            currentOffsets[movingId] = Offset(targetAbsX - movingLayer.x, currentOffset.y)
        } else {
            val anchorAbsY = anchorLayer.y + (currentOffsets[anchorId]?.y ?: 0f)
            val targetAbsY = (anchorAbsY + offsetVal) - celCorrectionY
            currentOffsets[movingId] = Offset(currentOffset.x, targetAbsY - movingLayer.y)
        }
    }

    /** `move()` FKiSS event (relative move from self). No idea if this works properly yet; further testing needed. */
    fun moveRelativeFromSelf(action: KissAction) {
        val movingId = action.target.replace("#", "").toIntOrNull() ?: return
        val parts = action.valueStr.split(",").map { it.trim().replace("#", "") }
        val offsetVal = if (parts.size > 1) parts[1].toFloatOrNull() ?: 0f else 0f

        val doll = currentDoll ?: return
        val movingLayer =
            doll.layers.find { it.descriptor.objectId == movingId } ?: return

        val celCorrectionX = movingLayer.descriptor.celOffsetX
        val celCorrectionY = movingLayer.descriptor.celOffsetY

        val targetBaseX = movingLayer.x + (currentOffsets[movingId]?.x ?: 0f)
        val targetBaseY = movingLayer.y + (currentOffsets[movingId]?.y ?: 0f)
        val targetAbsX = (targetBaseX + offsetVal) - celCorrectionX
        val targetAbsY = (targetBaseY + offsetVal) - celCorrectionY
        currentOffsets[movingId] = Offset(targetAbsX - movingLayer.x, targetAbsY - movingLayer.y)
    }

    /** Events to trigger upon switching sets. This can be archieved either through pressing the set buttons at the top, or though `changeset()` FKiSS events. */
    fun executeSetActions(setNumStr: String) {
        val actions = configParser.setActions[setNumStr] ?: return
        actions.forEach {
            performAction(it)
        }
        refreshTrigger++
    }

    fun executeLabelActions(labelNumStr: String) {
        val actions = configParser.labelActions[labelNumStr] ?: return
        actions.forEach {action ->
            performAction(action)
        }
        refreshTrigger++
    }

    // Track which objects are currently being "forced" visible by a press
    val activePressIds = mutableStateListOf<Int>()

    /**
    `press()` FKiSS event
     */
    fun executePressActions(hitLayer: KissLayer) {
        val objIdStr = hitLayer.descriptor.objectId.toString()
        val fileName = hitLayer.descriptor.fileName.lowercase().substringBefore(".")
        val actions = (configParser.pressActions[objIdStr] ?: emptyList()) +
                (configParser.pressActions[fileName] ?: emptyList())
        actions.distinct().forEach {
            performAction(it)
        }
        refreshTrigger++
    }

    /**
    When no `release()` action happens, we revert the `press()` event instead.
     */
    fun revertPressActions(pressedId: Int) {
        val actions = configParser.pressActions[pressedId.toString()] ?: return

        // Remove the touched object
        activePressIds.remove(pressedId)
        refreshTrigger++

        // Reverse the associated mapping commands
        actions.forEach { action ->
            when (action.type) {
                // Momentary Toggle: If MAP was called on press, call UNMAP on release
                ActionType.MAP -> {
                    val targetId = action.target.replace("#", "").toIntOrNull() ?: return@forEach
                    activePressIds.remove(targetId)
                    setMapping(action.target, false)
                }

                ActionType.UNMAP -> {
                    // If it was hidden, bring it back
                    setMapping(action.target, true)
                }

                else -> {}
            }
        }
        refreshTrigger++
    }

    /**
    `release()` FKiSS events
     */
    fun executeReleaseActions(hitLayer: KissLayer) {
        val objIdStr = hitLayer.descriptor.objectId.toString()
        val fileName = hitLayer.descriptor.fileName.lowercase().substringBefore(".")
        val actions = (configParser.releaseActions[objIdStr] ?: emptyList()) +
                (configParser.releaseActions[fileName] ?: emptyList())

        actions.distinct().forEach { performAction(it) }
        refreshTrigger++
    }

    /** Triggers release actions by name */
    fun triggerReleaseByName(target: String) {
        val cleanTarget = target.replace("#", "").lowercase()

        // Get the actions for this specific target from your parser's bucket
        val actions = configParser.releaseActions[cleanTarget] ?: emptyList()

        // Loop through them and apply them (just like executeReleaseActions does)
        actions.forEach { action ->
            when (action.type) {
                ActionType.UNMAP -> setMappingStrict(action.target, true)
                ActionType.MAP -> setMappingStrict(action.target, false)
                ActionType.ALTMAP -> altMappingStrict(action.target)
                ActionType.TIMER -> fireTimer(action)
                ActionType.RANDOM_TIMER -> fireRandomTimer(action)
                ActionType.SOUND -> {
                    val target = action.target.replace("\"", "").lowercase()
                    soundManager.play(target, false)
                }
                ActionType.MUSIC -> {
                    val doll = currentDoll ?: return@forEach

                    // Your parser puts the filename in 'target' because it's the first parameter
                    val rawTarget = action.target

                    val cleanTarget =
                        rawTarget.replace("\"", "").replace("null", "").trim().lowercase()

                    if (cleanTarget.isNotEmpty()) {
                        soundManager.handleMusicAction(cleanTarget, doll.allFiles)
                    } else {
                        soundManager.stopMusic()
                    }
                }
                // If this action is ANOTHER release, THEN you recurse
                ActionType.RELEASE -> triggerReleaseByName(action.target)
                ActionType.DROP -> {
                    val targetId = target.toIntOrNull()
                    val targetLayer = currentDoll?.layers?.find { layer ->
                        val layerName =
                            layer.descriptor.fileName.lowercase().substringBefore(".")
                        if (targetId != null) {
                            layer.descriptor.objectId == targetId
                        } else {
                            layerName == target
                        }
                    }
                    val isGroupFixed = currentDoll?.layers?.any {
                        targetLayer?.descriptor?.objectId == targetId && targetLayer?.descriptor?.isFixed == true
                    }

                    if (isGroupFixed == false) {
                        targetLayer?.let { triggerReleaseByName(action.target) }
                    }
                }

                ActionType.FIXDROP -> {
                    val targetId = target.toIntOrNull()
                    val targetLayer = currentDoll?.layers?.find { layer ->
                        val layerName =
                            layer.descriptor.fileName.lowercase().substringBefore(".")
                        if (targetId != null) {
                            layer.descriptor.objectId == targetId
                        } else {
                            layerName == target
                        }
                    }
                    val isGroupFixed = currentDoll?.layers?.any {
                        targetLayer?.descriptor?.objectId == targetId && targetLayer?.descriptor?.isFixed == true
                    }

                    if (isGroupFixed == true) {
                        targetLayer?.let { triggerReleaseByName(action.target) }
                    }
                }

                else -> {}
            }
        }
    }

    fun executeUnfixActions(hitLayer: KissLayer) {
        val objIdStr = hitLayer.descriptor.objectId.toString()
        val fileName = hitLayer.descriptor.fileName.lowercase().substringBefore(".")
        val actions = (configParser.unfixActions[objIdStr] ?: emptyList()) +
                (configParser.unfixActions[fileName] ?: emptyList())

        actions.distinct().forEach { performAction(it) }
        refreshTrigger++
    }

    fun executeSetFixActions(hitLayer: KissLayer) {
        val objIdStr = hitLayer.descriptor.objectId.toString()
        val fileName = hitLayer.descriptor.fileName.lowercase().substringBefore(".")
        val actions = (configParser.setFixActions[objIdStr] ?: emptyList()) +
                (configParser.setFixActions[fileName] ?: emptyList())

        actions.distinct().forEach { performAction(it) }
        refreshTrigger++
    }

    /**
    `timer()` FKiSS event
     */
    fun fireTimer(action: KissAction) {
        val id = action.target.replace("#", "").trim().toIntOrNull() ?: return
        val rawMs = action.valueStr.filter { it.isDigit() }
        val ms = rawMs.toLongOrNull() ?: 0L

        synchronized(alarms) {
            val alarm = alarms.getOrPut(id) { KissAlarm(id) }
            alarm.delay = ms.toInt()
            alarm.time = 0L
            alarm.enabled = true // Enable ONLY this specific alarm
        }
        refreshTrigger++
    }

    /**
    `randomtimer()` FKiSS event
     */
    fun fireRandomTimer(action: KissAction) {
        val id = action.target.toIntOrNull() ?: return
        val min = action.valueStr.toLongOrNull() ?: 0L
        val max = action.extraValue.toLongOrNull() ?: min

        // The Magic: Pick a random delay between the two numbers
        val randomDelay = if (min < max) (min..max).random() else min

        synchronized(alarms) {
            val alarm = alarms.getOrPut(id) { KissAlarm(id) }
            alarm.delay = randomDelay.toInt()
            alarm.time = 0L
            alarm.enabled = true
        }
        refreshTrigger++
    }

    /** Apply snap-to events */
    fun applySnap(rule: SnapRule) {
        val doll = currentDoll ?: return
        val movingLayer = doll.layers.find { it.descriptor.objectId == rule.snapObj } ?: return

        val newOffset = if (rule.isRelative) {
            val anchorLayer =
                doll.layers.find { it.descriptor.objectId == rule.targetObj } ?: return
            val anchorOffset = currentOffsets[rule.targetObj] ?: Offset.Zero

            // Where the anchor's HOOK is on screen
            val anchorScreenX = anchorLayer.x + anchorOffset.x
            val anchorScreenY = anchorLayer.y + anchorOffset.y

            // Where the snap object's HOOK should be
            val idealHookX = anchorScreenX + rule.xValue
            val idealHookY = anchorScreenY + rule.yValue

            // THE FIX: Account for the CEL headers
            val celCorrectionX =
                (anchorLayer.descriptor.celOffsetX - movingLayer.descriptor.celOffsetX)
            val celCorrectionY =
                (anchorLayer.descriptor.celOffsetY - movingLayer.descriptor.celOffsetY)

            // Final Compose Offset
            val finalX = (idealHookX - movingLayer.x) - celCorrectionX
            val finalY = (idealHookY - movingLayer.y) - celCorrectionY
            Offset(finalX, finalY)
        } else {
            // ABSOLUTE: (Target - Original) - Internal Offset
            val finalX = (rule.xValue - movingLayer.x) + movingLayer.descriptor.celOffsetX
            val finalY = (rule.yValue - movingLayer.y) + movingLayer.descriptor.celOffsetY
            Offset(finalX.toFloat(), finalY.toFloat())
        }

        currentOffsets[rule.snapObj] = newOffset
    }

    /** `map()`/`unmap()` FKiSS events */
    fun setMapping(target: String, isUnmapped: Boolean) {
        val cleanTarget = target.replace("\"", "").replace("#", "").lowercase().trim()
        val targetId = cleanTarget.toIntOrNull()

        currentDoll?.layers?.forEach { layer ->
            val fileName = layer.descriptor.fileName.lowercase()

            // Match if IDs match OR if filename matches (or starts with the target name)
            val isMatch = if (targetId != null) {
                layer.descriptor.objectId == targetId
            } else {
                fileName.startsWith(cleanTarget)
            }

            if (isMatch) {
                layer.descriptor.isUnmapped = isUnmapped
            }
        }
        refreshTrigger++ // Force Compose to redraw
    }

    /** `map()`/`unmap()` FKiSS events ("strict" version) */
    fun setMappingStrict(target: String, isUnmapped: Boolean) {
        val cleanTarget = target.replace("#", "").lowercase().substringBefore(".")
        val numericId = if (!target.contains(".")) cleanTarget.toIntOrNull() else null

        currentDoll?.layers?.forEach { layer ->
            val layerFileName = layer.descriptor.fileName.lowercase().substringBefore(".")

            val isMatch = if (numericId != null) {
                layer.descriptor.objectId == numericId
            } else {
                layerFileName == cleanTarget
            }

            if (isMatch) {
                layer.descriptor.isUnmapped = isUnmapped
            }
        }
        refreshTrigger++
    }

    /** `altmap()` FKiSS event */
    fun altMapping(target: String) {
        val cleanTarget = target.replace("#", "").replace("\"", "").trim()
            .lowercase().removeSuffix(".cel").removeSuffix(".lbm")
        val numericId = cleanTarget.toIntOrNull()

        currentDoll?.layers?.forEach { layer ->
            val desc = layer.descriptor
            val layerName = desc.fileName.lowercase().substringBefore(".")

            val isMatch = if (numericId != null) {
                desc.objectId == numericId
            } else {
                layerName == cleanTarget
            }

            if (isMatch) {
                // THE TOGGLE: Flip the current unmapped state
                desc.isUnmapped = !desc.isUnmapped

                // KiSS Rule: If we just made it visible,
                // ensure it's at its base position for the current set.
                if (!desc.isUnmapped) {
                    configParser.objectCoords[desc.objectId]?.let { (sx, sy) ->
                        layer.x = sx + desc.celOffsetX
                        layer.y = sy + desc.celOffsetY
                    }
                }
            }
        }
        refreshTrigger++
    }

    /** `altmap()` FKiSS event ("strict" version) */
    fun altMappingStrict(target: String) {
        val cleanTarget = target.replace("#", "").lowercase().substringBefore(".")
        val numericId = if (!target.contains(".")) cleanTarget.toIntOrNull() else null

        currentDoll?.layers?.forEach { layer ->
            val desc = layer.descriptor
            val layerFileName = layer.descriptor.fileName.lowercase().substringBefore(".")

            val isMatch = if (numericId != null) {
                layer.descriptor.objectId == numericId
            } else {
                layerFileName == cleanTarget
            }

            if (isMatch) {
                desc.isUnmapped = !desc.isUnmapped
            }
        }
        refreshTrigger++
    }

    /** Checks if a cel is mapped or not */
    fun isLayerVisible(layer: KissLayer): Boolean {
        val desc = layer.descriptor
        // If it's unmapped, it's never visible/hittable
        if (desc.isUnmapped) return false

        // Check sets
        return desc.allowedSets.isEmpty() || desc.allowedSets.contains(activeSet)
    }

    /** Runs whenever a doll is loaded */
    fun onDollLoaded(loadedDoll: KissDoll) {
        this.currentDoll = loadedDoll

        // Resolve filenames into IDs
        configParser.finalizeTriggers(loadedDoll.layers)
    }

    var notifyMessage by mutableStateOf<String?>(null)
        private set

    fun clearNotify() {
        notifyMessage = null
    }

    val snackbarHostState = SnackbarHostState()

    fun showNotify(message: String) {
        viewModelScope.launch {
            val result = snackbarHostState.showSnackbar(
                message = message,
                actionLabel = "OK",
                duration = SnackbarDuration.Indefinite,
                withDismissAction = false
            )

            when (result) {
                SnackbarResult.ActionPerformed -> {
                    // User clicked "OK" - you could trigger a FKiSS event here!
                }

                SnackbarResult.Dismissed -> {
                    // User ignored it or it timed out
                }
            }
        }
    }
}