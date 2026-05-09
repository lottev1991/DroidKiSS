@file:Suppress("UnusedImport")

package moe.lottev.droidkiss

import android.app.Application
import androidx.compose.runtime.*
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.launch
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import androidx.compose.ui.graphics.Color
import android.net.Uri
import android.util.Log
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
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlin.collections.component1
import kotlin.collections.forEach
import kotlin.text.equals

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
                    withContext(Dispatchers.Default) {
                        executeEvent("alarm($alarmId)")
                    }
                }
                delay(16)
            }
        }
    }

    /** Handles the cleanup and @end events before a doll is swapped or closed. */
    suspend fun shutdownCurrentDoll() {
        // Clear current offsets
        currentOffsets.clear()

        // Add a delay
        delay(500)

        // Stop timer
        stopTimer()

        // Stop all sounds
        soundManager.stopAll()

        // Clear master file cache for cleanup
        masterFileCache.clear()
    }

    var availableCnfs by mutableStateOf<List<String>>(emptyList())
    val masterFileCache = mutableMapOf<String, ByteArray>()

    /** Closes current archive, returning to the main screen */
    fun closeArchive() {
        if (currentDoll != null && uiState == KissUiState.Loaded(currentDoll!!)) {
            // Trigger any end() events if doll is loaded
            executeEvent("end")
        }
        viewModelScope.launch {
            uiState = KissUiState.Loading
            shutdownCurrentDoll()
            uiState = KissUiState.Empty
        }
    }
    @Suppress("unused")
    /** Load archive into the viewmodel */
    fun loadArchive(context: Context, uri: Uri, specificCnf: String? = null) {
        if (currentDoll != null && uiState == KissUiState.Loaded(currentDoll!!)) {
            // Trigger any end() events if doll is loaded
            executeEvent("end")
        }
        uiState = KissUiState.Loading
        viewModelScope.launch {
            shutdownCurrentDoll()
            isExpansionSet = false

            availableCnfs = emptyList() // Reset CNF list for the new archive

            try {
                // Extract all files from the archive into our Master Cache
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
                executeEvent("initialize")
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

    /** Prepare LZH for multiple CNFs */
    fun prepareArchive(context: Context, uri: Uri) {
        viewModelScope.launch {
            // Use the engine to see how many CNFs are inside
            val cnfs = engine.discoverCnfs(uri)

            if (cnfs.size > 1) {
                selectedUri = uri
                pendingCnfOptions = cnfs
                showCnfSelector = true // This triggers the dialog in the UI
            } else {
                // If only one, just load it immediately
                loadArchive(context, uri, cnfs.firstOrNull())
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
        checkCollisionsOnSetLoad() // stillin, stillout
        executeSetActions(newSet.toString())
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

    /** Drop even handler */
    fun handleDrop(draggedId: Int) {
        val doll = currentDoll ?: return

        // Find rules where the object being dropped is the one that needs to snap
        val rules = configParser.snapRules.filter { it.snapObj == draggedId }

        rules.forEach { rule ->
            val snapLayer =
                doll.layers.find { it.descriptor.objectId == rule.snapObj && !it.descriptor.isUnmapped } ?: return@forEach

            val layers = doll.layers

            if (rule.isRelative) {
                val anchorLayer = doll.layers.find { it.descriptor.objectId == rule.triggerObj && !it.descriptor.isUnmapped }
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

                val offsetX = anchorLayer.descriptor.celOffsetX
                val offsetY = anchorLayer.descriptor.celOffsetY

                val distanceX = sqrt((currentVisualX - offsetX) - anchorVisualX).toDouble().pow(2.0)
                val distanceY = sqrt((currentVisualY - offsetY) - anchorVisualY).toDouble().pow(2.0)

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

                if (targetLayer.descriptor.isUnmapped) return@forEach

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

                if (anchorLayer.descriptor.isUnmapped) return@forEach

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
                if (rule.disabled) return@forEach

                if (rule.triggerObj == eventId) applySnap(rule)
            }
        }
        refreshTrigger++
    }

    private val _eventFlow = MutableSharedFlow<ShellEvent>()
    val eventFlow = _eventFlow.asSharedFlow()
    /**
     * Consolidated action executor.
     */
    private fun performAction(action: KissAction): Boolean {
        try {
            when (action.type) {
                ActionType.UNMAP -> {
                    setMappingStrict(action.target, true)
                    return true
                }
                ActionType.MAP -> {
                    setMappingStrict(action.target, false)
                    return true
                }
                ActionType.ALTMAP -> {
                    altMappingStrict(action.target)
                    return true
                }

                ActionType.IFMAPPED -> {
                    val doll = currentDoll ?: return true
                    val target = action.target.replace("#", "").lowercase()
                    val targetId = target.toIntOrNull()

                    doll.layers.forEach { layer ->
                        val desc = layer.descriptor
                        if (!desc.isUnmapped && (target == desc.fileName || targetId == desc.objectId)) {
                            fireSpecialTimer(action)
                        }
                    }
                    return true
                }
                ActionType.IFNOTMAPPED -> {
                    val doll = currentDoll ?: return true
                    val target = action.target.replace("#", "").lowercase()
                    val targetId = target.toIntOrNull()

                    doll.layers.forEach { layer ->
                        val desc = layer.descriptor
                        if (desc.isUnmapped && (target == desc.fileName || targetId == desc.objectId)) {
                            fireSpecialTimer(action)
                        }
                    }
                    return true
                }
                ActionType.IFFIXED -> {
                    val target = action.target.replace("#", "").lowercase()
                    val targetId = target.toIntOrNull()
                    val targetLayer = currentDoll?.layers?.find { layer ->
                        val layerName = layer.descriptor.fileName.lowercase().substringBefore(".")
                        if (targetId != null) layer.descriptor.objectId == targetId else layerName == target
                    }

                    if (targetLayer != null) {
                        val desc = targetLayer.descriptor
                        if (desc.isFixed) {
                            fireSpecialTimer(action)
                        }
                    }
                    return true
                }

                ActionType.IFNOTFIXED -> {
                    val target = action.target.replace("#", "").lowercase()
                    val targetId = target.toIntOrNull()
                    val targetLayer = currentDoll?.layers?.find { layer ->
                        val layerName = layer.descriptor.fileName.lowercase().substringBefore(".")
                        if (targetId != null) layer.descriptor.objectId == targetId else layerName == target
                    }

                    if (targetLayer != null) {
                        val desc = targetLayer.descriptor
                        if (!desc.isFixed) {
                            fireSpecialTimer(action)
                        }
                    }
                    return true
                }
                ActionType.IFMOVED -> {
                    val target = action.target.replace("#", "").lowercase()
                    val targetId = target.toIntOrNull()
                    val targetLayer = currentDoll?.layers?.find { layer ->
                        val layerName = layer.descriptor.fileName.lowercase().substringBefore(".")
                        if (targetId != null) layer.descriptor.objectId == targetId else layerName == target
                    }

                    if (targetLayer != null) {
                        val currentOffset = currentOffsets[targetId] ?: Offset.Zero
                        if (currentOffset != Offset.Zero) {
                            fireSpecialTimer(action)
                        }
                    }
                    return true
                }
                ActionType.IFNOTMOVED -> {
                    val target = action.target.replace("#", "").lowercase()
                    val targetId = target.toIntOrNull()
                    val targetLayer = currentDoll?.layers?.find { layer ->
                        val layerName = layer.descriptor.fileName.lowercase().substringBefore(".")
                        if (targetId != null) layer.descriptor.objectId == targetId else layerName == target
                    }

                    if (targetLayer != null) {
                        val currentOffset = currentOffsets[targetId] ?: Offset.Zero
                        if (currentOffset == Offset.Zero) {
                            fireSpecialTimer(action)
                        }
                    }
                    return true
                }
                ActionType.TIMER -> {
                    fireTimer(action)
                    return true
                }
                ActionType.RANDOM_TIMER -> {
                    fireRandomTimer(action)
                    return true
                }
                ActionType.PRESS -> {
                    val target = action.target.replace("#", "").lowercase()
                    val targetId = target.toIntOrNull()
                    val targetLayer = currentDoll?.layers?.find { layer ->
                        val layerName = layer.descriptor.fileName.lowercase().substringBefore(".")
                        if (targetId != null) layer.descriptor.objectId == targetId else layerName == target
                    }
                    targetLayer?.let { executePressActions(it) }
                    return true
                }

                ActionType.RELEASE -> {
                    val target = action.target.replace("#", "").lowercase()
                    val targetId = target.toIntOrNull()
                    val targetLayer = currentDoll?.layers?.find { layer ->
                        val layerName = layer.descriptor.fileName.lowercase().substringBefore(".")
                        if (targetId != null) layer.descriptor.objectId == targetId else layerName == target
                    }
                    targetLayer?.let { executeReleaseActions(it) }
                    return true
                }
                ActionType.CATCH -> {
                    val target = action.target.replace("#", "").lowercase()
                    val targetId = target.toIntOrNull()
                    val targetLayer = currentDoll?.layers?.find { layer ->
                        val layerName = layer.descriptor.fileName.lowercase().substringBefore(".")
                        if (targetId != null) layer.descriptor.objectId == targetId else layerName == target
                    }
                    targetLayer?.let { executeCatchActions(it) }
                    return true
                }

                ActionType.DROP -> {
                    val target = action.target.replace("#", "").lowercase()
                    val targetId = target.toIntOrNull()
                    val targetLayer = currentDoll?.layers?.find { layer ->
                        val layerName = layer.descriptor.fileName.lowercase().substringBefore(".")
                        if (targetId != null) layer.descriptor.objectId == targetId else layerName == target
                    }
                    targetLayer?.let { executeDropActions(it) }
                    return true
                }
                ActionType.FIXCATCH -> {
                    val target = action.target.replace("#", "").lowercase()
                    val targetId = target.toIntOrNull()
                    val targetLayer = currentDoll?.layers?.find { layer ->
                        val layerName = layer.descriptor.fileName.lowercase().substringBefore(".")
                        if (targetId != null) layer.descriptor.objectId == targetId else layerName == target
                    }
                    targetLayer?.let { executeFixCatchActions(it) }
                    return true
                }

                ActionType.FIXDROP -> {
                    val target = action.target.replace("#", "").lowercase()
                    val targetId = target.toIntOrNull()
                    val targetLayer = currentDoll?.layers?.find { layer ->
                        val layerName = layer.descriptor.fileName.lowercase().substringBefore(".")
                        if (targetId != null) layer.descriptor.objectId == targetId else layerName == target
                    }
                    targetLayer?.let { executeFixDropActions(it) }
                    return true
                }
                ActionType.ALARM -> {
                    val id = action.target.trim().toIntOrNull() ?: return true
                    synchronized(immediateQueue) {
                        immediateQueue.add(id)
                    }
                    return true
                }
                ActionType.CHANGESET -> {
                    changeSet(action.target.toIntOrNull() ?: 0)
                    return true
                }

                ActionType.SOUND -> {
                    val target = action.target.replace("\"", "").lowercase()
                    soundManager.play(target, false)
                    return true
                }

                ActionType.MUSIC -> {
                    val doll = currentDoll ?: return true
                    val cleanTarget =
                        action.target.replace("\"", "").trim().lowercase()
                    if (cleanTarget.isNotEmpty()) {
                        soundManager.handleMusicAction(cleanTarget, doll.allFiles)
                    } else {
                        soundManager.stopMusic()
                    }
                    return true
                }

                ActionType.NOTIFY -> {
                    val target = action.target.trim()
                        .removePrefix("(").removeSuffix(")")
                        .trim()
                        .removeSurrounding("\"")
                        .removeSurrounding("'")
                    showNotify(target)
                    return true
                }

                ActionType.MOVETO -> {
                    val targetId = action.target.replace("#", "").toIntOrNull() ?: return true
                    val xValue = action.valueStr.trim().toIntOrNull() ?: 0
                    val yValue = action.extraValue.trim().toIntOrNull() ?: 0
                    val layer = currentDoll?.layers?.find { it.descriptor.objectId == targetId }
                    if (layer != null) {
                        val finalX = xValue - layer.x
                        val finalY = yValue - layer.y
                        currentOffsets[targetId] = Offset(finalX.toFloat(), finalY.toFloat())
                    }
                    return true
                }

                ActionType.MOVE -> {
                    moveRelativeFromSelf(action)
                    return true
                }
                ActionType.MOVEBYX, ActionType.MOVEBYY -> {
                    moveRelative(action)
                    return true
                }
                ActionType.MOVERANDX, ActionType.MOVERANDY -> {
                    moveRelativeRandom(action)
                    return true
                }
                ActionType.MOVETORAND -> {
                    moveToRandom(action)
                    return true
                }

                ActionType.SETFIX -> {
                    val target = action.target.replace("#", "").lowercase()
                    val targetId = target.toIntOrNull()
                    val value = action.valueStr.toIntOrNull() ?: 0
                    val shouldFix = value > 0

                    currentDoll?.layers?.forEach { layer ->
                        val layerName = layer.descriptor.fileName.lowercase().substringBefore(".")

                        // Match by ID OR by Filename (standard KiSS behavior)
                        val isMatch = if (targetId != null && !target.contains(".")) {
                            layer.descriptor.objectId == targetId
                        } else {
                            layerName == target
                        }

                        if (isMatch) {
                            layer.descriptor.isFixed = shouldFix
                            if (!shouldFix) {
                                executeUnfixActions(layer)
                            }
                        }
                    }
                    refreshTrigger++
                    return true
                }

                ActionType.GOSUB -> {
                    val target = action.target
                    executeLabelActions(target)
                    return true
                }

                ActionType.GOTO -> {
                    val target = action.target
                    executeLabelActions(target)
                    return false
                }

                ActionType.GOSUBRANDOM -> {
                    // target usually looks like "30, 100, 200"
                    val p1 = action.target.toIntOrNull() ?: 50
                    val p2 = action.valueStr.trim()
                    val p3 = action.extraValue.trim()

                    val roll = (1..100).random()
                    val finalTarget = if (roll <= p1) p2 else p3

                    if (finalTarget.isNotEmpty()) {
                        executeLabelActions(finalTarget)
                    }
                    return true // Gosub returns to caller
                }

                ActionType.GOTORANDOM -> {
                    val p1 = action.target.toIntOrNull() ?: 50
                    val p2 = action.valueStr.trim()
                    val p3 = action.extraValue.trim()

                    val roll = (1..100).random()
                    val finalTarget = if (roll <= p1) p2 else p3

                    if (finalTarget.isNotEmpty()) {
                        executeLabelActions(finalTarget)
                    }
                    return false // Goto terminates current handler
                }
                ActionType.GHOST -> {
                    val target = action.target.replace("#", "").lowercase()
                    val targetId = target.toIntOrNull()
                    val value = action.valueStr.toIntOrNull() ?: 0
                    val shouldGhost = value > 0

                    // Loop through ALL layers to catch every cel in the group
                    currentDoll?.layers?.forEach { layer ->
                        val layerName = layer.descriptor.fileName.lowercase().substringBefore(".")

                        val isMatch = if (targetId != null && !target.contains(".")) {
                            layer.descriptor.objectId == targetId
                        } else {
                            layerName == target
                        }

                        if (isMatch) {
                            layer.descriptor.isGhosted = shouldGhost
                        }
                    }
                    return true
                }
                ActionType.NOP -> return true
                ActionType.EXITEVENT -> return false
                ActionType.SHELL -> {
                    // Only emit shell events when it's a URL or an email address
                    val target = action.target.lowercase()
                    if (target.startsWith("http://") || target.startsWith("https://") || target.startsWith(
                            "www.") || target.startsWith("mailto:")) {
                        // Make it so that URLs starting with "www." still get parsed
                        val finalUrl = if (target.startsWith("www.")) {
                            "http://$target"
                        } else {
                            target
                        }
                        executeShellEvent(finalUrl)
                    }
                    return true
                }
                ActionType.CHANGECOL -> {
                    changeCol(action.target)
                    refreshTrigger++
                    return true
                }
                ActionType.SETKCF -> {
                    val target = action.target.lowercase().replace("#", "").replace("\"", "")
                    val targetId = target.toIntOrNull()
                    val value = action.valueStr.toIntOrNull() ?: 0

                    currentDoll?.layers?.forEach { layer ->
                        val layerName = layer.descriptor.fileName.lowercase().substringBefore(".")

                        val isMatch = if (targetId != null && !target.contains(".")) {
                            layer.descriptor.objectId == targetId
                        } else {
                            layerName == target
                        }

                        if (isMatch) {
                            layer.descriptor.paletteIndex = value
                        }
                    }
                    return true
                }
                ActionType.WINDOWSIZE -> {
                    val newWidth = action.target.toIntOrNull() ?: 0
                    val newHeight = action.valueStr.toIntOrNull() ?: 0
                    val doll = currentDoll

                    if (doll != null) {
                        doll.envWidth = newWidth
                        doll.envHeight = newHeight
                    }
                    return true
                }
                ActionType.VIEWPORT -> {
                    val doll = currentDoll
                    val viewportOffsetX = action.target.toFloatOrNull() ?: 0f
                    val viewportOffsetY = action.valueStr.toFloatOrNull() ?: 0f
                    if (doll != null) {
                        val allLayers = doll.layers
                        allLayers.forEach {layer ->
                            val objId = layer.descriptor.objectId
                            currentOffsets[objId] = Offset(viewportOffsetX, viewportOffsetY)
                        }
                    }
                    return true
                }
                ActionType.QUIT -> {
                    closeArchive()
                    return false
                }
                else -> return true
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return true
    }

    /** Changes current palette group. Used for `changecol` FKiSS events, as well as manual palette group changes from the menu. */
    fun changeCol(groupStr: String) {
        val doll = currentDoll ?: return
        val groupNum = groupStr.toIntOrNull() ?: 0

        doll.activeGlobalBank = groupNum

        doll.layers.forEach { layer ->
            val newPalette =
                layer.paletteGroups.getOrElse(groupNum) { layer.paletteGroups.firstOrNull() }
            if (newPalette != null) {
                rebakeLayer(
                    layer, newPalette,
                )
            }
        }
        executeColActions(groupStr)
        refreshTrigger++
    }

    /** Redraw layer after changing palette group */
    fun rebakeLayer(layer: KissLayer, palette: IntArray) {
        val width = layer.width
        val height = layer.height
        val pixels = IntArray(width * height)
        val data = layer.rawIndices

        // Check if we are dealing with a 32-bit (true-color), 8-bit (256-color) or 4-bit (16-color) layer
        // 8-bit data size is usually Width * Height
        // 4-bit data size is usually (Width * Height + 1) / 2

        when (layer.bitDepth) {
            32 -> {
                var cursor = 0
                for (i in pixels.indices) {
                    if (cursor + 3 >= data.size) break

                    // We MUST reconstruct the Int from the 4 bytes in the ByteArray
                    val b = data[cursor++].toInt() and 0xFF
                    val g = data[cursor++].toInt() and 0xFF
                    val r = data[cursor++].toInt() and 0xFF
                    val a = data[cursor++].toInt() and 0xFF

                    pixels[i] = (a shl 24) or (r shl 16) or (g shl 8) or b
                }
            }

            8 -> {
                // 8-Bit Path: One byte = One pixel
                for (i in 0 until (width * height)) {
                    if (i >= data.size) break
                    val colorIndex = data[i].toInt() and 0xFF

                    // Rule: Color 0 is always transparent
                    pixels[i] = if (colorIndex == 0) 0 else palette.getOrElse(colorIndex) { 0 }
                }
            }

            4 -> {
                // 4-Bit Path: One byte = Two pixels (Packed Nibbles)
                var cursor = 0
                for (y in 0 until height) {
                    for (x in 0 until width step 2) {
                        if (cursor >= data.size) break
                        val byte = data[cursor++].toInt()

                        // Pixel 1 (High nibble)
                        val idx1 = (byte shr 4) and 0x0F
                        pixels[y * width + x] = if (idx1 == 0) 0 else palette.getOrElse(idx1) { 0 }

                        // Pixel 2 (Low Nibble) - only if within row width
                        if (x + 1 < width) {
                            val idx2 = byte and 0x0F
                            pixels[y * width + x + 1] =
                                if (idx2 == 0) 0 else palette.getOrElse(idx2) { 0 }
                        }
                    }
                }
            }
        }
        layer.bitmap = Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
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
                        executeStillInActions(srcLayer, dstLayer)
                    }
                }

                "out" -> {
                    // Only fire if it WAS overlapping but now it's NOT
                    if (!isRectOverlapping) {
                        activeCollisionPairs.remove(stateKey)
                        executeEvent(eventKey)
                        executeStillOutActions(srcLayer, dstLayer)
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
            if (!isRectOverlapping && (type == "in" || type == "out")) {
                activeCollisionPairs.remove(stateKey)
                executeStillOutActions(srcLayer, dstLayer)
            }

            if (!isOverlapping && (type == "collide" || type == "apart")) {
                activeCollisionPairs.remove(stateKey)
                executeStillOutActions(srcLayer, dstLayer)
            }
        }
    }

    fun evaluateCollisionState(
        eventKey: String,
        srcLayer: KissLayer,
        dstLayer: KissLayer
    ): Boolean {
        // Use your getBounds method here
        val r1 = srcLayer.getBounds(currentOffsets[srcLayer.descriptor.objectId] ?: Offset.Zero)
        val r2 = dstLayer.getBounds(currentOffsets[dstLayer.descriptor.objectId] ?: Offset.Zero)

        val isRectOverlapping = Rect.intersects(r1, r2)

        // Pixel-perfect check
        return if (eventKey.contains("collide") || eventKey.contains("apart")) {
            isPixelCollisionWithOffset(
                srcLayer,
                currentOffsets[srcLayer.descriptor.objectId] ?: Offset.Zero,
                dstLayer
            )
        } else {
            isRectOverlapping
        }
    }

    fun checkCollisionsOnSetLoad() {
        val doll = currentDoll ?: return

        configParser.eventActions.keys.forEach { eventKey ->
            // 1. Parse the key to find the two involved layers
            val parts = eventKey.split("_")
            if (parts.size < 3) return@forEach

            val triggerSrc = parts[1]
            val triggerDst = parts[2]

            val srcLayer = doll.layers.find {
                it.descriptor.objectId.toString() == triggerSrc || it.descriptor.fileName.equals(
                    triggerSrc,
                    true
                )
            } ?: return@forEach
            val dstLayer = doll.layers.find {
                it.descriptor.objectId.toString() == triggerDst || it.descriptor.fileName.equals(
                    triggerDst,
                    true
                )
            } ?: return@forEach

            // 2. Evaluate
            val isColliding = evaluateCollisionState(eventKey, srcLayer, dstLayer)
            val stateKey = "active_$eventKey"

            // 3. Set the latch without firing the event (or fire if that's what you want)
            if (isColliding) {
                activeCollisionPairs.add(stateKey)
                // If you want "StillIn" to fire on load when they are already overlapping:
                executeStillInActions(srcLayer, dstLayer)
            } else {
                activeCollisionPairs.remove(stateKey)
                executeStillOutActions(srcLayer, dstLayer)
            }
        }
    }

    /**
    `movebyx()`/`movebyy()` FKiSS events (relative move from anchor)
     */
    fun moveRelative(action: KissAction) {
        val movingId = action.target.replace("#", "").toIntOrNull() ?: return
        val value = action.valueStr.replace("#", "")
        val extraValue = action.extraValue.replace("#", "")

        val anchorId = value.toIntOrNull() ?: 0
        val offsetVal = extraValue.toIntOrNull() ?: 0

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

    /** `moverandx()`/`moverandy()` FKiSS events (random relative move from self). */
    fun moveRelativeRandom(action: KissAction) {
        val movingId = action.target.replace("#", "").toIntOrNull() ?: return

        val min = action.valueStr.toLongOrNull() ?: 0L
        val max = action.extraValue.toLongOrNull() ?: min

        val currentOffset = currentOffsets[movingId] ?: Offset.Zero

        val randomOffset = if (min < max) (min..max).random() else min

        if (action.type == ActionType.MOVERANDX) {
            currentOffsets[movingId] = Offset(randomOffset.toFloat(), currentOffset.y)
        } else {
            currentOffsets[movingId] = Offset(currentOffset.x, randomOffset.toFloat())
        }
    }

    /** `move()` FKiSS event (relative move from self). No idea if this works properly yet; further testing needed. */
    fun moveRelativeFromSelf(action: KissAction) {
        val movingId = action.target.replace("#", "").toIntOrNull() ?: return

        val moveX = action.valueStr.toIntOrNull() ?: 0
        val moveY = action.extraValue.toIntOrNull() ?: 0

        val targetBaseX = (currentOffsets[movingId]?.x ?: 0f)
        val targetBaseY = (currentOffsets[movingId]?.y ?: 0f)
        currentOffsets[movingId] = Offset(targetBaseX + moveX, targetBaseY + moveY)
    }

    /** `movetorand()` (move randomly across the entire playfield). */
    fun moveToRandom(action: KissAction) {
        val doll = currentDoll ?: return
        val movingId = action.target.replace("#", "").toIntOrNull() ?: return

        val objectLayers =
            doll.layers.asReversed()
                .filter { it.descriptor.objectId == movingId && !it.descriptor.isUnmapped }

        val minBaseX = objectLayers.minOf { it.x }
        val minBaseY = objectLayers.minOf { it.y }
        val maxBaseX = objectLayers.maxOf { it.x + it.bitmap.width }
        val maxBaseY = objectLayers.maxOf { it.y + it.bitmap.height }

        val minAllowedOffsetH = -minBaseX
        val minAllowedOffsetV = -minBaseY
        val maxAllowedOffsetH = (doll.envWidth - maxBaseX)
        val maxAllowedOffsetV = (doll.envHeight - maxBaseY)

        val randomX = (minOf(minAllowedOffsetH, maxAllowedOffsetH)..maxOf(minAllowedOffsetH, maxAllowedOffsetH
        )).random()
        val randomY = (minOf(minAllowedOffsetV, maxAllowedOffsetV)..maxOf(minAllowedOffsetV, maxAllowedOffsetV
        )).random()

        currentOffsets[movingId] = Offset(randomX.toFloat(), randomY.toFloat())
    }

    /** Events to trigger upon switching sets. This can be archieved either through pressing the set buttons at the top, or though `changeset()` FKiSS events. */
    fun executeSetActions(setNumStr: String) {
        val actions = configParser.setActions[setNumStr] ?: return
        actions.forEach {
            performAction(it)
        }
        refreshTrigger++
    }

    /** Executes any action under a label */
    fun executeLabelActions(labelNumStr: String) {
        val actions = configParser.labelActions[labelNumStr] ?: return

        for (action in actions) {
            val continueProcessing = performAction(action)

            if (!continueProcessing) {
                break // Exit this loop immediately
            }
        }
        refreshTrigger++
    }

    /** `shell()` events. Only work on web URLS and email addresses. */
    fun executeShellEvent(target: String) {
        viewModelScope.launch {
            _eventFlow.emit(ShellEvent.OpenLink(target))
        }
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
        actions.distinct().forEach { performAction(it) }
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

    val activeCatchIds = mutableStateListOf<Int>()
    /**
    `catch()` FKiSS event. Similar to `press()` events, except they only fire when an object is unfixed.
     */
    fun executeCatchActions(hitLayer: KissLayer) {
        val objIdStr = hitLayer.descriptor.objectId.toString()
        val fileName = hitLayer.descriptor.fileName.lowercase().substringBefore(".")
        val actions = (configParser.catchActions[objIdStr] ?: emptyList()) +
                (configParser.catchActions[fileName] ?: emptyList())

        val objId = hitLayer.descriptor.objectId

        val doll = currentDoll ?: return
        val objectLayers =
            doll.layers.filter { it.descriptor.objectId == objId }
        val objectFixed = objectLayers.any { it.descriptor.isFixed }

        if (!objectFixed) {
            actions.distinct().forEach {
                performAction(it)
            }
        }
        refreshTrigger++
    }

    /**
    When no `drop()` action happens, we revert the `catch()` event instead.
     */
    fun revertCatchActions(catchId: Int) {
        val actions = configParser.catchActions[catchId.toString()] ?: return

        // Remove the touched object
        activeCatchIds.remove(catchId)
        refreshTrigger++

        // Reverse the associated mapping commands
        actions.forEach { action ->
            when (action.type) {
                // Momentary Toggle: If MAP was called on press, call UNMAP on release
                ActionType.MAP -> {
                    val targetId = action.target.replace("#", "").toIntOrNull() ?: return@forEach
                    activeCatchIds.remove(targetId)
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

    val activeFixCatchIds = mutableStateListOf<Int>()
    /**
    `fixcatch()` FKiSS event. Similar to `press()` events, except they only fire when an object is fixed.
     */
    fun executeFixCatchActions(hitLayer: KissLayer) {
        val objIdStr = hitLayer.descriptor.objectId.toString()
        val fileName = hitLayer.descriptor.fileName.lowercase().substringBefore(".")
        val actions = (configParser.fixCatchActions[objIdStr] ?: emptyList()) +
                (configParser.fixCatchActions[fileName] ?: emptyList())

        val objId = hitLayer.descriptor.objectId

        val doll = currentDoll ?: return
        val objectLayers =
            doll.layers.filter { it.descriptor.objectId == objId }
        val objectFixed = objectLayers.any { it.descriptor.isFixed }

        if (objectFixed) {
            actions.distinct().forEach {
                performAction(it)
            }
        }

        refreshTrigger++
    }

    /**
    When no `fixdrop()` action happens, we revert the `fixcatch()` event instead.
     */
    fun revertFixCatchActions(fixCatchId: Int) {
        val actions = configParser.fixCatchActions[fixCatchId.toString()] ?: return

        // Remove the touched object
        activeFixCatchIds.remove(fixCatchId)
        refreshTrigger++

        // Reverse the associated mapping commands
        actions.forEach { action ->
            when (action.type) {
                // Momentary Toggle: If MAP was called on press, call UNMAP on release
                ActionType.MAP -> {
                    val targetId = action.target.replace("#", "").toIntOrNull() ?: return@forEach
                    activeFixCatchIds.remove(targetId)
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

    /**
    `drop()` FKiSS events. Similar to `release()` events, except they only fire when an object is unfixed.
     */
    fun executeDropActions(hitLayer: KissLayer) {
        val objIdStr = hitLayer.descriptor.objectId.toString()
        val fileName = hitLayer.descriptor.fileName.lowercase().substringBefore(".")
        val actions = (configParser.dropActions[objIdStr] ?: emptyList()) +
                (configParser.dropActions[fileName] ?: emptyList())

        val doll = currentDoll ?: return
        val objId = hitLayer.descriptor.objectId
        val objectLayers =
            doll.layers.filter { it.descriptor.objectId == objId }
        val objectFixed = objectLayers.any { it.descriptor.isFixed }

        if (!objectFixed) {
            actions.distinct().forEach { performAction(it) }
        }
        refreshTrigger++
    }

    /**
    `fixdrop()` FKiSS events. Similar to `release()` events, except they only fire when an object is fixed.
     */
    fun executeFixDropActions(hitLayer: KissLayer) {
        val objIdStr = hitLayer.descriptor.objectId.toString()
        val fileName = hitLayer.descriptor.fileName.lowercase().substringBefore(".")
        val actions = (configParser.fixDropActions[objIdStr] ?: emptyList()) +
                (configParser.fixDropActions[fileName] ?: emptyList())

        val doll = currentDoll ?: return
        val objId = hitLayer.descriptor.objectId
        val objectLayers =
            doll.layers.filter { it.descriptor.objectId == objId }
        val objectFixed = objectLayers.any { it.descriptor.isFixed }

        if (objectFixed) {
            actions.distinct().forEach { performAction(it) }
        }

        refreshTrigger++
    }

    /** Execute actions that trigger upon `unfix` */
    fun executeUnfixActions(hitLayer: KissLayer) {
        val objIdStr = hitLayer.descriptor.objectId.toString()
        val fileName = hitLayer.descriptor.fileName.lowercase().substringBefore(".")
        val actions = (configParser.unfixActions[objIdStr] ?: emptyList()) + (configParser.unfixActions[fileName] ?: emptyList())

        actions.distinct().forEach { performAction(it) }
        refreshTrigger++
    }

    /** Execute actions that trigger upon `stillin` */
    fun executeStillInActions(layer1: KissLayer, layer2: KissLayer) {
        val layer1Obj = layer1.descriptor.objectId.toString()
        val layer2Obj = layer2.descriptor.objectId.toString()
        val actions = (configParser.stillInActions["$layer1Obj,$layer2Obj".replace(", ",",").replace("#", "")] ?: emptyList()) + (configParser.stillInActions["$layer2Obj,$layer1Obj".replace(", ", ",").replace("#", "")] ?: emptyList())

        actions.distinct().forEach { performAction(it) }
        refreshTrigger++
    }

    /** Execute actions that trigger upon `stillout` */
    fun executeStillOutActions(layer1: KissLayer, layer2: KissLayer) {
        val layer1Obj = layer1.descriptor.objectId.toString()
        val layer2Obj = layer2.descriptor.objectId.toString()
        val actions = (configParser.stillOutActions["$layer1Obj,$layer2Obj".replace(", ", ",").replace("#", "")] ?: emptyList()) + (configParser.stillOutActions["$layer2Obj,$layer1Obj".replace(
            ", ", ",").replace("#", "")] ?: emptyList())

        actions.distinct().forEach { performAction(it) }
        refreshTrigger++
    }

    /** Execute actions that trigger upon changing the active palette */
    fun executeColActions(colStr: String) {
        val actions = (configParser.colActions[colStr]
            ?: emptyList()) + (configParser.colActions[colStr] ?: emptyList())

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

    /** Special timer for `iffixed`/`ifnotfixed`/`ifmapped`/`ifnotmapped`/`ifmoved`/`ifnotmoved` */
    fun fireSpecialTimer(action: KissAction) {
        val target = action.target.replace("#","").lowercase()
        val id = action.valueStr.toIntOrNull() ?: return
        val rawMs = action.extraValue.filter { it.isDigit() }
        val ms = rawMs.toLongOrNull() ?: 0L

        if (target.isNotEmpty()) {
            synchronized(alarms) {
                val alarm = alarms.getOrPut(id) { KissAlarm(id) }
                alarm.delay = ms.toInt()
                alarm.time = 0L
                alarm.enabled = true // Enable ONLY this specific alarm
            }
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
    @Suppress("unused")
    fun altMapping(target: String) {
        val cleanTarget = target.replace("#", "").replace("\"", "").trim()
            .lowercase().removeSuffix(".cel")
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