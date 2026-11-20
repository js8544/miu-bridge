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
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
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
        var signedIn by remember { mutableStateOf(account.signedIn) }
        var connectSheet by remember { mutableStateOf(false) }
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
                    Guide(account, signedIn, running, phone, resumes, ::say, onDone = ::endGuide) {
                        signedIn = account.signedIn
                        restartService()
                    }
                }
              } else {
                item { StatusCard(running, phone, relay, signedIn) }
                item { SectionTitle(stringResource(R.string.section_connect)) }
                item {
                    ConnectCard(account, signedIn, running, relay, ::say, onConnect = { connectSheet = true }) {
                        signedIn = account.signedIn
                        restartService()
                    }
                }
                item { SectionTitle(stringResource(R.string.section_setup)) }
                item { SetupCard(phone, resumes, ::say) }
                item { SectionTitle(stringResource(R.string.section_more)) }
                item {
                    MoreCard(account, ::say) {
                        signedIn = account.signedIn
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
        if (connectSheet) ConnectSheet(account, signedIn, ::say) { connectSheet = false }
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

    // ---- status ----

    private class Look(val title: String, val body: String, val icon: ImageVector, val container: Color, val content: Color)

    @Composable
    private fun StatusCard(running: Boolean, phone: PhoneState, relay: RelayClient.State, signedIn: Boolean) {
        val scheme = MaterialTheme.colorScheme
        val scope = rememberCoroutineScope()
        var watching by remember { mutableStateOf(false) }
        if (watching) ScreenWatchDialog((application as App).phone) { watching = false }
        val look = when {
            !running -> Look(stringResource(R.string.status_stopped_title), stringResource(R.string.status_stopped_body), Icons.Filled.PlayArrow, scheme.surfaceContainerHigh, scheme.onSurface)
            !phone.shellReady -> Look(stringResource(R.string.status_preparing_title), phone.message.ifBlank { stringResource(R.string.status_preparing_body) }, Icons.Filled.Refresh, scheme.secondaryContainer, scheme.onSecondaryContainer)
            !signedIn -> Look(stringResource(R.string.status_local_title), stringResource(R.string.status_local_body), Icons.Filled.CheckCircle, scheme.primaryContainer, scheme.onPrimaryContainer)
            !relay.connected -> Look(stringResource(R.string.status_remote_down_title), relay.message.ifBlank { stringResource(R.string.remote_connecting) }, Icons.Filled.Warning, scheme.errorContainer, scheme.onErrorContainer)
            else -> Look(stringResource(R.string.status_ready_title), stringResource(R.string.status_ready_body), Icons.Filled.CheckCircle, scheme.primaryContainer, scheme.onPrimaryContainer)
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
     * First run, one thing per screen: get the phone ready, start, hand it to an agent, see the agent
     * arrive. Which step shows follows from the phone's real state, so leaving for a system page and
     * coming back lands on the right one.
     */
    @Composable
    private fun Guide(
        account: Account, signedIn: Boolean, running: Boolean, phone: PhoneState, resumes: Int,
        say: (String) -> Unit, onDone: () -> Unit, onAccountChanged: () -> Unit,
    ) {
        val scope = rememberCoroutineScope()
        val ui = remember { getSharedPreferences("ui", MODE_PRIVATE) }
        val (devOn, paired) = remember(resumes, phone.shellReady) {
            val pairedNow = phone.shellReady || WirelessAdb.isPaired(this)
            (pairedNow || Settings.Global.getInt(contentResolver, Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 0) == 1) to pairedNow
        }
        // For a phone whose debugging was opened from a computer instead of by pairing here.
        var preparedElsewhere by remember { mutableStateOf(ui.getBoolean("prepared_elsewhere", false)) }
        var handedOver by remember { mutableStateOf(ui.getBoolean("handed_over", false)) }
        fun setHandedOver(v: Boolean) { handedOver = v; ui.edit().putBoolean("handed_over", v).apply() }
        val ready = running && phone.shellReady
        val consent = remember { dev.miubridge.core.AppConsent(this) }
        var noticed by remember { mutableStateOf(consent.acceptedNotice) }
        val step = when {
            !ready && !paired && !preparedElsewhere -> 1
            !ready -> 2
            !noticed -> 3
            !handedOver -> 4
            else -> 5
        }

        Column(Modifier.fillMaxWidth().padding(top = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.guide_progress, step, 5), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, modifier = Modifier.weight(1f))
                TextButton(onClick = onDone) { Text(stringResource(R.string.guide_skip)) }
            }
            androidx.compose.material3.LinearProgressIndicator(progress = { step / 5f }, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(28.dp))
            when (step) {
                1 -> GuideStep(R.string.guide1_title, R.string.guide1_body) {
                    GroupCard {
                        Step(1, R.string.step_dev_title, R.string.step_dev_body, devOn) { startActivity(Intent(Settings.ACTION_DEVICE_INFO_SETTINGS)) }
                        Step(2, R.string.step_pair_title, R.string.step_pair_body, paired) {
                            startPairing()
                        }
                    }
                    Button(
                        onClick = {
                            if (!devOn) startActivity(Intent(Settings.ACTION_DEVICE_INFO_SETTINGS))
                            else startPairing()
                        },
                        modifier = Modifier.fillMaxWidth().padding(top = 20.dp),
                    ) { Text(stringResource(if (!devOn) R.string.guide1_open_about else R.string.guide1_pair)) }
                    // Some phones (HarmonyOS, Android 10 and earlier) have no wireless debugging: a computer opens the door once.
                    var computerHelp by remember { mutableStateOf(false) }
                    TextButton(onClick = { computerHelp = true }, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                        Text(stringResource(R.string.guide1_no_wireless))
                    }
                    if (computerHelp) ComputerSetupDialog(say, onDismiss = { computerHelp = false }) {
                        computerHelp = false
                        preparedElsewhere = true
                        ui.edit().putBoolean("prepared_elsewhere", true).apply()
                    }
                }
                2 -> {
                    // Nothing for the owner to decide here: start by ourselves.
                    LaunchedEffect(Unit) {
                        if (!BridgeService.running.value) {
                            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
                            BridgeService.start(this@MainActivity)
                        }
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
                            onClick = { preparedElsewhere = false; ui.edit().putBoolean("prepared_elsewhere", false).apply() },
                            modifier = Modifier.align(Alignment.CenterHorizontally),
                        ) { Text(stringResource(R.string.guide_back)) }
                    }
                }
                3 -> GuideStep(R.string.notice_title, R.string.notice_intro) {
                    RiskPoints()
                    Button(onClick = { consent.acceptNotice(); noticed = true }, modifier = Modifier.fillMaxWidth().padding(top = 24.dp)) {
                        Text(stringResource(R.string.notice_accept))
                    }
                }
                4 -> {
                    val token = (application as App).token.value
                    var remote by remember { mutableStateOf(true) }
                    val url = if (remote) account.mcpUrl ?: mcpUrl() else mcpUrl()
                    val copiedGuide = stringResource(R.string.copied_guide)
                    var copied by remember { mutableStateOf(false) }
                    GuideStep(R.string.guide3_title, R.string.guide3_body) {
                        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                            SegmentedButton(selected = remote, onClick = { remote = true }, shape = SegmentedButtonDefaults.itemShape(0, 2)) { Text(stringResource(R.string.route_remote)) }
                            SegmentedButton(selected = !remote, onClick = { remote = false }, shape = SegmentedButtonDefaults.itemShape(1, 2)) { Text(stringResource(R.string.route_lan)) }
                        }
                        Text(
                            stringResource(if (remote) R.string.guide3_remote_hint else R.string.guide3_lan_hint),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                        if (remote && !signedIn) {
                            // Reaching the phone from other networks needs the account first.
                            SignInForm(account, say, onAccountChanged, Modifier.padding(top = 16.dp), showTitle = false)
                        } else {
                            Button(
                                onClick = { copy(connectionDetails(url, token, account.baseUrl)); say(copiedGuide); copied = true },
                                modifier = Modifier.fillMaxWidth().padding(top = 20.dp),
                                colors = if (copied) ButtonDefaults.filledTonalButtonColors() else ButtonDefaults.buttonColors(),
                            ) {
                                Icon(AppIcons.Copy, null, Modifier.size(18.dp))
                                Spacer(Modifier.width(8.dp))
                                Text(stringResource(R.string.connect_copy_guide))
                            }
                            CopyRow(stringResource(R.string.connect_token), "••••••••" + token.takeLast(4), token, stringResource(R.string.copied_token), say)
                            if (copied) Button(onClick = { setHandedOver(true) }, modifier = Modifier.fillMaxWidth().padding(top = 16.dp)) {
                                Text(stringResource(R.string.guide3_next))
                            }
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

    /** The risks an owner takes on by letting agents operate apps, in plain words. Shown in the guide and to earlier owners. */
    @Composable
    private fun RiskPoints() {
        GroupCard {
            Column(Modifier.padding(vertical = 8.dp)) {
                for ((icon, text) in listOf(
                    Icons.Filled.Warning to R.string.notice_point_terms,
                    Icons.Filled.CheckCircle to R.string.notice_point_yours,
                    AppIcons.LockOpen to R.string.notice_point_irreversible,
                    AppIcons.Smartphone to R.string.notice_point_ask,
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

    /** How to hand this phone to an agent, and the account that makes it reachable from other networks. */
    @Composable
    private fun ConnectCard(
        account: Account, signedIn: Boolean, running: Boolean, relay: RelayClient.State,
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
            if (!signedIn) {
                SignInForm(account, say, onAccountChanged)
            } else {
                var confirmSignOut by remember { mutableStateOf(false) }
                val email = account.email.orEmpty()
                var usage by remember { mutableStateOf<Account.Usage?>(null) }
                // Asked once, right after signing in (and once for owners who signed in before it existed).
                var askTrace by remember { mutableStateOf(!(application as App).traces.asked) }
                if (askTrace) TraceDialog { join ->
                    (application as App).traces.setEnabled(join)
                    askTrace = false
                }
                LaunchedEffect(email, relay.connected) { usage = account.usage() }
                ListItem(
                    headlineContent = { Text(email) },
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
                            Text(email.take(1).uppercase(), color = MaterialTheme.colorScheme.onPrimary, style = MaterialTheme.typography.titleMedium)
                        }
                    },
                    trailingContent = { TextButton(onClick = { confirmSignOut = true }) { Text(stringResource(R.string.action_sign_out)) } },
                    colors = clearItem(),
                )
                if (confirmSignOut) AlertDialog(
                    onDismissRequest = { confirmSignOut = false },
                    title = { Text(stringResource(R.string.signout_title)) },
                    text = { Text(stringResource(R.string.signout_body)) },
                    confirmButton = {
                        TextButton(onClick = { confirmSignOut = false; scope.launch { account.signOut(); onAccountChanged() } }) { Text(stringResource(R.string.action_sign_out)) }
                    },
                    dismissButton = { TextButton(onClick = { confirmSignOut = false }) { Text(stringResource(R.string.action_cancel)) } },
                )
            }
        }
    }

    /** Email + emailed code. The first sign-in creates the account. */
    @Composable
    private fun SignInForm(account: Account, say: (String) -> Unit, onAccountChanged: () -> Unit, modifier: Modifier = Modifier.padding(20.dp), showTitle: Boolean = true) {
        val scope = rememberCoroutineScope()
        var email by remember { mutableStateOf("") }
        var code by remember { mutableStateOf("") }
        var codeSent by remember { mutableStateOf(false) }
        var busy by remember { mutableStateOf(false) }
        var cooldown by remember { mutableIntStateOf(0) }
        LaunchedEffect(cooldown) { if (cooldown > 0) { delay(1_000); cooldown-- } }
        val needEmail = stringResource(R.string.need_email)
        val sentTo = stringResource(R.string.code_sent, email.trim())
        // The message about what happened shows at the bottom of the screen: get the keyboard out of its way.
        val keyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
        fun sendCode() {
            if (email.isBlank()) { say(needEmail); return }
            keyboard?.hide()
            busy = true
            scope.launch {
                val sent = account.sendCode(email.trim())
                busy = false
                if (sent.codeMayArrive) { codeSent = true; cooldown = 60 }
                say(sent.message ?: sentTo)
            }
        }
        Column(modifier) {
            if (showTitle) {
                Text(stringResource(R.string.signin_title), style = MaterialTheme.typography.titleMedium)
                Text(
                    stringResource(R.string.signin_body),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp, bottom = 16.dp),
                )
            }
            OutlinedTextField(
                value = email, onValueChange = { email = it },
                label = { Text(stringResource(R.string.field_email)) },
                leadingIcon = { Icon(Icons.Filled.Email, null) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                modifier = Modifier.fillMaxWidth(),
            )
            AnimatedVisibility(codeSent) {
                OutlinedTextField(
                    value = code, onValueChange = { code = it.filter(Char::isDigit).take(6) },
                    label = { Text(stringResource(R.string.field_code)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                )
            }
            FilledTonalButton(
                onClick = {
                    if (!codeSent) sendCode() else {
                        keyboard?.hide()
                        busy = true
                        scope.launch {
                            val error = account.signIn(email.trim(), code)
                            busy = false
                            if (error != null) say(error) else onAccountChanged()
                        }
                    }
                },
                enabled = !busy,
                modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
            ) {
                if (busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                else Text(stringResource(if (codeSent) R.string.action_sign_in else R.string.action_get_code))
            }
            if (codeSent) {
                TextButton(onClick = { sendCode() }, enabled = cooldown == 0 && !busy, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                    Text(if (cooldown > 0) stringResource(R.string.action_resend_wait, cooldown) else stringResource(R.string.action_resend))
                }
            }
        }
    }

    /**
     * The complete hand-off for any agent: the address, the token, and both ways to use them. The
     * agent picks MCP or scripts. An agent that keeps secrets in a credential store asks for the
     * token again on its own, which is why the sheet also offers each value separately.
     */
    private fun connectionDetails(url: String, token: String, relay: String): String = """
        Miu Bridge: operate apps on my Android phone, on a background display.

        URL: $url
        Token: $token
        Send the token as the header "Authorization: Bearer <token>". Keep it secret.

        Use it whichever way suits you; both reach the same phone and the same tools:
        1. As an MCP server (Streamable HTTP) at the URL, with that header.
           Claude Code: claude mcp add --transport http phone "$url" --header "Authorization: Bearer $token"
        2. From scripts in your own sandbox, with the SDK (JavaScript or Python, no dependencies):
           curl -fsSO $relay/sdk/miu-bridge.mjs   # or miu_bridge.py
           export MIU_BRIDGE_URL="$url"
           export MIU_BRIDGE_TOKEN="$token"
           Guide: $relay/sdk/
        If you call the URL without the SDK, send a User-Agent header that names your client.
    """.trimIndent()

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

    /** The owner picks how the agent reaches the phone, then copies the full hand-off, and the token alone when an agent's credential store asks for it. */
    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun ConnectSheet(account: Account, signedIn: Boolean, say: (String) -> Unit, onDismiss: () -> Unit) {
        val token = (application as App).token.value
        var remote by remember { mutableStateOf(signedIn) }
        val url = if (remote) account.mcpUrl ?: mcpUrl() else mcpUrl()
        val copiedGuide = stringResource(R.string.copied_guide)
        ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
            Column(Modifier.verticalScroll(rememberScrollState()).padding(start = 24.dp, end = 24.dp, bottom = 24.dp)) {
                Text(stringResource(R.string.connect_sheet_title), style = MaterialTheme.typography.headlineSmall)
                Text(
                    stringResource(R.string.connect_route),
                    style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 20.dp, bottom = 8.dp),
                )
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    SegmentedButton(selected = remote, onClick = { remote = true }, enabled = signedIn, shape = SegmentedButtonDefaults.itemShape(0, 2)) {
                        Text(stringResource(R.string.route_remote))
                    }
                    SegmentedButton(selected = !remote, onClick = { remote = false }, shape = SegmentedButtonDefaults.itemShape(1, 2)) {
                        Text(stringResource(R.string.route_lan))
                    }
                }
                if (!signedIn) Text(
                    stringResource(R.string.route_remote_need_login),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
                Button(
                    onClick = { copy(connectionDetails(url, token, account.baseUrl)); say(copiedGuide) },
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
        val consent = remember { dev.miubridge.core.AppConsent(this) }
        var askPerApp by remember { mutableStateOf(consent.askPerApp) }
        val allowedCount = remember(askPerApp) { consent.allowedApps.size }
        fun setAskPerApp(on: Boolean) { askPerApp = on; consent.askPerApp = on }
        var tracing by remember { mutableStateOf(app.traces.enabled) }
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
                headlineContent = { Text(stringResource(R.string.ask_app_title)) },
                supportingContent = {
                    Text(if (askPerApp && allowedCount > 0) stringResource(R.string.ask_app_body_count, allowedCount) else stringResource(R.string.ask_app_body))
                },
                leadingContent = { Icon(Icons.Filled.Warning, null) },
                trailingContent = { Switch(checked = askPerApp, onCheckedChange = ::setAskPerApp) },
                colors = clearItem(),
                modifier = Modifier.clickable { setAskPerApp(!askPerApp) },
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
                headlineContent = { Text(stringResource(R.string.trace_title)) },
                supportingContent = { Text(stringResource(R.string.trace_setting_body)) },
                leadingContent = { Icon(AppIcons.Insights, null) },
                trailingContent = { Switch(checked = tracing, onCheckedChange = ::setTracing) },
                colors = clearItem(),
                modifier = Modifier.clickable { setTracing(!tracing) },
            )
            ListItem(
                headlineContent = { Text(stringResource(R.string.relay_title)) },
                supportingContent = { Text(if (relayUrl == Account.DEFAULT_RELAY_URL) stringResource(R.string.relay_default) else relayUrl) },
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

    private fun mcpUrl(): String = "http://${wifiIp() ?: "<phone-ip>"}:${McpServer.DEFAULT_PORT}/mcp"

    private fun wifiIp(): String? = NetworkInterface.getNetworkInterfaces().toList()
        .filter { it.isUp && !it.isLoopback && (it.name.startsWith("wlan") || it.name.startsWith("eth")) }
        .flatMap { it.inetAddresses.toList() }
        .firstOrNull { it is Inet4Address }?.hostAddress
}
