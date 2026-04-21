package moe.lottev.droidkiss

import kotlin.collections.forEach

/** The config parser. This reads the CNF files and loads the data from it. */
class ConfigParser(private val onMappingChanged: (String, Boolean) -> Unit) {
    val cels = mutableListOf<KissLayerDescriptor>()
    val kcfs = mutableListOf<String>()
    private val layers = mutableListOf<KissLayer>()
    val eventActions = mutableMapOf<String, List<KissAction>>()

    // Map to store timer intervals (e.g., "alarm1" -> 500ms)
    val alarmIntervals = mutableMapOf<String, Long>()

    val snapRules = mutableListOf<SnapRule>()
    var currentEventName: String? = null
    val currentEventActions = mutableListOf<KissAction>()

    // Example structure to store them
    val pressActions = mutableMapOf<String, List<KissAction>>()
    val releaseActions = mutableMapOf<String, List<KissAction>>()

    val inActions = mutableMapOf<String, List<SnapRule>>()
    val inEventActions = mutableMapOf<String, List<KissAction>>()

    val collideActions = mutableMapOf<String, List<KissAction>>()
    val apartActions = mutableMapOf<String, List<KissAction>>()

    val setActions = mutableMapOf<String, List<KissAction>>()

    val setFixActions = mutableMapOf<String, List<KissAction>>()
    val unfixActions = mutableMapOf<String, List<KissAction>>()

    val labelActions = mutableMapOf<String, List<KissAction>>()

    /** Global doll loader. This loads pretty much everything found in the current CNF. */
    @Suppress("RegExpRedundantClassElement")
    fun load(data: ByteArray) {
        // Reset everything to class-level variables
        cels.clear()
        kcfs.clear()
        eventActions.clear()
        alarmIntervals.clear()
        objectCoords.clear()
        currentEventName = null
        currentEventActions.clear()
        lineCounter = 0

        val content = data.toString(Charsets.UTF_8) // UTF-8 character support

        parseSnapRules(content, null)
        parseGlobalCoords(content)

        val lines = content.lines()

        val screenRegex = Regex("""^%?\s*(\d+)\s+(\d+)""")
        lines.forEach { rawLine ->
            screenRegex.find(rawLine.trim())?.let { match ->
                envWidth = match.groupValues[1].toInt()
                envHeight = match.groupValues[2].toInt()
            }

            val trimmed = rawLine.trim()

            if (trimmed.isEmpty() || (trimmed.startsWith(";") && !trimmed.contains("@"))) {
                return@forEach
            }

            val codePart = trimmed.split(";")[0].trim()
            val lowerCode = codePart.lowercase()

            val lineForObjects = rawLine.trim().replaceFirst(";", "").trim()
            val lowerLine = lineForObjects.lowercase()
            var cleaned = rawLine.trim()

            if (!codePart.contains("(") && lowerCode.contains(".cel")) {
                parseCelLine(lineForObjects)
            }

            if (cleaned.startsWith(";@")) {
                cleaned = cleaned.substring(1).trim() // Remove the ; and keep the @
            }

            // Special event headers
            // Not every event header is in this, only the necessary ones
            val forbidden = listOf(
                "sound",
                "music",
                "transparent",
                "randomtimer",
                "timer",
                "map",
                "unmap",
                "altmap",
                "press",
                "release",
                "catch",
                "drop",
                "fixcatch",
                "fixdrop",
                "in",
                "out",
                "collide",
                "apart",
                "notify",
                "set",
                "gosub",
                "gosubrandom",
                "goto",
                "gotorandom",
                "debug",
                "version",
                "ghost",
                "nop",
            )
            val hasForbiddenWord = forbidden.any { cleaned.contains(it) && !cleaned.contains("setfix") }

            val actionRegex = Regex("""(\w+)\s*\(([^)]+)\)""")

            val allMatches = actionRegex.findAll(cleaned).toList()
            val startsWithAt = cleaned.replace(" ", "").startsWith(";@")

            val isAlarmLine = cleaned.contains("alarm(", ignoreCase = true)
            val isPressLine = cleaned.contains("press(", ignoreCase = true)
            val isReleaseLine = cleaned.contains("release(", ignoreCase = true)
            val isInLine = cleaned.contains("in(", ignoreCase = true) && !cleaned.contains("begin(", ignoreCase = true)
            val isCollideLine = cleaned.contains("collide(", ignoreCase = true)
            val isApartLine = cleaned.contains("apart(", ignoreCase = true)
            val isSetLine = cleaned.contains("set(", ignoreCase = true)
            val isUnfixLine = cleaned.contains("unfix(", ignoreCase = true)
            val isLabelLine = cleaned.contains("label(", ignoreCase = true)

            val isNewHeader =
                startsWithAt || isInLine || isPressLine || isReleaseLine || isCollideLine || isApartLine || isSetLine || isAlarmLine || isUnfixLine || isLabelLine || (cleaned.contains("(") && !hasForbiddenWord)
            val isOldHeader = lowerLine.startsWith("begin") || lowerLine.startsWith("end")

            // Special events
            val offLimits = listOf("eventhandler", "begin", "end")
            val firstCmd = allMatches.getOrNull(0)?.groupValues?.get(1)?.lowercase() ?: ""
            val isOffLimits = offLimits.contains(firstCmd)

            // Header detection
            if ((startsWithAt && !isOffLimits) || isOldHeader || isNewHeader) {
                // ONE call to save the previous block
                saveCurrentBlock()

                // Gathers the name (Greedy version)
                val nameMatch = Regex("""\(([^)]+)\)""").find(cleaned)
                val raw = nameMatch?.groupValues?.get(1)?.replace("\"", "")?.lowercase() ?: ""

                currentEventName = when {
                    isPressLine -> "press($raw)" // Keeps "luna2.cel" as "luna2.cel"
                    isReleaseLine -> "release($raw)"
                    isAlarmLine -> "alarm($raw)"
                    isCollideLine -> "collide($raw)"
                    isApartLine -> "apart($raw)"
                    isInLine -> "in($raw)"
                    isSetLine -> "set($raw)"
                    isUnfixLine -> "unfix($raw)"
                    isLabelLine -> "label($raw)"
                    isOldHeader -> cleaned.substringAfter("begin").trim().lowercase()
                    cleaned.lowercase().contains("initialize") -> "initialize"
                    else -> cleaned.removePrefix(";").removePrefix("@").substringBefore("(").trim()
                        .lowercase()
                }

                if (currentEventName!!.isEmpty()) currentEventName = "eventhandler"

                // HARVEST ACTIONS (Do not let Part C do this for messy lines!)
                val actionRegex = Regex("""(\w+)\s*\(([^)]+)\)""")
                val matches = actionRegex.findAll(cleaned).toList()

                // If there's more than 1 match, or if it's a messy line, grab everything
                matches.forEach { match ->
                    val cmd = match.groupValues[1].trim().lowercase()
                    val rawParams = match.groupValues[2].replace("\"", "")

                    if (cmd == "press" && cleaned.contains("press(")) return@forEach

                    if (cmd == "release" && cleaned.contains("release(")) return@forEach

                    if (cmd == "fixcatch" && cleaned.contains("fixcatch(")) return@forEach

                    if (cmd == "fixdrop" && cleaned.contains("fixdrop(")) return@forEach

                    if (cmd == "catch" && cleaned.contains("catch(")) return@forEach

                    if (cmd == "drop" && cleaned.contains("drop(")) return@forEach

                    // Skip the header itself
                    if ((cmd == "press" || cmd == "release" || cmd == "in" || cmd == "out" || cmd == "collide" || cmd == "apart") && cleaned.contains("$cmd(")) {
                        val triggerId = rawParams.replace("#", "").trim()
                        if (currentEventName?.contains(triggerId) == true) return@forEach
                    }

                    val type = when (cmd) {
                        "transparent" -> ActionType.TRANSPARENT
                        "timer" -> ActionType.TIMER
                        "randomtimer" -> ActionType.RANDOM_TIMER
                        "unmap" -> ActionType.UNMAP
                        "map" -> ActionType.MAP
                        "altmap" -> ActionType.ALTMAP
                        "alarm" -> ActionType.ALARM
                        "move" -> ActionType.MOVE
                        "movebyx" -> ActionType.MOVEBYX
                        "movebyy" -> ActionType.MOVEBYY
                        "moveto" -> ActionType.MOVETO
                        "press" -> ActionType.PRESS
                        "release" -> ActionType.RELEASE
                        "catch" -> ActionType.CATCH
                        "drop" -> ActionType.DROP
                        "fixcatch" -> ActionType.FIXCATCH
                        "fixdrop" -> ActionType.FIXDROP
                        "in" -> ActionType.IN
                        "collide" -> ActionType.COLLIDE
                        "apart" -> ActionType.APART
                        "sound" -> ActionType.SOUND
                        "music" -> ActionType.MUSIC
                        "notify" -> ActionType.NOTIFY
                        "set" -> ActionType.SET
                        "changeset" -> ActionType.CHANGESET
                        "setfix" -> ActionType.SETFIX
                        "unfix" -> ActionType.UNFIX
                        "gosub" -> ActionType.GOSUB
                        "goto" -> ActionType.GOTO
                        "gosubrandom" -> ActionType.GOSUBRANDOM
                        "gotorandom" -> ActionType.GOTORANDOM
                        "label" -> ActionType.LABEL
                        "ghost" -> ActionType.GHOST
                        "nop" -> ActionType.NOP
                        else -> null
                    }

                    val parts = rawParams.split(",").map { it.trim() }

                    if (type != null) {
                        val action = KissAction(
                            target = parts.getOrNull(0) ?: "",
                            type = type,
                            valueStr = parts.getOrNull(1) ?: "null",
                            extraValue = parts.getOrNull(2) ?: "null"
                        )
                        currentEventActions.add(action)
                    }
                }
                return@forEach
            }

            if (currentEventName == null) {
                val pattern = Regex("""(unmap|map|altmap)\(([^)]+)\)""")
                pattern.findAll(cleaned).forEach { match ->
                    val action = match.groupValues[1]
                    val target = match.groupValues[2].replace("\"", "").replace("#", "")

                    onMappingChanged(target, (action == "unmap" || action == "altmap"))
                }
            }

            // --- C. ACTION COLLECTION ---
            if (currentEventName != null && cleaned.contains("(")) {
                // This regex finds: action(param1, param2)
                val actionRegex = Regex("""(\w+)\s*\(([^)]+)\)""")
                val matches = actionRegex.findAll(cleaned)

                matches.forEach { match ->
                    val cmd = match.groupValues[1].lowercase()

                    // Skip if this "action" is actually the header we just named
                    if ((cmd == "alarm" || cmd == "press" || cmd == "release" || cmd == "in" || cmd == "collide" || cmd == "apart") &&
                        (cleaned.startsWith(";@$cmd") || cleaned.startsWith("@$cmd"))
                    ) {
                        return@forEach
                    }

                    val rawParams = match.groupValues[2].replace("\"", "")
                    val parts = rawParams.split(",").map { it.trim() }
                    val target = parts.getOrNull(0) ?: ""

                    val type = when (cmd) {
                        "transparent" -> ActionType.TRANSPARENT
                        "timer" -> ActionType.TIMER
                        "randomtimer" -> ActionType.RANDOM_TIMER
                        "alarm" -> ActionType.ALARM
                        "unmap" -> ActionType.UNMAP
                        "map" -> ActionType.MAP
                        "altmap" -> ActionType.ALTMAP
                        "move" -> ActionType.MOVE
                        "movebyx" -> ActionType.MOVEBYX
                        "movebyy" -> ActionType.MOVEBYY
                        "moveto" -> ActionType.MOVETO
                        "press" -> ActionType.PRESS
                        "release" -> ActionType.RELEASE
                        "catch" -> ActionType.CATCH
                        "drop" -> ActionType.DROP
                        "fixcatch" -> ActionType.FIXCATCH
                        "fixdrop" -> ActionType.FIXDROP
                        "in" -> ActionType.IN
                        "out" -> ActionType.OUT
                        "collide" -> ActionType.COLLIDE
                        "apart" -> ActionType.APART
                        "sound" -> ActionType.SOUND
                        "music" -> ActionType.MUSIC
                        "notify" -> ActionType.NOTIFY
                        "set" -> ActionType.SET
                        "changeset" -> ActionType.CHANGESET
                        "setfix" -> ActionType.SETFIX
                        "unfix" -> ActionType.UNFIX
                        "gosub" -> ActionType.GOSUB
                        "goto" -> ActionType.GOTO
                        "gosubrandom" -> ActionType.GOSUBRANDOM
                        "gotorandom" -> ActionType.GOTORANDOM
                        "label" -> ActionType.LABEL
                        "ghost" -> ActionType.GHOST
                        "nop" -> ActionType.NOP
                        else -> null
                    }

                    if (type != null && target.isNotEmpty()) {
                        currentEventActions.add(
                            KissAction(
                                target,
                                type,
                                parts.getOrNull(1) ?: "null",
                                parts.getOrNull(2) ?: "null",
                            )
                        )
                    }
                }
            }

            if (lowerLine.contains(".kcf")) {
                Regex("""([\w\d._-]+\.[kK][cC][fF])""").find(lineForObjects)
                    ?.let { kcfs.add(it.value) }
            }
        }

        // Save the current block
        saveCurrentBlock()
    }

    /** Global block saver. */
    private fun saveCurrentBlock() {
        val name = currentEventName ?: return
        if (currentEventActions.isEmpty()) return

        val rawTrigger = name.substringAfter("(").substringBefore(")").trim()
        val cleanTrigger = rawTrigger
            .replace("\"", "")
            .replace("#", "")
            .lowercase()
            .removeSuffix(".cel")
        if (name.contains("press", ignoreCase = true) || name.contains("catch", ignoreCase = true) || name.contains(
                "fixcatch", ignoreCase = true)) {
            pressActions[cleanTrigger] = currentEventActions.toList()
        } else if (name.contains("release", ignoreCase = true) || name.contains("drop", ignoreCase = true) || name.contains("fixdrop", ignoreCase = true)) {
            releaseActions[cleanTrigger] = currentEventActions.toList()
        } else if (name.contains("in", ignoreCase = true) && !name.contains("initialize", ignoreCase = true) && !name.contains("begin", ignoreCase = true)) {
            inActions[cleanTrigger] = snapRules.toList()
            inEventActions[cleanTrigger] = currentEventActions.toList()
        } else if (name.contains("collide", ignoreCase = true)) {
            collideActions[cleanTrigger] = currentEventActions.toList()
        } else if (name.contains("apart", ignoreCase = true)) {
            apartActions[cleanTrigger] = currentEventActions.toList()
        } else if (name.contains("set", ignoreCase = true) && !name.contains("setfix", ignoreCase = true)) {
            setActions[rawTrigger] = currentEventActions.toList()
        } else if (name.contains("setfix", ignoreCase = true)) {
            setFixActions[cleanTrigger] = currentEventActions.toList()
        } else if (name.contains("unfix", ignoreCase = true)) {
            unfixActions[cleanTrigger] = currentEventActions.toList()
        } else if (name.contains("label", ignoreCase = true)) {
            labelActions[cleanTrigger] = currentEventActions.toList()
        } else {
            eventActions[name.lowercase().replace(" ", "")] = currentEventActions.toList()
        }
        currentEventActions.clear()
        currentEventName = null
    }

    var borderColorIndex: Int = 0

    /** Parses border color from CNF. */
    fun parseBorderColor(content: String) {
        // KiSS spec: [ is the border color.
        // We want to make sure we aren't grabbing (size) or other bracketed info.
        // This regex looks for a line that starts with [ followed by a number,
        // but ignores things like (784, 500)
        val borderRegex = Regex("""^\[\s*(\d+)(?:\s*;.*)?$""", RegexOption.MULTILINE)

        val match = borderRegex.find(content)
        if (match != null) {
            val index = match.groupValues[1].toIntOrNull() ?: 0
            // Safety cap: If it's over 255, it's almost certainly not a palette index
            if (index <= 255) {
                borderColorIndex = index
            }
        }
    }

    private fun extractSetsFromLine(line: String) {
        // Find the colon that precedes the set list
        val setPart = line.substringAfterLast(":", "")

        if (setPart.isNotBlank()) {
            // Split by any whitespace: "0 1 2" -> ["0", "1", "2"]
            val indices = setPart.trim().split(Regex("\\s+"))

            indices.forEach { str ->
                str.toIntOrNull()?.let {
                    definedSetIndices.add(it)
                }
            }
        }
    }

    var lineCounter = 0 // Reset this every time you start parsing a new CNF

    /** Parses CEL lines from CNF. */
    @Suppress("RegExpRedundantClassElement")
    private fun parseCelLine(line: String) {
        val trimmed = line.trim()
        if (trimmed.isEmpty()) return

        // Check for % hidden flag and %t transparency
        val isInitiallyHidden = trimmed.startsWith("%")
        val transparencyRegex = Regex("""%t(\d{1,3})""", RegexOption.IGNORE_CASE)
        val alphaMatch = transparencyRegex.find(line)
        val alphaValue = alphaMatch?.groupValues?.get(1)?.toIntOrNull()?.coerceIn(0, 255) ?: 0

        // Strip markers for parsing
        val cleanLine = trimmed.replace("%", "").split(";")[0].trim()
        if (!cleanLine.startsWith("#")) return

        // THE FIX: This Regex handles the TABS found in MORI.CNF
        // Group 1: ID (e.g. 17)
        // Group 2: The .Part (e.g. 99)
        // Group 3: The Filename
        val regex =
            Regex("""#(\d+)(?:\.(\d+))?[\s\t]+([\w\d._-]+)[^*:\d]*(?:\*(\d+))?[^:\d]*(?::([\d\s]+))?""")
        val match = regex.find(cleanLine)

        if (match != null) {
            extractSetsFromLine(trimmed)
            val objectId = match.groupValues[1].toIntOrNull() ?: 0

            // "ObjectPart" Logic: if there is a dot, it's a fixed object
            val hasFixDot = match.groupValues[2].isNotEmpty()

            // Currently unused, might call later
            val initialFixValue = match.groupValues[2].substringAfter('.').toIntOrNull() ?: 0

            val fileName = match.groupValues[3]
            val setString = match.groupValues[5]

            val allowedSets = if (setString.isBlank()) {
                emptyList()
            } else {
                setString.trim().split(Regex("\\s+")).mapNotNull { it.toIntOrNull() }
            }

            val paletteIdx = match.groupValues[4].toIntOrNull() ?: 0

            cels.add(
                KissLayerDescriptor(
                    fileName = fileName,
                    objectId = objectId,
                    isInitiallyFixed = hasFixDot,
                    initialFixValue = initialFixValue,
                    initialUnmapped = isInitiallyHidden,
                    x = 0, y = 0,
                    depth = lineCounter++,
                    allowedSets = allowedSets,
                    initialAlpha = alphaValue,
                    paletteIndex = paletteIdx,
                )
            )
        }
    }

    fun getRequiredCels(): List<KissLayerDescriptor> = cels
    fun getRequiredPalettes(): List<String> = kcfs

    val objectCoords = mutableMapOf<Int, Pair<Int, Int>>()

    var envWidth: Int = 640  // Default fallback
    var envHeight: Int = 480 // Default fallback

    /** Parses global object coordinates from CNF (x,y). */
    fun parseGlobalCoords(content: String, setIndex: Int = 0) {
        // Clear the map so we start fresh for the new set
        objectCoords.clear()

        // Split the whole file into blocks starting with $0
        val allBlocks = content.split(Regex("""\$\d"""))

        // Safety check: if no $0 exists, get out
        if (allBlocks.size <= 1) return

        // KiSS Rule: The first item in 'allBlocks' is everything BEFORE the first $0.
        // So $0 (Set 0) is actually index 1. $0 (Set 1) is index 2, etc.
        val targetBlockRaw = if (setIndex + 1 < allBlocks.size) {
            allBlocks[setIndex + 1]
        } else {
            allBlocks.last() // Fallback to last known set if index is out of bounds
        }

        // Stop at the next $ marker (so we don't grab other KiSS data)
        val coordBlock = targetBlockRaw.substringBefore("$").substringBefore(";")

        // Normalize whitespace (tabs/newlines to single spaces)
        val normalized = coordBlock.replace(Regex("[\\r\\n\\t]+"), " ").trim()

        // Split by SPACE to get each "X,Y" slot
        val slots = normalized.split(Regex("\\s+"))

        slots.forEachIndexed { index, slot ->
            // Handle placeholders and empty slots
            if (slot == "*" || slot == "." || slot.isEmpty()) return@forEachIndexed

            val parts = slot.split(",")
            if (parts.size >= 2) {
                val x = parts[0].toIntOrNull() ?: 0
                val y = parts[1].toIntOrNull() ?: 0
                objectCoords[index] = Pair(x, y)
            }
        }
    }

    private fun updateOrAddRule(objToMove: Int, anchorObj: Int, x: Int?, y: Int?) {
        val existing = snapRules.findLast { it.snapObj == objToMove && it.triggerObj == anchorObj }
        if (existing != null) {
            val idx = snapRules.indexOf(existing)
            snapRules[idx] = existing.copy(
                xValue = x ?: existing.xValue,
                yValue = y ?: existing.yValue,
            )
        } else {
            snapRules.add(
                SnapRule(
                    triggerObj = anchorObj,
                    targetObj = anchorObj,
                    snapObj = objToMove,
                    xValue = x ?: 0,
                    yValue = y ?: 0,
                    isRelative = true,
                    isRelativeFromSelf = false,
                    disabled = false
                )
            )
        }
    }

    val inTriggers = mutableListOf<KissInTrigger>()

    /** Parses `in()` actions. NOTE: Might be redundant. */
    fun parseInActions(content: String) {
        content.lines().forEach { rawLine ->
            val line = rawLine.trim().lowercase()
            if (!line.startsWith(";@in") && !line.startsWith("@in")) return@forEach

            // Extract the collision pair: in(#355, #0)
            val triggerMatch = Regex("""in\s*\(\s*#?(\d+)\s*,\s*#?(\d+)\s*\)""").find(line)
            if (triggerMatch != null) {
                val srcId = triggerMatch.groupValues[1].toInt()
                val dstId = triggerMatch.groupValues[2].toInt()

                // Extract the payload (everything after the trigger)
                val payload = line.substringAfter(")")

                // Parse and store
                val actions = parseActionsFromLine(payload)
                inTriggers.add(KissInTrigger(srcId, dstId, actions))
            }
        }
    }

    /** Parses actions from CNF lines. */
    fun parseActionsFromLine(payload: String): List<KissAction> {
        val actions = mutableListOf<KissAction>()
        // Finds patterns like command(param)
        val regex = Regex("""(\w+)\s*\(([^)]*)\)""")

        regex.findAll(payload).forEach { match ->
            val cmd = match.groupValues[1]
            val params = match.groupValues[2].split(",").map { it.trim() }
            val rawTarget = params.getOrNull(0) ?: ""

            val resolvedTarget = if (rawTarget.startsWith("#")) {
                rawTarget.replace("#", "")
            } else if (rawTarget.contains(".") || rawTarget.any { it.isLetter() }) {
                // It's a filename! Look it up in the layers we just provided
                val fileName = rawTarget.replace("\"", "")
                layers.find { it.descriptor.fileName.equals(fileName, true) }
                    ?.descriptor?.objectId?.toString() ?: rawTarget
            } else {
                rawTarget
            }

            val type = when (cmd) {
                "alarm" -> ActionType.ALARM
                "in" -> ActionType.IN
                "out" -> ActionType.OUT
                "collide" -> ActionType.COLLIDE
                "apart" -> ActionType.APART
                "press" -> ActionType.PRESS
                "release" -> ActionType.RELEASE
                "catch" -> ActionType.CATCH
                "drop" -> ActionType.DROP
                "fixcatch" -> ActionType.FIXCATCH
                "fixdrop" -> ActionType.FIXDROP
                "map" -> ActionType.MAP
                "unmap" -> ActionType.UNMAP
                "move" -> ActionType.MOVE
                "moveto" -> ActionType.MOVETO
                "movebyx" -> ActionType.MOVEBYX
                "movebyy" -> ActionType.MOVEBYY
                "timer" -> ActionType.TIMER
                "altmap" -> ActionType.ALTMAP
                "sound" -> ActionType.SOUND
                "music" -> ActionType.MUSIC
                "notify" -> ActionType.NOTIFY
                "set" -> ActionType.SET
                "changeset" -> ActionType.CHANGESET
                "setfix" -> ActionType.SETFIX
                "unfix" -> ActionType.UNFIX
                "gosub" -> ActionType.GOSUB
                "goto" -> ActionType.GOTO
                "gosubrandom" -> ActionType.GOSUBRANDOM
                "gotorandom" -> ActionType.GOTORANDOM
                "label" -> ActionType.LABEL
                "ghost" -> ActionType.GHOST
                "nop" -> ActionType.NOP
                else -> null
            }

            val finalTarget = when (type) {
                ActionType.MOVE, ActionType.MOVETO, ActionType.MOVEBYX, ActionType.MOVEBYY -> {
                    // Resolve to #ID
                    if (rawTarget.startsWith("#")) rawTarget
                    else "#$resolvedTarget"
                }
                else -> {
                    // Keep as filename
                    rawTarget.replace("\"", "")
                }
            }

            if (type != null) {
                actions.add(
                    KissAction(
                        target = finalTarget, // Store as #ID for consistency
                        type = type,
                        valueStr = params.drop(1).joinToString(","),
                        extraValue = ""
                    )
                )
            }
        }
        return actions
    }

    val pendingTriggers = mutableListOf<PendingTrigger>()

    /** Parses snap-to rules. */
    fun parseSnapRules(content: String, doll: KissDoll?) {
        snapRules.clear()
        var activeCollisionPair: Pair<Int, Int>? = null

        content.lines().forEach { line ->
            var clean = line.trim().lowercase()
            if (clean.isEmpty()) return@forEach

            // STRIP THE DECORATORS: Remove ; and @ from the start
            while (clean.startsWith(";") || clean.startsWith("@")) {
                clean = clean.substring(1).trim()
            }

            // UNIVERSAL TRIGGER PARSER (Regex)
            // This handles: in(), collide(), and apart() with both #ID and "file.cel"
            // This regex handles: in(#1, #2), in "a.cel" "b.cel", and @in #1 #2
            val triggerRegex =
                Regex("""^(in|out|collide|apart)\s*\(?\s*(?:#?(\d+)|"([^"]+)")\s*[, ]\s*(?:#?(\d+)|"([^"]+)")\s*\)?""")
            val triggerMatch = triggerRegex.find(clean)

            if (triggerMatch != null) {
                val type = triggerMatch.groupValues[1]
                val srcRaw = triggerMatch.groupValues[2].takeIf { it.isNotBlank() }
                    ?: triggerMatch.groupValues[3].takeIf { it.isNotBlank() }
                    ?: ""
                val dstRaw = triggerMatch.groupValues[4].takeIf { it.isNotBlank() }
                    ?: triggerMatch.groupValues[5].takeIf { it.isNotBlank() }
                    ?: ""

                val actions = parseActionsFromLine(line.substringAfter(")"))

                // Store it as "Pending" instead of trying to resolve it now
                pendingTriggers.add(PendingTrigger(type, srcRaw, dstRaw, line.substringAfter(")")))
                val src = triggerMatch.groupValues[2].ifBlank { triggerMatch.groupValues[3] }
                val dst = triggerMatch.groupValues[4].ifBlank { triggerMatch.groupValues[5] }

                val srcName = srcRaw.replace("\"", "").lowercase()
                val dstName = dstRaw.replace("\"", "").lowercase()

                val eventKey = "${type}_${srcName}_${dstName}"
                eventActions[eventKey] = actions

                // Resolve IDs immediately for snapping
                val sId = src.toIntOrNull()
                    ?: doll?.layers?.find { it.descriptor.fileName == src }?.descriptor?.objectId
                    ?: -1
                val dId = dst.toIntOrNull()
                    ?: doll?.layers?.find { it.descriptor.fileName == dst }?.descriptor?.objectId
                    ?: -1
                activeCollisionPair = Pair(sId, dId)
            }

            // DETACH TRIGGER
            // If the line is just "apart", stop applying move rules
            if (clean.contains("apart(")) {
                activeCollisionPair = null
            }

            // SNAPPING RULES (moveby / moveto)
            // These only apply if we are currently "inside" a trigger pair
            if (activeCollisionPair != null) {
                if (clean.contains("movebyx(")) {
                    val parts = clean.substringAfter("movebyx(").substringBefore(")").split(",")
                    if (parts.size >= 3) {
                        val obj = parts[0].replace("#", "").trim().toIntOrNull()
                            //?: activeCollisionPair!!.first
                        val anchor = parts[1].replace("#", "").trim().toIntOrNull()
                            //?: activeCollisionPair!!.second
                        val x = parts[2].trim().toIntOrNull() ?: 0
                        updateOrAddRule(obj ?: 0, anchor ?: 0, x, null)
                    }
                }
                if (clean.contains("movebyy(")) {
                    val parts = clean.substringAfter("movebyy(").substringBefore(")").split(",")
                    if (parts.size >= 3) {
                        val obj = parts[0].replace("#", "").trim().toIntOrNull()
                            //?: activeCollisionPair!!.first
                        val anchor = parts[1].replace("#", "").trim().toIntOrNull()
                            //?: activeCollisionPair!!.second
                        val y = parts[2].trim().toIntOrNull() ?: 0
                        updateOrAddRule(obj ?: 0, anchor ?: 0, null, y)
                    }
                }
            }

            // ABSOLUTE MOVETO
            if (clean.contains("moveto(")) {
                val moveContent = clean.substringAfter("moveto(").substringBefore(")")
                val parts = moveContent.split(",").map { it.replace("#", "").trim() }
                if (parts.size >= 3) {
                    val obj = parts[0].toIntOrNull() ?: 0
                    val tx = parts[1].toIntOrNull() ?: 0
                    val ty = parts[2].toIntOrNull() ?: 0
                    // We use -1/-1 for trigger/target because moveto is global
                    snapRules.add(SnapRule(-1, -1, obj, tx, ty, isRelative = false, isRelativeFromSelf = false, disabled = false))
                }
            }

            // Relative from itself ("move")
            if (clean.contains("move(")) {
                val moveContent = clean.substringAfter("move(").substringBefore(")")
                val parts = moveContent.split(",").map { it.replace("#", "").trim() }

                if (parts.size >= 3) {
                    val obj = parts[0].toIntOrNull() ?: 0
                    val tx = parts[1].toIntOrNull() ?: 0
                    val ty = parts[2].toIntOrNull() ?: 0
                    // We use -1/-1 for trigger/target because moveto is global
                    snapRules.add(SnapRule(-1, -1, obj, tx, ty, isRelative = false, isRelativeFromSelf = true, disabled = false))
                }
            }
        }
    }

    fun finalizeTriggers(actualLayers: List<KissLayer>) {
        pendingTriggers.forEach { pending ->
            // Parse actions once per trigger (not per layer!)
            val actions = parseActionsFromLine(pending.rawPayload)

            // Resolve the IDs for Src and Dst once
            val idA = pending.srcRaw.toIntOrNull() ?: actualLayers.find {
                it.descriptor.fileName.equals(pending.srcRaw.replace("\"", ""), true)
            }?.descriptor?.objectId ?: -1

            val idB = pending.dstRaw.toIntOrNull() ?: actualLayers.find {
                it.descriptor.fileName.equals(pending.dstRaw.replace("\"", ""), true)
            }?.descriptor?.objectId ?: -1

            // Only store if we found both objects
            if (idA != -1 && idB != -1) {
                // We store the key using IDs: e.g., "in_350_0"
                val key1 = "${pending.type}_${idA}_${idB}"
                val key2 = "${pending.type}_${idB}_${idA}" // Bidirectional for safety

                eventActions[key1] = actions
                eventActions[key2] = actions
            }
        }
        pendingTriggers.clear()
    }

    val definedSetIndices = mutableSetOf<Int>()

}