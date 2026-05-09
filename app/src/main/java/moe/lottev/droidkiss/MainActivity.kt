package moe.lottev.droidkiss

import android.annotation.SuppressLint
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import moe.lottev.droidkiss.ui.theme.DroidKiSSTheme
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntOffset
import kotlinx.coroutines.launch
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.media.AudioManager
import android.provider.MediaStore
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.exclude
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ColorLens
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScaffoldDefaults
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.RectangleShape
import kotlinx.coroutines.Dispatchers
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withLink
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.core.graphics.get
import kotlin.math.roundToInt
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.graphics.createBitmap
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import kotlin.let
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

/**
 This is where the main app screen resides.
 Dolls, menus, buttons, and functions (both viewer functions as well as FKiSS functionality) all get rendered here.
 I also made the app translatable, this includes the content descriptions.
 */
class MainActivity : ComponentActivity() {
    private var fileUriState = mutableStateOf<Uri?>(null)
    private val viewModel: ViewModel by viewModels()

    /** Makes it so the app is always full screen, hiding the system UI upon load, focus and resume. You're still able to access the system UI by swiping up and/or down. */
    private fun hideSystemUI() {
        val windowController = WindowCompat.getInsetsController(window, window.decorView)
        windowController.hide(WindowInsetsCompat.Type.systemBars())
        windowController.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }

    /** On initialization of the app. */
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS is specifically designed to
            // force the app into the cutout area in ALL orientations.
            window.attributes.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        } else
            window.attributes.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES

        super.onCreate(savedInstanceState)
        handleIntent(intent)
        window.attributes.layoutInDisplayCutoutMode =
            WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES

        WindowCompat.setDecorFitsSystemWindows(window, false)
        hideSystemUI()
        volumeControlStream = AudioManager.STREAM_MUSIC
        setContent {
            val bgColor = viewModel.dollBorderColor
            DroidKiSSTheme {
                val lifecycleOwner = LocalLifecycleOwner.current

                DisposableEffect(lifecycleOwner) {
                    val observer = LifecycleEventObserver { _, event ->
                        if (event == Lifecycle.Event.ON_RESUME) {
                            onAppForegrounded(true)
                        }
                    }
                    lifecycleOwner.lifecycle.addObserver(observer)
                    onDispose {
                        lifecycleOwner.lifecycle.removeObserver(observer)
                    }
                }
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = bgColor) {
                    val context = LocalContext.current
                    val openArchiveLauncher = rememberLauncherForActivityResult(
                        contract = ActivityResultContracts.OpenDocument()
                    ) { uri: Uri? ->
                        uri?.let {
                            viewModel.prepareArchive(context, uri)
                        }
                    }
                    DollScreen(
                        viewModel,
                        onLaunchPicker = {
                            openArchiveLauncher.launch(arrayOf("application/x-lzh", "application/zip", "application/octet-stream"))
                        },
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // IMPORTANT: Set the activity intent to the new one,
        // otherwise handleIntent might read the old Uri.
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent?.action == Intent.ACTION_VIEW) {
            val uri = intent.data
            if (uri != null) {
                fileUriState.value = uri
                // Trigger the actual loading logic
                viewModel.prepareArchive(this, uri)
            }
        }
    }

    /** When the app is resumed. */
    override fun onResume() {
        hideSystemUI()
        viewModel.soundManager.resumeMusic()
        viewModel.soundManager.resumeAll()
        viewModel.resumeTimer()
        super.onResume()
    }

    /** When the window changes focus. */
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            hideSystemUI()
            viewModel.soundManager.resumeMusic()
            viewModel.soundManager.resumeAll()
            viewModel.resumeTimer()
        }
    }

    /** When the app is in the foreground.  */
    fun onAppForegrounded(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            hideSystemUI()
            viewModel.soundManager.resumeMusic()
            viewModel.soundManager.resumeAll()
            viewModel.resumeTimer()
        }
    }

    /** When the app pauses. */
    override fun onPause() {
        viewModel.soundManager.pauseMusic()
        viewModel.soundManager.pauseAll()
        viewModel.suspendTimer()
        super.onPause()
    }

    /** When the app shuts down. */
    override fun onDestroy() {
        viewModel.executeEvent("end")
        viewModel.soundManager.stopAll()
        viewModel.stopTimer()
        super.onDestroy()
    }
}

/** DroidKiSS logo composable */
@Preview
@Composable
fun DroidKissLogo() {
    Image(
        painter = painterResource(id = R.drawable.droidkiss_logo),
        contentDescription = stringResource(R.string.desc_app_logo),
    )
}

/** Button for showing application info. */
@Preview
@Suppress("AssignedValueIsNeverRead")
@Composable
fun AppInfoButton() {
    var showDialog by remember { mutableStateOf(false) }

    Button(
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.onPrimary),
        onClick = { showDialog = true }
    ) {
        Text(stringResource(R.string.app_info_btn))
    }

    if (showDialog) {
        // Pass the lambda to set state back to false
        AppInfoDialog(onDismiss = { showDialog = false })
    }
}

/** Dialog for showing application info. */
@Composable
fun AppInfoDialog(onDismiss: () -> Unit) { // Added parameter here
    val context = LocalContext.current
    val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
    val versionName = packageInfo.versionName

    AlertDialog(
        onDismissRequest = onDismiss, // Use the passed lambda
        title = { Text(stringResource(R.string.app_info_title)) },
        text = {
            Column {
                Text("${stringResource(R.string.app_name_caller)}: ${stringResource(R.string.app_name)}")
                Text("${stringResource(R.string.app_version_caller)} $versionName")
                Text("${stringResource(R.string.dev_caller)} Lotte V")
                Text("${stringResource(R.string.license_caller)} GPL 3.0")
                Text(buildAnnotatedString {
                    withLink(LinkAnnotation.Url("https://github.com/lottev1991/droidkiss")) {
                        append(stringResource(R.string.source_code_caller))
                    }
                })
                Text(buildAnnotatedString {
                    withLink(LinkAnnotation.Url("https://lottev.moe")) {
                        append(stringResource(R.string.website_caller))
                    }
                })
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { // Use the passed lambda
                Text(stringResource(R.string.close))
            }
        }
    )
}

/** The doll viewport */
@SuppressLint("UnusedBoxWithConstraintsScope")
@Composable
fun DollViewport(viewModel: ViewModel) {
    val doll = viewModel.currentDoll ?: return
    val barColor = viewModel.dollBorderColor
    val zoom = viewModel.zoomLevel
    val density = LocalDensity.current
    var viewportSize by remember { mutableStateOf(IntSize.Zero) }

    Box(modifier = Modifier
        .fillMaxSize()
        .clipToBounds()
        .background(barColor)
        .onGloballyPositioned { coords ->
            viewportSize = coords.size
        },
        contentAlignment = Alignment.Center,
    ) {
        if (viewportSize.width > 0 && viewportSize.height > 0) {
            val dollWidthPx = with(density) { ((doll.envWidth) * zoom).dp.toPx() }.roundToInt()
            val dollHeightPx =
                with(density) { ((doll.envHeight) * zoom).dp.toPx() }.roundToInt()

            // Compare doll size to exact pixel size of the viewport
            val needsHorizontal =
                if (dollWidthPx <= (viewportSize.height) && dollWidthPx <= (viewportSize.width)) {
                    false
                } else {
                    dollWidthPx > (viewportSize.height) && dollWidthPx > (viewportSize.width)
                }

            val needsVertical =
                if (dollHeightPx <= (viewportSize.height) && dollHeightPx <= (viewportSize.width)) {
                    false
                } else {
                   dollHeightPx > (viewportSize.width) && dollWidthPx > (viewportSize.height)
                }

            val hScroll = rememberScrollState()
            val vScroll = rememberScrollState()

            // Reset centering only when doll or zoom changes
            LaunchedEffect(doll, zoom) {
                if (needsHorizontal) hScroll.scrollTo((dollWidthPx - viewportSize.width) / 2)
                if (needsVertical) vScroll.scrollTo((dollHeightPx - viewportSize.height) / 2)
            }

            // Build the scroll modifier ONLY if overflow is true
            val scrollModifier = Modifier
                .then(if (needsHorizontal) Modifier.horizontalScroll(hScroll) else Modifier)
                .then(if (needsVertical) Modifier.verticalScroll(vScroll) else Modifier)

            Box(
                modifier = Modifier
                    .fillMaxSize() // Match viewportSize
                    .clipToBounds()
                    .then(scrollModifier),
                contentAlignment = Alignment.Center

            ) {
                // The Doll "Table"
                Box(
                    modifier = Modifier
                        .size(
                            width = (doll.envWidth * zoom).dp,
                            height = (doll.envHeight * zoom).dp
                        )
                        .background(doll.bgColor)
                        .clipToBounds()
                ) {
                    DollCanvas(doll, viewModel, rememberGraphicsLayer())
                }
            }
        }
    }
}

/** Buttons for loaded KiSS dolls. */
@Composable
fun DollButtons(viewModel: ViewModel) {
    Column(
        modifier = Modifier
            .fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Top
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.Bottom
        ) {

            // Zoom in button
            // I put a hard cap of 10 on the zoom in function.
            // This is because the app can crash upon extreme scaling.
            // The exact "crash" amount is dependent on the doll (larger dolls crash earlier than smaller ones) but I hard-capped it so I won't have to calculate the entire scaled viewport first.

            Button(
                border = BorderStroke(
                    1.dp,
                    MaterialTheme.colorScheme.onPrimary
                ),
                onClick = {
                    if (viewModel.zoomLevel < 10.0) { // Cap zoom to prevent app crashing
                        viewModel.zoomLevel += 1.0
                    }
                }) {
                Text(stringResource(R.string.zoom_in))
            }

            // Zoom out button
            // Due to the smaller physical size of smartphones, we can't zoom out to less than 100%.
            // This is usually not a problem due to their relatively high resolution,
            // meaning that bigger dolls will usually fit, unlike on older desktop computers.
            // That being said, if it ever does become a problem, I won't mind changing it (it's not very difficult to do).
            Button(
                border = BorderStroke(
                    1.dp,
                    MaterialTheme.colorScheme.onPrimary
                ),
                onClick = {
                    if (viewModel.zoomLevel > 1.0) { // Never zoom out less than 100%
                        viewModel.zoomLevel -= 1.0
                    }
                }) {
                Text(stringResource(R.string.zoom_out))
            }
            // Reset zoom to 1:1 (100%)
            Button(
                border = BorderStroke(
                    1.dp,
                    MaterialTheme.colorScheme.onPrimary
                ),
                onClick = { viewModel.zoomLevel = 1.0 }
            ) {
                Text(stringResource(R.string.zoom_reset))
            }

            Button(
                border = BorderStroke(
                    1.dp,
                    MaterialTheme.colorScheme.onPrimary
                ),
                onClick = {
                    viewModel.currentOffsets.clear()
                }
            ) {
                Text(stringResource(R.string.reset_positions))
            }
            Button(
                border = BorderStroke(
                    1.dp,
                    MaterialTheme.colorScheme.onPrimary
                ),
                onClick = { viewModel.toggleScrollMode() },
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (viewModel.isScrollMode) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.secondary,
                )
            ) {
                Text(
                    if (viewModel.isScrollMode) "${stringResource(R.string.mode)}: ${
                        stringResource(R.string.mode_scroll)
                    }" else "${stringResource(R.string.mode)}: ${
                        stringResource(R.string.mode_drag_press)
                    }"
                )
            }
        }
    }
}



/** The doll screen. Loads the doll canvas, as well as the current UI state and the buttons. */
@Composable
fun DollScreen(viewModel: ViewModel, onLaunchPicker: () -> Unit) {
    val context = LocalContext.current
    val state = viewModel.uiState

    if (viewModel.showCnfSelector) {
        CnfSelectionDialog(
            options = viewModel.pendingCnfOptions,
            onSelect = { selectedCnf ->
                viewModel.showCnfSelector = false
                viewModel.selectedUri?.let { uri ->
                    // Pass the context we got from LocalContext.current
                    if (viewModel.isExpansionSet) {
                        viewModel.loadExpansionSet(context, uri, selectedCnf)
                        viewModel.isExpansionSet = false
                    } else {
                        viewModel.loadArchive(context, uri, selectedCnf)
                    }

                }
            },
            onDismiss = { viewModel.showCnfSelector = false }
        )
    }

    val notifyMsg = viewModel.notifyMessage
    LaunchedEffect(notifyMsg) {
        notifyMsg?.let {
            Toast.makeText(context, it, Toast.LENGTH_SHORT).show()
            viewModel.clearNotify()
        }
    }
    Scaffold(
        contentWindowInsets = ScaffoldDefaults.contentWindowInsets
            .exclude(WindowInsets.displayCutout),
        topBar = {
            when (state) {
                is ViewModel.KissUiState.Loaded -> {
                    Row(horizontalArrangement = Arrangement.SpaceAround){
                        DollMenu(viewModel, onLaunchPicker)
                        PaletteMenu(viewModel)
                    }
                    SetButtons(viewModel)
                }
                else -> {}
            }
        },
        bottomBar = {
            when (state) {
                is ViewModel.KissUiState.Loaded -> {
                    DollButtons(viewModel)
                }
                else -> {}
            }
        },
        snackbarHost = { SnackbarHost(hostState = viewModel.snackbarHostState) },
        containerColor = MaterialTheme.colorScheme.primaryContainer,
    ) { paddingValues ->
        Column(
            modifier = Modifier.padding(paddingValues)
        ) {
            when (state) {
                is ViewModel.KissUiState.Empty -> {
                    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                        DroidKissLogo()
                        Button(onClick = onLaunchPicker, border = BorderStroke(
                            1.dp,
                            MaterialTheme.colorScheme.onPrimary)) {
                            Text(stringResource(R.string.load_doll))
                        }
                        AppInfoButton()
                    }
                }

                is ViewModel.KissUiState.Loading -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }

                is ViewModel.KissUiState.Loaded -> {
                    DollViewport(viewModel)
                }

                is ViewModel.KissUiState.Error -> {
                    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center) {
                        Text("${stringResource(R.string.error)}: ${state.message}", color = MaterialTheme.colorScheme.error, fontSize = 20.sp)
                        Button(onClick = { viewModel.closeArchive() },
                            border = BorderStroke(
                            1.dp,
                            MaterialTheme.colorScheme.onPrimary)) {
                            Text(stringResource(R.string.error_return))
                        }
                    }
                }
            }
        }
    }
}

/** The doll canvas. This is where the currently loaded KiSS doll gets drawn. */
@SuppressLint("UnrememberedMutableState", "UnusedBoxWithConstraintsScope")
@Composable
fun DollCanvas(doll: KissDoll, viewModel: ViewModel, graphicsLayer: GraphicsLayer) {
    val density = LocalDensity.current
    @Suppress("unused", "UnusedVariable")
    val trigger = viewModel.refreshTrigger

    // For shell events
    val uriHandler = LocalUriHandler.current
    LaunchedEffect(Unit) {
        viewModel.eventFlow.collect { event ->
            when (event) {
                is ShellEvent.OpenLink -> {
                    uriHandler.openUri(event.url)
                }
            }
        }
    }

    // Remembers the object being dragged
    var activeDraggingId by remember { mutableStateOf<Int?>(null) }
    val hapticFeedback = LocalHapticFeedback.current
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .clipToBounds()
            .background(doll.themeColor)
            .drawWithContent {
                graphicsLayer.record {
                    this@drawWithContent.drawContent()
                }
                drawLayer(graphicsLayer)
            },
        contentAlignment = Alignment.Center
    ) {
        val scale = viewModel.zoomLevel.roundToInt()
        val doll = viewModel.currentDoll ?: return@BoxWithConstraints
        val viewportWidth = with(density) { (doll.envWidth * scale).toDp() }
        val viewportHeight = with(density) { (doll.envHeight * scale).toDp() }

        var totalAccumulatedX = 0f
        var totalAccumulatedY = 0f

        val dragModifier = Modifier
            .pointerInput(
                scale,
                viewModel.activeSet,
                viewModel.isScrollMode,
            ) { // Keys ensure it resets when set changes
                detectDragGestures (
                    onDragStart = { offset ->
                        val tapX = offset.x / scale
                        val tapY = offset.y / scale

                        val hitLayer = doll.layers.asReversed().find { layer ->
                            if (!viewModel.isLayerVisible(layer)) return@find false
                            if (layer.descriptor.isGhosted) return@find false
                            val drag =
                                viewModel.currentOffsets[layer.descriptor.objectId] ?: Offset.Zero
                            val localX = (tapX - (layer.x + drag.x)).toInt()
                            val localY = (tapY - (layer.y + drag.y)).toInt()

                            if (localX in 0 until layer.bitmap.width && localY in 0 until layer.bitmap.height) {
                                ((layer.bitmap[localX, localY] shr 24) and 0xFF) > 0
                            } else false
                        }

                        activeDraggingId = hitLayer?.descriptor?.objectId

                        // SYNC: Set the ghost finger to the object's current offset
                        activeDraggingId?.let { id ->
                            val currentOffset = viewModel.currentOffsets[id] ?: Offset.Zero
                            totalAccumulatedX = currentOffset.x
                            totalAccumulatedY = currentOffset.y
                        }
                    },
                    onDrag = { change, dragAmount ->
                        activeDraggingId?.let { id ->
                            change.consume()

                            // Update the 'Ghost Finger' (Accumulator)
                            totalAccumulatedX += dragAmount.x / scale
                            totalAccumulatedY += dragAmount.y / scale

                            val objectLayers =
                                doll.layers.asReversed().filter { it.descriptor.objectId == id && !it.descriptor.isUnmapped}

                            val isGroupFixed = doll.layers.any {
                                it.descriptor.objectId == id && it.descriptor.isFixed
                            }

                            if (!isGroupFixed) {
                                val minBaseX = objectLayers.minOf { it.x }
                                val minBaseY = objectLayers.minOf { it.y }
                                val maxBaseX = objectLayers.maxOf { it.x + it.bitmap.width }
                                val maxBaseY = objectLayers.maxOf { it.y + it.bitmap.height }

                                // Define the Walls based on the group's "Footprint"
                                // We use minBaseX/Y to ensure the top-most/left-most cel
                                // stops at the edge of the screen.
                                val minAllowedOffsetH = -minBaseX.toFloat()
                                val minAllowedOffsetV = -minBaseY.toFloat()
                                val maxAllowedOffsetH = (doll.envWidth - maxBaseX).toFloat()
                                val maxAllowedOffsetV = (doll.envHeight - maxBaseY).toFloat()

                                // Update the Offset
                                // We use the same clamp for the WHOLE ID group.
                                viewModel.currentOffsets[id] = Offset(
                                    totalAccumulatedX.coerceIn(
                                        minOf(minAllowedOffsetH, maxAllowedOffsetH),
                                        maxOf(minAllowedOffsetH, maxAllowedOffsetH)
                                    ),
                                    totalAccumulatedY.coerceIn(
                                        minOf(minAllowedOffsetV, maxAllowedOffsetV),
                                        maxOf(minAllowedOffsetV, maxAllowedOffsetV)
                                    )
                                )
                            }
                        }
                    },
                    onDragEnd = {
                        activeDraggingId?.let { id ->
                            val hitLayer =
                                doll.layers.find { it.descriptor.objectId == id } ?: return@let
                            viewModel.executeReleaseActions(hitLayer)
                            viewModel.executeDropActions(hitLayer)
                            viewModel.executeFixDropActions(hitLayer)
                            viewModel.handleDrop(id)
                            viewModel.checkCollideApart(id)
                            viewModel.configParser.parseInActions(id.toString())
                        }
                        activeDraggingId = null
                    },
                    onDragCancel = {
                        activeDraggingId?.let { id ->
                            viewModel.currentOffsets[id] = Offset.Zero
                        }
                        activeDraggingId = null
                    }
                )
            }

        Box(
            modifier = Modifier
                .size(viewportWidth, viewportHeight)
                .background(doll.bgColor)
                .clip(RectangleShape)
                .border(Dp.Hairline, Color.Black)
                .then(if (!viewModel.isScrollMode) dragModifier else Modifier) // Disable dragging when in scroll mode
                .pointerInput(scale, viewModel.activeSet) {
                    awaitPointerEventScope {
                        while (true) {
                            val down = awaitFirstDown()
                            val tapX = down.position.x / scale
                            val tapY = down.position.y / scale

                            val hitLayer = doll.layers.asReversed().find { layer ->
                                if (!viewModel.isLayerVisible(layer)) return@find false
                                if (layer.descriptor.isGhosted) return@find false
                                val drag = viewModel.currentOffsets[layer.descriptor.objectId]
                                    ?: Offset.Zero
                                val localX = (tapX - (layer.x + drag.x)).toInt()
                                val localY = (tapY - (layer.y + drag.y)).toInt()

                                if (localX in 0 until layer.bitmap.width && localY in 0 until layer.bitmap.height) {
                                    ((layer.bitmap[localX, localY] shr 24) and 0xFF) > 0
                                } else false
                            }

                            if (hitLayer != null && !viewModel.isScrollMode) {
                                val objectId = hitLayer.descriptor.objectId
                                val startPos = down.position
                                var wasDragInitiated = false

                                val longPressTimeout = viewConfiguration.longPressTimeoutMillis

                                val finalEvent = withTimeoutOrNull(longPressTimeout) {
                                    while (true) {
                                        val event = awaitPointerEvent()
                                        val primaryChange = event.changes.find { it.id == down.id }
                                            ?: event.changes.first()

                                        val distance =
                                            (primaryChange.position - startPos).getDistance()

                                        if (distance > viewConfiguration.touchSlop) {
                                            wasDragInitiated = true
                                            return@withTimeoutOrNull primaryChange
                                        }

                                        if (!primaryChange.pressed) {
                                            return@withTimeoutOrNull primaryChange // Finger lifted
                                        }
                                    }
                                }

                                if (finalEvent == null) {
                                    // --- TIMEOUT REACHED: This is a Long Press ---
                                    hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)

                                    val objectLayers =
                                        doll.layers.filter { it.descriptor.objectId == objectId }
                                    val currentlyFixed = objectLayers.any { it.descriptor.isFixed }

                                    // Flip the state for the whole group
                                    objectLayers.forEach { it.descriptor.isFixed = !currentlyFixed }

                                    if (currentlyFixed) {
                                        viewModel.executeUnfixActions(hitLayer)
                                    }

                                    waitForUpOrCancellation()
                                } else if (!wasDragInitiated) {
                                    // --- NO TIMEOUT, NO DRAG: This is a Tap ---
                                    viewModel.executeFixCatchActions(hitLayer)
                                    viewModel.executePressActions(hitLayer)
                                    viewModel.executeCatchActions(hitLayer)
                                    viewModel.executeReleaseActions(hitLayer)
                                    viewModel.executeDropActions(hitLayer)
                                    viewModel.executeFixDropActions(hitLayer)
                                    viewModel.revertPressActions(objectId)
                                    viewModel.revertCatchActions(objectId)
                                    viewModel.revertFixCatchActions(objectId)
                                }
                                // If wasDragInitiated is true, we do nothing and let the drag gesture start.
                            }
                        }
                    }
                }
        ) {
            key(viewModel.refreshTrigger) {
                // Draw image for screenshot rendering
                doll.layers.filter { layer ->
                    // Visible if:
                    // It's not unmapped OR
                    // It is currently being forced visible by a PRESS action
                    val isCurrentlyMapped =
                        !layer.descriptor.isUnmapped || viewModel.activePressIds.contains(layer.descriptor.objectId) || viewModel.activeCatchIds.contains(layer.descriptor.objectId) || viewModel.activeFixCatchIds.contains(layer.descriptor.objectId)

                    // AND it must belong to the current set (or be a base item)
                    val isInSet = layer.descriptor.allowedSets.isEmpty() ||
                            layer.descriptor.allowedSets.contains(viewModel.activeSet)

                    isCurrentlyMapped && isInSet
                }.forEach { layer ->
                    val desc = layer.descriptor
                    // Check Sets (The User Switch)
                    // If allowedSets is empty, it's a "base" item visible in all sets
                    val isInSet =
                        desc.allowedSets.isEmpty() || desc.allowedSets.contains(viewModel.activeSet)

                    if (isInSet) {
                        val dragOffset = viewModel.currentOffsets[desc.objectId] ?: Offset.Zero
                        Image(
                            bitmap = layer.bitmap.asImageBitmap(),
                            modifier = Modifier
                                .offset {
                                    // Calculate the KiSS-space position first
                                    // Round it to the nearest whole pixel to prevent sub-pixel bleeding
                                    // THEN multiply by scale for the UI layout
                                    val finalX =
                                        ((layer.x + dragOffset.x).roundToInt() * scale)
                                    val finalY =
                                        ((layer.y + dragOffset.y).roundToInt() * scale)
                                    IntOffset(finalX, finalY)
                                }
                                .graphicsLayer {
                                    scaleX = scale.toFloat()
                                    scaleY = scale.toFloat()
                                    transformOrigin = TransformOrigin(0f, 0f)
                                    alpha = layer.alpha
                                },
                            contentDescription = "${layer.descriptor.fileName}; ${stringResource(R.string.desc_doll_cel)}",
                            filterQuality = FilterQuality.None,
                            contentScale = ContentScale.None
                        )
                    }
                }
            }
        }

    }
}

/** Menu for extra options.
 Created so that the button bar at the bottom wouldn't get too crowded. */
@Suppress("AssignedValueIsNeverRead")
@Composable
fun DollMenu(viewModel: ViewModel, onLaunchPicker: () -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    var showDialog by remember { mutableStateOf(false) }

    val coroutineScope = rememberCoroutineScope()
    val context = LocalContext.current
    val expansionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let { viewModel.prepareExpansion(context, it) }
    }
    val screenshotMessage = stringResource(R.string.saved_screenshot_toast)

    if (showDialog) {
        AppInfoDialog(onDismiss = { showDialog = false })
    }

    var showCnfSource by remember { mutableStateOf(false) }
    if (showCnfSource) {
        Dialog(
            onDismissRequest = { showCnfSource = false },
            properties = DialogProperties(usePlatformDefaultWidth = false), // Allows full screen
        ) {
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = MaterialTheme.colorScheme.primaryContainer,
            ) {
                Column {
                    // With scrollable text
                    CnfSourceWindow(viewModel, onBackClick = { showCnfSource = false })
                }
            }
        }
    }

    Box(
        contentAlignment = Alignment.BottomStart,
    ) {
        IconButton(
            colors = IconButtonDefaults.iconButtonColors(
                contentColor = MaterialTheme.colorScheme.inverseSurface,
            ),
            onClick = { expanded = true }) {
            Icon(
                Icons.Filled.Menu,
                contentDescription = stringResource(R.string.desc_doll_menu),
            )
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            containerColor = MaterialTheme.colorScheme.primaryContainer,
        ) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.load_another_doll)) },
                onClick = {
                    onLaunchPicker.invoke()
                    viewModel.soundManager.stopMusic()
                    expanded = false
                }
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.load_expansion_set)) },
                onClick = {
                    expanded = false
                    viewModel.isExpansionSet = true
                    expansionLauncher.launch(
                        arrayOf("application/x-lzh", "application/zip", "application/octet-stream")
                    )
                }
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.take_doll_screenshot)) },
                onClick = {
                    expanded = false
                    Toast.makeText(
                        context,
                        "$screenshotMessage: KiSS_${System.currentTimeMillis()}.png",
                        Toast.LENGTH_SHORT
                    ).show()
                    coroutineScope.launch(Dispatchers.Default) { // Using Default for CPU-bound Bitmap drawing
                        val doll = viewModel.currentDoll ?: return@launch

                        // Create a "Clean" Pixel-Perfect Canvas
                        // We use the doll's internal resolution (e.g., 640x480)
                        val snapshot = createBitmap(doll.envWidth, doll.envHeight).apply {
                            density = Bitmap.DENSITY_NONE
                        }

                        val canvas = Canvas(snapshot)

                        // The "Point-Sampled" Paint
                        val pixelPaint = Paint().apply {
                            isAntiAlias = false
                            isFilterBitmap = false
                            isDither = false
                        }

                        // Clear Canvas with the Doll's Background Color
                        val bgColorInt = android.graphics.Color.argb(
                            255,
                            (doll.bgColor.red * 255).toInt(),
                            (doll.bgColor.green * 255).toInt(),
                            (doll.bgColor.blue * 255).toInt()
                        )
                        canvas.drawColor(bgColorInt)

                        // Draw Layers using the updated ChangeSet coordinates
                        doll.layers.forEach { layer ->
                            // Only draw what's visible in this set
                            val isInSet = (layer.descriptor.allowedSets.isEmpty()) ||
                                    layer.descriptor.allowedSets.contains(viewModel.activeSet)

                            if (!layer.descriptor.isUnmapped && isInSet) {
                                val dragOffset =
                                    viewModel.currentOffsets[layer.descriptor.objectId]
                                        ?: Offset.Zero

                                // STRICT INTEGER POSITIONS
                                val x = (layer.x + dragOffset.x).roundToInt().toFloat()
                                val y = (layer.y + dragOffset.y).roundToInt().toFloat()

                                canvas.drawBitmap(layer.bitmap, x, y, pixelPaint)
                            }
                        }

                        // Save and Cleanup
                        saveScreenshot(
                            context,
                            snapshot,
                            "KiSS_${System.currentTimeMillis()}"
                        )

                        // Recycles only if you're sure you're done with it to save memory
                        snapshot.recycle()
                    }
                }
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.show_cnf_source)) },
                onClick = {
                    expanded = false
                    showCnfSource = true
                }
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.close_current_doll)) },
                onClick = {
                    expanded = false
                    viewModel.closeArchive()
                }
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.app_info_btn)) },
                onClick = {
                    expanded = false
                    showDialog = true
                }
            )
        }
    }
}

/** Palette menu. This is where users can select palette groups (0-9). */
@Composable
fun PaletteMenu(viewModel: ViewModel) {
    var expanded by remember { mutableStateOf(false) }

    Box(
        contentAlignment = Alignment.BottomStart,
        modifier = Modifier.verticalScroll(rememberScrollState())
    ) {
        IconButton(
            colors = IconButtonDefaults.iconButtonColors(
                contentColor = MaterialTheme.colorScheme.inverseSurface,
            ),
            onClick = { expanded = true }) {
            Icon(
                Icons.Filled.ColorLens,
                contentDescription = "palette menu (REPLACE WITH LOCALIZED STRING)",
            )
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            containerColor = MaterialTheme.colorScheme.primaryContainer,
        ) {
            (0..9).forEach { palNum ->
                DropdownMenuItem(
                    text = { Text("${stringResource(R.string.palette_select)}$palNum") },
                    onClick = {
                        viewModel.changeCol(palNum.toString())
                        expanded = false
                    }
                )
            }
        }
    }
}

/** Top bar containing set buttons. The number of them is dynamic, depending on the loaded doll. */
@Composable
fun SetButtons(viewModel: ViewModel) {
    Column(
        modifier = Modifier
            .fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Bottom
    ) {
        val sets = viewModel.getAvailableSets()
        Row(
            modifier = Modifier
                .fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.Bottom
        ) {
            sets.forEach { setNum ->
                val isDefined = viewModel.isSetDefined(setNum)
                val isActive = viewModel.activeSet == setNum
                val onColor =
                    if (isActive) {
                        MaterialTheme.colorScheme.inverseSurface
                    } else {
                        MaterialTheme.colorScheme.onPrimary
                    }
                Button(
                    onClick = {
                        if (viewModel.activeSet != setNum) {
                            viewModel.changeSet(setNum)
                        }
                    },
                    enabled = isDefined,
                    border = BorderStroke(1.dp, onColor),
                    colors = ButtonDefaults.buttonColors(
                        contentColor = onColor,
                        containerColor = if (isActive) MaterialTheme.colorScheme.inversePrimary else MaterialTheme.colorScheme.primary,
                    ),
                    ) {
                    Text(setNum.toString())
                }
            }
        }
    }
}

/** CNF selector dialog. If a doll contains multiple CNF files, you can select one from the list here. */
@Composable
fun CnfSelectionDialog(
    options: List<String>,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.select_cnf)) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                options.forEach { cnfName ->
                    TextButton(
                        onClick = { onSelect(cnfName) },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(cnfName, textAlign = TextAlign.Left)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        }
    )
}

/** CNF source window */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CnfSourceWindow(viewModel: ViewModel, onBackClick: () -> Unit) {
    var fileContent by remember { mutableStateOf(viewModel.fullCnfText) }
    val dollName = viewModel.currentDoll?.name ?: ""

    LaunchedEffect(Unit) {
        fileContent = try {
            viewModel.fullCnfText
        } catch (e: Exception) {
            ViewModel.KissUiState.Error("${e.message}")
            "${e.message}"
        }
    }

    Scaffold(
        contentWindowInsets = ScaffoldDefaults.contentWindowInsets
            .exclude(WindowInsets.displayCutout),
        containerColor = MaterialTheme.colorScheme.primaryContainer,
        topBar = {
            TopAppBar(
                title = { Text(dollName) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                ),
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.back)
                        )
                    }
                }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.surfaceContainer)
                .padding(paddingValues)
                .verticalScroll(rememberScrollState())
        ) {
            SelectionContainer { // Make text selectable
                Text(text = fileContent)
            }
        }
    }
}


/** Function to save doll screenshots. They get saved in a special folder; this is hardcoded. The filenames are hardcoded as well, containing the current date and time. */
fun saveScreenshot(context: Context, bitmap: Bitmap, fileName: String) {
    val contentResolver = context.contentResolver
    val imageDetails = ContentValues().apply {
        put(MediaStore.Images.Media.DISPLAY_NAME, "$fileName.png")
        put(MediaStore.Images.Media.MIME_TYPE, "image/png")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/KiSS_Screenshots")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
    }
    val imageUri =
        contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, imageDetails)

    imageUri?.let { uri ->
        contentResolver.openOutputStream(uri)?.use { outputStream ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, outputStream)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            imageDetails.clear()
            imageDetails.put(MediaStore.Images.Media.IS_PENDING, 0)
            contentResolver.update(uri, imageDetails, null, null)
        }
    }
}