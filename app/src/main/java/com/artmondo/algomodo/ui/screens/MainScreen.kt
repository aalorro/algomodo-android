package com.artmondo.algomodo.ui.screens

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.artmondo.algomodo.audio.AudioPlayer
import com.artmondo.algomodo.generators.AspectRatio
import com.artmondo.algomodo.core.registry.GeneratorRegistry
import com.artmondo.algomodo.ui.components.*
import com.artmondo.algomodo.ui.dialogs.*
import com.artmondo.algomodo.viewmodel.ExportViewModel
import com.artmondo.algomodo.viewmodel.MainViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.input.pointer.pointerInput
import kotlin.math.abs
import kotlin.math.roundToInt
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.ui.graphics.lerp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    viewModel: MainViewModel = hiltViewModel(),
    exportViewModel: ExportViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val exportState by exportViewModel.state.collectAsStateWithLifecycle()
    val presets by viewModel.presets.collectAsStateWithLifecycle(initialValue = emptyList())
    val canUndo by viewModel.canUndo.collectAsStateWithLifecycle()
    val canRedo by viewModel.canRedo.collectAsStateWithLifecycle()

    // Merge sourceImage and audioAnalysis into params so generators receive them
    val renderParams = buildMap<String, Any> {
        putAll(state.params)
        state.sourceImage?.let { put("_sourceImage", it) }
        state.audioAnalysis?.let { put("_audioAnalysis", it) }
    }

    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // Back button exit confirmation
    var showExitDialog by remember { mutableStateOf(false) }
    val activity = context as? android.app.Activity
    BackHandler { showExitDialog = true }

    if (showExitDialog) {
        AlertDialog(
            onDismissRequest = { showExitDialog = false },
            title = { Text("Exit Algomodo?") },
            confirmButton = {
                TextButton(onClick = { activity?.finish() }) {
                    Text("Exit")
                }
            },
            dismissButton = {
                TextButton(onClick = { showExitDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    // Dialogs
    var showAbout by remember { mutableStateOf(false) }
    var showInstructions by remember { mutableStateOf(false) }
    var showChangelog by remember { mutableStateOf(false) }
    var showPrivacy by remember { mutableStateOf(false) }
    var showDonation by remember { mutableStateOf(false) }
    var showUseCases by remember { mutableStateOf(false) }
    var showReportBug by remember { mutableStateOf(false) }
    var showOriginalImage by remember { mutableStateOf(false) }
    var showCustomPaletteDialog by remember { mutableStateOf(false) }
    var isCanvasExpanded by remember { mutableStateOf(false) }
    var showPresetSavedBubble by remember { mutableStateOf(false) }

    // Auto-dismiss preset saved bubble after 5 seconds
    LaunchedEffect(showPresetSavedBubble) {
        if (showPresetSavedBubble) {
            delay(5000)
            showPresetSavedBubble = false
        }
    }

    // Image picker
    val imagePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        uri?.let { loadBitmapFromUri(context, it) { bitmap -> viewModel.setSourceImage(bitmap) } }
    }

    // Audio player — lives at MainScreen level so it persists across tab switches
    val audioPlayer = remember { AudioPlayer(context) }
    var isAudioPlaying by remember { mutableStateOf(false) }
    var audioSliderPosition by remember { mutableFloatStateOf(0f) }
    var isAudioSeeking by remember { mutableStateOf(false) }

    // Poll playback position
    LaunchedEffect(isAudioPlaying) {
        while (isAudioPlaying) {
            if (!isAudioSeeking) {
                val pos = audioPlayer.currentPositionMs
                val dur = audioPlayer.durationMs
                if (dur > 0) audioSliderPosition = pos.toFloat() / dur
            }
            delay(100)
        }
    }

    // Cleanup on dispose
    DisposableEffect(Unit) {
        onDispose { audioPlayer.release() }
    }

    // Audio file picker — loads player directly, then starts analysis
    val audioPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        uri?.let {
            val loaded = audioPlayer.load(it)
            if (loaded) {
                val durSec = audioPlayer.durationMs.toFloat() / 1000f
                if (durSec > 0f) viewModel.setAudioDuration(durSec)
            }
            viewModel.loadAudio(context, it)
        }
    }

    // Recipe file picker (import)
    val recipeImportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        uri?.let {
            try {
                val inputStream = context.contentResolver.openInputStream(it)
                val json = inputStream?.bufferedReader()?.readText() ?: return@let
                inputStream.close()
                val success = viewModel.importRecipe(json)
                if (!success) {
                    android.util.Log.e("Algomodo", "Failed to parse recipe JSON")
                }
            } catch (e: Exception) {
                android.util.Log.e("Algomodo", "Failed to read recipe file", e)
            }
        }
    }

    // Presets file picker (import)
    val presetsImportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        uri?.let {
            try {
                val inputStream = context.contentResolver.openInputStream(it)
                val json = inputStream?.bufferedReader()?.readText() ?: return@let
                inputStream.close()
                viewModel.importPresetsText(json)
            } catch (e: Exception) {
                android.util.Log.e("Algomodo", "Failed to read presets file", e)
            }
        }
    }

    // Pager for tabs
    val pagerState = rememberPagerState(pageCount = { 4 })

    LaunchedEffect(state.activeTab) {
        if (pagerState.currentPage != state.activeTab) {
            pagerState.scrollToPage(state.activeTab)
        }
    }

    LaunchedEffect(pagerState.currentPage) {
        viewModel.setActiveTab(pagerState.currentPage)
    }

    // Info menu state
    var showInfoMenu by remember { mutableStateOf(false) }

    Box(modifier = Modifier.fillMaxSize()) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        // Canvas gesture modifier
        val canvasGestureModifier = Modifier.pointerInput(state.interactionEnabled) {
            if (!state.interactionEnabled) return@pointerInput
            val swipeThreshold = 50.dp.toPx()
            awaitEachGesture {
                val down = awaitFirstDown()
                val startPos = down.position
                var lastPos = startPos
                do {
                    val event = awaitPointerEvent()
                    val change = event.changes.firstOrNull { it.id == down.id }
                    if (change != null) lastPos = change.position
                } while (change?.pressed == true)
                val drag = lastPos - startPos
                val distance = drag.getDistance()
                if (distance < viewConfiguration.touchSlop * 2) {
                    isCanvasExpanded = !isCanvasExpanded
                } else if (distance > swipeThreshold) {
                    if (abs(drag.x) > abs(drag.y)) {
                        if (drag.x > 0) viewModel.randomize() else viewModel.undo()
                    } else {
                        if (drag.y < 0) viewModel.surpriseMe() else viewModel.undo()
                    }
                }
            }
        }

        // ===== TOP SECTION: Canvas + Palette =====
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .then(if (!isCanvasExpanded) Modifier.weight(0.9f) else Modifier)
        ) {
            // Canvas with info button overlay
            Box(
                modifier = (if (isCanvasExpanded) Modifier.fillMaxSize()
                    else Modifier.fillMaxHeight())
                    .then(canvasGestureModifier),
                contentAlignment = Alignment.Center
            ) {
                val ratio = state.aspectRatio.asFloat()
                val canvasModifier = remember(ratio) {
                    Modifier.layout { measurable, constraints ->
                        val maxW = constraints.maxWidth
                        val maxH = constraints.maxHeight
                        val (w, h) = if (maxW.toFloat() / maxH > ratio) {
                            val targetW = (maxH * ratio).roundToInt().coerceIn(0, maxW)
                            targetW to maxH
                        } else {
                            val targetH = (maxW / ratio).roundToInt().coerceIn(0, maxH)
                            maxW to targetH
                        }
                        val placeable = measurable.measure(Constraints.fixed(w, h))
                        layout(w, h) { placeable.place(0, 0) }
                    }
                }

                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    if (showOriginalImage && state.sourceImage != null) {
                        Image(
                            bitmap = state.sourceImage!!.asImageBitmap(),
                            contentDescription = "Original source image",
                            modifier = canvasModifier.background(Color.Black),
                            contentScale = ContentScale.Fit
                        )
                    } else {
                        AlgoCanvas(
                            generator = state.generator,
                            params = renderParams,
                            seed = state.seed,
                            palette = state.palette,
                            quality = state.quality,
                            aspectRatio = state.aspectRatio,
                            postFX = state.postFX,
                            isAnimating = state.isAnimating,
                            animationFps = state.animationFps,
                            showFps = state.showFps,
                            renderTrigger = state.renderTrigger,
                            onPauseTimeCapture = { viewModel.setSnapshotTime(it) },
                            modifier = canvasModifier
                        )
                    }

                }

                // Info button — top-left on canvas
                Box(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(6.dp)
                ) {
                    IconButton(
                        onClick = { showInfoMenu = !showInfoMenu },
                        modifier = Modifier
                            .size(42.dp)
                            .background(
                                Color(0x660091EA),
                                CircleShape
                            )
                    ) {
                        Icon(
                            Icons.Filled.Info,
                            contentDescription = "Info",
                            modifier = Modifier.size(23.dp),
                            tint = Color.White
                        )
                    }
                    DropdownMenu(
                        expanded = showInfoMenu,
                        onDismissRequest = { showInfoMenu = false },
                        offset = DpOffset(0.dp, 4.dp)
                    ) {
                        DropdownMenuItem(
                            text = { Text("About") },
                            leadingIcon = { Icon(Icons.Filled.Info, contentDescription = null, modifier = Modifier.size(18.dp)) },
                            onClick = { showInfoMenu = false; showAbout = true }
                        )
                        DropdownMenuItem(
                            text = { Text("Use Cases") },
                            leadingIcon = { Icon(Icons.Filled.Lightbulb, contentDescription = null, modifier = Modifier.size(18.dp)) },
                            onClick = { showInfoMenu = false; showUseCases = true }
                        )
                        DropdownMenuItem(
                            text = { Text("Help") },
                            leadingIcon = { Icon(Icons.Filled.HelpOutline, contentDescription = null, modifier = Modifier.size(18.dp)) },
                            onClick = { showInfoMenu = false; showInstructions = true }
                        )
                        DropdownMenuItem(
                            text = { Text("Changelog") },
                            leadingIcon = { Icon(Icons.Filled.NewReleases, contentDescription = null, modifier = Modifier.size(18.dp)) },
                            onClick = { showInfoMenu = false; showChangelog = true }
                        )
                        DropdownMenuItem(
                            text = { Text("Privacy") },
                            leadingIcon = { Icon(Icons.Filled.PrivacyTip, contentDescription = null, modifier = Modifier.size(18.dp)) },
                            onClick = { showInfoMenu = false; showPrivacy = true }
                        )
                        DropdownMenuItem(
                            text = { Text("Report Bug") },
                            leadingIcon = { Icon(Icons.Filled.BugReport, contentDescription = null, modifier = Modifier.size(18.dp)) },
                            onClick = { showInfoMenu = false; showReportBug = true }
                        )
                    }
                }

                // Expanded: generator label + translucent button overlay on canvas
                if (isCanvasExpanded) {
                    val familyDisplayName = remember(state.generator?.family) {
                        GeneratorRegistry.allFamilies()
                            .find { it.id == state.generator?.family }?.displayName
                            ?: state.generator?.family?.replaceFirstChar { it.uppercase() }
                            ?: ""
                    }
                    Text(
                        text = "$familyDisplayName  \u00BB  ${state.generator?.styleName ?: ""}",
                        color = Color.White,
                        fontSize = 13.sp,
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .padding(top = 6.dp)
                            .background(Color(0x88000000), RoundedCornerShape(4.dp))
                            .padding(horizontal = 10.dp, vertical = 4.dp)
                    )
                    Column(
                        modifier = Modifier.align(Alignment.BottomCenter),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        HorizontalPaletteStrip(
                            selectedPalette = state.palette,
                            onSelectPalette = { viewModel.setPalette(it) },
                            isLocked = "palette" in state.lockedParams,
                            onToggleLock = { viewModel.toggleParamLock("palette") },
                            customPalettes = state.customPalettes,
                            onAddCustomPalette = { showCustomPaletteDialog = true },
                            onDeleteCustomPalette = { viewModel.deleteCustomPalette(it) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.5f))
                        )
                        ActionButtonsRow(
                            state = state,
                            canUndo = canUndo,
                            canRedo = canRedo,
                            viewModel = viewModel,
                            exportViewModel = exportViewModel,
                            context = context,
                            renderParams = renderParams,
                            audioPlayer = audioPlayer,
                            isAudioPlaying = isAudioPlaying,
                            onAudioPlayingChange = { isAudioPlaying = it },
                            translucent = true,
                            onPresetSaved = { showPresetSavedBubble = true },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }

            // Vertical palette strip — visible when not expanded
            if (!isCanvasExpanded) {
                VerticalPaletteSelector(
                    selectedPalette = state.palette,
                    onSelectPalette = { viewModel.setPalette(it) },
                    isLocked = "palette" in state.lockedParams,
                    onToggleLock = { viewModel.toggleParamLock("palette") },
                    customPalettes = state.customPalettes,
                    onAddCustomPalette = { showCustomPaletteDialog = true },
                    onDeleteCustomPalette = { viewModel.deleteCustomPalette(it) },
                    modifier = Modifier
                        .fillMaxHeight()
                        .weight(1f)
                        .padding(start = 20.dp)
                )
            }
        }

        // ===== ACTION BUTTONS =====
        if (!isCanvasExpanded) {
            ActionButtonsRow(
                state = state,
                canUndo = canUndo,
                canRedo = canRedo,
                viewModel = viewModel,
                exportViewModel = exportViewModel,
                context = context,
                renderParams = renderParams,
                audioPlayer = audioPlayer,
                isAudioPlaying = isAudioPlaying,
                onAudioPlayingChange = { isAudioPlaying = it },
                translucent = false,
                onPresetSaved = { showPresetSavedBubble = true }
            )
        }

        // ===== SEARCH + SEED ROW =====
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Generator search
            var searchQuery by remember { mutableStateOf("") }
            var searchExpanded by remember { mutableStateOf(false) }
            val allGenerators = remember { GeneratorRegistry.allGenerators() }
            val filteredGenerators = remember(searchQuery) {
                if (searchQuery.length < 1) emptyList()
                else allGenerators.filter {
                    it.styleName.contains(searchQuery, ignoreCase = true) ||
                    it.family.contains(searchQuery, ignoreCase = true)
                }.take(8)
            }

            val keyboardController = LocalSoftwareKeyboardController.current
            Box(modifier = Modifier.weight(1f)) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(32.dp)
                        .border(1.dp, MaterialTheme.colorScheme.onSurfaceVariant, RoundedCornerShape(4.dp))
                        .padding(horizontal = 6.dp),
                    contentAlignment = Alignment.CenterStart
                ) {
                    if (searchQuery.isEmpty()) {
                        Text(
                            "Search generators...",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                        )
                    }
                    BasicTextField(
                        value = searchQuery,
                        onValueChange = {
                            searchQuery = it
                            searchExpanded = it.isNotEmpty()
                        },
                        singleLine = true,
                        textStyle = TextStyle(
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurface
                        ),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = {
                            keyboardController?.hide()
                            searchExpanded = false
                            searchQuery = ""
                        }),
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                if (searchExpanded && filteredGenerators.isNotEmpty()) {
                    Surface(
                        modifier = Modifier
                            .padding(top = 34.dp)
                            .widthIn(max = 250.dp),
                        shape = RoundedCornerShape(4.dp),
                        shadowElevation = 4.dp,
                        color = MaterialTheme.colorScheme.surface,
                        tonalElevation = 3.dp
                    ) {
                        Column {
                            filteredGenerators.forEach { generator ->
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            viewModel.selectGenerator(generator)
                                            searchQuery = ""
                                            searchExpanded = false
                                            keyboardController?.hide()
                                        }
                                        .padding(horizontal = 12.dp, vertical = 8.dp)
                                ) {
                                    Column {
                                        Text(generator.styleName, fontSize = 13.sp)
                                        Text(
                                            generator.family,
                                            fontSize = 10.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // Seed control
            SeedControl(
                seed = state.seed,
                isLocked = state.seedLocked,
                onSeedChange = { viewModel.setSeed(it) },
                onToggleLock = { viewModel.setSeedLocked(it) },
                modifier = Modifier.weight(1f)
            )
        }

        // Source image button (for image family)
        if (state.generator?.family == "image") {
            val needsImage = state.sourceImage == null
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (needsImage) {
                    val flashTransition = rememberInfiniteTransition(label = "loadFlash")
                    val flashAlpha by flashTransition.animateFloat(
                        initialValue = 0.4f,
                        targetValue = 1f,
                        animationSpec = infiniteRepeatable(
                            animation = tween(600, easing = LinearEasing),
                            repeatMode = RepeatMode.Reverse
                        ),
                        label = "flashAlpha"
                    )
                    val neonGreen = Color(0xFF39FF14)
                    Button(
                        onClick = {
                            imagePickerLauncher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                        },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(containerColor = Color.Transparent)
                    ) {
                        Text(
                            "LOAD IMAGE",
                            fontSize = 13.sp,
                            fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                            color = neonGreen.copy(alpha = flashAlpha)
                        )
                    }
                } else {
                    OutlinedButton(
                        onClick = {
                            imagePickerLauncher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                        },
                        modifier = Modifier.weight(1f)
                    ) { Text("Load Image", fontSize = 12.sp) }
                }
                if (state.sourceImage != null) {
                    OutlinedButton(
                        onClick = { showOriginalImage = !showOriginalImage },
                        modifier = Modifier.weight(1f)
                    ) { Text(if (showOriginalImage) "Result" else "Source", fontSize = 12.sp, maxLines = 1) }
                    OutlinedButton(
                        onClick = {
                            showOriginalImage = false
                            viewModel.setSourceImage(null)
                        },
                        modifier = Modifier.weight(1f)
                    ) { Text("Clear", fontSize = 12.sp) }
                }
            }
        }

        // ===== BOTTOM SECTION: Tabs + Content (~55% of screen) =====

        // Tab bar
        TabRow(
            selectedTabIndex = pagerState.currentPage,
            containerColor = MaterialTheme.colorScheme.surface
        ) {
            Tab(selected = pagerState.currentPage == 0, onClick = { scope.launch { pagerState.scrollToPage(0) } }) {
                Text("Generators", modifier = Modifier.padding(vertical = 10.dp), fontSize = 12.sp)
            }
            Tab(selected = pagerState.currentPage == 1, onClick = { scope.launch { pagerState.scrollToPage(1) } }) {
                Text("Params", modifier = Modifier.padding(vertical = 10.dp), fontSize = 12.sp)
            }
            Tab(selected = pagerState.currentPage == 2, onClick = { scope.launch { pagerState.scrollToPage(2) } }) {
                Text("Export", modifier = Modifier.padding(vertical = 10.dp), fontSize = 12.sp)
            }
            Tab(selected = pagerState.currentPage == 3, onClick = { scope.launch { pagerState.scrollToPage(3) } }) {
                Text("Settings", modifier = Modifier.padding(vertical = 10.dp), fontSize = 12.sp)
            }
        }

        // Tab content (takes remaining space) + floating presets overlay
        var presetsExpanded by remember { mutableStateOf(false) }

        Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
            HorizontalPager(
                state = pagerState,
                beyondViewportPageCount = 0,
                userScrollEnabled = false,
                modifier = Modifier.fillMaxSize()
            ) { page ->
                when (page) {
                    0 -> GeneratorPicker(
                        selectedGeneratorId = state.generator?.id,
                        selectedFamilyId = state.familyId,
                        onSelectGenerator = { viewModel.selectGenerator(it) },
                        onSelectFamily = { viewModel.selectFamily(it) }
                    )
                    1 -> {
                        ParameterControls(
                            generator = state.generator,
                            params = state.params,
                            lockedParams = state.lockedParams,
                            onParamChange = { key, value -> viewModel.updateParam(key, value) },
                            onToggleLock = { viewModel.toggleParamLock(it) },
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                2 -> Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                ) {
                    ExportPanel(
                        exportState = exportState,
                        isAnimating = state.isAnimating,
                        supportsVector = state.generator?.supportsVector == true,
                        isAudioLoaded = state.isAudioLoaded,
                        audioFileName = state.audioFileName,
                        audioDurationSec = state.audioDurationSec,
                        isAudioPlaying = isAudioPlaying,
                        audioSliderPosition = audioSliderPosition,
                        onLoadAudio = { audioPickerLauncher.launch("audio/*") },
                        onClearAudio = {
                            audioPlayer.release()
                            isAudioPlaying = false
                            audioSliderPosition = 0f
                            viewModel.clearAudio()
                        },
                        onAudioPlayPause = {
                            if (isAudioPlaying) {
                                audioPlayer.pause()
                                isAudioPlaying = false
                                viewModel.setAnimating(false)
                            } else {
                                audioPlayer.play()
                                isAudioPlaying = true
                                viewModel.setAnimating(true)
                            }
                        },
                        onAudioSeek = { pos ->
                            audioSliderPosition = pos
                        },
                        onAudioSeekFinished = {
                            val dur = audioPlayer.durationMs
                            if (dur > 0) audioPlayer.seekTo((audioSliderPosition * dur).toInt())
                            isAudioSeeking = false
                        },
                        onAudioSeekStarted = {
                            isAudioSeeking = true
                        },
                        onImageResolutionChange = { exportViewModel.setImageResolution(it) },
                        onExportPng = {
                            val gen = state.generator ?: return@ExportPanel
                            val ar = state.aspectRatio
                            val res = exportState.imageResolution
                            exportViewModel.exportPng(context, gen, renderParams, state.seed, state.palette, state.quality, state.postFX, ar.width(res), ar.height(res), state.snapshotTime)
                        },
                        onExportJpg = {
                            val gen = state.generator ?: return@ExportPanel
                            val ar = state.aspectRatio
                            val res = exportState.imageResolution
                            exportViewModel.exportJpg(context, gen, renderParams, state.seed, state.palette, state.quality, state.postFX, ar.width(res), ar.height(res), state.snapshotTime)
                        },
                        onExportSvg = {
                            val gen = state.generator ?: return@ExportPanel
                            val ar = state.aspectRatio
                            exportViewModel.exportSvg(context, gen, renderParams, state.seed, state.palette, ar.exportWidth(), ar.exportHeight())
                        },
                        onExportGif = {
                            val gen = state.generator ?: return@ExportPanel
                            exportViewModel.exportGif(context, gen, renderParams, state.seed, state.palette, state.quality, state.animationFps, state.aspectRatio)
                        },
                        onExportVideo = {
                            val gen = state.generator ?: return@ExportPanel
                            exportViewModel.exportVideo(context, gen, renderParams, state.seed, state.palette, state.quality, state.animationFps, aspectRatio = state.aspectRatio, audioUri = state.audioUri)
                        },
                        onExportRecipe = { fileName ->
                            val json = viewModel.exportRecipeJson()
                            shareText(context, json, fileName)
                        },
                        onImportRecipe = {
                            recipeImportLauncher.launch("application/json")
                        },
                        onExportPresets = {
                            viewModel.exportPresetsText { text ->
                                shareText(context, text, "algomodo-presets.txt")
                            }
                        },
                        onImportPresets = {
                            presetsImportLauncher.launch("text/plain")
                        },
                        onGifDurationChange = { exportViewModel.setGifDuration(it) },
                        onGifResolutionChange = { exportViewModel.setGifResolution(it) },
                        onGifBoomerangChange = { exportViewModel.setGifBoomerang(it) },
                        onGifEndlessChange = { exportViewModel.setGifEndless(it) },
                        onVideoStartChange = { exportViewModel.setVideoStartSec(it) },
                        onVideoEndChange = { exportViewModel.setVideoEndSec(it) },
                        generatorStyleName = state.generator?.styleName ?: ""
                    )
                }
                3 -> Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                ) {
                    SettingsPanel(
                        theme = state.theme,
                        quality = state.quality,
                        aspectRatio = state.aspectRatio,
                        performanceMode = state.performanceMode,
                        showFps = state.showFps,
                        interactionEnabled = state.interactionEnabled,
                        animationFps = state.animationFps,
                        postFX = state.postFX,
                        onThemeChange = { viewModel.setTheme(it) },
                        onQualityChange = { viewModel.setQuality(it) },
                        onAspectRatioChange = { viewModel.setAspectRatio(it) },
                        onPerformanceModeChange = { viewModel.setPerformanceMode(it) },
                        onShowFpsChange = { viewModel.setShowFps(it) },
                        onInteractionChange = { viewModel.setInteractionEnabled(it) },
                        onAnimationFpsChange = { viewModel.setAnimationFps(it) },
                        onPostFXChange = { viewModel.setPostFX(it) }
                    )
                }
            }
            }

            // Floating presets button — bottom-left, visible on all tabs
            SmallFloatingActionButton(
                onClick = { presetsExpanded = !presetsExpanded },
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(12.dp),
                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.85f),
                contentColor = MaterialTheme.colorScheme.onSurfaceVariant
            ) {
                BadgedBox(
                    badge = {
                        if (presets.isNotEmpty()) {
                            Badge(
                                containerColor = MaterialTheme.colorScheme.primary,
                                contentColor = MaterialTheme.colorScheme.onPrimary
                            ) {
                                Text("${presets.size}", fontSize = 9.sp)
                            }
                        }
                    }
                ) {
                    Icon(
                        if (presetsExpanded) Icons.Filled.KeyboardArrowDown else Icons.Filled.Star,
                        contentDescription = "Toggle presets",
                        modifier = Modifier.size(20.dp)
                    )
                }
            }

            // Collapsible presets overlay — slides up from bottom
            androidx.compose.animation.AnimatedVisibility(
                visible = presetsExpanded,
                enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
                exit = slideOutVertically(targetOffsetY = { it }) + fadeOut(),
                modifier = Modifier.align(Alignment.BottomCenter)
            ) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.95f),
                    tonalElevation = 6.dp,
                    shadowElevation = 8.dp
                ) {
                    PresetsPanel(
                        presets = presets,
                        onSavePreset = { viewModel.savePreset(it) },
                        onLoadPreset = { viewModel.loadPreset(it) },
                        onDeletePreset = { viewModel.deletePreset(it) },
                        generatorStyleName = state.generator?.styleName ?: ""
                    )
                }
            }
        } // end Box
    } // end Column

    // Dialogs
    if (showAbout) AboutDialog { showAbout = false }
    if (showInstructions) InstructionsDialog { showInstructions = false }
    if (showChangelog) ChangelogDialog { showChangelog = false }
    if (showPrivacy) PrivacyDialog { showPrivacy = false }
    if (showDonation) DonationDialog { showDonation = false }
    if (showUseCases) UseCasesDialog { showUseCases = false }
    if (showReportBug) ReportBugDialog { showReportBug = false }
    if (showCustomPaletteDialog) {
        CustomPaletteDialog(
            existingPalettes = state.customPalettes,
            onDismiss = { showCustomPaletteDialog = false },
            onSave = { palette ->
                viewModel.addCustomPalette(palette)
                viewModel.setPalette(palette)
                showCustomPaletteDialog = false
            }
        )
    }

    // Preset saved bubble overlay
    AnimatedVisibility(
        visible = showPresetSavedBubble,
        enter = fadeIn(),
        exit = fadeOut(),
        modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 90.dp)
    ) {
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.inverseSurface,
            tonalElevation = 4.dp,
            shadowElevation = 4.dp
        ) {
            Text(
                text = "Preset saved! Find it in the Params tab.",
                color = MaterialTheme.colorScheme.inverseOnSurface,
                fontSize = 13.sp,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp)
            )
        }
    }
    } // end Box

    // Show share sheet after export
    exportState.lastExportUri?.let { uri ->
        LaunchedEffect(uri) {
            shareFile(context, uri)
            exportViewModel.clearLastExport()
        }
    }
}

@Composable
private fun ShinyCanvasButton(onClick: () -> Unit) {
    val infiniteTransition = rememberInfiniteTransition(label = "shine")
    val shimmerProgress by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(2400, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "shimmer"
    )

    // Sweep a highlight through the icon tint
    val gold = Color(0xFFFFD700)
    val white = Color(0xFFFFFFFF)
    val base = MaterialTheme.colorScheme.primary
    val tint = when {
        shimmerProgress < 0.3f -> {
            val t = shimmerProgress / 0.3f
            lerp(base, gold, t)
        }
        shimmerProgress < 0.5f -> {
            val t = (shimmerProgress - 0.3f) / 0.2f
            lerp(gold, white, t)
        }
        shimmerProgress < 0.7f -> {
            val t = (shimmerProgress - 0.5f) / 0.2f
            lerp(white, gold, t)
        }
        else -> {
            val t = (shimmerProgress - 0.7f) / 0.3f
            lerp(gold, base, t)
        }
    }

    // Glow alpha pulses with the shimmer
    val glowAlpha = if (shimmerProgress in 0.25f..0.75f)
        ((1f - abs(shimmerProgress - 0.5f) * 4f).coerceIn(0f, 0.4f))
    else 0f

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        IconButton(onClick = onClick, modifier = Modifier.size(44.dp)) {
            Box(contentAlignment = Alignment.Center) {
                // Glow halo behind icon
                if (glowAlpha > 0f) {
                    Icon(
                        Icons.Filled.AutoAwesome, contentDescription = null,
                        modifier = Modifier.size(36.dp),
                        tint = gold.copy(alpha = glowAlpha)
                    )
                }
                Icon(
                    Icons.Filled.AutoAwesome, contentDescription = "Surprise",
                    modifier = Modifier.size(28.dp),
                    tint = tint
                )
            }
        }
        Text("Surprise", fontSize = 8.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ActionButtonsRow(
    state: com.artmondo.algomodo.viewmodel.MainUiState,
    canUndo: Boolean,
    canRedo: Boolean,
    viewModel: MainViewModel,
    exportViewModel: ExportViewModel,
    context: Context,
    renderParams: Map<String, Any>,
    audioPlayer: AudioPlayer,
    isAudioPlaying: Boolean,
    onAudioPlayingChange: (Boolean) -> Unit,
    translucent: Boolean,
    onPresetSaved: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(
                if (translucent) MaterialTheme.colorScheme.surface.copy(alpha = 0.6f)
                else MaterialTheme.colorScheme.surface
            )
            .padding(horizontal = 4.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically
    ) {
        CanvasButton(Icons.Filled.Undo, "Undo", enabled = canUndo) { viewModel.undo() }

        val playTooltipState = rememberTooltipState(isPersistent = true)
        var playTooltipShown by remember { mutableStateOf(false) }
        LaunchedEffect(Unit) {
            if (!playTooltipShown) {
                delay(800)
                playTooltipShown = true
                playTooltipState.show()
                delay(8000)
                playTooltipState.dismiss()
            }
        }
        TooltipBox(
            positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
            tooltip = { PlainTooltip { Text("Press play to animate") } },
            state = playTooltipState
        ) {
            CanvasButton(
                if (state.isAnimating) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                if (state.isAnimating) "Pause" else "Play",
                enabled = state.generator?.supportsAnimation == true
            ) {
                viewModel.toggleAnimation()
                if (state.isAudioLoaded) {
                    if (!state.isAnimating) {
                        audioPlayer.play()
                        onAudioPlayingChange(true)
                    } else {
                        audioPlayer.pause()
                        onAudioPlayingChange(false)
                    }
                }
            }
        }
        CanvasButton(Icons.Filled.Casino, "Rand") { viewModel.randomize() }
        CanvasButton(Icons.Filled.Redo, "Redo", enabled = canRedo) { viewModel.redo() }
        ShinyCanvasButton { viewModel.surpriseMe() }
        CanvasButton(Icons.Filled.Refresh, "Reload") { viewModel.reload() }
        CanvasButton(Icons.Filled.Clear, "Clear") { viewModel.clearCanvas() }
        Box {
            var showSaveMenu by remember { mutableStateOf(false) }
            CanvasButton(Icons.Filled.Save, "Save") { showSaveMenu = true }
            DropdownMenu(
                expanded = showSaveMenu,
                onDismissRequest = { showSaveMenu = false }
            ) {
                DropdownMenuItem(
                    text = { Text("Save as PNG") },
                    leadingIcon = { Icon(Icons.Filled.Image, contentDescription = null, modifier = Modifier.size(20.dp)) },
                    onClick = {
                        showSaveMenu = false
                        state.generator?.let { gen ->
                            exportViewModel.quickSave(
                                context, gen, renderParams, state.seed, state.palette,
                                state.quality, state.postFX, state.isAnimating,
                                aspectRatio = state.aspectRatio,
                                snapshotTime = state.snapshotTime
                            )
                        }
                    }
                )
                DropdownMenuItem(
                    text = { Text("Save as Preset") },
                    leadingIcon = { Icon(Icons.Filled.Bookmark, contentDescription = null, modifier = Modifier.size(20.dp)) },
                    onClick = {
                        showSaveMenu = false
                        val gen = state.generator ?: return@DropdownMenuItem
                        val timestamp = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US)
                            .format(java.util.Date())
                        viewModel.savePreset("${gen.styleName} $timestamp")
                        onPresetSaved()
                    }
                )
            }
        }
    }
}

@Composable
private fun CanvasButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        IconButton(onClick = onClick, enabled = enabled, modifier = Modifier.size(44.dp)) {
            Icon(
                icon, contentDescription = label,
                modifier = Modifier.size(28.dp),
                tint = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)
            )
        }
        Text(label, fontSize = 8.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private fun loadBitmapFromUri(context: Context, uri: Uri, onLoaded: (android.graphics.Bitmap) -> Unit) {
    try {
        val inputStream = context.contentResolver.openInputStream(uri) ?: return
        var bitmap = android.graphics.BitmapFactory.decodeStream(inputStream)
        inputStream.close()
        if (bitmap != null) {
            // Scale down if too large (cropping is handled by ViewModel)
            val maxSize = 2048
            if (bitmap.width > maxSize || bitmap.height > maxSize) {
                val scale = maxSize.toFloat() / maxOf(bitmap.width, bitmap.height)
                val scaled = android.graphics.Bitmap.createScaledBitmap(
                    bitmap, (bitmap.width * scale).toInt(), (bitmap.height * scale).toInt(), true
                )
                bitmap.recycle()
                onLoaded(scaled)
            } else {
                onLoaded(bitmap)
            }
        }
    } catch (e: Exception) {
        android.util.Log.e("Algomodo", "Failed to load image from URI: $uri", e)
    }
}

private fun shareText(context: Context, text: String, fileName: String) {
    val mimeType = if (fileName.endsWith(".json")) "application/json" else "text/plain"
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = mimeType
        putExtra(Intent.EXTRA_TEXT, text)
        putExtra(Intent.EXTRA_SUBJECT, fileName)
    }
    context.startActivity(Intent.createChooser(intent, "Share"))
}

private fun shareFile(context: Context, uri: Uri) {
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "*/*"
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(intent, "Share"))
}
