package dev.miubridge

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.IconButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.miubridge.phone.PhoneState
import dev.miubridge.phone.WirelessAdb
import dev.miubridge.server.Account
import dev.miubridge.server.McpServer
import dev.miubridge.server.RelayClient
import dev.miubridge.server.Updates
import dev.miubridge.phone.AppPolicy
import dev.miubridge.phone.RiskyApps
import dev.miubridge.ui.AppIcons
import dev.miubridge.ui.MiuBridgeTheme
import dev.miubridge.ui.LivePane
import dev.miubridge.ui.LiveThumbnail
import dev.miubridge.ui.TWO_PANE_MIN_WIDTH
import dev.miubridge.ui.LiveToggle
import dev.miubridge.ui.rememberLiveViewEnabled
import dev.miubridge.ui.ScreenWatchDialog
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * Home: whether agents can use this phone right now, the setup steps still to do, how to connect
 * an agent (any MCP agent on any device), and the rarely used extras. Material 3 throughout.
 */
class MainActivity : ComponentActivity() {
    /** Bumped on resume so settings the owner changed in system pages are read again. */
    private var resumes by mutableIntStateOf(0)

    // The pairing code is typed into a notification, so the permission has to be settled before the
    // settings page covers this one: a notification posted while the question is still open is dropped.
    private val askNotificationsThenPair = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) WirelessPairing.start(this)
        else android.widget.Toast.makeText(this, R.string.pair_needs_notifications, android.widget.Toast.LENGTH_LONG).show()
    }

    private fun startPairing() {
        val granted = android.os.Build.VERSION.SDK_INT < 33 ||
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == android.content.pm.PackageManager.PERMISSION_GRANTED
        if (granted) WirelessPairing.start(this) else askNotificationsThenPair.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { MiuBridgeTheme { Home() } }
    }

    override fun onResume() {
        super.onResume()
        resumes++
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun Home() {
        val app = application as App
        val account = remember { Account(this) }
        val running by BridgeService.running.collectAsState()
        val phone by app.phone.state.collectAsState()
        val relay by RelayClient.state.collectAsState()
        var linked by remember { mutableStateOf(account.linked) }
        var connectSheet by remember { mutableStateOf(false) }
        var update by remember { mutableStateOf<Updates.Release?>(null) }
        LaunchedEffect(Unit) { update = Updates(this@MainActivity).newer() }
        // First run walks the owner through setup one step at a time; a phone that was set up before goes straight home.
        val ui = remember { getSharedPreferences("ui", MODE_PRIVATE) }
        var guiding by remember {
            mutableStateOf(
                if (ui.contains("guiding")) ui.getBoolean("guiding", false)
                else (!java.io.File(filesDir, "phone_helper.json").exists() && !WirelessAdb.isPaired(this)).also { ui.edit().putBoolean("guiding", it).apply() },
            )
        }
        fun endGuide() { guiding = false; ui.edit().putBoolean("guiding", false).apply() }
        val liveView = rememberLiveViewEnabled()
        val snackbar = remember { SnackbarHostState() }
        val scope = rememberCoroutineScope()
        fun say(message: String) { scope.launch { snackbar.currentSnackbarData?.dismiss(); snackbar.showSnackbar(message) } }
        fun restartService() {
            if (!BridgeService.running.value) return
            BridgeService.stop(this)
            scope.launch { delay(800); BridgeService.start(this@MainActivity) }
        }

        val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
        Scaffold(
            modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
            topBar = {
                // A plain bar: the tall collapsing one took a quarter of the first screen.
                TopAppBar(
                    title = { Text(stringResource(R.string.app_name)) },
                    actions = { LiveToggle(app.phone, liveView) },
                    scrollBehavior = scrollBehavior,
                )
            },
            snackbarHost = { SnackbarHost(snackbar) },
        ) { padding ->
          BoxWithConstraints {
           // Unfolded or on a tablet, the background display gets a pane of its own beside the content.
           val twoPane = maxWidth >= TWO_PANE_MIN_WIDTH && liveView.value
           Row {
            Box(Modifier.weight(1f)) {
            LazyColumn(
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = padding.calculateTopPadding(), bottom = padding.calculateBottomPadding() + 24.dp),
            ) {
              if (guiding) {
                item {
                    Guide(account, linked, running, phone, resumes, ::say, onDone = ::endGuide) {
                        linked = account.linked
                        restartService()
                    }
                }
              } else {
                update?.let { release -> item { UpdateCard(release) } }
                item { StatusCard(running, phone, relay, linked) }
                item { SectionTitle(stringResource(R.string.section_connect)) }
                item {
                    ConnectCard(account, linked, running, relay, ::say, onConnect = { connectSheet = true }) {
                        linked = account.linked
                        restartService()
                    }
                }
                item { SectionTitle(stringResource(R.string.section_setup)) }
                item { SetupCard(phone, resumes, ::say) }
                item { SectionTitle(stringResource(R.string.section_more)) }
                item {
                    MoreCard(account, ::say) {
                        linked = account.linked
                        restartService()
                    }
                }
              }
                item {
                    Text(
                        "${stringResource(R.string.app_name)} ${packageManager.getPackageInfo(packageName, 0).versionName}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.outline,
                        modifier = Modifier.fillMaxWidth().padding(top = 24.dp),
                        textAlign = TextAlign.Center,
                    )
                }
            }
            // Floats in the top-right corner while an agent is working on the background display.
            // In the guide it sits at the bottom, clear of the step's text and buttons.
            if (!twoPane) LiveThumbnail(
                app.phone, liveView.value,
                if (guiding) Modifier.align(Alignment.BottomEnd).padding(bottom = padding.calculateBottomPadding() + 24.dp, end = 16.dp)
                else Modifier.align(Alignment.TopEnd).padding(top = padding.calculateTopPadding() + 8.dp, end = 16.dp),
            )
            }
            if (twoPane) LivePane(
                app.phone,
                Modifier.weight(0.8f).fillMaxHeight()
                    .padding(top = padding.calculateTopPadding(), end = 16.dp, bottom = padding.calculateBottomPadding() + 16.dp),
            )
           }
          }
        }
        if (connectSheet) ConnectSheet(account, linked, ::say) { connectSheet = false }
        // Owners who set up before the notice existed (or before it last changed) see it once here.
        val consent = remember { dev.miubridge.core.AppConsent(this) }
        var noticePending by remember { mutableStateOf(!consent.acceptedNotice) }
        if (!guiding && noticePending) AlertDialog(
            onDismissRequest = {},
            title = { Text(stringResource(R.string.notice_title)) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Text(stringResource(R.string.notice_intro), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(bottom = 12.dp))
                    RiskPoints()
                }
            },
            confirmButton = { TextButton(onClick = { consent.acceptNotice(); noticePending = false }) { Text(stringResource(R.string.notice_accept)) } },
        )
    }

    @Composable
    private fun UpdateCard(release: Updates.Release) {
        Box(Modifier.padding(bottom = 12.dp)) {
            GroupCard {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.update_title, release.version)) },
                    supportingContent = { Text(stringResource(R.string.update_body)) },
                    leadingContent = { Icon(Icons.Filled.Info, null) },
                    trailingContent = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null) },
                    colors = clearItem(),
                    modifier = Modifier.clickable {
                        runCatching { startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(release.page))) }
                    },
                )
            }
        }
    }

    // ---- status ----

    private class Look(val title: String, val body: String, val icon: ImageVector, val container: Color, val content: Color)

    @Composable
    private fun StatusCard(running: Boolean, phone: PhoneState, relay: RelayClient.State, linked: Boolean) {
        val scheme = MaterialTheme.colorScheme
        val scope = rememberCoroutineScope()
        var watching by remember { mutableStateOf(false) }
        if (watching) ScreenWatchDialog((application as App).phone) { watching = false }
        val look = when {
            !running -> Look(stringResource(R.string.status_stopped_title), stringResource(R.string.status_stopped_body), Icons.Filled.PlayArrow, scheme.surfaceContainerHigh, scheme.onSurface)
            !phone.shellReady -> Look(stringResource(R.string.status_preparing_title), phone.message.ifBlank { stringResource(R.string.status_preparing_body) }, Icons.Filled.Refresh, scheme.secondaryContainer, scheme.onSecondaryContainer)
            !linked -> Look(stringResource(R.string.status_local_title), if (phone.resting) phone.message else stringResource(R.string.status_local_body), Icons.Filled.CheckCircle, scheme.primaryContainer, scheme.onPrimaryContainer)
            !relay.connected -> Look(stringResource(R.string.status_remote_down_title), relay.message.ifBlank { stringResource(R.string.remote_connecting) }, Icons.Filled.Warning, scheme.errorContainer, scheme.onErrorContainer)
            else -> Look(stringResource(R.string.status_ready_title), if (phone.resting) phone.message else stringResource(R.string.status_ready_body), Icons.Filled.CheckCircle, scheme.primaryContainer, scheme.onPrimaryContainer)
        }
        Card(
            colors = CardDefaults.cardColors(containerColor = look.container, contentColor = look.content),
            shape = MaterialTheme.shapes.extraLarge,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.padding(20.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(look.icon, null, Modifier.size(40.dp))
                    Spacer(Modifier.width(16.dp))
                    Column {
                        Text(look.title, style = MaterialTheme.typography.headlineSmall)
                        Text(look.body, style = MaterialTheme.typography.bodyMedium)
                    }
                }
                Spacer(Modifier.height(16.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    val ready = running && phone.shellReady
                    StatusPill(stringResource(R.string.pill_screen), ready)
                    StatusPill(stringResource(R.string.pill_reader), ready && phone.a11yReady)
                    StatusPill(stringResource(R.string.pill_remote), running && relay.connected)
                }
                Spacer(Modifier.height(20.dp))
                if (running) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilledTonalButton(
                            onClick = { BridgeService.stop(this@MainActivity) },
                            colors = ButtonDefaults.filledTonalButtonColors(containerColor = scheme.surface, contentColor = scheme.onSurface),
                            modifier = Modifier.weight(1f),
                        ) { Text(stringResource(R.string.action_stop)) }
                        // A missed permission prompt or a failed start must be easy to try again.
                        if (!phone.shellReady) Button(
                            onClick = { scope.launch { (application as App).phone.ensureShell() } },
                            modifier = Modifier.weight(1f),
                        ) { Text(stringResource(R.string.action_retry)) }
                        // Whatever agent is working, the owner can look at the background screen.
                        else Button(onClick = { watching = true }, modifier = Modifier.weight(1f)) {
                            Icon(AppIcons.Smartphone, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.action_watch), maxLines = 1)
                        }
                    }
                } else {
                    Button(
                        onClick = {
                            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
                            BridgeService.start(this@MainActivity)
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(stringResource(R.string.action_start)) }
                }
            }
        }
    }

    @Composable
    private fun StatusPill(label: String, on: Boolean) {
        val scheme = MaterialTheme.colorScheme
        Surface(shape = CircleShape, color = scheme.surface.copy(alpha = if (on) 0.7f else 0.35f), contentColor = scheme.onSurface) {
            Row(Modifier.padding(horizontal = 10.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(8.dp).background(if (on) scheme.tertiary else scheme.outline, CircleShape))
                Spacer(Modifier.width(6.dp))
                Text(label, style = MaterialTheme.typography.labelLarge, maxLines = 1)
            }
        }
    }

    @Composable
    private fun SectionTitle(text: String) {
        Text(
            text,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 8.dp, top = 28.dp, bottom = 8.dp),
        )
    }

    @Composable
    private fun GroupCard(content: @Composable () -> Unit) {
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
            shape = MaterialTheme.shapes.extraLarge,
            modifier = Modifier.fillMaxWidth(),
        ) { content() }
    }

    @Composable
    private fun clearItem() = ListItemDefaults.colors(containerColor = Color.Transparent)

    // ---- first-run guide ----

    /**
     * First run for someone who has never heard of developer options: what is about to happen, give
     * the phone its permission one action at a time, hand the instructions to the agent, see it
     * arrive. Which step shows follows from the phone's real state, so leaving for a system page
     * and coming back lands on the right one.
     */
    @Composable
    private fun Guide(
        account: Account, linked: Boolean, running: Boolean, phone: PhoneState, resumes: Int,
        say: (String) -> Unit, onDone: () -> Unit, onAccountChanged: () -> Unit,
    ) {
        val scope = rememberCoroutineScope()
        val ui = remember { getSharedPreferences("ui", MODE_PRIVATE) }
        val paired = remember(resumes, phone.shellReady) { phone.shellReady || WirelessAdb.isPaired(this) }
        // For a phone whose debugging was opened from a computer instead of by pairing here.
        var preparedElsewhere by remember { mutableStateOf(ui.getBoolean("prepared_elsewhere", false)) }
        fun setPreparedElsewhere(v: Boolean) { preparedElsewhere = v; ui.edit().putBoolean("prepared_elsewhere", v).apply() }
        var handedOver by remember { mutableStateOf(ui.getBoolean("handed_over", false)) }
        fun setHandedOver(v: Boolean) { handedOver = v; ui.edit().putBoolean("handed_over", v).apply() }
        val ready = running && phone.shellReady
        val consent = remember { dev.miubridge.core.AppConsent(this) }
        var noticed by remember { mutableStateOf(consent.acceptedNotice) }
        val step = when {
            !noticed -> 0
            !ready -> 1
            !handedOver -> 2
            else -> 3
        }

        Column(Modifier.fillMaxWidth().padding(top = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (step == 0) stringResource(R.string.guide_welcome_label) else stringResource(R.string.guide_progress, step, 3),
                    style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onDone) { Text(stringResource(R.string.guide_skip)) }
            }
            androidx.compose.material3.LinearProgressIndicator(progress = { step / 4f }, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(28.dp))
            when (step) {
                0 -> GuideStep(R.string.guide0_title, R.string.guide0_body) {
                    GroupCard {
                        Column(Modifier.padding(vertical = 8.dp)) {
                            for ((i, text) in listOf(R.string.guide0_one, R.string.guide0_two, R.string.guide0_three).withIndex()) ListItem(
                                headlineContent = { Text(stringResource(text)) },
                                leadingContent = { StepIcon(false, i + 1) },
                                colors = clearItem(),
                            )
                        }
                    }
                    Text(
                        stringResource(R.string.guide0_risks), style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.padding(top = 24.dp, bottom = 8.dp),
                    )
                    RiskPoints()
                    Button(onClick = { consent.acceptNotice(); noticed = true }, modifier = Modifier.fillMaxWidth().padding(top = 24.dp)) {
                        Text(stringResource(R.string.guide0_start))
                    }
                }
                1 -> if (paired || preparedElsewhere) {
                    // Nothing for the owner to decide here: start by ourselves.
                    LaunchedEffect(Unit) {
                        if (!BridgeService.running.value) BridgeService.start(this@MainActivity)
                    }
                    GuideStep(R.string.guide2_title, R.string.guide2_body) {
                        GroupCard {
                            ListItem(
                                headlineContent = { Text(phone.message.ifBlank { stringResource(R.string.status_preparing_body) }) },
                                leadingContent = { CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp) },
                                colors = clearItem(),
                            )
                        }
                        Button(
                            onClick = {
                                if (!BridgeService.running.value) BridgeService.start(this@MainActivity)
                                else scope.launch { (application as App).phone.ensureShell() }
                            },
                            modifier = Modifier.fillMaxWidth().padding(top = 20.dp),
                        ) { Text(stringResource(R.string.action_retry)) }
                        if (preparedElsewhere && !paired) TextButton(
                            onClick = { setPreparedElsewhere(false) },
                            modifier = Modifier.align(Alignment.CenterHorizontally),
                        ) { Text(stringResource(R.string.guide_back)) }
                    }
                } else GuideStep(R.string.guide_perm_title, R.string.guide_perm_body) {
                    var computerHelp by remember { mutableStateOf(false) }
                    val (devOn, wirelessOn, wifi) = remember(resumes) {
                        Triple(
                            Settings.Global.getInt(contentResolver, Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 0) == 1,
                            WirelessAdb.isOn(this@MainActivity),
                            onWifi(),
                        )
                    }
                    val done = listOf(wifi || wirelessOn, devOn, wirelessOn, false)
                    val current = done.indexOfFirst { !it }
                    PermStep(1, R.string.perm_wifi_title, done[0], current == 0) {
                        Text(stringResource(R.string.perm_wifi_body), style = MaterialTheme.typography.bodyMedium)
                        Button(onClick = { startActivity(Intent(Settings.ACTION_WIFI_SETTINGS)) }, modifier = Modifier.fillMaxWidth().padding(top = 12.dp)) {
                            Text(stringResource(R.string.perm_wifi_action))
                        }
                    }
                    PermStep(2, R.string.perm_dev_title, done[1], current == 1) {
                        Instructions(R.string.perm_dev_1, R.string.perm_dev_2, R.string.perm_dev_3)
                        Sketch(listOf(R.string.sketch_about_other to null, R.string.sketch_build to R.string.sketch_tap7))
                        Button(onClick = { startActivity(Intent(Settings.ACTION_DEVICE_INFO_SETTINGS)) }, modifier = Modifier.fillMaxWidth().padding(top = 12.dp)) {
                            Text(stringResource(R.string.guide1_open_about))
                        }
                    }
                    PermStep(3, R.string.perm_wireless_title, done[2], current == 2) {
                        Instructions(R.string.perm_wireless_1, R.string.perm_wireless_2, R.string.perm_wireless_3)
                        Sketch(listOf(R.string.sketch_usb to null, R.string.sketch_wireless to R.string.sketch_switch_on))
                        Button(onClick = { WirelessPairing.openWirelessDebuggingSettings(this@MainActivity) }, modifier = Modifier.fillMaxWidth().padding(top = 12.dp)) {
                            Text(stringResource(R.string.perm_wireless_action))
                        }
                        OutlinedButton(onClick = { computerHelp = true }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                            Text(stringResource(R.string.guide1_no_wireless))
                        }
                    }
                    PermStep(4, R.string.perm_pair_title, done[3], current == 3) {
                        Instructions(R.string.perm_pair_1, R.string.perm_pair_2, R.string.perm_pair_3, R.string.perm_pair_4)
                        Sketch(listOf(R.string.sketch_pair_code to R.string.sketch_tap, R.string.sketch_code to R.string.sketch_type_this))
                        Button(onClick = { startPairing() }, modifier = Modifier.fillMaxWidth().padding(top = 12.dp)) {
                            Text(stringResource(R.string.guide1_pair))
                        }
                    }
                    if (computerHelp) ComputerSetupDialog(say, onDismiss = { computerHelp = false }) {
                        computerHelp = false
                        setPreparedElsewhere(true)
                    }
                }
                2 -> {
                    val token = (application as App).token.value
                    // The phone goes on the relay by itself, so the one text works for an agent anywhere.
                    var linking by remember { mutableStateOf(!linked) }
                    var failed by remember { mutableStateOf<String?>(null) }
                    var attempt by remember { mutableStateOf(0) }
                    LaunchedEffect(attempt) {
                        if (account.linked) { linking = false; return@LaunchedEffect }
                        linking = true
                        failed = account.link()
                        linking = false
                        onAccountChanged()
                    }
                    val details = connectionDetails(lanUrl(), account.mcpUrl, token, account.baseUrl)
                    val copiedGuide = stringResource(R.string.copied_guide)
                    val shareTitle = stringResource(R.string.guide_share_title)
                    var sent by remember { mutableStateOf(false) }
                    GuideStep(R.string.guide3_title, R.string.guide3_body) {
                        Button(
                            onClick = { copy(details); say(copiedGuide); sent = true },
                            enabled = !linking,
                            modifier = Modifier.fillMaxWidth(),
                            colors = if (sent) ButtonDefaults.filledTonalButtonColors() else ButtonDefaults.buttonColors(),
                        ) {
                            Icon(AppIcons.Copy, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.guide3_copy))
                        }
                        // Many agents live in a chat app: hand the text straight to it.
                        OutlinedButton(
                            onClick = {
                                startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, details), shareTitle))
                                sent = true
                            },
                            enabled = !linking,
                            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                        ) { Text(stringResource(R.string.guide3_share)) }
                        Text(
                            when {
                                linking -> stringResource(R.string.guide3_linking)
                                linked -> stringResource(R.string.guide3_two_ways)
                                else -> stringResource(R.string.guide3_lan_only, failed.orEmpty())
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = if (!linking && !linked) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 12.dp),
                        )
                        if (!linking && !linked) TextButton(onClick = { attempt++ }) { Text(stringResource(R.string.action_retry)) }
                        Text(
                            stringResource(R.string.guide3_secret), style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 12.dp),
                        )
                        CopyRow(stringResource(R.string.connect_token), "••••••••" + token.takeLast(4), token, stringResource(R.string.copied_token), say)
                        if (sent) Button(onClick = { setHandedOver(true) }, modifier = Modifier.fillMaxWidth().padding(top = 16.dp)) {
                            Text(stringResource(R.string.guide3_next))
                        }
                    }
                }
                else -> {
                    // lastUsedAt moves when an agent acts on the background display.
                    val since = remember { phone.lastUsedAt }
                    val arrived = phone.lastUsedAt > since
                    val prompt = stringResource(R.string.guide4_prompt)
                    val copiedPrompt = stringResource(R.string.guide4_copied)
                    GuideStep(R.string.guide4_title, R.string.guide4_body) {
                        CopyRow(stringResource(R.string.guide4_say), prompt, prompt, copiedPrompt, say)
                        Spacer(Modifier.height(16.dp))
                        GroupCard {
                            ListItem(
                                headlineContent = { Text(stringResource(if (arrived) R.string.guide4_arrived else R.string.guide4_waiting)) },
                                supportingContent = { if (!arrived) Text(stringResource(R.string.guide4_waiting_body)) },
                                leadingContent = {
                                    if (arrived) Icon(Icons.Filled.CheckCircle, null, tint = MaterialTheme.colorScheme.tertiary, modifier = Modifier.size(28.dp))
                                    else CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                                },
                                colors = clearItem(),
                            )
                        }
                        if (arrived) Button(onClick = onDone, modifier = Modifier.fillMaxWidth().padding(top = 20.dp)) { Text(stringResource(R.string.guide_finish)) }
                        else OutlinedButton(onClick = onDone, modifier = Modifier.fillMaxWidth().padding(top = 20.dp)) { Text(stringResource(R.string.guide4_later)) }
                        TextButton(onClick = { setHandedOver(false) }, modifier = Modifier.align(Alignment.CenterHorizontally)) { Text(stringResource(R.string.guide_back)) }
                    }
                }
            }
        }
    }

    /** Wi-Fi is up, whatever else (a VPN, mobile data) is also carrying traffic. */
    private fun onWifi(): Boolean {
        val cm = getSystemService(android.net.ConnectivityManager::class.java)
        @Suppress("DEPRECATION")
        return cm.allNetworks.any { cm.getNetworkCapabilities(it)?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) == true }
    }

    /** One action of the permission step: a tick when the phone shows it is done, the how-to only for the one in turn. */
    @Composable
    private fun PermStep(number: Int, title: Int, done: Boolean, current: Boolean, content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
        Row(Modifier.fillMaxWidth().padding(bottom = 16.dp)) {
            StepIcon(done, number)
            Column(Modifier.padding(start = 16.dp).weight(1f)) {
                Text(
                    stringResource(title),
                    style = MaterialTheme.typography.titleMedium,
                    color = if (done || current) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp),
                )
                AnimatedVisibility(current) { Column(Modifier.padding(top = 8.dp)) { content() } }
            }
        }
    }

    @Composable
    private fun Instructions(vararg lines: Int) {
        for ((i, line) in lines.withIndex()) Row(Modifier.padding(bottom = 6.dp)) {
            Text("${i + 1}.", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.width(22.dp))
            Text(stringResource(line), style = MaterialTheme.typography.bodyMedium)
        }
    }

    /** A drawing of the system page the owner is about to see: its rows, and what to do on the one that matters. */
    @Composable
    private fun Sketch(rows: List<Pair<Int, Int?>>) {
        Surface(
            shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
        ) {
            Column(Modifier.padding(8.dp)) {
                for ((label, hint) in rows) Row(
                    Modifier.fillMaxWidth()
                        .background(if (hint != null) MaterialTheme.colorScheme.primaryContainer else Color.Transparent, RoundedCornerShape(8.dp))
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        stringResource(label), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f),
                        color = if (hint != null) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (hint != null) Text(stringResource(hint), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                }
            }
        }
    }

    /** The risks an owner takes on by letting agents operate apps, in plain words. Shown in the guide and to earlier owners. */
    @Composable
    private fun RiskPoints() {
        GroupCard {
            Column(Modifier.padding(vertical = 8.dp)) {
                for ((icon, text) in listOf(
                    Icons.Filled.Warning to R.string.notice_point_terms,
                    Icons.Filled.CheckCircle to R.string.notice_point_yours,
                    AppIcons.LockOpen to R.string.notice_point_irreversible,
                )) ListItem(
                    headlineContent = { Text(stringResource(text), style = MaterialTheme.typography.bodyMedium) },
                    leadingContent = { Icon(icon, null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
                    colors = clearItem(),
                )
            }
        }
    }

    /** For a phone without wireless debugging: the one command a computer has to run, and where to go from there. */
    @Composable
    private fun ComputerSetupDialog(say: (String) -> Unit, onDismiss: () -> Unit, onDone: () -> Unit) {
        val command = "adb tcpip 5555"
        val copied = stringResource(R.string.guide4_copied)
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(stringResource(R.string.computer_title)) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Text(stringResource(R.string.computer_intro), style = MaterialTheme.typography.bodyMedium)
                    for ((i, step) in listOf(R.string.computer_step1, R.string.computer_step2, R.string.computer_step3, R.string.computer_step4).withIndex()) {
                        Row(Modifier.padding(top = 12.dp)) {
                            Text("${i + 1}.", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.width(24.dp))
                            Text(stringResource(step), style = MaterialTheme.typography.bodyMedium)
                        }
                        if (step == R.string.computer_step3) CopyRow(stringResource(R.string.computer_command), command, command, copied, say)
                    }
                    Text(
                        stringResource(R.string.computer_note), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 12.dp),
                    )
                    TextButton(onClick = { startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)) }, modifier = Modifier.padding(top = 4.dp)) {
                        Text(stringResource(R.string.computer_open_dev))
                    }
                }
            },
            confirmButton = { TextButton(onClick = onDone) { Text(stringResource(R.string.computer_done)) } },
            dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
        )
    }

    @Composable
    private fun GuideStep(title: Int, body: Int, content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
        Column(Modifier.fillMaxWidth()) {
            Text(stringResource(title), style = MaterialTheme.typography.headlineMedium)
            Text(
                stringResource(body), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp, bottom = 24.dp),
            )
            content()
        }
    }

    // ---- setup ----

    @Composable
    private fun SetupCard(phone: PhoneState, resumes: Int, say: (String) -> Unit) {
        // Read again on every resume and whenever the phone state changes.
        val (devOn, paired, battery) = remember(resumes, phone.shellReady) {
            val pairedNow = phone.shellReady || WirelessAdb.isPaired(this)
            Triple(
                pairedNow || Settings.Global.getInt(contentResolver, Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 0) == 1,
                pairedNow,
                getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName),
            )
        }
        val allDone = paired && battery
        var expandedChoice by remember { mutableStateOf<Boolean?>(null) }
        val expanded = expandedChoice ?: !allDone
        val alreadyAllowed = stringResource(R.string.already_allowed)

        GroupCard {
            if (allDone) {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.setup_done)) },
                    leadingContent = { StepIcon(done = true, number = 0) },
                    trailingContent = { Icon(if (expanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown, stringResource(if (expanded) R.string.cd_collapse else R.string.cd_expand)) },
                    colors = clearItem(),
                    modifier = Modifier.clickable { expandedChoice = !expanded },
                )
            }
            AnimatedVisibility(expanded) {
                Column {
                    if (allDone) HorizontalDivider(Modifier.padding(horizontal = 16.dp))
                    Step(1, R.string.step_dev_title, R.string.step_dev_body, devOn) {
                        startActivity(Intent(Settings.ACTION_DEVICE_INFO_SETTINGS))
                    }
                    Step(2, R.string.step_pair_title, R.string.step_pair_body, paired) {
                        startPairing()
                    }
                    Step(3, R.string.step_battery_title, R.string.step_battery_body, battery) {
                        if (!battery) {
                            startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
                        } else say(alreadyAllowed)
                    }
                    Step(4, R.string.step_autostart_title, R.string.step_autostart_body, done = false) {
                        startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
                    }
                }
            }
        }
    }

    @Composable
    private fun Step(number: Int, title: Int, body: Int, done: Boolean, onClick: () -> Unit) {
        ListItem(
            headlineContent = { Text(stringResource(title)) },
            supportingContent = { Text(stringResource(body)) },
            leadingContent = { StepIcon(done, number) },
            trailingContent = {
                if (done) Text(stringResource(R.string.step_done), color = MaterialTheme.colorScheme.tertiary)
                else Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null)
            },
            colors = clearItem(),
            modifier = Modifier.clickable(onClick = onClick),
        )
    }

    @Composable
    private fun StepIcon(done: Boolean, number: Int) {
        val scheme = MaterialTheme.colorScheme
        Box(
            Modifier.size(40.dp).background(if (done) scheme.tertiaryContainer else scheme.surfaceContainerHighest, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            if (done) Icon(Icons.Filled.Check, null, tint = scheme.onTertiaryContainer, modifier = Modifier.size(22.dp))
            else Text("$number", style = MaterialTheme.typography.titleMedium, color = scheme.onSurfaceVariant)
        }
    }

    // ---- connect ----

    /** How to hand this phone to an agent, and the switch that makes it reachable from other networks. */
    @Composable
    private fun ConnectCard(
        account: Account, linked: Boolean, running: Boolean, relay: RelayClient.State,
        say: (String) -> Unit, onConnect: () -> Unit, onAccountChanged: () -> Unit,
    ) {
        val scope = rememberCoroutineScope()
        GroupCard {
            Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 16.dp)) {
                Text(
                    stringResource(R.string.connect_body),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Button(onClick = onConnect, modifier = Modifier.fillMaxWidth().padding(top = 16.dp)) {
                    Icon(AppIcons.Link, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.connect_button))
                }
            }
            HorizontalDivider(Modifier.padding(horizontal = 20.dp))
            if (!linked) {
                var linking by remember { mutableStateOf(false) }
                ListItem(
                    headlineContent = { Text(stringResource(R.string.remote_title)) },
                    supportingContent = { Text(stringResource(if (linking) R.string.remote_connecting else R.string.remote_off_body)) },
                    leadingContent = { Icon(AppIcons.Cloud, null) },
                    trailingContent = {
                        TextButton(enabled = !linking, onClick = {
                            linking = true
                            scope.launch {
                                account.link()?.let(say)
                                linking = false
                                onAccountChanged()
                            }
                        }) { Text(stringResource(R.string.action_turn_on)) }
                    },
                    colors = clearItem(),
                )
            } else {
                var confirmOff by remember { mutableStateOf(false) }
                var usage by remember { mutableStateOf<Account.Usage?>(null) }
                // Asked once, when remote access is first on.
                var askTrace by remember { mutableStateOf(!(application as App).traces.asked) }
                if (askTrace) TraceDialog { join ->
                    (application as App).traces.setEnabled(join)
                    askTrace = false
                }
                LaunchedEffect(relay.connected) { usage = account.usage() }
                ListItem(
                    headlineContent = { Text(stringResource(R.string.remote_title)) },
                    supportingContent = {
                        Column {
                            Text(
                                when {
                                    relay.connected -> stringResource(R.string.remote_on)
                                    !running -> stringResource(R.string.status_stopped_title)
                                    else -> relay.message.ifBlank { stringResource(R.string.remote_connecting) }
                                },
                                color = if (relay.connected) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            usage?.let { u ->
                                val over = u.limit != null && u.used >= u.limit
                                Text(
                                    if (u.limit == null) stringResource(R.string.usage_unlimited, u.used)
                                    else stringResource(if (over) R.string.usage_over else R.string.usage_month, u.used, u.limit),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (over) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    },
                    leadingContent = {
                        Box(Modifier.size(40.dp).background(MaterialTheme.colorScheme.primary, CircleShape), contentAlignment = Alignment.Center) {
                            Icon(AppIcons.Cloud, null, tint = MaterialTheme.colorScheme.onPrimary)
                        }
                    },
                    trailingContent = { TextButton(onClick = { confirmOff = true }) { Text(stringResource(R.string.action_turn_off)) } },
                    colors = clearItem(),
                )
                if (confirmOff) AlertDialog(
                    onDismissRequest = { confirmOff = false },
                    title = { Text(stringResource(R.string.remote_turn_off_title)) },
                    text = { Text(stringResource(R.string.remote_turn_off_body)) },
                    confirmButton = {
                        TextButton(onClick = { confirmOff = false; scope.launch { account.unlink(); onAccountChanged() } }) { Text(stringResource(R.string.action_turn_off)) }
                    },
                    dismissButton = { TextButton(onClick = { confirmOff = false }) { Text(stringResource(R.string.action_cancel)) } },
                )
            }
        }
    }

    /**
     * What an agent needs to reach this phone, wherever it runs: the address on the phone's own Wi-Fi
     * ([lan], null without Wi-Fi) and the one through the relay ([remote], null when remote access is off).
     */
    private fun connectionDetails(lan: String?, remote: String?, token: String, relay: String): String {
        val both = lan != null && remote != null
        val where = when {
            both -> """
                Address: two reach the same phone; use the one that fits where you run.
                - On the phone's own Wi-Fi (direct, fastest): $lan
                - Anywhere else (through a relay): $remote
                The Wi-Fi address changes when the phone changes network; the other stays the same.
            """.trimIndent()
            remote != null -> "Address: $remote"
            else -> "Address: ${lan ?: "http://<phone-ip>:${McpServer.DEFAULT_PORT}/mcp"}\nIt works while you are on the same Wi-Fi as the phone."
        }
        val url = if (both) "<address>" else remote ?: lan ?: "<address>"
        return """
Miu Bridge: operate apps on my Android phone, on a background display I do not see.

$where
Token: $token
The token is the key to the phone. Keep it secret.

Connect either way; the tools are the same:
1. As an MCP server (Streamable HTTP) at the address, with the header "Authorization: Bearer <token>".
   Claude Code: claude mcp add --transport http phone "$url" --header "Authorization: Bearer $token"
2. From scripts in your own sandbox, with the SDK (Python or JavaScript). Guide: $relay/sdk/
   export MIU_BRIDGE_URL="$url"
   export MIU_BRIDGE_TOKEN="$token"
   export MIU_BRIDGE_E2E=1   # optional: end-to-end encryption, so the relay cannot read what passes
If you call the address with your own HTTP client, send a User-Agent header that names it.
        """.trim()
    }

    /** One value the owner copies on its own: a label, the value (or a masked form of it), a copy button. */
    @Composable
    private fun CopyRow(label: String, shown: String, value: String, copiedMessage: String, say: (String) -> Unit) {
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        ) {
            Row(Modifier.padding(start = 16.dp, top = 8.dp, bottom = 8.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(shown, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                IconButton(onClick = { copy(value); say(copiedMessage) }) {
                    Icon(AppIcons.Copy, stringResource(R.string.cd_copy_item, label), Modifier.size(20.dp))
                }
            }
        }
    }

    /** The owner copies the full hand-off, and the token alone when an agent's credential store asks for it. */
    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun ConnectSheet(account: Account, linked: Boolean, say: (String) -> Unit, onDismiss: () -> Unit) {
        val token = (application as App).token.value
        val copiedGuide = stringResource(R.string.copied_guide)
        ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
            Column(Modifier.verticalScroll(rememberScrollState()).padding(start = 24.dp, end = 24.dp, bottom = 24.dp)) {
                Text(stringResource(R.string.connect_sheet_title), style = MaterialTheme.typography.headlineSmall)
                Button(
                    onClick = { copy(connectionDetails(lanUrl(), account.mcpUrl.takeIf { linked }, token, account.baseUrl)); say(copiedGuide) },
                    modifier = Modifier.fillMaxWidth().padding(top = 20.dp),
                ) {
                    Icon(AppIcons.Copy, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.connect_copy_guide))
                }
                CopyRow(stringResource(R.string.connect_token), "••••••••" + token.takeLast(4), token, stringResource(R.string.copied_token), say)
                Text(
                    stringResource(R.string.connect_explain),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 12.dp),
                )
                Text(
                    stringResource(R.string.connect_warning),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        }
    }

    /** The experience improvement program, offered once after sign-in. Joining is never required. */
    @Composable
    private fun TraceDialog(onAnswer: (Boolean) -> Unit) {
        var showSample by remember { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = {},
            title = { Text(stringResource(R.string.trace_title)) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Text(stringResource(R.string.trace_body))
                    Text(stringResource(R.string.trace_feedback), modifier = Modifier.padding(top = 8.dp))
                    Text(stringResource(R.string.trace_never), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 12.dp))
                    TextButton(onClick = { showSample = !showSample }, contentPadding = PaddingValues(0.dp)) {
                        Text(stringResource(if (showSample) R.string.trace_hide_sample else R.string.trace_show_sample))
                    }
                    AnimatedVisibility(showSample) {
                        Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = RoundedCornerShape(8.dp)) {
                            Text((application as App).traces.sample(), fontFamily = FontFamily.Monospace,
                                style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(12.dp))
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { onAnswer(true) }) { Text(stringResource(R.string.trace_join)) } },
            dismissButton = { TextButton(onClick = { onAnswer(false) }) { Text(stringResource(R.string.trace_decline)) } },
        )
    }

    /** Every note agents left about tools, newest first, and whether it has gone out yet. */
    @Composable
    private fun FeedbackDialog(notes: List<dev.miubridge.server.Traces.Note>, onClose: () -> Unit) {
        val format = remember { java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT, java.text.DateFormat.SHORT) }
        AlertDialog(
            onDismissRequest = onClose,
            title = { Text(stringResource(R.string.feedback_count, notes.size)) },
            text = {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    items(notes.size) { i ->
                        val n = notes[i]
                        Column {
                            Text(
                                format.format(java.util.Date(n.at)) + " · " +
                                    stringResource(if (n.sent) R.string.feedback_sent else R.string.feedback_pending),
                                style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(stringResource(R.string.feedback_expected, n.expected), style = MaterialTheme.typography.bodyMedium)
                            Text(stringResource(R.string.feedback_happened, n.happened), style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = onClose) { Text(stringResource(R.string.action_close)) } },
        )
    }

    /** One line saying what the owner's app rule lets agents do. */
    @Composable
    private fun policySummary(mode: AppPolicy.Mode, blocked: Set<String>, allowed: Set<String>, installed: Set<String>): String {
        // The block list holds risky apps that may not be installed; count only the ones on this phone.
        val closed = blocked.count { it in installed }
        return when {
            mode == AppPolicy.Mode.BLOCK && closed == 0 -> stringResource(R.string.apps_policy_all)
            mode == AppPolicy.Mode.BLOCK -> stringResource(R.string.apps_policy_blocked, closed)
            allowed.isEmpty() -> stringResource(R.string.apps_policy_none)
            else -> stringResource(R.string.apps_policy_allowed, allowed.size)
        }
    }

    /**
     * Picks the mode (block list or allow list) and the apps on the list for that mode. Each mode keeps
     * its own list. Returns (mode, blocked, allowed) on save, or null when cancelled.
     */
    @Composable
    private fun AppPolicyDialog(
        mode: AppPolicy.Mode, blocked: Set<String>, allowed: Set<String>,
        onDone: (Triple<AppPolicy.Mode, Set<String>, Set<String>>?) -> Unit,
    ) {
        var m by remember { mutableStateOf(mode) }
        var b by remember { mutableStateOf(blocked) }
        var a by remember { mutableStateOf(allowed) }
        // The apps already on the current list first, so the owner sees what they chose without scrolling.
        val apps = remember {
            val listed = if (mode == AppPolicy.Mode.BLOCK) blocked else allowed
            (application as App).phone.launchableApps().sortedBy { it.second !in listed }
        }
        val chosen = if (m == AppPolicy.Mode.BLOCK) b else a
        fun toggle(pkg: String) {
            if (m == AppPolicy.Mode.BLOCK) b = if (pkg in b) b - pkg else b + pkg
            else a = if (pkg in a) a - pkg else a + pkg
        }
        AlertDialog(
            onDismissRequest = { onDone(null) },
            title = { Text(stringResource(R.string.apps_policy_title)) },
            text = {
                Column {
                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                        AppPolicy.Mode.entries.forEachIndexed { i, option ->
                            SegmentedButton(
                                selected = m == option,
                                onClick = { m = option },
                                shape = SegmentedButtonDefaults.itemShape(i, AppPolicy.Mode.entries.size),
                            ) {
                                Text(stringResource(if (option == AppPolicy.Mode.BLOCK) R.string.apps_policy_mode_block else R.string.apps_policy_mode_allow))
                            }
                        }
                    }
                    Text(
                        stringResource(if (m == AppPolicy.Mode.BLOCK) R.string.apps_policy_explain_block else R.string.apps_policy_explain_allow),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 12.dp),
                    )
                    LazyColumn(Modifier.heightIn(max = 360.dp)) {
                        items(apps.size) { i ->
                            val (label, pkg) = apps[i]
                            Row(
                                Modifier.fillMaxWidth().clickable { toggle(pkg) }.padding(vertical = 2.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Checkbox(checked = pkg in chosen, onCheckedChange = { toggle(pkg) })
                                Column {
                                    Text(label, style = MaterialTheme.typography.bodyMedium)
                                    Text(pkg, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    if (m == AppPolicy.Mode.BLOCK && pkg in RiskyApps.PACKAGES) {
                                        Text(stringResource(R.string.apps_policy_risky), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                                    }
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { onDone(Triple(m, b, a)) }) { Text(stringResource(R.string.action_save)) } },
            dismissButton = { TextButton(onClick = { onDone(null) }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }

    // ---- more ----

    @Composable
    private fun MoreCard(account: Account, say: (String) -> Unit, onAccountChanged: () -> Unit) {
        val app = application as App
        val scope = rememberCoroutineScope()
        var relayDialog by remember { mutableStateOf(false) }
        var relayUrl by remember { mutableStateOf(account.baseUrl) }
        var standby by remember { mutableStateOf(app.phone.standby) }
        var confirmRotate by remember { mutableStateOf(false) }
        val replaced = stringResource(R.string.token_replaced)
        var humanPace by remember { mutableStateOf(app.phone.pace != "fast") }
        var tracing by remember { mutableStateOf(app.traces.enabled) }
        var feedbackDialog by remember { mutableStateOf(false) }
        var policyMode by remember { mutableStateOf(app.phone.appPolicy.mode) }
        var policyBlocked by remember { mutableStateOf(app.phone.appPolicy.blocked) }
        var policyAllowed by remember { mutableStateOf(app.phone.appPolicy.allowed) }
        var policyDialog by remember { mutableStateOf(false) }
        val installedApps = remember(policyDialog) { app.phone.launchableApps().map { it.second }.toSet() }
        fun setTracing(on: Boolean) {
            tracing = on
            app.traces.setEnabled(on)
        }
        fun setHumanPace(on: Boolean) {
            humanPace = on
            app.phone.pace = if (on) "auto" else "fast"
        }
        fun setStandby(on: Boolean) {
            standby = on
            app.phone.standby = on
            app.phone.applyStandby(BridgeService.running.value)
        }
        GroupCard {
            ListItem(
                headlineContent = { Text(stringResource(R.string.onphone_title)) },
                supportingContent = { Text(stringResource(R.string.onphone_body)) },
                leadingContent = { Icon(AppIcons.Sparkle, null) },
                trailingContent = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null) },
                colors = clearItem(),
                modifier = Modifier.clickable { startActivity(Intent(this@MainActivity, AgentActivity::class.java)) },
            )
            ListItem(
                headlineContent = { Text(stringResource(R.string.standby_title)) },
                supportingContent = { Text(stringResource(R.string.standby_body)) },
                leadingContent = { Icon(AppIcons.LockOpen, null) },
                trailingContent = { Switch(checked = standby, onCheckedChange = ::setStandby) },
                colors = clearItem(),
                modifier = Modifier.clickable { setStandby(!standby) },
            )
            ListItem(
                headlineContent = { Text(stringResource(R.string.pace_title)) },
                supportingContent = { Text(stringResource(R.string.pace_body)) },
                leadingContent = { Icon(AppIcons.Pace, null) },
                trailingContent = { Switch(checked = humanPace, onCheckedChange = ::setHumanPace) },
                colors = clearItem(),
                modifier = Modifier.clickable { setHumanPace(!humanPace) },
            )
            ListItem(
                headlineContent = { Text(stringResource(R.string.apps_policy_title)) },
                supportingContent = { Text(policySummary(policyMode, policyBlocked, policyAllowed, installedApps)) },
                leadingContent = { Icon(AppIcons.Smartphone, null) },
                trailingContent = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null) },
                colors = clearItem(),
                modifier = Modifier.clickable { policyDialog = true },
            )
            if (policyDialog) AppPolicyDialog(policyMode, policyBlocked, policyAllowed) { saved ->
                policyDialog = false
                if (saved != null) {
                    val policy = app.phone.appPolicy
                    policy.mode = saved.first; policy.blocked = saved.second; policy.allowed = saved.third
                    policyMode = saved.first; policyBlocked = saved.second; policyAllowed = saved.third
                }
            }
            ListItem(
                headlineContent = { Text(stringResource(R.string.trace_title)) },
                supportingContent = { Text(stringResource(R.string.trace_setting_body)) },
                leadingContent = { Icon(AppIcons.Insights, null) },
                trailingContent = { Switch(checked = tracing, onCheckedChange = ::setTracing) },
                colors = clearItem(),
                modifier = Modifier.clickable { setTracing(!tracing) },
            )
            // What agents wrote, so the owner sees every note that is sent in their name.
            val notes = remember(tracing) { if (tracing) app.traces.notes() else emptyList() }
            if (notes.isNotEmpty()) {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.feedback_count, notes.size)) },
                    leadingContent = { Spacer(Modifier.size(24.dp)) },
                    trailingContent = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null) },
                    colors = clearItem(),
                    modifier = Modifier.clickable { feedbackDialog = true },
                )
            }
            if (feedbackDialog) FeedbackDialog(app.traces.notes()) { feedbackDialog = false }
            ListItem(
                headlineContent = { Text(stringResource(R.string.relay_title)) },
                supportingContent = { Text(if (account.usesDefaultRelay) stringResource(R.string.relay_default) else relayUrl) },
                leadingContent = { Icon(AppIcons.Cloud, null) },
                colors = clearItem(),
                modifier = Modifier.clickable { relayDialog = true },
            )
            ListItem(
                headlineContent = { Text(stringResource(R.string.rotate_title)) },
                supportingContent = { Text(stringResource(R.string.rotate_body)) },
                leadingContent = { Icon(Icons.Filled.Refresh, null) },
                colors = clearItem(),
                modifier = Modifier.clickable { confirmRotate = true },
            )
        }
        if (relayDialog) {
            var draft by remember { mutableStateOf(if (account.usesDefaultRelay) "" else account.baseUrl) }
            var invalid by remember { mutableStateOf(false) }
            fun apply(url: String) = scope.launch {
                if (!account.useRelay(url)) { invalid = true; return@launch }
                relayDialog = false
                if (account.baseUrl != relayUrl) { relayUrl = account.baseUrl; onAccountChanged() }
            }
            AlertDialog(
                onDismissRequest = { relayDialog = false },
                icon = { Icon(AppIcons.Cloud, null) },
                title = { Text(stringResource(R.string.relay_dialog_title)) },
                text = {
                    Column {
                        Text(stringResource(R.string.relay_dialog_body))
                        OutlinedTextField(
                            value = draft, onValueChange = { draft = it; invalid = false },
                            label = { Text(stringResource(R.string.relay_field)) },
                            placeholder = { Text(Account.DEFAULT_RELAY_URL) },
                            isError = invalid,
                            supportingText = if (invalid) ({ Text(stringResource(R.string.relay_invalid)) }) else null,
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                            modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                        )
                    }
                },
                confirmButton = { TextButton(onClick = { apply(draft) }) { Text(stringResource(R.string.action_save)) } },
                dismissButton = {
                    Row {
                        if (!account.usesDefaultRelay) TextButton(onClick = { apply("") }) { Text(stringResource(R.string.relay_use_default)) }
                        TextButton(onClick = { relayDialog = false }) { Text(stringResource(R.string.action_cancel)) }
                    }
                },
            )
        }
        if (confirmRotate) AlertDialog(
            onDismissRequest = { confirmRotate = false },
            icon = { Icon(Icons.Filled.Refresh, null) },
            title = { Text(stringResource(R.string.rotate_confirm_title)) },
            text = { Text(stringResource(R.string.rotate_confirm_body)) },
            confirmButton = {
                TextButton(onClick = { confirmRotate = false; app.token.regenerate(); say(replaced) }) { Text(stringResource(R.string.action_replace)) }
            },
            dismissButton = { TextButton(onClick = { confirmRotate = false }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }

    private fun copy(text: String) =
        (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("Miu Bridge", text))

    /** This phone's address for agents on its own Wi-Fi, or null when it is not on one. */
    private fun lanUrl(): String? = wifiIp()?.let { "http://$it:${McpServer.DEFAULT_PORT}/mcp" }

    private fun wifiIp(): String? = NetworkInterface.getNetworkInterfaces().toList()
        .filter { it.isUp && !it.isLoopback && (it.name.startsWith("wlan") || it.name.startsWith("eth")) }
        .flatMap { it.inetAddresses.toList() }
        .firstOrNull { it is Inet4Address }?.hostAddress
}
