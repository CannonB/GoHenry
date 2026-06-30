package com.gohenry.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import kotlinx.coroutines.flow.MutableStateFlow
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.BatteryFull
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ElectricBolt
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.LocalGasStation
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.OpenInBrowser
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Thermostat
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.KeyOff
import androidx.compose.material.icons.outlined.SignalWifiBad
import androidx.compose.material3.Badge
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.Divider
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.Shapes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import android.location.Geocoder
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt
import kotlin.random.Random

class MainActivity : ComponentActivity() {
    private val deepLinkIntent = MutableStateFlow<Intent?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Leave the splash theme behind now the window is up; Compose draws on the normal theme.
        setTheme(R.style.Theme_GoHenry)
        GoHenryFcmService.ensureChannel(this)
        PushRegistrar(this).registerIfPossible()
        deepLinkIntent.value = intent
        setContent { GoHenryApp(deepLinkIntent = deepLinkIntent) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        deepLinkIntent.value = intent
    }

    companion object {
        const val EXTRA_DEEPLINK = "gohenry.deeplink"
        const val EXTRA_VIN = "gohenry.vin"
        const val DEEPLINK_ROADTRIP = "roadtrip"
    }
}

@Composable
private fun GoHenryApp(vm: GoHenryViewModel = viewModel(), deepLinkIntent: MutableStateFlow<Intent?>? = null) {
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    RequestNotificationPermission()
    LaunchedEffect(Unit) { vm.loadFleet() }
    if (deepLinkIntent != null) {
        val pending by deepLinkIntent.collectAsStateWithLifecycle()
        LaunchedEffect(pending) {
            val i = pending ?: return@LaunchedEffect
            if (i.getStringExtra(MainActivity.EXTRA_DEEPLINK) == MainActivity.DEEPLINK_ROADTRIP) {
                vm.requestRoadTripDeepLink(i.getStringExtra(MainActivity.EXTRA_VIN))
            }
            deepLinkIntent.value = null
        }
    }
    LaunchedEffect(state.fordReauthUrl) {
        val url = state.fordReauthUrl ?: return@LaunchedEffect
        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
        vm.consumeFordReauthUrl()
    }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) { vm.refreshNotificationHistory(); vm.refreshCombinedHistory(); vm.refreshUnreadBadges() }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    var showSplash by rememberSaveable { mutableStateOf(true) }
    GoHenryTheme {
        Surface(Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxSize()) {
                GoHenryScaffold(state, vm)
                AnimatedVisibility(visible = showSplash, exit = fadeOut()) {
                    SplashOverlay { showSplash = false }
                }
            }
        }
    }
}

/**
 * Compose cold-start splash: the brand "blur" mark centered on the deep brand
 * background, turning one full clockwise revolution over a ~3s hold before the
 * app fades in. The static window splash (Theme.GoHenry.Splash) shows the same
 * mark/background first so the hand-off is seamless.
 */
@Composable
private fun SplashOverlay(onDone: () -> Unit) {
    val rotation = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        rotation.animateTo(360f, animationSpec = tween(durationMillis = 3000, easing = LinearEasing))
        onDone()
    }
    Box(Modifier.fillMaxSize().background(Color(0xFF102A43)), contentAlignment = Alignment.Center) {
        Icon(
            painterResource(R.drawable.ic_blur_on), null,
            tint = Color.White,
            modifier = Modifier.size(150.dp).rotate(rotation.value),
        )
    }
}

@Composable
private fun RequestNotificationPermission() {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    LaunchedEffect(Unit) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
}

private val KronaOneFamily = FontFamily(Font(R.font.krona_one_regular))
private val MeaCulpaFamily = FontFamily(Font(R.font.mea_culpa_regular))
private val BrandTitle = buildAnnotatedString {
    withStyle(SpanStyle(fontFamily = KronaOneFamily)) { append("Go") }
    withStyle(SpanStyle(fontFamily = MeaCulpaFamily)) { append("Henry") }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GoHenryScaffold(state: GoHenryUiState, vm: GoHenryViewModel) {
    val isRoot = state.screen == Screen.CarSelect
    val refreshing = state.loading || state.telemetryLoading
    // Home and the detail screen share the same brand header (move icon + large
    // "GoHenry" title) so the detail view reads as the same screen with detail below.
    val brandScreen = isRoot || state.screen == Screen.VehicleStatus
    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    if (brandScreen) Text(BrandTitle, style = MaterialTheme.typography.displaySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    else Text(titleFor(state), maxLines = 1, overflow = TextOverflow.Ellipsis)
                },
                navigationIcon = {
                    when {
                        brandScreen -> Box(Modifier.padding(start = 12.dp)) { BrandSpinIcon(refreshing) }
                        // Road-trip detail backs out to the trips tray on Home.
                        state.screen == Screen.RoadTripDetail ->
                            IconButton(onClick = { vm.backFromRoadTripDetail() }) { Icon(Icons.Default.ArrowBack, "Back") }
                        else -> IconButton(onClick = vm::showCarSelect) { Icon(Icons.Default.ArrowBack, "Back") }
                    }
                },
                actions = {
                    // CSV share lives top-right of the alert-history screens (single
                    // vehicle + all vehicles), matching the home hero's icon style.
                    val context = LocalContext.current
                    when (state.screen) {
                        Screen.NotificationHistory -> {
                            val rows = state.notificationHistory
                            IconButton(
                                onClick = {
                                    val label = state.vehicles.find { it.vin == state.notificationHistoryVin }?.title
                                        ?: rows.firstOrNull()?.data?.get("nickname")?.takeIf { it.isNotBlank() }
                                        ?: state.notificationHistoryVin ?: "vehicle"
                                    HistoryCsvExport.share(context, label, rows)
                                },
                                enabled = rows.isNotEmpty(),
                            ) { Icon(painterResource(R.drawable.ic_share), "Share alert history (CSV)") }
                        }
                        Screen.CombinedHistory -> {
                            val filterVin = state.combinedHistoryFilterVin
                            val rows = if (filterVin == null) state.combinedHistory
                                else state.combinedHistory.filter { it.vin == filterVin }
                            IconButton(
                                onClick = {
                                    val label = state.vehicles.find { it.vin == filterVin }?.title ?: "all-vehicles"
                                    HistoryCsvExport.share(context, label, rows)
                                },
                                enabled = rows.isNotEmpty(),
                            ) { Icon(painterResource(R.drawable.ic_share), "Share alert history (CSV)") }
                        }
                        Screen.RoadTripHistory -> {
                            val filterVin = state.roadTripHistoryFilterVin
                            val trips = state.roadTripHistoryFor(filterVin)
                            val labelByVin = state.vehicles.associate { it.vin to it.title }
                            val exportLabel = state.vehicles.find { it.vin == filterVin }?.title ?: "all-vehicles"
                            if (state.roadTripHistoryExporting) {
                                IconButton(onClick = {}, enabled = false) {
                                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                                }
                            } else {
                                IconButton(
                                    onClick = {
                                        vm.exportRoadTripHistory { full ->
                                            RoadTripCsvExport.share(context, exportLabel, full, labelByVin)
                                        }
                                    },
                                    enabled = trips.isNotEmpty(),
                                ) { Icon(painterResource(R.drawable.ic_share), "Share road-trip history (CSV)") }
                            }
                        }
                        Screen.CachedFields -> {
                            val rows = state.cachedFields
                            IconButton(
                                onClick = {
                                    val label = state.selectedVehicle?.title ?: "vehicle"
                                    FieldsCsvExport.share(context, label, rows, null)
                                },
                                enabled = rows.isNotEmpty(),
                            ) { Icon(painterResource(R.drawable.ic_share), "Share raw fields (CSV)") }
                        }
                        else -> {}
                    }
                },
            )
        },
        floatingActionButton = {},
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            when (state.screen) {
                Screen.CarSelect -> CarSelectScreen(state, vm)
                Screen.VehicleStatus -> VehicleStatusScreen(state, vm)
                Screen.CachedFields -> CachedFieldsScreen(state, vm)
                Screen.Notifications -> NotificationsScreen(state, vm)
                Screen.NotificationHistory -> NotificationHistoryScreen(state, vm)
                Screen.CombinedHistory -> CombinedHistoryScreen(state, vm)
                Screen.RoadTripHistory -> RoadTripHistoryScreen(state, vm)
                Screen.Settings -> SettingsScreen(state, vm)
                Screen.RoadTripDetail -> RoadTripDetailScreen(state, vm)
            }
            // All-vehicle history shortcut: bottom-RIGHT Material 3 split button.
            // Primary area opens notification history (default); the ▼ area opens a
            // menu to choose Notification or Road-trip history.
            if (isRoot) HistorySplitButton(
                onPrimary = vm::showCombinedHistory,
                onNotification = vm::showCombinedHistory,
                onRoadTrip = vm::showRoadTripHistory,
                modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
            )
            // Settings shortcut: bottom-LEFT, sized to the SAME width as the history
            // split button so the two corner buttons match.
            if (isRoot) Surface(
                onClick = vm::showSettings,
                modifier = Modifier.align(Alignment.BottomStart).padding(16.dp).width(CornerButtonWidth).height(56.dp),
                shape = RoundedCornerShape(28.dp),
                color = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                shadowElevation = 6.dp,
                tonalElevation = 6.dp,
            ) {
                Box(contentAlignment = Alignment.Center) { Icon(Icons.Default.Settings, "Settings") }
            }
            // Road-trip launcher: a wide pill centered between the two corner buttons.
            // Opens the road-trip tray; turns solid when a trip is active. Fixed width
            // (fill between the corner buttons) so it's the same size active or not.
            if (isRoot && state.selectedVehicle != null) {
                val tripActive = state.telemetry?.hasActiveRoadTrip == true
                ExtendedFloatingActionButton(
                    onClick = { vm.openRoadTripTray() },
                    containerColor = if (tripActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.primaryContainer,
                    contentColor = if (tripActive) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onPrimaryContainer,
                    icon = { Icon(Icons.Default.Explore, null) },
                    text = { Text(if (tripActive) "Trip active" else "Road trips", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 16.dp, start = CornerButtonWidth + 24.dp, end = CornerButtonWidth + 24.dp).fillMaxWidth(),
                )
            }
            if (state.roadTripTrayOpen) RoadTripTray(state, vm)
        }
    }
}

private fun titleFor(state: GoHenryUiState): String = when (state.screen) {
    Screen.CarSelect -> "GoHenry"
    Screen.VehicleStatus -> state.selectedVehicle?.title ?: "Vehicle status"
    Screen.CachedFields -> "Cached fields"
    Screen.Notifications -> "Notification alerts"
    Screen.NotificationHistory -> "Notification history"
    Screen.CombinedHistory -> "All recent alerts"
    Screen.RoadTripHistory -> "Road trip history"
    Screen.Settings -> "Settings"
    Screen.RoadTripDetail -> state.selectedRoadTrip?.name ?: "Road trip"
}

/**
 * The top-left brand mark (the "blur" icon). Each refresh spins it one full
 * clockwise revolution over 1s as a visual cue; shared by Home and the detail
 * screen since both use the brand header.
 *
 * The spin is driven off the rising edge of [refreshing] via a long-lived
 * collector (not keyed on [refreshing]), so even a sub-second refresh always
 * completes a full 1s revolution instead of being cut short when loading clears.
 */
@Composable
private fun BrandSpinIcon(refreshing: Boolean) {
    val rotation = remember { Animatable(0f) }
    val current by rememberUpdatedState(refreshing)
    LaunchedEffect(Unit) {
        var wasRefreshing = false
        snapshotFlow { current }.collect { isRefreshing ->
            if (isRefreshing && !wasRefreshing) {
                rotation.snapTo(0f)
                rotation.animateTo(360f, animationSpec = tween(durationMillis = 1000, easing = LinearEasing))
            }
            wasRefreshing = isRefreshing
        }
    }
    Icon(
        painterResource(R.drawable.ic_blur_on), null,
        tint = MaterialTheme.colorScheme.primary,
        modifier = Modifier.size(44.dp).rotate(rotation.value),
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CarSelectScreen(state: GoHenryUiState, vm: GoHenryViewModel) {
    val refreshing = state.loading || state.telemetryLoading
    PullToRefreshBox(
        isRefreshing = refreshing,
        onRefresh = { if (state.vehicles.isEmpty()) vm.loadFleet() else vm.refresh() },
        modifier = Modifier.fillMaxSize(),
    ) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            ErrorBanner(state.error)
            if (!state.loading && state.vehicles.isEmpty()) {
                val anyLinked = state.fordAccounts.any { it.isLinked }
                val (emptyTitle, emptyBody) = when {
                    // A real backend/config failure already shows in the ErrorBanner above.
                    state.error != null ->
                        "Couldn't load vehicles" to "See the error above, then check backend settings in local.properties."
                    // We know the Ford status and nothing is linked — point at linking, not config.
                    state.fordAccounts.isNotEmpty() && !anyLinked ->
                        "No vehicles — link your Ford account" to "Open Settings (cog icon, top-right) and tap Link to connect Ford. Your cars appear after linking."
                    // Linked but still empty (or status unknown) — refresh / re-link.
                    else ->
                        "No vehicles yet" to "Pull down to refresh, or open Settings (cog icon, top-right) to link/re-link or add a Ford account."
                }
                EmptyState(emptyTitle, emptyBody, Icons.Default.DirectionsCar)
                Button(onClick = vm::loadFleet, modifier = Modifier.align(Alignment.CenterHorizontally)) { Text("Load fleet") }
            } else if (state.vehicles.isEmpty()) {
                // Still loading and no cached vehicles yet — show a spinner rather
                // than the carousel (size==0 would divide-by-zero in the pager).
                Box(Modifier.fillMaxWidth().height(heroCardHeight()), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            } else {
                val size = state.vehicles.size
                val loop = size > 1
                // Infinite carousel: back the pager with a very large virtual page
                // count and map page -> vehicle via modulo, so swiping past the last
                // card wraps straight back to the first (and vice-versa). Single-car
                // fleets don't loop.
                val pageCount = if (loop) 100_000 else size.coerceAtLeast(1)
                val initialPage = remember(size) {
                    val sel = state.selectedIndex.coerceIn(0, (size - 1).coerceAtLeast(0))
                    if (loop) { val mid = pageCount / 2; mid - (mid % size) + sel } else sel
                }
                val pagerState = rememberPagerState(initialPage = initialPage, pageCount = { pageCount })
                // Swipe -> selection.
                LaunchedEffect(pagerState.currentPage, size) { if (size > 0) vm.select(pagerState.currentPage % size) }
                // External selection (e.g. returning from the detail screen) -> snap
                // the carousel to that same vehicle by the shortest direction.
                LaunchedEffect(state.selectedIndex, size) {
                    if (size > 0) {
                        val cur = pagerState.currentPage
                        val curIdx = ((cur % size) + size) % size
                        if (curIdx != state.selectedIndex) {
                            val fwd = ((state.selectedIndex - curIdx) % size + size) % size
                            val delta = if (fwd <= size - fwd) fwd else fwd - size
                            pagerState.animateScrollToPage(cur + delta)
                        }
                    }
                }
                // Lock the pager to the SELECTED hero's natural content height and
                // top-align its pages, so the home card's top edge matches the detail
                // screen's hero and there is no slack above/below at any font scale.
                val selIdx = state.selectedIndex.coerceIn(0, size - 1)
                HeroCarouselHost(
                    fallback = heroCardHeight(),
                    selectedHero = {
                        VehicleCarouselCard(state.vehicles[selIdx], state.telemetry, selIdx, Color(state.cardColorForSlug(state.vehicles[selIdx].appSlug)), state.notificationTrackingVins.contains(state.vehicles[selIdx].vin), state.notificationUnreadVins.contains(state.vehicles[selIdx].vin), state.lastRefreshedAt?.let { "Updated ${relativeTime(it)}" }, state.lostSignalPolls.toLong() * state.pollCadenceMinutes * 60_000L, {}, {}, {})
                    },
                ) {
                    HorizontalPager(state = pagerState, verticalAlignment = Alignment.Top, modifier = Modifier.fillMaxWidth()) { page ->
                        val idx = ((page % size) + size) % size
                        val refreshedLabel = if (idx == state.selectedIndex) state.lastRefreshedAt?.let { "Updated ${relativeTime(it)}" } else null
                        VehicleCarouselCard(state.vehicles[idx], if (idx == state.selectedIndex) state.telemetry else null, idx, Color(state.cardColorForSlug(state.vehicles[idx].appSlug)), state.notificationTrackingVins.contains(state.vehicles[idx].vin), state.notificationUnreadVins.contains(state.vehicles[idx].vin), refreshedLabel, state.lostSignalPolls.toLong() * state.pollCadenceMinutes * 60_000L, { vm.select(idx); vm.showVehicleStatus() }, { vm.showNotificationHistory(state.vehicles[idx].vin) }, { vm.select(idx); vm.showNotifications() })
                    }
                }
                PageDots(size, ((pagerState.currentPage % size) + size) % size)
                Text("Tap a card for full status · pull down to refresh", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.align(Alignment.CenterHorizontally))
            }
        }
    }
}

/**
 * Base footprint of the vehicle hero card at the system's default font scale. The
 * effective height is [heroCardHeight], which grows this with the user's font scale
 * so the card's bottom row (settings glyph + last-refreshed cue) never clips when
 * text is enlarged for accessibility.
 */
private val HERO_CARD_BASE_HEIGHT = 580.dp

/**
 * The locked hero-card height, scaled up with the system font scale (clamped 1×–2×)
 * so enlarged text grows the card rather than pushing its bottom row off the bottom
 * edge. Because only text — not the fixed gauge/icons/padding — grows with the font,
 * scaling the whole footprint is a safe upper bound that guarantees no clipping. Both
 * the home carousel pager and the detail screen read this SAME value, so the two
 * heroes stay pixel-identical (hard rule: home is the master for every hero dimension).
 */
@Composable
private fun heroCardHeight(): Dp = HERO_CARD_BASE_HEIGHT * LocalDensity.current.fontScale.coerceIn(1f, 2f)

/**
 * Hosts the hero carousel [pager], locking it to the natural content height of the
 * currently-selected hero ([selectedHero], measured once with NO vertical bound).
 *
 * The home carousel pager needs a fixed page height (otherwise swiping between the
 * telemetry-bearing selected card and its shorter neighbours would jump), but a
 * hand-picked constant clips at large font scales and a font-scaled constant leaves
 * slack (the card no longer fills its page). Measuring the selected card's real
 * content height and locking the pager to exactly that gives a footprint that always
 * equals its content — no clipping, no slack — at every font scale. Because the
 * detail screen renders the SAME hero with wrap-content height, the two are identical
 * in size AND top-aligned at the same offset. Falls back to [fallback] until the
 * first measurement resolves.
 */
@Composable
private fun HeroCarouselHost(
    fallback: Dp,
    selectedHero: @Composable () -> Unit,
    pager: @Composable () -> Unit,
) {
    SubcomposeLayout(Modifier.fillMaxWidth()) { constraints ->
        val fallbackPx = fallback.roundToPx()
        // Measure the selected hero with an unbounded height so the lock tracks the
        // card's true content (and grows the moment telemetry / larger text arrives).
        val heroHeight = subcompose("heroMeasure", selectedHero)
            .map { it.measure(constraints.copy(minHeight = 0, maxHeight = Constraints.Infinity)) }
            .maxOfOrNull { it.height }
            ?.takeIf { it > 0 } ?: fallbackPx
        val placeables = subcompose("pager", pager)
            .map { it.measure(constraints.copy(minHeight = heroHeight, maxHeight = heroHeight)) }
        layout(constraints.maxWidth, heroHeight) { placeables.forEach { it.place(0, 0) } }
    }
}

/**
 * One carousel page: the vehicle hero card with its quick actions (open, history,
 * notification settings). Telemetry is only passed for the selected card.
 */
@Composable
private fun VehicleCarouselCard(vehicle: Vehicle, telemetry: Telemetry?, index: Int, baseColor: Color, tracked: Boolean, unread: Boolean, refreshedLabel: String?, staleThresholdMillis: Long = SIGNAL_STALE_THRESHOLD_MILLIS, onOpen: () -> Unit, onHistory: () -> Unit, onNotificationSettings: () -> Unit) {
    VehicleHero(vehicle, telemetry, index, baseColor, tracked, unread, refreshedLabel, staleThresholdMillis, onClick = onOpen, onHistory = onHistory, onNotificationSettings = onNotificationSettings)
}

/**
 * The colored, brush-filled hero used identically on the carousel card and at
 * the top of the detail screen, so the detail view reads as the same card with
 * more detail below. Tapping it runs [onClick] (open detail on the carousel;
 * back to home on the detail screen).
 */
@Composable
private fun VehicleHero(
    vehicle: Vehicle,
    telemetry: Telemetry?,
    index: Int,
    baseColor: Color,
    tracked: Boolean,
    unread: Boolean = false,
    refreshedLabel: String?,
    staleThresholdMillis: Long = SIGNAL_STALE_THRESHOLD_MILLIS,
    onClick: () -> Unit,
    onHistory: (() -> Unit)? = null,
    onNotificationSettings: (() -> Unit)? = null,
) {
    val visual = themeFromColor(baseColor)
    val context = LocalContext.current
    val cc = visual.contentColor
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp), shape = RoundedCornerShape(visual.cornerRadius), colors = CardDefaults.cardColors(containerColor = Color.Transparent), elevation = CardDefaults.cardElevation(10.dp)) {
        Box(Modifier.fillMaxWidth().background(visual.brush()).padding(vertical = 14.dp, horizontal = 14.dp)) {
            Canvas(Modifier.matchParentSize()) { drawCardMotif(visual.motif) }
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                // Title cluster — engine icon + nickname + vehicle type sit together
                // up top; VIN is a small line beneath them.
                Row(verticalAlignment = Alignment.CenterVertically) {
                    EngineTypeIcon(vehicle.engineType, cc, 34.dp)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(vehicle.title, color = cc, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Black, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(engineDisplayName(vehicle.engineType), color = cc.copy(alpha = 0.85f), style = MaterialTheme.typography.labelLarge)
                    }
                    // Signal status sits right after the nickname as a wifi glyph.
                    SignalStatusIcon(telemetry, cc, 22.dp, staleThresholdMillis)
                    // Bell opens this car's notification history; a red dot rides
                    // on it only when there are unread captured alerts for this VIN.
                    if (tracked && onHistory != null) {
                        if (unread) BadgedBox(badge = { Badge() }) { IconButton(onClick = onHistory) { Icon(Icons.Default.Notifications, "History", tint = cc) } }
                        else IconButton(onClick = onHistory) { Icon(Icons.Default.Notifications, "History", tint = cc) }
                    }
                }
                Text("VIN ${vehicle.vin}", color = cc.copy(alpha = 0.66f), style = MaterialTheme.typography.labelSmall)
                // Status, a row down: large headline + FleetFoot-style qualifiers, boxed.
                HeroStatusBlock(telemetry, cc)
                // Charge/fuel gauge sits lower, enlarged, with the status block giving it air above.
                HeroGauge(vehicle, telemetry, Modifier.fillMaxWidth().height(240.dp), bright = true)
                // Ignition + gear pills, two equal-width values on a single line under the gauge.
                HeroPills(telemetry, cc)
                // Location now lives below the gauge: a slightly larger, tappable Maps
                // pin, with a reverse-geocoded city/neighborhood label beneath it.
                val hLat = telemetry?.latitude; val hLon = telemetry?.longitude
                if (hLat != null && hLon != null) {
                    val place = rememberPlaceName(hLat, hLon)
                    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                        IconButton(onClick = { openInGoogleMaps(context, hLat, hLon) }, modifier = Modifier.size(40.dp)) {
                            Icon(Icons.Default.LocationOn, "Open location in Google Maps", tint = cc, modifier = Modifier.size(32.dp))
                        }
                        if (place != null) {
                            Text(place, color = cc.copy(alpha = 0.85f), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
                // Bottom strip: notification-settings shortcut (left) + last-refreshed cue (right).
                if (onNotificationSettings != null || refreshedLabel != null) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        if (onNotificationSettings != null) {
                            // Match the visual size of the top-right notification (bell)
                            // glyph; the outlined settings mark needs a slightly larger
                            // box than the filled bell to read at the same size.
                            IconButton(onClick = onNotificationSettings) {
                                Icon(painterResource(R.drawable.ic_notification_settings), "Notification settings", tint = cc, modifier = Modifier.size(28.dp))
                            }
                        }
                        Spacer(Modifier.weight(1f))
                        if (refreshedLabel != null) {
                            Icon(Icons.Default.Refresh, null, tint = cc.copy(alpha = 0.75f), modifier = Modifier.size(14.dp))
                            Spacer(Modifier.width(4.dp))
                            Text(refreshedLabel, color = cc.copy(alpha = 0.75f), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }
    }
}

/**
 * The prominent drive-status block: a large status headline with its tinted
 * icon, and the FleetFoot-style qualifiers (Active/Idle · Signal · …) tucked
 * inside the same highlighted box. The odometer/range and Maps pin now live
 * below the gauge, not here; no coordinates are ever shown.
 */
@Composable
private fun HeroStatusBlock(telemetry: Telemetry?, cc: Color) {
    val status = driveStatusLabel(telemetry)
    val detail = statusDetailLabel(telemetry)
    // Road-trip indicator folds into the SAME second line (no extra row) so the home
    // carousel hero and the detail hero stay pixel-identical.
    val roadtripActive = telemetry?.hasActiveRoadTrip == true
    val secondLine = when {
        detail != null && roadtripActive -> "$detail · Roadtrip Active"
        roadtripActive -> "Roadtrip Active"
        else -> detail
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        // Highlighted, centered status box so the headline drive state stands out;
        // the idle/signal qualifiers sit right inside it.
        Box(
            Modifier.fillMaxWidth().background(cc.copy(alpha = 0.16f), RoundedCornerShape(16.dp)).padding(vertical = 10.dp, horizontal = 14.dp),
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(statusIconVector(status ?: ""), null, tint = cc, modifier = Modifier.size(24.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(status ?: "Status unknown", color = cc, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (secondLine != null) {
                    Text(secondLine, color = cc.copy(alpha = 0.82f), style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

/** Two equal-width tinted pills under the gauge: ignition (left) + gear (right), shown for every engine type. */
@Composable
private fun HeroPills(t: Telemetry?, cc: Color) {
    val ignition = t?.ignition?.takeIf { it.isNotBlank() } ?: "—"
    val gear = t?.gearLever?.takeIf { it.isNotBlank() } ?: "—"
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        HeroChip("Ignition: $ignition", cc, Modifier.weight(1f))
        HeroChip("Gear: $gear", cc, Modifier.weight(1f))
    }
}

/** Pill-shaped stat chip tinted to the card's content color. */
@Composable
private fun HeroChip(text: String, cc: Color, modifier: Modifier = Modifier) {
    Text(
        text,
        color = cc,
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.SemiBold,
        textAlign = TextAlign.Center,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier.background(cc.copy(alpha = 0.16f), RoundedCornerShape(999.dp)).padding(horizontal = 10.dp, vertical = 9.dp),
    )
}

/** Engine-type glyph using FleetFoot's electric/hybrid drawables (gas/unknown fall back to Material icons). */
@Composable
private fun EngineTypeIcon(engineType: String?, tint: Color, size: Dp) {
    when (engineVisualKind(engineType)) {
        EngineVisualKind.BatteryElectric, EngineVisualKind.PlugInHybrid ->
            Icon(painterResource(R.drawable.ic_electric_car), null, tint = tint, modifier = Modifier.size(size))
        EngineVisualKind.Hybrid ->
            Icon(painterResource(R.drawable.ic_car_fan_recirculate_2), null, tint = tint, modifier = Modifier.size(size))
        EngineVisualKind.Gas ->
            Icon(Icons.Default.LocalGasStation, null, tint = tint, modifier = Modifier.size(size))
        EngineVisualKind.Unknown ->
            Icon(Icons.Default.DirectionsCar, null, tint = tint, modifier = Modifier.size(size))
    }
}

/**
 * Human drive status (Driving / Parked / …) derived from cached telemetry,
 * FleetFoot-style. The raw "On"/"Off" ignition value is intentionally NOT shown
 * as the headline — it's collapsed into the more meaningful Driving/Parked state.
 */
private fun driveStatusLabel(t: Telemetry?): String? {
    if (t == null) return null
    t.lastStatus
        ?.takeIf { it.isNotBlank() && it.lowercase() !in setOf("on", "off") }
        ?.let { return it.lowercase().replaceFirstChar { c -> c.uppercase() } }
    val ign = t.ignition?.lowercase().orEmpty()
    return when {
        ign.contains("run") || ign.contains("start") || ign == "on" -> "Driving"
        ign.isNotBlank() -> "Parked"
        t.lastWasActive == true -> "Driving"
        t.lastWasActive == false -> "Parked"
        else -> null
    }
}

/**
 * Charging state derived from BEV telemetry, driving both the hero status text and
 * the gauge glyph so the two always agree:
 *  - Charge display `IN_PROGRESS`            → "Charging"   + battery-charging icon
 *  - Charge display `COMPLETE`/target-reached → "Charged"    + battery-full icon
 *  - Any other display but charger connected  → "Plugged in" + ev-station icon
 *  - Otherwise                                → none (default battery glyph)
 * `gaugeIconRes` is 0 for [None]; callers must guard on `!= None` before drawing it.
 */
private enum class ChargeState(val label: String, val gaugeIconRes: Int) {
    Charging("Charging", R.drawable.ic_battery_charging_60),
    Charged("Charged", R.drawable.ic_battery_full),
    PluggedIn("Plugged in", R.drawable.ic_ev_station),
    None("", 0),
}

/**
 * Maps the raw Ford BEV charge fields to a [ChargeState]. Reads `SoCChargeDisplayStatus`
 * (Ford `xevBatteryChargeDisplayStatus`, the "charge display") and `ChargerStatus`
 * (`xevPlugChargerStatus`). Non-BEV telemetry lacks these fields → [ChargeState.None].
 */
private fun chargeStateOf(t: Telemetry?): ChargeState {
    if (t == null) return ChargeState.None
    val raw = t.rawFields?.associateBy { it.name } ?: emptyMap()
    val display = raw["SoCChargeDisplayStatus"]?.value?.trim()?.uppercase().orEmpty()
    val charger = raw["ChargerStatus"]?.value?.trim().orEmpty()
    return when {
        display.contains("IN_PROGRESS") || display.contains("INPROGRESS") -> ChargeState.Charging
        display.contains("COMPLETE") || display.contains("TARGET_REACHED") || display.contains("CHARGETARGETREACHED") -> ChargeState.Charged
        charger.isNotBlank() && !charger.equals("DISCONNECTED", ignoreCase = true) -> ChargeState.PluggedIn
        else -> ChargeState.None
    }
}

/**
 * Picks the battery-bar glyph whose visual fill best matches a drive-battery SoC%
 * (Ford `xevBatteryStateOfCharge`, curated `SoCCharge%`). Used on the hero gauge when
 * the car is a disconnected BEV/PHEV so the center icon mirrors the charge level.
 * The number in each drawable name is the approximate % it depicts; we snap to the
 * nearest one. Null SoC → empty (0-bar).
 */
private fun socBarIconRes(pct: Double?): Int {
    val p = (pct ?: 0.0).coerceIn(0.0, 100.0)
    val steps = listOf(
        0 to R.drawable.ic_battery_0_bar,
        5 to R.drawable.ic_battery_5_bar,
        10 to R.drawable.ic_battery_10_bar,
        25 to R.drawable.ic_battery_25_bar,
        50 to R.drawable.ic_battery_50_bar,
        75 to R.drawable.ic_battery_75_bar,
        90 to R.drawable.ic_battery_90_bar,
        100 to R.drawable.ic_battery_full,
    )
    return steps.minByOrNull { kotlin.math.abs(it.first - p) }!!.second
}

/**
 * Animated "charging" gauge glyph: cycles the battery-charging frames (20→full) at
 * ~0.5s each, looping, so an actively charging BEV/PHEV shows a filling battery.
 */
@Composable
private fun ChargingGaugeIcon(tint: Color, modifier: Modifier = Modifier) {
    val frames = remember {
        intArrayOf(
            R.drawable.ic_battery_charging_20,
            R.drawable.ic_battery_charging_30,
            R.drawable.ic_battery_charging_50,
            R.drawable.ic_battery_charging_60,
            R.drawable.ic_battery_charging_80,
            R.drawable.ic_battery_charging_90,
            R.drawable.ic_battery_full,
        )
    }
    var idx by remember { mutableStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(500)
            idx = (idx + 1) % frames.size
        }
    }
    Icon(painterResource(frames[idx]), null, modifier, tint = tint)
}

/**
 * Secondary status qualifiers (FleetFoot-style): Active/Idle · Trip · charge state.
 * Signal is NOT included here — it now renders as an icon beside the nickname.
 */
private fun statusDetailLabel(t: Telemetry?): String? {
    if (t == null) return null
    val parts = mutableListOf<String>()
    t.lastWasActive?.let { parts += if (it) "Active" else "Idle" }
    if (t.lastHasOpenActivity == true) parts += "Trip in progress"
    // Charge segment ("Charging"/"Charged"/"Plugged in") comes from the BEV charge
    // fields, superseding the old plain pluggedIn flag so text + gauge icon agree.
    chargeStateOf(t).takeIf { it != ChargeState.None }?.let { parts += it.label }
    return parts.joinToString(" · ").ifBlank { null }
}

/** Signal status as one of the three provided wifi glyphs: OK / lost / unknown. */
private enum class SignalState { Ok, Lost, Unknown }

/**
 * Human-friendly "about this long" label for a whole number of minutes, used by
 * the Lost-signal setting to show the effective dwell time (missed polls × cadence).
 */
private fun formatApproxDuration(totalMinutes: Int): String {
    if (totalMinutes < 60) return "$totalMinutes min"
    val h = totalMinutes / 60
    val m = totalMinutes % 60
    return if (m == 0) "$h h" else "$h h $m min"
}

/**
 * How long since the backend's last successful Ford read before the car is shown
 * as having lost signal. This is the fallback when no live threshold is supplied;
 * the hero card passes a value computed from the user's settings (lost-signal poll
 * count × poll cadence) so the icon and the backend `signal.lost` notification use
 * the exact same rule.
 */
private const val SIGNAL_STALE_THRESHOLD_MILLIS = 20L * 60 * 1000

/**
 * Connectivity state for the hero wifi glyph, driven by telemetry staleness: the
 * backend only advances `capturedAt` on a successful poll, so a gap of at least
 * [staleThresholdMillis] means the vehicle is offline — the same definition the
 * backend uses to push `signal.lost`. The backend `lastTripLostSignal` flag is
 * honored as a secondary trigger. Falls back to it (then "unknown") only when
 * there is no timestamp to judge by.
 */
private fun signalStateOf(t: Telemetry?, staleThresholdMillis: Long = SIGNAL_STALE_THRESHOLD_MILLIS): SignalState {
    if (t == null) return SignalState.Unknown
    val iso = t.capturedAt ?: t.lastPolledAt
    val capturedMs = iso?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() }
    if (capturedMs != null) {
        val stale = System.currentTimeMillis() - capturedMs >= staleThresholdMillis
        return if (stale || t.lastTripLostSignal == true || t.telemetryFeedLost == true) SignalState.Lost else SignalState.Ok
    }
    return when {
        t.lastTripLostSignal == true || t.telemetryFeedLost == true -> SignalState.Lost
        t.lastTripLostSignal == false && t.telemetryFeedLost != true -> SignalState.Ok
        else -> SignalState.Unknown
    }
}

/**
 * Small caption shown under the wifi glyph naming *which* loss is active. The backend
 * splits the two failure modes: `signal.lost` (the car's own data froze while the
 * ignition is on → surfaced as [Telemetry.lastTripLostSignal]) and `telemetryfeed.lost`
 * (the Ford feed is unreachable → [Telemetry.telemetryFeedLost], or time-based
 * staleness of `capturedAt`). Returns "Signal", "Telemetry", "Both", or null (no loss).
 */
private fun lostSignalKindLabel(t: Telemetry?, staleThresholdMillis: Long = SIGNAL_STALE_THRESHOLD_MILLIS): String? {
    if (t == null) return null
    val signalLost = t.lastTripLostSignal == true
    val iso = t.capturedAt ?: t.lastPolledAt
    val capturedMs = iso?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() }
    val stale = capturedMs != null && System.currentTimeMillis() - capturedMs >= staleThresholdMillis
    val telemetryLost = t.telemetryFeedLost == true || stale
    return when {
        signalLost && telemetryLost -> "Both"
        signalLost -> "Signal"
        telemetryLost -> "Telemetry"
        else -> null
    }
}

/** The wifi glyph shown right after the nickname to reflect connectivity. */
@Composable
private fun SignalStatusIcon(t: Telemetry?, tint: Color, size: Dp, staleThresholdMillis: Long = SIGNAL_STALE_THRESHOLD_MILLIS) {
    val (res, desc) = when (signalStateOf(t, staleThresholdMillis)) {
        SignalState.Ok -> R.drawable.ic_signal_wifi_ok to "Signal OK"
        SignalState.Lost -> R.drawable.ic_signal_wifi_bad to "Signal lost"
        SignalState.Unknown -> R.drawable.ic_signal_wifi_unknown to "Signal status unknown"
    }
    // A small caption names which loss is active ("Signal", "Telemetry", "Both") so
    // the user can tell a frozen-feed signal.lost from an unreachable telemetryfeed.lost.
    val kind = lostSignalKindLabel(t, staleThresholdMillis)
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(painterResource(res), contentDescription = desc, tint = tint, modifier = Modifier.size(size))
        if (kind != null) {
            Text(kind, color = tint, style = MaterialTheme.typography.labelSmall, fontSize = 9.sp, lineHeight = 10.sp)
        }
    }
}

/** Status → tinted Compose icon (FleetFoot statusIconVector port). */
private fun statusIconVector(status: String): ImageVector {
    val n = status.lowercase()
    return when {
        n.contains("park") -> Icons.Filled.DirectionsCar
        n.contains("charg") || n.contains("plug") -> Icons.Filled.ElectricBolt
        n.contains("driv") || n.contains("motion") || n.contains("moving") -> Icons.Filled.Speed
        else -> Icons.Filled.Info
    }
}

/** Opens coordinates in Google Maps (app or browser) via the universal Maps URL; guarded so a device with no handler doesn't crash. */
private fun openInGoogleMaps(context: android.content.Context, lat: Double, lng: Double) {
    val uri = Uri.parse("https://www.google.com/maps/search/?api=1&query=$lat,$lng")
    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}

/** Process-wide cache so each coordinate is reverse-geocoded only once. */
private val placeNameCache = java.util.concurrent.ConcurrentHashMap<String, String>()

/**
 * Reverse-geocodes lat/lon to a short "Neighborhood, City" label using Android's
 * built-in [Geocoder] — no API key, no billing, no extra dependency. Runs off the
 * main thread and caches per ~100 m coordinate. Returns null until resolved (or if
 * the platform geocoder is unavailable / offline), so callers fall back to the pin.
 */
@Composable
private fun rememberPlaceName(lat: Double?, lon: Double?): String? {
    val context = LocalContext.current
    // Round to ~100 m so minor GPS jitter reuses the same cache entry.
    val key = if (lat != null && lon != null) "%.3f,%.3f".format(lat, lon) else null
    var name by remember(key) { mutableStateOf(key?.let { placeNameCache[it] }) }
    LaunchedEffect(key) {
        if (key == null || lat == null || lon == null) return@LaunchedEffect
        placeNameCache[key]?.let { name = it; return@LaunchedEffect }
        if (!Geocoder.isPresent()) return@LaunchedEffect
        val resolved = withContext(Dispatchers.IO) {
            runCatching {
                @Suppress("DEPRECATION")
                val addr = Geocoder(context, Locale.getDefault()).getFromLocation(lat, lon, 1)?.firstOrNull()
                addr?.let { a ->
                    val hood = a.subLocality?.takeIf { it.isNotBlank() }
                    val city = (a.locality ?: a.subAdminArea ?: a.adminArea)?.takeIf { it.isNotBlank() }
                    listOfNotNull(hood, city).distinct().joinToString(", ").ifBlank { null }
                }
            }.getOrNull()
        }
        if (resolved != null) { placeNameCache[key] = resolved; name = resolved }
    }
    return name
}

@Composable
private fun PageDots(count: Int, selected: Int) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) { repeat(count) { i -> Box(Modifier.padding(4.dp).size(if (i == selected) 10.dp else 7.dp).clip(CircleShape).background(if (i == selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant)) } }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun VehicleStatusScreen(state: GoHenryUiState, vm: GoHenryViewModel) {
    val vehicle = state.selectedVehicle
    val telemetry = state.telemetry
    if (vehicle == null) { EmptyState("No vehicle selected", "Go back and choose a vehicle.", Icons.Default.DirectionsCar); return }
    val refreshing = state.loading || state.telemetryLoading
    PullToRefreshBox(isRefreshing = refreshing, onRefresh = vm::refresh, modifier = Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            item {
                // ErrorBanner shares the hero's item so an empty banner adds no leading
                // gap — the hero sits at the same top offset as the home carousel,
                // making the home→detail transition seamless.
                ErrorBanner(state.error)
                // Identical colored hero to the home carousel card (same callbacks, so
                // the bottom strip + bell match); tapping it returns to the home screen
                // so the detail view reads as the same card with more detail below.
                // Rendered with wrap-content height (no fixed Box) so it equals the home
                // carousel page, which is itself locked to this hero's natural height —
                // keeping both heroes the same size AND top-aligned at every font scale.
                VehicleHero(vehicle, telemetry, state.selectedIndex, Color(state.cardColorForSlug(vehicle.appSlug)), state.notificationTrackingVins.contains(vehicle.vin), state.notificationUnreadVins.contains(vehicle.vin), state.lastRefreshedAt?.let { "Updated ${relativeTime(it)}" }, state.lostSignalPolls.toLong() * state.pollCadenceMinutes * 60_000L, onClick = vm::showCarSelect, onHistory = { vm.showNotificationHistory(vehicle.vin) }, onNotificationSettings = vm::showNotifications)
                if (state.telemetryLoading) { Spacer(Modifier.height(8.dp)); LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 4.dp)) }
            }
            item { TelemetryGrid(telemetry) }
            item { DetailCards(vehicle, telemetry) }
            item { Button(onClick = { vm.showCachedFields(vehicle.vin) }, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Default.List, null); Spacer(Modifier.width(8.dp)); Text("Raw fields") } }
        }
    }
}

/**
 * Road-trip tray: a partial-height bottom sheet opened from the home-screen
 * road-trip button. The header (active-trip status + Start/Stop and the optional
 * name field for a new trip) stays pinned; pull the sheet up to scroll the trip
 * history. Replaces the old full-screen trips list and the separate start dialog.
 * Active-trip state comes straight from the already-loaded telemetry (no extra
 * call), so it survives a reinstall.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RoadTripTray(state: GoHenryUiState, vm: GoHenryViewModel) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false)
    val telemetry = state.telemetry
    val active = telemetry?.hasActiveRoadTrip == true
    var tripName by remember { mutableStateOf("") }
    ModalBottomSheet(onDismissRequest = { vm.closeRoadTripTray() }, sheetState = sheetState) {
        Column(Modifier.fillMaxWidth()) {
            // Pinned header — stays put while the history list below scrolls.
            Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Explore, null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(8.dp))
                    Text("Road trips", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                    state.selectedVehicle?.title?.let {
                        Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                if (active) {
                    Text(telemetry?.activeRoadTripName ?: "Active road trip", style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    val started = formatLocalTimestamp(telemetry?.activeRoadTripStartedAt)
                    Text(if (started != null) "Road trip active · started $started" else "Road trip active", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Button(onClick = vm::stopRoadTrip, enabled = !state.roadTripBusy, modifier = Modifier.fillMaxWidth()) {
                        if (state.roadTripBusy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                        else { Icon(Icons.Default.Close, null); Spacer(Modifier.width(8.dp)); Text("Stop trip") }
                    }
                } else {
                    OutlinedTextField(
                        value = tripName,
                        onValueChange = { tripName = it },
                        singleLine = true,
                        enabled = !state.roadTripBusy,
                        label = { Text("Trip name (optional)") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Button(onClick = { vm.startRoadTrip(tripName.trim().ifBlank { null }); tripName = "" }, enabled = !state.roadTripBusy, modifier = Modifier.fillMaxWidth()) {
                        if (state.roadTripBusy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                        else { Icon(Icons.Default.Add, null); Spacer(Modifier.width(8.dp)); Text("Start trip") }
                    }
                }
                state.roadTripsError?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
                Text("History", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.height(8.dp))
            HorizontalDivider()
            // Scrollable history — pull the sheet up to reveal more.
            if (state.roadTripsLoading && state.roadTrips.isEmpty()) {
                Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            } else if (state.roadTrips.isEmpty()) {
                Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                    Text(
                        if (active) "Your current trip will appear here once it has activity." else "No road trips yet — start one above.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                }
            } else {
                val trayColor = state.selectedVehicle?.let { state.cardColorForSlug(it.appSlug) } ?: CardColorStore.DEFAULT_CARD_COLOR
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 460.dp).padding(horizontal = 24.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(state.roadTrips, key = { it.id }) { trip -> RoadTripRow(trip, trayColor) { vm.openRoadTrip(trip.id) } }
                    item { Spacer(Modifier.height(24.dp)) }
                }
            }
        }
    }
}

/** One row in the road-trips list: name, status, date, and a compact stat line.
 *  Tinted with the owning car's home-screen card color, matching notification history. */
@Composable
private fun RoadTripRow(trip: RoadTrip, cardColor: Int, onClick: () -> Unit) {
    val visual = themeFromColor(Color(cardColor))
    val cc = visual.contentColor
    val ccDim = cc.copy(alpha = 0.78f)
    ElevatedCard(
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.elevatedCardColors(containerColor = Color.Transparent),
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
    ) {
        Row(Modifier.fillMaxWidth().background(visual.brush()).padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(trip.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, color = cc)
                Text(roadTripWhen(trip), style = MaterialTheme.typography.bodySmall, color = ccDim)
                Text(roadTripStatLine(trip), style = MaterialTheme.typography.bodySmall, color = ccDim)
            }
            if (trip.isActive) {
                Badge(containerColor = cc, contentColor = visual.gradientMid) { Text("ACTIVE") }
                Spacer(Modifier.width(8.dp))
            }
            Icon(Icons.Default.ExpandMore, "Open", Modifier.rotate(-90f), tint = cc)
        }
    }
}

/** Road-trip detail: stats header + full event timeline. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RoadTripDetailScreen(state: GoHenryUiState, vm: GoHenryViewModel) {
    val trip = state.selectedRoadTrip
    if (trip == null) { EmptyState("Road trip", "Select a road trip to see its detail.", Icons.Default.Explore); return }
    val vehicleName = state.vehicles.find { it.vin == trip.vin }?.title
    var showRename by remember { mutableStateOf(false) }
    var showDelete by remember { mutableStateOf(false) }
    LazyColumn(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(trip.name, style = MaterialTheme.typography.titleLarge)
                            vehicleName?.let {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Default.DirectionsCar, null, modifier = Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Spacer(Modifier.width(4.dp))
                                    Text(it, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                            }
                        }
                        if (trip.isActive) { Badge(containerColor = MaterialTheme.colorScheme.primary) { Text("ACTIVE") }; Spacer(Modifier.width(8.dp)) }
                        IconButton(onClick = { showRename = true }, enabled = !state.roadTripBusy) { Icon(Icons.Default.Edit, "Rename trip") }
                        IconButton(onClick = { showDelete = true }, enabled = !state.roadTripBusy) { Icon(Icons.Default.Delete, "Delete trip", tint = MaterialTheme.colorScheme.error) }
                    }
                    Text(roadTripWhen(trip), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(roadTripStatLine(trip), style = MaterialTheme.typography.bodyMedium)
                    if (trip.startMethod.equals("auto", ignoreCase = true) || trip.endReason.equals("auto", ignoreCase = true)) {
                        Text(
                            buildString {
                                if (trip.startMethod.equals("auto", ignoreCase = true)) append("Auto-started")
                                if (trip.endReason.equals("auto", ignoreCase = true)) { if (isNotEmpty()) append(" · "); append("auto-closed") }
                            },
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
        if (state.roadTripDetailLoading && trip.timeline.isEmpty()) {
            item { Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() } }
        } else if (trip.timeline.isEmpty()) {
            item { EmptyState("No events yet", "Trip alerts (start, stop, charge, tire, alarm) will appear here as they happen.", Icons.Default.Notifications) }
        } else {
            item { Text("Timeline", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            items(trip.timeline.reversed()) { ev -> RoadTripEventRow(ev) }
        }
    }
    if (showRename) {
        RenameRoadTripSheet(
            current = trip.name,
            busy = state.roadTripBusy,
            onDismiss = { showRename = false },
            onSave = { newName -> vm.renameRoadTrip(trip.id, newName); showRename = false },
        )
    }
    if (showDelete) {
        AlertDialog(
            onDismissRequest = { showDelete = false },
            icon = { Icon(Icons.Default.Delete, null, tint = MaterialTheme.colorScheme.error) },
            title = { Text("Delete road trip?") },
            text = { Text("This permanently removes \u201c${trip.name}\u201d and its event timeline. This can't be undone.") },
            confirmButton = {
                Button(
                    onClick = { showDelete = false; vm.deleteRoadTrip(trip.id) },
                    enabled = !state.roadTripBusy,
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                ) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { showDelete = false }) { Text("Cancel") } },
        )
    }
}

/** Bottom-sheet tray to rename a road trip (no dedicated screen). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RenameRoadTripSheet(current: String, busy: Boolean, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    val sheetState = rememberModalBottomSheetState()
    var name by remember { mutableStateOf(current) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("Rename road trip", style = MaterialTheme.typography.titleLarge)
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                singleLine = true,
                label = { Text("Trip name") },
                modifier = Modifier.fillMaxWidth(),
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = onDismiss, modifier = Modifier.weight(1f)) { Text("Cancel") }
                Button(onClick = { onSave(name.trim()) }, enabled = !busy && name.trim().isNotBlank(), modifier = Modifier.weight(1f)) {
                    if (busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                    else Text("Save")
                }
            }
        }
    }
}

/** One timeline event row, capped at TWO lines: the action with its date/time
 *  alongside it (line 1), and any captured position/altitude/outside-temp (line 2). */
@Composable
private fun RoadTripEventRow(ev: RoadTripEvent) {
    val extras = buildList {
        if (ev.latitude != null && ev.longitude != null) add("%.5f, %.5f".format(ev.latitude, ev.longitude))
        ev.altitudeM?.let { add("Alt %.0f m".format(it)) }
        ev.outsideTempC?.let { add("Out %.0f°C".format(it)) }
    }
    Card(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(notificationEventIcon(ev.event), null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                // Line 1: action on the left, date/time alongside it on the right.
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(ev.detail ?: ev.event, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    formatLocalTimestamp(ev.ts)?.let {
                        Spacer(Modifier.width(8.dp))
                        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                    }
                }
                // Line 2: position / altitude / outside temp (whatever was captured).
                if (extras.isNotEmpty()) {
                    Text(extras.joinToString("  •  "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

/** "Jun 27, 2:14 PM → 4:02 PM" style window label for a road trip. */
private fun roadTripWhen(trip: RoadTrip): String {
    val start = formatLocalTimestamp(trip.startedAt) ?: "—"
    val end = if (trip.isActive) "now" else (formatLocalTimestamp(trip.endedAt) ?: "—")
    return "$start → $end"
}

/** Compact "3 trips · 1 charge · 12 km · 7 events" stat line for a road trip. */
private fun roadTripStatLine(trip: RoadTrip): String {
    val parts = buildList {
        add("${trip.segmentCount} ${if (trip.segmentCount == 1) "trip" else "trips"}")
        if (trip.chargeStops > 0) add("${trip.chargeStops} ${if (trip.chargeStops == 1) "charge" else "charges"}")
        trip.distanceKm?.let { add("${it.toInt()} km") }
        add("${trip.eventCount} ${if (trip.eventCount == 1) "event" else "events"}")
    }
    return parts.joinToString(" · ")
}

@Composable
private fun HeroGauge(vehicle: Vehicle, telemetry: Telemetry?, modifier: Modifier = Modifier, bright: Boolean = false) {
    val batteryGauge = usesBatteryGauge(vehicle.engineType)
    val rawByName = remember(telemetry) { telemetry?.rawFields?.associateBy { it.name } ?: emptyMap() }
    // BEV gauge tracks the drive battery SoC% (the curated SoCCharge% field, same as
    // the detail screen) — never the 12V battery. HEV uses fuel level.
    val pct = when {
        batteryGauge -> rawByName["SoCCharge%"]?.value?.toDoubleOrNull() ?: telemetry?.socPct
        telemetry?.fuelLevelValue != null -> telemetry.fuelLevelValue
        else -> telemetry?.socPct
    }?.coerceIn(0.0, 100.0)
    val animated by animateFloatAsState(((pct ?: 0.0) / 100.0).toFloat(), label = "gauge")
    val primary = if (bright) Color.White else MaterialTheme.colorScheme.primary
    val track = if (bright) Color.White.copy(alpha = 0.22f) else MaterialTheme.colorScheme.surfaceVariant
    // BEV gauge glyph follows the charge state (charging / charged / plugged in),
    // matching the hero status text; falls back to the plain battery glyph when the
    // car is a BEV that's disconnected, or a fuel pump for HEV/gas.
    val chargeState = chargeStateOf(telemetry)
    Box(modifier, contentAlignment = Alignment.Center) {
        // Reduced inset + thicker stroke enlarge the drawn gauge inside the same box,
        // so the surrounding pills/hero don't move.
        Canvas(Modifier.fillMaxSize().padding(6.dp)) {
            val stroke = 26.dp.toPx(); val side = size.minDimension - stroke; val topLeft = Offset((size.width - side) / 2f, (size.height - side) / 2f)
            drawArc(track, 145f, 250f, false, topLeft, Size(side, side), style = Stroke(stroke, cap = StrokeCap.Round))
            drawArc(primary, 145f, 250f * animated, false, topLeft, Size(side, side), style = Stroke(stroke, cap = StrokeCap.Round))
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            // BEV/PHEV gauge glyph:
            //  · Charging (SoCChargeDisplayStatus IN_PROGRESS) → animated filling battery
            //  · Charged / Plugged in                          → full / ev-station glyph
            //  · Disconnected (or no charge data)              → SoC-level battery bar
            // Non-battery (gas/HEV) gauges keep the fuel pump.
            when {
                !batteryGauge -> Icon(Icons.Default.LocalGasStation, null, Modifier.size(48.dp), tint = primary)
                chargeState == ChargeState.Charging -> ChargingGaugeIcon(tint = primary, modifier = Modifier.size(48.dp))
                chargeState != ChargeState.None -> Icon(painterResource(chargeState.gaugeIconRes), null, Modifier.size(48.dp), tint = primary)
                else -> Icon(painterResource(socBarIconRes(pct)), null, Modifier.size(48.dp), tint = primary)
            }
            Text(pct?.roundToInt()?.let { "$it%" } ?: "--", color = primary, fontSize = 54.sp, fontWeight = FontWeight.Black)
            Text(if (batteryGauge) "charge" else "Fuel Level", color = primary.copy(alpha = 0.78f), style = MaterialTheme.typography.titleMedium)
            if (batteryGauge) {
                telemetry?.rangeValue?.let { Text("${formatNumber(it)} ${telemetry.rangeUnit ?: "mi"} range", color = primary.copy(alpha = 0.78f)) }
            } else {
                // HEV: fuel driving range shown in KM beneath the fuel level (no "range" label).
                rawByName["FuelRange"]?.value?.takeIf { it.isNotBlank() }?.let { Text("$it KM", color = primary.copy(alpha = 0.78f), style = MaterialTheme.typography.bodySmall) }
            }
        }
    }
}

@Composable
private fun TelemetryGrid(t: Telemetry?) {
    // 2x2 grid of the four detail cards, using the same names + formatting as the
    // detail sections (sourced from the raw Ford field map).
    val raw = remember(t) { t?.rawFields?.associateBy { it.name } ?: emptyMap() }
    val odometer = raw["Odometer"]?.value?.toDoubleOrNull()?.let { "%,.1f KM".format(it) }
    val compass = raw["Compass"]?.value?.takeIf { it.isNotBlank() }
    val outsideTemp = raw["OutsideTemp"]?.takeIf { it.value.isNotBlank() }?.display
    val alarm = raw["Alarm"]?.value?.takeIf { it.isNotBlank() }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            TelemetryTile("Odometer", odometer, Icons.Default.Speed, Modifier.weight(1f))
            TelemetryTile("Compass", compass, Icons.Default.Explore, Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            TelemetryTile("Outside Temp", outsideTemp, Icons.Default.Thermostat, Modifier.weight(1f))
            TelemetryTile("Alarm", alarm, Icons.Default.Security, Modifier.weight(1f))
        }
    }
}

@Composable
private fun TelemetryTile(label: String, value: String?, icon: ImageVector, modifier: Modifier = Modifier) {
    ElevatedCard(modifier, shape = MaterialTheme.shapes.large) { Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { Icon(icon, null, tint = MaterialTheme.colorScheme.primary); Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant); Text(value ?: "—", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis) } }
}

@Composable
private fun DetailCards(vehicle: Vehicle, t: Telemetry?) {
    // name -> RawField for every raw field on the wire, so rows can format values.
    val rawByName = remember(t) { t?.rawFields?.associateBy { it.name } ?: emptyMap() }
    // Accordion: at most one section open at a time. Opening a section collapses
    // any other. Null = all collapsed. Survives rotation via rememberSaveable.
    var openSection by rememberSaveable { mutableStateOf<String?>(null) }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        // Full Ford field catalog (workbook col M=DETAIL), grouped by col N and
        // engine-scoped by col K. Sections with no applicable field are hidden.
        DetailSectionCatalog.forEach { section ->
            val visible = section.items.filter { fieldAppliesToEngine(it.engine, vehicle.engineType) }
            if (visible.any { it is DetailRow }) {
                CollapsibleSection(
                    section.title,
                    section.icon,
                    expanded = openSection == section.title,
                    onToggle = { openSection = if (openSection == section.title) null else section.title },
                ) {
                    pruneBreaks(visible).forEach { item ->
                        when (item) {
                            is DetailBreak -> DetailBreakRow(item.label)
                            is DetailRow -> KeyValue(item.label, item.value(rawByName))
                        }
                    }
                }
            }
        }
        // App-side polling context (not part of the Ford field map). Timestamps are
        // shown in the phone's local time zone.
        CollapsibleSection(
            "Polling details",
            Icons.Default.Refresh,
            expanded = openSection == "Polling details",
            onToggle = { openSection = if (openSection == "Polling details") null else "Polling details" },
        ) {
            KeyValue("Captured", formatLocalTimestamp(t?.capturedAt))
            KeyValue("Last polled", formatLocalTimestamp(t?.lastPolledAt))
            KeyValue("Last status", t?.lastStatus)
            KeyValue("Was active", t?.lastWasActive?.toString())
            KeyValue("Open activity", t?.lastHasOpenActivity?.toString())
            KeyValue("Lost car signal", t?.lastTripLostSignal?.toString())
            KeyValue("Lost Ford telemetry signal", t?.telemetryFeedLost?.toString())
        }
    }
}

/** In-section sub-header used by [DetailSection] breakers (e.g. "Tire"). */
@Composable
private fun DetailBreakRow(label: String) {
    Text(
        label.uppercase(),
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 4.dp),
    )
}

@Composable
private fun CachedFieldsScreen(state: GoHenryUiState, vm: GoHenryViewModel) {
    val rawByName = remember(state.cachedFields) { state.cachedFields.associateBy { it.name } }
    // Track which field names the catalog actually consumes so anything left over
    // (e.g. extra curated/raw fields not in the detail catalog) still shows under "Other".
    val accessed = remember(rawByName) { mutableSetOf<String>() }
    val tracking = remember(rawByName) {
        object : Map<String, RawField> by rawByName {
            override fun get(key: String): RawField? { accessed.add(key); return rawByName[key] }
        }
    }
    // The raw-data screen shows every field for every engine type (no engine filtering).
    val sections = remember(rawByName) {
        DetailSectionCatalog.mapNotNull { section ->
            val visible = pruneBreaks(section.items)
            val lines = visible.mapNotNull { item ->
                when (item) {
                    is DetailBreak -> item to null
                    is DetailRow -> item to item.value(tracking)
                }
            }
            if (visible.any { it is DetailRow }) Triple(section.title, section.icon, lines) else null
        }
    }
    val others = remember(rawByName, accessed.size) {
        state.cachedFields.filter { it.name !in accessed }.sortedBy { it.name }
    }
    LazyColumn(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item { if (state.cachedFieldsLoading) LinearProgressIndicator(Modifier.fillMaxWidth()); ErrorBanner(state.cachedFieldsError) }
        if (!state.cachedFieldsLoading && state.cachedFields.isEmpty()) item { EmptyState("No cached fields", "The backend did not return raw telemetry fields.", Icons.Default.List) }
        items(sections) { (title, icon, lines) ->
            CollapsibleSection(title, icon) {
                lines.forEach { (item, value) ->
                    when (item) {
                        is DetailBreak -> DetailBreakRow(item.label)
                        is DetailRow -> KeyValue(item.label, value, sub = fordFieldPath(item.rawNames, rawByName))
                        else -> {}
                    }
                }
            }
        }
        if (others.isNotEmpty()) item {
            CollapsibleSection("Other", Icons.Default.List) {
                others.forEach { KeyValue(it.name, it.display, sub = it.path) }
            }
        }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                IconButton(onClick = { state.selectedVehicle?.vin?.let(vm::showCachedFields) }) {
                    Icon(Icons.Default.Refresh, "Reload fields")
                }
            }
        }
    }
}

@Composable
private fun NotificationsScreen(state: GoHenryUiState, vm: GoHenryViewModel) {
    val vehicle = state.selectedVehicle
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        ErrorBanner(state.prefsError)
        // Ford linking now lives on the Settings screen (cog, top-right). This
        // screen is purely per-vehicle alert preferences + local capture.
        if (vehicle == null) { EmptyState("No vehicle selected", "Pick a vehicle from the carousel to set its alerts. To link or add a Ford account, open Settings (cog icon, top-right).", Icons.Default.Notifications); return@Column }
        val prefs = state.prefs[vehicle.vin] ?: NotifyPrefs(start = false, stop = false)
        // Engine types that don't charge (HEV / gas) hide the charge-alert toggles.
        val showCharge = usesBatteryGauge(vehicle.engineType)
        ElevatedCard(shape = MaterialTheme.shapes.extraLarge) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(vehicle.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                if (state.prefsLoading) LinearProgressIndicator(Modifier.fillMaxWidth())
                // Group: trip start / stop.
                ToggleRow("Trip Start", prefs.start) { vm.setStart(vehicle.vin, it) }
                ToggleRow("Trip Stop", prefs.stop) { vm.setStop(vehicle.vin, it) }
                if (showCharge) {
                    // Group: charge in progress / complete / error.
                    HorizontalDivider(thickness = 1.dp, color = MaterialTheme.colorScheme.outlineVariant)
                    ToggleRow("Charge in progress", prefs.chargeInProgress) { vm.setChargeInProgress(vehicle.vin, it) }
                    ToggleRow("Charge complete", prefs.chargeComplete) { vm.setChargeComplete(vehicle.vin, it) }
                    ToggleRow("Charge error", prefs.chargeError) { vm.setChargeError(vehicle.vin, it) }
                }
                // Group: safety alerts (all engine types).
                HorizontalDivider(thickness = 1.dp, color = MaterialTheme.colorScheme.outlineVariant)
                ToggleRow("Tire pressure", prefs.tirePressure) { vm.setTirePressure(vehicle.vin, it) }
                ToggleRow("Alarm triggered", prefs.alarm) { vm.setAlarm(vehicle.vin, it) }
                // Group: road-trip lifecycle (auto-start / auto-close pushes).
                HorizontalDivider(thickness = 1.dp, color = MaterialTheme.colorScheme.outlineVariant)
                ToggleRow("Road trip started", prefs.roadTripStart) { vm.setRoadTripStart(vehicle.vin, it) }
                ToggleRow("Road trip ended", prefs.roadTripEnd) { vm.setRoadTripEnd(vehicle.vin, it) }
                // Group: lost signal / Ford telemetry (kept last in the list).
                HorizontalDivider(thickness = 1.dp, color = MaterialTheme.colorScheme.outlineVariant)
                ToggleRow("Lost car signal", prefs.lostSignal) { vm.setLostSignal(vehicle.vin, it) }
                ToggleRow("Lost Ford telemetry signal", prefs.telemetryFeedLost) { vm.setTelemetryFeedLost(vehicle.vin, it) }
            }
        }
        ElevatedCard(shape = MaterialTheme.shapes.extraLarge) { Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) { Text("Local notification capture", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold); ToggleRow("Save recent alerts for this VIN", state.notificationTrackingVins.contains(vehicle.vin)) { vm.setNotificationTracking(vehicle.vin, it) }; Text("Retention: ${state.notificationTrackingDays} day(s)", style = MaterialTheme.typography.titleMedium); Slider(value = state.notificationTrackingDays.toFloat(), onValueChange = { vm.setNotificationTrackingDays(it.roundToInt()) }, valueRange = NotificationStore.MIN_DAYS.toFloat()..NotificationStore.MAX_DAYS.toFloat(), steps = NotificationStore.MAX_DAYS - NotificationStore.MIN_DAYS - 1) } }
    }
}

@Composable
private fun ToggleRow(label: String, checked: Boolean, onChecked: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) { Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge); Switch(checked = checked, onCheckedChange = onChecked) }
}

/**
 * Account-level Settings screen (cog, top-right). Owns Ford authorization — the
 * ONLY place to link Ford the first time, re-link before the token expires, and
 * add additional vehicles — plus a self-diagnostics panel that validates the
 * backend config and surfaces any current errors so problems are debuggable
 * in-app without a logcat.
 */
@Composable
private fun SettingsScreen(state: GoHenryUiState, vm: GoHenryViewModel) {
    val linkedCount = state.fordAccounts.count { it.isLinked }
    val configuredCount = state.fordAccounts.size

    // Accordion: at most one section open at a time. Opening a section closes any
    // other and scrolls its header to the top of the window (the header glides up as
    // the expand/collapse animation settles). Survives rotation via rememberSaveable.
    val scrollState = rememberScrollState()
    var openSection by rememberSaveable { mutableStateOf<String?>(null) }
    var viewportTopY by remember { mutableStateOf(0f) }
    val sectionTopY = remember { mutableStateMapOf<String, Float>() }
    fun sectionModifier(title: String): Modifier =
        Modifier.onGloballyPositioned { sectionTopY[title] = it.positionInRoot().y }
    fun toggleSection(title: String) { openSection = if (openSection == title) null else title }
    // Follow the opening section's header to the top for the duration of the
    // animateContentSize transition, snapping each frame so it lands flush at the top.
    LaunchedEffect(openSection) {
        val title = openSection ?: return@LaunchedEffect
        repeat(24) {
            val y = sectionTopY[title] ?: return@LaunchedEffect
            val target = (scrollState.value + (y - viewportTopY)).roundToInt().coerceAtLeast(0)
            if (kotlin.math.abs(target - scrollState.value) > 1) scrollState.scrollTo(target)
            withFrameNanos { }
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .onGloballyPositioned { viewportTopY = it.positionInRoot().y }
            .verticalScroll(scrollState)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        ErrorBanner(state.fordError)

        // --- Ford authorization (link / re-link, one collapsible card per app) ---
        CollapsibleSection(
            title = "Ford authorization",
            icon = Icons.Default.Lock,
            modifier = sectionModifier("Ford authorization"),
            expanded = openSection == "Ford authorization",
            onToggle = { toggleSection("Ford authorization") },
            trailing = { IconButton(onClick = vm::loadFordAccounts) { Icon(Icons.Default.Refresh, "Refresh Ford accounts") } },
        ) {
            Text("One card per Ford app. Each Ford sign-in unlocks a single vehicle. Tap a card to expand, then Link/Re-link to authorize or refresh before the token expires — your cars and telemetry are kept.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (state.fordLoading) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (!state.fordLoading && state.fordAccounts.isEmpty()) Text("No Ford apps configured on the backend yet.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            state.fordAccounts.forEach { account ->
                val nickname = if (account.isPrimary) state.vehicles.firstOrNull()?.nickname?.takeIf { it.isNotBlank() } else null
                FordAccountRow(account, nickname, state.fordReauthingSlug == account.appSlug, state.cardColorForSlug(account.appSlug), { vm.setCardColor(account.appSlug, it) }) { vm.startFordReauth(account.appSlug) }
            }
        }

        // --- Add another vehicle (up to 5) helper ---
        CollapsibleSection(
            title = "Add another vehicle",
            icon = Icons.Default.Add,
            modifier = sectionModifier("Add another vehicle"),
            expanded = openSection == "Add another vehicle",
            onToggle = { toggleSection("Add another vehicle") },
        ) {
            val unlinked = state.fordAccounts.count { !it.isLinked }
            Text("GoHenry tracks up to 5 cars. Because one Ford sign-in unlocks only one vehicle, each extra car needs its own Ford app (slug) configured on the backend.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            when {
                unlinked > 0 -> Text("$unlinked Ford app${if (unlinked == 1) "" else "s"} above ${if (unlinked == 1) "is" else "are"} configured but not linked. Expand a card above and tap Link to authorize the next vehicle.", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
                configuredCount in 1..4 -> Text("All $configuredCount configured app${if (configuredCount == 1) "" else "s"} ${if (configuredCount == 1) "is" else "are"} linked. To add another car, register a new Ford app and re-run the installer (PowerShell: -ExtraFordApps, or answer \"Add another vehicle now?\"). The new app then appears here as a Link card.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                configuredCount >= 5 -> Text("You've reached the 5-vehicle maximum for GoHenry.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        // --- Polling cadence (how often the backend fetches Ford telemetry) ---
        CollapsibleSection(
            title = "Polling cadence",
            iconPainter = painterResource(R.drawable.bigtop_updates_24px),
            modifier = sectionModifier("Polling cadence"),
            expanded = openSection == "Polling cadence",
            onToggle = { toggleSection("Polling cadence") },
            trailing = { IconButton(onClick = vm::loadPollCadence) { Icon(Icons.Default.Refresh, "Reload cadence") } },
        ) {
            // Local drag value so the slider is smooth; we only call the backend when
            // the gesture ends (onValueChangeFinished), then reconcile with its echo.
            var dragMinutes by remember(state.pollCadenceMinutes) { mutableStateOf(state.pollCadenceMinutes.toFloat()) }
            val minutes = dragMinutes.roundToInt()
            Text("How often GoHenry fetches Ford telemetry for every car. Lower = fresher data and quicker alerts; higher = lighter on the Ford API and battery.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (state.pollCadenceLoading) LinearProgressIndicator(Modifier.fillMaxWidth())
            Text("Every $minutes minute${if (minutes == 1) "" else "s"}", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Slider(
                value = dragMinutes,
                onValueChange = { dragMinutes = it },
                onValueChangeFinished = { vm.setPollCadence(dragMinutes.roundToInt()) },
                valueRange = 1f..10f,
                steps = 8,
            )
            Text("Min 1 min · Max 10 min · Default 2 min", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        // --- Lost signal (how many missed polls before a car is flagged offline) ---
        CollapsibleSection(
            title = "Lost signal",
            icon = Icons.Outlined.SignalWifiBad,
            modifier = sectionModifier("Lost signal"),
            expanded = openSection == "Lost signal",
            onToggle = { toggleSection("Lost signal") },
            trailing = { IconButton(onClick = vm::loadPollCadence) { Icon(Icons.Default.Refresh, "Reload settings") } },
        ) {
            // Local drag value for a smooth slider; backend is called on gesture end.
            var dragPolls by remember(state.lostSignalPolls) { mutableStateOf(state.lostSignalPolls.toFloat()) }
            val polls = dragPolls.roundToInt()
            val approxMinutes = polls * state.pollCadenceMinutes
            Text("How many polls in a row a car can look offline before GoHenry flags it — flipping the wifi icon on its card and (if enabled) sending an alert. This one threshold governs BOTH the \"Telemetry feed lost\" alert (the Ford feed is unreachable) and the \"Lost signal\" alert (the feed is reachable but the car's data is frozen with the ignition on). It scales with your polling cadence.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (state.pollCadenceLoading) LinearProgressIndicator(Modifier.fillMaxWidth())
            Text("$polls missed poll${if (polls == 1) "" else "s"}", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text("≈ ${formatApproxDuration(approxMinutes)} at the current ${state.pollCadenceMinutes}-min cadence", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Slider(
                value = dragPolls,
                onValueChange = { dragPolls = it },
                onValueChangeFinished = { vm.setLostSignalPolls(dragPolls.roundToInt()) },
                valueRange = 5f..20f,
                steps = 14,
            )
            Text("Min 5 polls · Max 20 polls · Default 10 polls", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        // --- Road-trip automation (auto-start + auto-close safety net) ---
        CollapsibleSection(
            title = "Road-trip automation",
            icon = Icons.Default.Explore,
            modifier = sectionModifier("Road-trip automation"),
            expanded = openSection == "Road-trip automation",
            onToggle = { toggleSection("Road-trip automation") },
            trailing = { IconButton(onClick = vm::loadRoadTripSettings) { Icon(Icons.Default.Refresh, "Reload road-trip settings") } },
        ) {
            val rt = state.roadTripSettings
            if (state.roadTripSettingsLoading) LinearProgressIndicator(Modifier.fillMaxWidth())
            Text("When a car starts moving, automatically open a road trip and close it once it has been idle or has run too long. Manual start/stop still works any time.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            ToggleRow("Auto-start trips on first movement", rt.autoStart) { vm.setRoadTripSettings(it, rt.idleHours, rt.maxDays, rt.endOnStop) }
            ToggleRow("End the trip on stop", rt.endOnStop) { vm.setRoadTripSettings(rt.autoStart, rt.idleHours, rt.maxDays, it) }
            Text("When on, a car's 'stop' alert (ignition off) also closes any open road trip — no waiting for the idle timer.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

            var dragIdle by remember(rt.idleHours) { mutableStateOf(rt.idleHours.toFloat()) }
            val idle = dragIdle.roundToInt()
            Text("Auto-close after $idle idle hour${if (idle == 1) "" else "s"}", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Slider(
                value = dragIdle,
                onValueChange = { dragIdle = it },
                onValueChangeFinished = { vm.setRoadTripSettings(rt.autoStart, dragIdle.roundToInt(), rt.maxDays, rt.endOnStop) },
                valueRange = 2f..12f,
                steps = 9,
            )

            var dragMax by remember(rt.maxDays) { mutableStateOf(rt.maxDays.toFloat()) }
            val maxD = dragMax.roundToInt()
            Text("Hard cap: close after $maxD day${if (maxD == 1) "" else "s"}", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Slider(
                value = dragMax,
                onValueChange = { dragMax = it },
                onValueChangeFinished = { vm.setRoadTripSettings(rt.autoStart, rt.idleHours, dragMax.roundToInt(), rt.endOnStop) },
                valueRange = 1f..7f,
                steps = 5,
            )
            Text("Idle 2–12 h (default 12) · Max 1–7 days (default 7)", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        // --- Background activity (battery-optimization exemption for reliable alerts) ---
        CollapsibleSection(
            title = "Background activity",
            iconPainter = painterResource(R.drawable.arrow_shape_up_stack_24px),
            modifier = sectionModifier("Background activity"),
            expanded = openSection == "Background activity",
            onToggle = { toggleSection("Background activity") },
        ) {
            BackgroundActivityContent()
        }

        // --- Notification diagnostics (verify alerts can actually reach THIS phone) ---
        NotificationDiagnosticsSection(
            modifier = sectionModifier("Notification diagnostics"),
            expanded = openSection == "Notification diagnostics",
            onToggle = { toggleSection("Notification diagnostics") },
        )

        // --- Diagnostics (validate config + surface current errors) ---
        CollapsibleSection(
            title = "Diagnostics",
            icon = Icons.Default.Info,
            modifier = sectionModifier("Diagnostics"),
            expanded = openSection == "Diagnostics",
            onToggle = { toggleSection("Diagnostics") },
        ) {
            KeyValue("Backend reachable", if (state.error == null && (state.vehicles.isNotEmpty() || state.fordAccounts.isNotEmpty())) "yes" else if (state.error != null) "no — see error below" else "unknown — run check")
            KeyValue("Vehicles loaded", state.vehicles.size.toString())
            KeyValue("Ford apps configured", configuredCount.toString())
            KeyValue("Ford apps linked", linkedCount.toString())
            Divider()
            Text("Backend configuration (from local.properties)", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(vm.configSummary(), style = MaterialTheme.typography.bodySmall, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
            if (!state.error.isNullOrBlank() || !state.fordError.isNullOrBlank()) {
                Divider()
                Text("Recent errors", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                state.error?.let { ErrorBanner(it) }
                state.fordError?.let { ErrorBanner(it) }
            }
            Button(onClick = { vm.loadFleet(); vm.loadFordAccounts() }, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Default.Refresh, null); Spacer(Modifier.width(8.dp)); Text("Run connectivity check") }
        }

        // --- About (light-hearted writeup + a spinnable brand mark) ---
        CollapsibleSection(
            title = "About GoHenry",
            iconPainter = painterResource(R.drawable.note_stack_24px),
            modifier = sectionModifier("About GoHenry"),
            expanded = openSection == "About GoHenry",
            onToggle = { toggleSection("About GoHenry") },
        ) {
            // The brand mark mirrors the cold-start splash glyph at the same 150.dp
            // size. Each tap plays ONE randomly-chosen flourish — spin right, spin
            // left, expand, shrink, flash, or wobble — driven through a graphicsLayer
            // (rotation/scale/alpha). No press ripple/shading on the icon itself.
            val rot = remember { Animatable(0f) }
            val scl = remember { Animatable(1f) }
            val alp = remember { Animatable(1f) }
            val animScope = rememberCoroutineScope()
            val iconInteraction = remember { MutableInteractionSource() }
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                Icon(
                    painterResource(R.drawable.ic_blur_on), "Tap to animate the GoHenry mark",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .size(150.dp)
                        .graphicsLayer {
                            rotationZ = rot.value
                            scaleX = scl.value
                            scaleY = scl.value
                            alpha = alp.value
                        }
                        .clickable(interactionSource = iconInteraction, indication = null) {
                            animScope.launch {
                                // Start each flourish from a clean baseline so taps don't compound.
                                rot.snapTo(0f); scl.snapTo(1f); alp.snapTo(1f)
                                when (Random.nextInt(6)) {
                                    0 -> rot.animateTo(360f, tween(900, easing = FastOutSlowInEasing))   // spin right
                                    1 -> rot.animateTo(-360f, tween(900, easing = FastOutSlowInEasing))  // spin left
                                    2 -> scl.animateTo(1f, keyframes {                                    // expand
                                        durationMillis = 600
                                        1.0f at 0
                                        1.5f at 300 using FastOutSlowInEasing
                                        1.0f at 600
                                    })
                                    3 -> scl.animateTo(1f, keyframes {                                    // shrink
                                        durationMillis = 600
                                        1.0f at 0
                                        0.55f at 300 using FastOutSlowInEasing
                                        1.0f at 600
                                    })
                                    4 -> alp.animateTo(1f, keyframes {                                    // flash
                                        durationMillis = 700
                                        1.0f at 0
                                        0.15f at 150
                                        1.0f at 300
                                        0.15f at 450
                                        1.0f at 700
                                    })
                                    else -> rot.animateTo(0f, keyframes {                                 // wobble
                                        durationMillis = 700
                                        0f at 0
                                        -18f at 150 using FastOutSlowInEasing
                                        18f at 350 using FastOutSlowInEasing
                                        -10f at 500 using FastOutSlowInEasing
                                        0f at 700
                                    })
                                }
                            }
                        },
                )
            }
            Text(
                "GoHenry is a small hobby app for keeping tabs on a personal fleet of up to five Ford EVs across up to five phones. It polls each car on a schedule, shows live status in the carousel, and sends a notification when something noteworthy happens — a trip starts or ends, a charge completes, or a car stops reporting.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                "It runs on a lightweight, SQL-free Azure backend paired with a Material 3 Android app. Most behaviour is configurable here in Settings: how often cars are polled, how many missed or frozen polls count as \"offline\", and how road trips are automatically opened and closed.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                "Full setup steps, architecture notes, and the complete notification reference live in the project README that ships with the app.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * Body of the "Background activity" settings section (ported from FleetFoot's
 * key settings). GoHenry's trip & charge banners are DATA-ONLY FCM messages, so
 * a restricted App-Standby bucket can defer or drop them. Exempting GoHenry from
 * battery optimization keeps it wakeable. Re-checks the exemption on every
 * ON_RESUME so it flips to "allowed" the moment the user returns from the dialog.
 */
@Composable
private fun BackgroundActivityContent() {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var ignoring by remember { mutableStateOf(isIgnoringBatteryOptimizations(context)) }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) ignoring = isIgnoringBatteryOptimizations(context)
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    Text("Prioritize background activity so data-only trip and charge alerts arrive without being delayed or dropped by battery restrictions.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    if (ignoring) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.CheckCircle, null, tint = Color(0xFF2E7D32))
            Spacer(Modifier.width(10.dp))
            Text("Unrestricted — alerts arrive any time.", style = MaterialTheme.typography.bodyMedium)
        }
    } else {
        Row(verticalAlignment = Alignment.Top) {
            Icon(Icons.Default.WarningAmber, null, tint = MaterialTheme.colorScheme.error)
            Spacer(Modifier.width(10.dp))
            Text("Battery optimization is on, so alerts can be delayed or dropped. Set GoHenry to \u201CUnrestricted\u201D for reliable delivery.", style = MaterialTheme.typography.bodyMedium)
        }
        Button(onClick = { openBatteryOptimizationSettings(context) }) {
            Icon(Icons.Default.BatteryFull, null)
            Spacer(Modifier.width(8.dp))
            Text("Allow background")
        }
    }
}

/** True when GoHenry is exempt from battery optimization (i.e. "Unrestricted"). */
private fun isIgnoringBatteryOptimizations(context: android.content.Context): Boolean {
    val pm = context.getSystemService(android.content.Context.POWER_SERVICE) as? android.os.PowerManager ?: return true
    return pm.isIgnoringBatteryOptimizations(context.packageName)
}

/**
 * Opens the battery-optimization exemption UI, most-direct first: the per-app
 * "allow?" dialog (needs REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, declared in the
 * manifest), then the full list, then app details. Each launch is guarded so an
 * OEM that blocks one action falls through to the next.
 */
private fun openBatteryOptimizationSettings(context: android.content.Context) {
    val pkg = context.packageName
    val candidates = listOf(
        Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$pkg")),
        Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS),
        Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$pkg")),
    )
    for (intent in candidates) {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (runCatching { context.startActivity(intent) }.isSuccess) return
    }
}

/**
 * Settings ▸ Notification diagnostics. A read-only, on-device self-check that
 * tells the user whether a backend alert can actually land on THIS phone, plus
 * a local "send test notification" that exercises the real alert channel without
 * touching the backend or FCM. SQL-free: every value comes from OS state or the
 * app's own SharedPreferences ([NotificationDiagnostics.collect]). Re-collects on
 * every ON_RESUME so it refreshes the moment the user returns from a system
 * settings screen (e.g. after granting permission).
 */
@Composable
private fun NotificationDiagnosticsSection(
    modifier: Modifier = Modifier,
    expanded: Boolean? = null,
    onToggle: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var tick by remember { mutableStateOf(0) }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) tick++
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val diag = remember(tick) { NotificationDiagnostics.collect(context) }

    CollapsibleSection(
        title = "Notification diagnostics",
        icon = Icons.Default.Notifications,
        modifier = modifier,
        expanded = expanded,
        onToggle = onToggle,
        trailing = { IconButton(onClick = { tick++ }) { Icon(Icons.Default.Refresh, "Re-check") } },
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                if (diag.deliverable) Icons.Default.CheckCircle else Icons.Default.WarningAmber,
                null,
                tint = if (diag.deliverable) Color(0xFF2E7D32) else MaterialTheme.colorScheme.error,
            )
            Spacer(Modifier.width(10.dp))
            Text(
                if (diag.deliverable) "Ready — alerts can reach this phone." else "Blocked — fix the items marked below.",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
            )
        }

        Divider()
        Text("Device permission", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
        KeyValue("Notification permission", when {
            !diag.postPermissionRequired -> "granted (pre-Android 13)"
            diag.postPermissionGranted -> "granted"
            else -> "DENIED — tap Open settings"
        })
        KeyValue("System notifications", if (diag.systemNotificationsEnabled) "on" else "OFF — tap Open settings")
        KeyValue("Alert channel", when {
            !diag.channelExists -> "missing — send a test"
            diag.channelBlocked -> "blocked — tap Open settings"
            else -> "active"
        })

        Divider()
        Text("Push registration", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
        KeyValue("FCM token", diag.tokenPrefix ?: "not registered yet")
        KeyValue("Last registered", NotificationDiagnostics.millisToLocal(diag.lastRegisteredAtMillis))
        KeyValue("Last result", diag.lastRegisterOk?.let { if (it) "ok" else "failed" } ?: "never")
        KeyValue("Last push received", NotificationDiagnostics.millisToLocal(diag.lastPushSeenMillis))

        Divider()
        Text("Local capture", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
        KeyValue("Tracked vehicles", diag.captureEnabledVinCount.toString())
        KeyValue("Retention (days)", diag.captureRetentionDays.toString())
        KeyValue("Stored alerts", diag.capturedTotal.toString())

        Divider()
        Button(
            onClick = { GoHenryFcmService.postTestNotification(context); tick++ },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(Icons.Default.Notifications, null)
            Spacer(Modifier.width(8.dp))
            Text("Send test notification")
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = { openAppNotificationSettings(context) }, modifier = Modifier.weight(1f)) {
                Icon(Icons.Default.Settings, null)
                Spacer(Modifier.width(6.dp))
                Text("Open settings")
            }
            TextButton(onClick = { shareDiagnostics(context, diag) }, modifier = Modifier.weight(1f)) {
                Icon(Icons.Default.Share, null)
                Spacer(Modifier.width(6.dp))
                Text("Share")
            }
        }
        Text(
            "\u201CSend test notification\u201D posts a local banner through the real alert channel — it never touches the backend or your saved alert history.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * Opens the OS notification settings for GoHenry, most-specific first: the alert
 * channel page, then the app-notification page, then app details. Each launch is
 * guarded so an OEM that blocks one action falls through to the next.
 */
private fun openAppNotificationSettings(context: android.content.Context) {
    val candidates = mutableListOf<Intent>()
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        candidates += Intent(android.provider.Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS).apply {
            putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, context.packageName)
            putExtra(android.provider.Settings.EXTRA_CHANNEL_ID, GoHenryFcmService.CHANNEL_ID)
        }
        candidates += Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
            putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, context.packageName)
        }
    }
    candidates += Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))
    for (intent in candidates) {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (runCatching { context.startActivity(intent) }.isSuccess) return
    }
}

/** Shares the plain-text diagnostics report via the system chooser. */
private fun shareDiagnostics(context: android.content.Context, diag: NotificationDiagnostics) {
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_SUBJECT, "GoHenry notification diagnostics")
        putExtra(Intent.EXTRA_TEXT, NotificationDiagnostics.buildShareText(diag))
    }
    runCatching {
        context.startActivity(
            Intent.createChooser(send, "Share diagnostics").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}

/**
 * Reusable, animated collapsible section card. Default COLLAPSED. A tap anywhere
 * on the header toggles it (chevron rotates, body fades + expands). In dark mode
 * a subtle outline border lifts the card off the background for extra contrast.
 */
@Composable
private fun CollapsibleSection(
    title: String,
    icon: ImageVector? = null,
    iconPainter: Painter? = null,
    modifier: Modifier = Modifier,
    defaultExpanded: Boolean = false,
    expanded: Boolean? = null,
    onToggle: (() -> Unit)? = null,
    trailing: @Composable (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    // Controlled (caller owns the open/closed state, e.g. accordion) vs. the
    // default uncontrolled mode that keeps its own remembered state.
    var internalExpanded by rememberSaveable(title) { mutableStateOf(defaultExpanded) }
    val isExpanded = expanded ?: internalExpanded
    val toggle = onToggle ?: { internalExpanded = !internalExpanded }
    val rotation by animateFloatAsState(if (isExpanded) 180f else 0f, label = "section-chevron")
    val shape = MaterialTheme.shapes.extraLarge
    val borderMod = if (isSystemInDarkTheme()) Modifier.border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape) else Modifier
    ElevatedCard(shape = shape, modifier = modifier.fillMaxWidth().then(borderMod).animateContentSize()) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(Modifier.fillMaxWidth().clickable { toggle() }, verticalAlignment = Alignment.CenterVertically) {
                if (iconPainter != null) Icon(iconPainter, null, tint = MaterialTheme.colorScheme.primary)
                else if (icon != null) Icon(icon, null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(10.dp))
                Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                if (trailing != null) trailing()
                Icon(Icons.Default.ExpandMore, if (isExpanded) "Collapse" else "Expand", Modifier.rotate(rotation))
            }
            AnimatedVisibility(isExpanded) { Column(verticalArrangement = Arrangement.spacedBy(12.dp), content = content) }
        }
    }
}

/**
 * One Ford app, collapsible. COLLAPSED shows just the status check icon, the
 * slug name (with a "primary" tag), the linked vehicle nickname, and the status
 * label. EXPANDED reveals the full timeline details and the Link / Re-link
 * action, so the list stays scannable until you act on a specific car.
 */
@Composable
private fun FordAccountRow(account: FordAccountStatus, vehicleNickname: String?, loading: Boolean, currentColor: Int, onPickColor: (Int) -> Unit, onRelink: () -> Unit) {
    var expanded by rememberSaveable(account.appSlug) { mutableStateOf(false) }
    var showColorPicker by remember { mutableStateOf(false) }
    val rotation by animateFloatAsState(if (expanded) 180f else 0f, label = "ford-chevron")
    val ok = account.isLinked && !account.needsReauth
    val statusColor = if (ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
    val shape = MaterialTheme.shapes.large
    val borderMod = if (isSystemInDarkTheme()) Modifier.border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape) else Modifier
    ElevatedCard(shape = shape, modifier = Modifier.fillMaxWidth().then(borderMod).animateContentSize()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth().clickable { expanded = !expanded }, verticalAlignment = Alignment.CenterVertically) {
                Icon(if (ok) Icons.Default.CheckCircle else Icons.Default.Info, null, tint = statusColor)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(account.appSlug.replaceFirstChar { it.uppercase() }, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        if (account.isPrimary) { Spacer(Modifier.width(8.dp)); Text("primary", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold) }
                    }
                    if (vehicleNickname != null) Text(vehicleNickname, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(account.status, style = MaterialTheme.typography.labelMedium, color = statusColor, fontWeight = FontWeight.Medium)
                }
                Icon(Icons.Default.ExpandMore, if (expanded) "Collapse" else "Expand", Modifier.rotate(rotation))
            }
            AnimatedVisibility(expanded) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    KeyValue("Linked", account.isLinked.toString())
                    KeyValue("Primary", account.isPrimary.toString())
                    KeyValue("Last refresh", account.lastRefreshAt)
                    KeyValue("Days until re-auth", account.daysUntilReauth?.toString())
                    // Per-slug carousel/notification card color. Tapping the swatch
                    // opens a palette (default dark grey) so each car can be tinted.
                    Row(Modifier.fillMaxWidth().clickable { showColorPicker = true }.padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("Card color", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Box(Modifier.size(28.dp).clip(CircleShape).background(Color(currentColor)).border(1.dp, MaterialTheme.colorScheme.outline, CircleShape))
                        Spacer(Modifier.width(8.dp))
                        Icon(Icons.Default.Edit, "Change card color", tint = MaterialTheme.colorScheme.primary)
                    }
                    Button(onClick = onRelink, enabled = !loading, modifier = Modifier.fillMaxWidth()) {
                        if (loading) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp) else Icon(Icons.Default.OpenInBrowser, null)
                        Spacer(Modifier.width(8.dp)); Text(if (account.isLinked) "Re-link" else "Link")
                    }
                }
            }
        }
    }
    if (showColorPicker) {
        CardColorPickerDialog(
            current = currentColor,
            onPick = { onPickColor(it); showColorPicker = false },
            onDismiss = { showColorPicker = false },
        )
    }
}

/**
 * Simple swatch palette for choosing a slug's carousel/notification card color.
 * The first swatch is the dark-grey default; the rest are muted Material hues that
 * read well behind the card's white-tinted content and gradient.
 */
@Composable
private fun CardColorPickerDialog(current: Int, onPick: (Int) -> Unit, onDismiss: () -> Unit) {
    val swatches = listOf(
        CardColorStore.DEFAULT_CARD_COLOR, // dark grey (default)
        0xFF455A64.toInt(), // blue grey
        0xFF1565C0.toInt(), // blue
        0xFF00838F.toInt(), // teal
        0xFF2E7D32.toInt(), // green
        0xFF6A1B9A.toInt(), // purple
        0xFFAD1457.toInt(), // magenta
        0xFFC62828.toInt(), // red
        0xFFEF6C00.toInt(), // orange
        0xFF5D4037.toInt(), // brown
        0xFFE0E0E0.toInt(), // light grey
        0xFFC0C0C0.toInt(), // silver
        0xFF0D47A1.toInt(), // dark blue
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
        title = { Text("Card color") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                swatches.chunked(5).forEach { rowColors ->
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        rowColors.forEach { c ->
                            val selected = c == current
                            Box(
                                Modifier.size(40.dp).clip(CircleShape).background(Color(c))
                                    .border(if (selected) 3.dp else 1.dp, if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline, CircleShape)
                                    .clickable { onPick(c) },
                                contentAlignment = Alignment.Center,
                            ) {
                                if (selected) Icon(Icons.Default.Check, "Selected", tint = if (Color(c).luminance() > 0.5f) Color.Black else Color.White)
                            }
                        }
                    }
                }
            }
        },
    )
}

@Composable
private fun NotificationHistoryScreen(state: GoHenryUiState, vm: GoHenryViewModel) {
    val engineByVin = remember(state.vehicles) { state.vehicles.associate { it.vin to it.engineType } }
    val colorByVin = remember(state.vehicles, state.slugCardColors) { state.vehicles.associate { it.vin to state.cardColorForSlug(it.appSlug) } }
    val vin = state.notificationHistoryVin
    // Title shows the vehicle nickname (not the raw VIN); fall back to a captured
    // nickname or a neutral label if the car isn't in the current fleet.
    val title = state.vehicles.find { it.vin == vin }?.title
        ?: state.notificationHistory.firstOrNull { it.vin == vin }?.data?.get("nickname")?.takeIf { it.isNotBlank() }
        ?: "Selected vehicle"
    HistoryList(title, "Newest first", state.notificationHistory, "No captured alerts for this vehicle.", engineByVin, colorByVin)
}

/**
 * "All recent alerts" — a swipeable, infinite-loop carousel over the fleet. The
 * first page shows every vehicle; each subsequent page restricts the list to one
 * car, with that car's nickname in the header. Swipe left/right to change the
 * filter (the old dropdown is gone).
 */
@Composable
private fun CombinedHistoryScreen(state: GoHenryUiState, vm: GoHenryViewModel) {
    val engineByVin = remember(state.vehicles) { state.vehicles.associate { it.vin to it.engineType } }
    val colorByVin = remember(state.vehicles, state.slugCardColors) { state.vehicles.associate { it.vin to state.cardColorForSlug(it.appSlug) } }
    // null = "All tracked vehicles"; the rest are per-vehicle filter pages.
    val pages = remember(state.vehicles) { listOf<Vehicle?>(null) + state.vehicles }
    val size = pages.size
    val loop = size > 1
    val pageCount = if (loop) 100_000 else size
    val initialPage = remember(size) { if (loop) { val mid = pageCount / 2; mid - (mid % size) } else 0 }
    val pagerState = rememberPagerState(initialPage = initialPage, pageCount = { pageCount })
    // Keep the ViewModel's filter in sync with the visible page so the top-bar
    // CSV share exports exactly what's on screen.
    LaunchedEffect(pagerState.currentPage, size) {
        if (size > 0) vm.setCombinedHistoryFilter(pages[((pagerState.currentPage % size) + size) % size]?.vin)
    }
    HorizontalPager(state = pagerState, modifier = Modifier.fillMaxSize()) { page ->
        val idx = ((page % size) + size) % size
        val vehicle = pages[idx]
        val rows = if (vehicle == null) state.combinedHistory else state.combinedHistory.filter { it.vin == vehicle.vin }
        val title = vehicle?.title ?: "All tracked vehicles"
        val subtitle = if (size > 1) "‹ swipe to change vehicle ›" else "Newest first"
        val empty = if (vehicle == null) "No captured alerts across tracked vehicles." else "No captured alerts for ${vehicle.title}."
        HistoryList(title, subtitle, rows, empty, engineByVin, colorByVin)
    }
}

/** Shared width for the two bottom-corner home-screen buttons (history + settings). */
private val CornerButtonWidth = 100.dp

/**
 * Material 3 split button for the home screen's bottom-left history shortcut.
 * The wide primary area opens notification history (the default); the narrow ▼
 * area opens a dropdown to pick Notification or Road-trip history. The menu
 * animates in and dismisses when the user taps outside it.
 */
@Composable
private fun HistorySplitButton(
    onPrimary: () -> Unit,
    onNotification: () -> Unit,
    onRoadTrip: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var menuOpen by remember { mutableStateOf(false) }
    Box(modifier) {
        Surface(
            modifier = Modifier.width(CornerButtonWidth).height(56.dp),
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            shadowElevation = 6.dp,
            tonalElevation = 6.dp,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // Primary action: notification history (default). Fills the width left
                // over after the fixed-width ▼ area.
                Box(
                    Modifier.weight(1f).fillMaxHeight()
                        .clickable(onClick = onPrimary)
                        .semantics { contentDescription = "Show notification history" },
                    contentAlignment = Alignment.Center,
                ) { Icon(Icons.Default.History, null) }
                VerticalDivider(
                    modifier = Modifier.height(28.dp),
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.3f),
                )
                // Secondary action: choose history type.
                Box(
                    Modifier.width(40.dp).fillMaxHeight()
                        .clickable { menuOpen = true }
                        .semantics { contentDescription = "Choose history type" },
                    contentAlignment = Alignment.Center,
                ) {
                    val rotation by animateFloatAsState(if (menuOpen) 180f else 0f, label = "history-split-chevron")
                    Icon(Icons.Default.ArrowDropDown, null, Modifier.rotate(rotation))
                }
            }
        }
        // Anchored to the whole button (not the narrow ▼); since the button sits at
        // the screen bottom, the menu opens directly ABOVE it.
        DropdownMenu(
            expanded = menuOpen,
            onDismissRequest = { menuOpen = false },
            offset = DpOffset(0.dp, 8.dp),
        ) {
            DropdownMenuItem(
                text = { Text("Notification history") },
                leadingIcon = { Icon(Icons.Default.Notifications, null) },
                onClick = { menuOpen = false; onNotification() },
            )
            DropdownMenuItem(
                text = { Text("Road trip history") },
                leadingIcon = { Icon(Icons.Default.Explore, null) },
                onClick = { menuOpen = false; onRoadTrip() },
            )
        }
    }
}

/**
 * "Road trip history" — a swipeable, infinite-loop carousel over the fleet,
 * mirroring [CombinedHistoryScreen]. Page 0 shows every car's trips merged;
 * each later page filters to one car. Tap a trip to open its detail; the
 * top-bar share button exports the visible page to CSV.
 */
@Composable
private fun RoadTripHistoryScreen(state: GoHenryUiState, vm: GoHenryViewModel) {
    val colorByVin = remember(state.vehicles, state.slugCardColors) { state.vehicles.associate { it.vin to state.cardColorForSlug(it.appSlug) } }
    val pages = remember(state.vehicles) { listOf<Vehicle?>(null) + state.vehicles }
    val size = pages.size
    val loop = size > 1
    val pageCount = if (loop) 100_000 else size
    val initialPage = remember(size) { if (loop) { val mid = pageCount / 2; mid - (mid % size) } else 0 }
    val pagerState = rememberPagerState(initialPage = initialPage, pageCount = { pageCount })
    LaunchedEffect(pagerState.currentPage, size) {
        if (size > 0) vm.setRoadTripHistoryFilter(pages[((pagerState.currentPage % size) + size) % size]?.vin)
    }
    HorizontalPager(state = pagerState, modifier = Modifier.fillMaxSize()) { page ->
        val idx = ((page % size) + size) % size
        val vehicle = pages[idx]
        val trips = state.roadTripHistoryFor(vehicle?.vin)
        val title = vehicle?.title ?: "All tracked vehicles"
        val subtitle = if (size > 1) "‹ swipe to change vehicle ›" else "Active first, then newest"
        val empty = if (vehicle == null) "No road trips across tracked vehicles." else "No road trips for ${vehicle.title}."
        RoadTripHistoryList(title, subtitle, trips, empty, state.roadTripHistoryLoading, state.roadTripHistoryError, colorByVin) { trip ->
            vm.openRoadTripFromHistory(trip.vin, trip.id)
        }
    }
}

/** Title + subtitle header over a scrollable list of road-trip rows (or empty/loading state). */
@Composable
private fun RoadTripHistoryList(
    title: String,
    subtitle: String,
    trips: List<RoadTrip>,
    empty: String,
    loading: Boolean,
    error: String?,
    colorByVin: Map<String, Int>,
    onOpen: (RoadTrip) -> Unit,
) {
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Column(Modifier.padding(top = 16.dp, bottom = 4.dp)) {
                Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text(subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        error?.let { item { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) } }
        if (loading && trips.isEmpty()) {
            item { Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() } }
        } else if (trips.isEmpty()) {
            item { EmptyState("No road trips", empty, Icons.Default.Explore) }
        } else {
            items(trips, key = { it.id }) { trip -> RoadTripRow(trip, colorByVin[trip.vin] ?: CardColorStore.DEFAULT_CARD_COLOR) { onOpen(trip) } }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

/**
 * Day-grouped alert list. The screen title scrolls, but each day's "date (count)"
 * header is STICKY — it pins to the top while only that day's rows scroll under
 * it. Tapping a day header collapses/expands it (all but today collapsed). A
 * divider separates each row.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun HistoryList(title: String, subtitle: String, rows: List<StoredNotification>, empty: String, engineByVin: Map<String, String?>, colorByVin: Map<String, Int>) {
    val grouped = rows.groupBy { dayLabel(it.receivedAtMillis) }
    val todayLabel = remember { dayLabel(System.currentTimeMillis()) }
    // Collapse every day but today by default; remembered per row-set.
    val collapsed = remember(rows) { mutableStateMapOf<String, Boolean>().apply { grouped.keys.forEach { put(it, it != todayLabel) } } }
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item { Column(Modifier.padding(top = 16.dp, bottom = 4.dp)) { Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold); Text(subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant) } }
        if (rows.isEmpty()) item { EmptyState("Nothing captured", empty, Icons.Default.History) }
        grouped.forEach { (day, notifications) ->
            val isCollapsed = collapsed[day] ?: (day != todayLabel)
            stickyHeader(key = "h-$day") { DayHeader(day, notifications.size, isCollapsed) { collapsed[day] = !isCollapsed } }
            if (!isCollapsed) {
                itemsIndexed(notifications) { i, n ->
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (i > 0) HorizontalDivider(thickness = 1.dp, color = MaterialTheme.colorScheme.outlineVariant)
                        NotificationCard(n, engineByVin[n.vin], colorByVin[n.vin] ?: CardColorStore.DEFAULT_CARD_COLOR)
                    }
                }
            }
        }
    }
}

/** Sticky day separator: "date (count)" with a collapse chevron, on an opaque surface so rows scroll cleanly beneath it. */
@Composable
private fun DayHeader(day: String, count: Int, collapsed: Boolean, onToggle: () -> Unit) {
    val rotation by animateFloatAsState(if (collapsed) 0f else 180f, label = "day-chevron")
    Surface(color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().clickable(onClick = onToggle).padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.History, null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(10.dp))
            Text("$day ($count)", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Icon(Icons.Default.ExpandMore, if (collapsed) "Expand" else "Collapse", Modifier.rotate(rotation))
        }
    }
}

/**
 * Compact alert row: a FleetFoot-style event icon, the alert body with the
 * vehicle's home-screen engine glyph + nickname + time above it, and a tappable
 * Maps pin when a location is attached. No coordinates / event type text shown.
 */
@Composable
private fun NotificationCard(n: StoredNotification, engineType: String?, cardColor: Int) {
    val context = LocalContext.current
    val nickname = n.data["nickname"]?.takeIf { it.isNotBlank() } ?: n.vin
    val lat = n.data["latitude"]?.toDoubleOrNull()
    val lon = n.data["longitude"]?.toDoubleOrNull()
    val visual = themeFromColor(Color(cardColor))
    val cc = visual.contentColor
    val ccDim = cc.copy(alpha = 0.78f)
    ElevatedCard(shape = MaterialTheme.shapes.large, colors = CardDefaults.elevatedCardColors(containerColor = Color.Transparent)) {
        Row(Modifier.fillMaxWidth().background(visual.brush()).padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(notificationEventIcon(n.event), null, tint = cc)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    EngineTypeIcon(engineType, ccDim, 16.dp)
                    Spacer(Modifier.width(6.dp))
                    Text("$nickname • ${timeLabel(n.receivedAtMillis)}", style = MaterialTheme.typography.labelMedium, color = ccDim)
                }
                Text(n.body, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, color = cc)
            }
            if (lat != null && lon != null) {
                Spacer(Modifier.width(8.dp))
                IconButton(onClick = { openInGoogleMaps(context, lat, lon) }) { Icon(Icons.Default.LocationOn, "Open location in Google Maps", tint = cc) }
            }
        }
    }
}

/** Icon for a captured notification, keyed off its backend `event` (FleetFoot mapping). */
private fun notificationEventIcon(event: String?): ImageVector = when (event) {
    "trip.started" -> Icons.Outlined.Key
    "trip.ended" -> Icons.Outlined.KeyOff
    "charge.in_progress" -> Icons.Filled.BatteryChargingFull
    "charge.complete" -> Icons.Filled.BatteryFull
    "charge.error" -> Icons.Filled.WarningAmber
    "signal.lost" -> Icons.Outlined.SignalWifiBad
    "telemetryfeed.lost" -> Icons.Outlined.SignalWifiBad
    else -> Icons.Filled.Notifications
}

@Composable
private fun ErrorBanner(error: String?) {
    if (error.isNullOrBlank()) return
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer), shape = MaterialTheme.shapes.large) {
        Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.Top) { Icon(Icons.Default.Close, null, tint = MaterialTheme.colorScheme.onErrorContainer); Spacer(Modifier.width(10.dp)); Text(error, color = MaterialTheme.colorScheme.onErrorContainer, style = MaterialTheme.typography.bodyMedium) }
    }
}

@Composable
private fun EmptyState(title: String, body: String, icon: ImageVector) {
    Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) { Icon(icon, null, Modifier.size(56.dp), tint = MaterialTheme.colorScheme.primary); Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold); Text(body, color = MaterialTheme.colorScheme.onSurfaceVariant) }
}

@Composable
private fun KeyValue(label: String, value: String?, modifier: Modifier = Modifier, sub: String? = null) {
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.weight(0.42f)) {
            Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
            if (!sub.isNullOrBlank()) Text(sub, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall)
        }
        Text(value?.takeIf { it.isNotBlank() } ?: "—", Modifier.weight(0.58f), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
    }
}

private fun formatNumber(value: Double): String = if (value % 1.0 == 0.0) value.roundToInt().toString() else "%.1f".format(value)
private fun tempText(value: Double?, unit: String?): String? = value?.let { "${formatNumber(it)}°${unit ?: ""}" }
private fun dayLabel(millis: Long): String = Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("EEE, MMM d"))
private fun timeLabel(millis: Long): String = Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("HH:mm"))

/** Short relative "x ago" label for the carousel's last-refreshed cue. */
private fun relativeTime(millis: Long): String {
    val secs = ((System.currentTimeMillis() - millis) / 1000L).coerceAtLeast(0)
    return when {
        secs < 5 -> "just now"
        secs < 60 -> "${secs}s ago"
        secs < 3600 -> "${secs / 60}m ago"
        secs < 86400 -> "${secs / 3600}h ago"
        else -> timeLabel(millis)
    }
}

private val GoHenryLight = lightColorScheme(primary = Color(0xFF2F6FED), secondary = Color(0xFF6750A4), tertiary = Color(0xFFB3261E), surface = Color(0xFFFFFBFE), surfaceVariant = Color(0xFFE6E8F0))
private val GoHenryDark = darkColorScheme(primary = Color(0xFF9ECAFF), secondary = Color(0xFFD0BCFF), tertiary = Color(0xFFFFB4AB))
private val GoHenryShapes = Shapes(extraSmall = RoundedCornerShape(10.dp), small = RoundedCornerShape(14.dp), medium = RoundedCornerShape(18.dp), large = RoundedCornerShape(24.dp), extraLarge = RoundedCornerShape(34.dp))
private val BaseTypography = Typography()
private val GoHenryTypography = Typography(headlineMedium = BaseTypography.headlineMedium.copy(fontWeight = FontWeight.Black), headlineSmall = BaseTypography.headlineSmall.copy(fontWeight = FontWeight.ExtraBold), titleLarge = BaseTypography.titleLarge.copy(fontWeight = FontWeight.Bold))

/**
 * Deepens the background and lifts the surface-container ramp in dark mode so
 * cards/sections read with clearly more contrast against the backdrop. Applied
 * on top of dynamic OR static dark schemes, so the effect is consistent across
 * Android versions.
 */
private fun ColorScheme.boostDarkContrast(): ColorScheme = copy(
    background = Color(0xFF0B0D11),
    surface = Color(0xFF0B0D11),
    surfaceContainerLowest = Color(0xFF101318),
    surfaceContainerLow = Color(0xFF171B22),
    surfaceContainer = Color(0xFF1C2129),
    surfaceContainerHigh = Color(0xFF242A33),
    surfaceContainerHighest = Color(0xFF2D343F),
    surfaceVariant = Color(0xFF2D343F),
    outline = Color(0xFF8B92A0),
    outlineVariant = Color(0xFF4A515E),
)

@Composable
private fun GoHenryTheme(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val dark = isSystemInDarkTheme()
    val base = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) { if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context) } else if (dark) GoHenryDark else GoHenryLight
    val colors = if (dark) base.boostDarkContrast() else base
    MaterialTheme(colorScheme = colors, typography = GoHenryTypography, shapes = GoHenryShapes, content = content)
}

