package moe.lottev.droidkiss

import android.content.Context
import android.net.Uri
import androidx.compose.ui.graphics.Color
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.collections.set

/** The KiSS doll engine. */
class DollEngine(context: Context,
                 private val onMappingChanged: (String, Boolean) -> Unit,
                 private val onSoundDetected: (String, ByteArray) -> Unit) {
    private val repo = ArchiveRepository(context)
    private var configParser = ConfigParser(
        onMappingChanged = { target, isUnmapped ->
            onMappingChanged(target, isUnmapped)
        },
    )
    private val paletteDecoder = PaletteDecoder()
    private val celDecoder = CelDecoder()

    var rawCnfString: String = ""

    suspend fun discoverCnfs(uri: Uri): List<String> {
        return withContext(Dispatchers.IO) {
            val archive = repo.loadLzh(uri) ?: repo.loadZip(uri) ?: return@withContext emptyList()
            val entries =
                (archive.contents as? List<*>)?.filterIsInstance<ArchiveEntry>() ?: emptyList()

            // Return a list of all found CNF filenames
            entries.filter { it.name.endsWith(".cnf", ignoreCase = true) }
                .map { it.name }
        }
    }

    /** Extract all files from archive */
    suspend fun extractAllFiles(uri: Uri): Map<String, ByteArray> {
        return withContext(Dispatchers.IO) {
            val lha = repo.loadLzh(uri) ?: return@withContext emptyMap()
            val zip = repo.loadZip(uri) ?: return@withContext emptyMap()
            val entries =
                (lha.contents as? List<*>)?.filterIsInstance<ArchiveEntry>() ?: emptyList()
            val zipEntries =
                (zip.contents as? List<*>)?.filterIsInstance<ArchiveEntry>() ?: emptyList()
            val map = mutableMapOf<String, ByteArray>()

            entries.forEach { entry ->
                repo.extractLhaEntry(lha, entry)?.let { bytes ->
                    map[entry.name] = bytes
                }
            }
            zipEntries.forEach { entry ->
                repo.extractZipEntry(zip, entry)?.let { bytes ->
                    map[entry.name] = bytes
                }
            }
            map
        }
    }

    /** Initialize CNFs from cache */
    suspend fun initializeFromCache(
        cache: Map<String, ByteArray>,
        targetCnf: String? = null
    ): KissDoll = withContext(Dispatchers.IO) {
        var eWidth = 640
        var eHeight = 480
        configParser = ConfigParser(onMappingChanged)

        val cnfName = targetCnf
        ?: cache.keys.find { it.endsWith(".cnf", ignoreCase = true) }?.lowercase()
        ?: throw Exception("No CNF found")

        val actualCnfKey = targetCnf ?: cache.keys.find { it.endsWith(".cnf", ignoreCase = true) }
        ?: throw Exception("No CNF found")

        val cnfBytes =
            cache[actualCnfKey] ?: throw Exception("CNF bytes missing (Key: $actualCnfKey)")

        val rawText = String(cnfBytes, charset("SHIFT_JIS"))
        // Switched to SHIFT_JIS for common Japanese dolls.
        // Note that this may break CNF text that's neither in English or Japanese (e.g. Spanish, French, etc.). For now, I have no plans to add yet another bloated dependency, so apologies in advance.

        rawCnfString = rawText

        // Environment Size
        val envRegex = Regex("""\((\d+)\s*,\s*(\d+)\)""")
        envRegex.find(rawText)?.let {
            eWidth = it.groupValues[1].toIntOrNull() ?: eWidth
            eHeight = it.groupValues[2].toIntOrNull() ?: eHeight
        }

        configParser.load(cnfBytes)

        configParser.parseGlobalCoords(rawText)

        configParser.parseBorderColor(rawText)

        var allCels = configParser.getRequiredCels()
        if (allCels.isEmpty()) {
            allCels = cache.keys
                .filter { it.endsWith(".cel", ignoreCase = true) }
                .mapIndexed { index, entry ->
                    KissLayerDescriptor(
                        fileName = entry,
                        x = 0, y = 0, depth = index,
                        objectId = index,
                        initialFixValue = 0,
                    )
                }
        }
        // Load aLL Palettes
        val paletteLibrary = mutableMapOf<String, List<IntArray>>()
        val requiredPalettes = configParser.getRequiredPalettes()

        requiredPalettes.forEachIndexed { index, kcfName ->
            val actualKey = cache.keys.firstOrNull { it.equals(kcfName, ignoreCase = true) }

            if (actualKey != null) {
                val paletteBytes = cache[actualKey]

                if (paletteBytes != null) {
                    val banks = paletteDecoder.decodeAllBanks(paletteBytes)

                    val markerKey = ('a' + index).toString()
                    paletteLibrary[markerKey] = banks
                    paletteLibrary[kcfName.lowercase()] = banks
                }
            } else {
                return@forEachIndexed
            }
        }

        val paletteList = mutableListOf<List<IntArray>>()
        requiredPalettes.forEach { kcfName ->
            val actualKey = cache.keys.firstOrNull { it.equals(kcfName, ignoreCase = true) }
            actualKey?.let {
                val bytes = cache[it]
                if (bytes != null) {
                    paletteList.add(paletteDecoder.decodeAllBanks(bytes))
                }
            }
        }

        cache.forEach { (fileName, fileBytes) ->
            val nameLower = fileName.lowercase()

            if (nameLower.endsWith(".wav") || nameLower.endsWith(".au")) {
                onSoundDetected(nameLower, fileBytes)
            }
        }

        val loadedLayers = allCels.mapNotNull { descriptor ->
            val assignedPaletteBanks = paletteList.getOrNull(descriptor.paletteIndex)
                ?: paletteList.firstOrNull()
            val paletteToUse = assignedPaletteBanks?.getOrNull(0)

            val initialAlphaInCompose = 1f - (descriptor.initialAlpha / 255f)
            val entry =
                cache.keys.firstOrNull { it.equals(descriptor.fileName, ignoreCase = true) }
                    ?: return@mapNotNull null

            val actualCelKey =
                cache.keys.firstOrNull { it.equals(descriptor.fileName, ignoreCase = true) }
                    ?: return@mapNotNull null
            val celBytes = cache[actualCelKey] ?: return@mapNotNull null

            val result =
                celDecoder.decodeToBitmap(celBytes, paletteToUse) ?: return@mapNotNull null

            val updatedDescriptor = descriptor.copy(
                celOffsetX = result.offsetX,
                celOffsetY = result.offsetY
            )

            val basePos = configParser.objectCoords[descriptor.objectId] ?: Pair(0, 0)
            val finalX = basePos.first + result.offsetX
            val finalY = basePos.second + result.offsetY

            KissLayer(
                descriptor = updatedDescriptor,
                bitmap = result.bitmap,
                rawIndices = result.rawIndices,
                width = result.bitmap.width,
                height = result.bitmap.height,
                x = finalX, // This is the value changeSet will update later
                y = finalY,
                fileName = entry,
                alpha = initialAlphaInCompose,
            )
        }

        // Border Color Logic
        val paletteBanks = paletteLibrary["a"]
        val resolvedColor = if (!paletteBanks.isNullOrEmpty()) {
            val bank0 = paletteBanks[0]
            val rawColor = bank0.getOrElse(configParser.borderColorIndex) { 0 }
            val opaqueColor = rawColor or (0xFF shl 24)

            if (configParser.borderColorIndex == 0 && opaqueColor == 0xFF000000.toInt()) {
                val altColor = bank0.getOrElse(0) { 0 }
                if (altColor != 0) Color(altColor or (0xFF shl 24))
                else Color.Black
            } else {
                Color(opaqueColor)
            }
        } else {
            Color.Black
        }

        val bgColor = if (!paletteBanks.isNullOrEmpty()) {
            val bank0 = paletteBanks[0]
            val altColor = bank0.getOrElse(0) { 0 }
            Color(altColor or (0xFF shl 24))
        } else {
            Color.Black
        }

        return@withContext KissDoll(
            name = cnfName,
            layers = loadedLayers.reversed(),
            activeGlobalBank = 0,
            envWidth = eWidth,
            envHeight = eHeight,
            themeColor = resolvedColor,
            bgColor = bgColor,
            config = configParser,
            allFiles = cache,
        )
    }
}