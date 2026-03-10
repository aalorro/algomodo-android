package com.artmondo.algomodo.ui.screens

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.artmondo.algomodo.core.registry.GeneratorRegistry
import com.artmondo.algomodo.ui.components.*
import com.artmondo.algomodo.ui.dialogs.*
import com.artmondo.algomodo.viewmodel.ExportViewModel
import com.artmondo.algomodo.viewmodel.MainViewModel
import kotlinx.coroutines.launch
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.input.pointer.pointerInput
import kotlin.math.abs

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

    // Merge sourceImage into params so image-family generators receive it
    val renderParams = if (state.sourceImage != null)
        state.params + ("_sourceImage" to state.sourceImage!!)
    else state.params

    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // Dialogs
    var showAbout by remember { mutableStateOf(false) }
    var showInstructions by remember { mutableStateOf(false) }
    var showChangelog by remember { mutableStateOf(false) }
    var showPrivacy by remember { mutableStateOf(false) }
    var showDonation by remember { mutableStateOf(false) }
    var showUseCases by remember { mutableStateOf(false) }
    var showReportBug by remember { mutableStateOf(false) }
    var showOriginalImage by remember { mutableStateOf(false) }
    var isCanvasExpanded by remember { mutableStateOf(false) }

    // Image picker
    val imagePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        uri?.let { loadBitmapFromUri(context, it) { bitmap -> viewModel.setSourceImage(bitmap) } }
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
            // Canvas (square) with info button overlay
            Box(
                modifier = (if (isCanvasExpanded) Modifier.fillMaxWidth() else Modifier.fillMaxHeight())
                    .then(canvasGestureModifier)
            ) {
                val canvasModifier = if (isCanvasExpanded)
                    Modifier.fillMaxWidth().aspectRatio(1f)
                else
                    Modifier.fillMaxHeight().aspectRatio(1f)

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
                        postFX = state.postFX,
                        isAnimating = state.isAnimating,
                        animationFps = state.animationFps,
                        showFps = state.showFps,
                        renderTrigger = state.renderTrigger,
                        modifier = canvasModifier
                    )
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
            }

            // Vertical palette strip — hidden when canvas is expanded
            if (!isCanvasExpanded) {
                VerticalPaletteSelector(
                    selectedPalette = state.palette,
                    onSelectPalette = { viewModel.setPalette(it) },
                    modifier = Modifier
                        .fillMaxHeight()
                        .weight(1f)
                        .padding(start = 20.dp)
                )
            }
        }

        // ===== ACTION BUTTONS =====
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surface)
                .padding(horizontal = 4.dp, vertical = 2.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            CanvasButton(Icons.Filled.Undo, "Undo", enabled = canUndo) { viewModel.undo() }
            CanvasButton(
                if (state.isAnimating) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                if (state.isAnimating) "Pause" else "Play",
                enabled = state.generator?.supportsAnimation == true
            ) { viewModel.toggleAnimation() }
            CanvasButton(Icons.Filled.Casino, "Rand") { viewModel.randomize() }
            CanvasButton(Icons.Filled.Redo, "Redo", enabled = canRedo) { viewModel.redo() }
            CanvasButton(Icons.Filled.AutoAwesome, "Surprise") { viewModel.surpriseMe() }
            CanvasButton(Icons.Filled.Refresh, "Reload") { viewModel.reload() }
            CanvasButton(Icons.Filled.Clear, "Clear") { viewModel.clearCanvas() }
            CanvasButton(Icons.Filled.Save, "Save") {
                state.generator?.let { gen ->
                    exportViewModel.quickSave(
                        context, gen, renderParams, state.seed, state.palette,
                        state.quality, state.postFX, state.isAnimating
                    )
                }
            }
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
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                DropdownMenu(
                    expanded = searchExpanded && filteredGenerators.isNotEmpty(),
                    onDismissRequest = { searchExpanded = false },
                    modifier = Modifier.widthIn(max = 250.dp)
                ) {
                    filteredGenerators.forEach { generator ->
                        DropdownMenuItem(
                            text = {
                                Column {
                                    Text(generator.styleName, fontSize = 13.sp)
                                    Text(
                                        generator.family,
                                        fontSize = 10.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            },
                            onClick = {
                                viewModel.selectGenerator(generator)
                                searchQuery = ""
                                searchExpanded = false
                            }
                        )
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
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = {
                        imagePickerLauncher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                    },
                    modifier = Modifier.weight(1f)
                ) { Text("Load", fontSize = 12.sp) }
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

        // Tab content (takes remaining space)
        HorizontalPager(
            state = pagerState,
            beyondViewportPageCount = 0,
            userScrollEnabled = false,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
        ) { page ->
            when (page) {
                0 -> GeneratorPicker(
                    selectedGeneratorId = state.generator?.id,
                    selectedFamilyId = state.familyId,
                    onSelectGenerator = { viewModel.selectGenerator(it) },
                    onSelectFamily = { viewModel.selectFamily(it) }
                )
                1 -> {
                    // No verticalScroll wrapper — ParameterControls has its own LazyColumn
                    Column(modifier = Modifier.fillMaxSize()) {
                        ParameterControls(
                            generator = state.generator,
                            params = state.params,
                            lockedParams = state.lockedParams,
                            onParamChange = { key, value -> viewModel.updateParam(key, value) },
                            onToggleLock = { viewModel.toggleParamLock(it) },
                            modifier = Modifier.weight(1f)
                        )
                        PresetsPanel(
                            presets = presets,
                            onSavePreset = { viewModel.savePreset(it) },
                            onLoadPreset = { viewModel.loadPreset(it) },
                            onDeletePreset = { viewModel.deletePreset(it) },
                            generatorStyleName = state.generator?.styleName ?: ""
                        )
                    }
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
                        onExportPng = {
                            val gen = state.generator ?: return@ExportPanel
                            exportViewModel.exportPng(context, gen, renderParams, state.seed, state.palette, state.quality, state.postFX, 1080, 1080)
                        },
                        onExportJpg = {
                            val gen = state.generator ?: return@ExportPanel
                            exportViewModel.exportJpg(context, gen, renderParams, state.seed, state.palette, state.quality, state.postFX, 1080, 1080)
                        },
                        onExportSvg = {
                            val gen = state.generator ?: return@ExportPanel
                            exportViewModel.exportSvg(context, gen, renderParams, state.seed, state.palette, 1080, 1080)
                        },
                        onExportGif = {
                            val gen = state.generator ?: return@ExportPanel
                            exportViewModel.exportGif(context, gen, renderParams, state.seed, state.palette, state.quality, state.animationFps)
                        },
                        onExportVideo = {
                            val gen = state.generator ?: return@ExportPanel
                            exportViewModel.exportVideo(context, gen, renderParams, state.seed, state.palette, state.quality, state.animationFps)
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
                        onVideoDurationChange = { exportViewModel.setVideoDuration(it) },
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
                        performanceMode = state.performanceMode,
                        showFps = state.showFps,
                        interactionEnabled = state.interactionEnabled,
                        animationFps = state.animationFps,
                        postFX = state.postFX,
                        onThemeChange = { viewModel.setTheme(it) },
                        onQualityChange = { viewModel.setQuality(it) },
                        onPerformanceModeChange = { viewModel.setPerformanceMode(it) },
                        onShowFpsChange = { viewModel.setShowFps(it) },
                        onInteractionChange = { viewModel.setInteractionEnabled(it) },
                        onAnimationFpsChange = { viewModel.setAnimationFps(it) },
                        onPostFXChange = { viewModel.setPostFX(it) }
                    )
                }
            }
        }
    } // end Column

    // Dialogs
    if (showAbout) AboutDialog { showAbout = false }
    if (showInstructions) InstructionsDialog { showInstructions = false }
    if (showChangelog) ChangelogDialog { showChangelog = false }
    if (showPrivacy) PrivacyDialog { showPrivacy = false }
    if (showDonation) DonationDialog { showDonation = false }
    if (showUseCases) UseCasesDialog { showUseCases = false }
    if (showReportBug) ReportBugDialog { showReportBug = false }

    // Show share sheet after export
    exportState.lastExportUri?.let { uri ->
        LaunchedEffect(uri) {
            shareFile(context, uri)
            exportViewModel.clearLastExport()
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
            // Center-crop to square
            if (bitmap.width != bitmap.height) {
                val side = minOf(bitmap.width, bitmap.height)
                val xOff = (bitmap.width - side) / 2
                val yOff = (bitmap.height - side) / 2
                val cropped = android.graphics.Bitmap.createBitmap(bitmap, xOff, yOff, side, side)
                bitmap.recycle()
                bitmap = cropped
            }
            // Scale down if too large
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
