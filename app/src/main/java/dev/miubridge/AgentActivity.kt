package dev.miubridge

import android.os.Bundle
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
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import dev.miubridge.agent.AgentSession
import dev.miubridge.agent.AgentSession.Entry
import dev.miubridge.agent.AgentSession.Kind
import dev.miubridge.agent.LlmClient
import dev.miubridge.ui.AppIcons
import dev.miubridge.ui.MiuBridgeTheme
import dev.miubridge.ui.LivePane
import dev.miubridge.ui.LiveThumbnail
import dev.miubridge.ui.TWO_PANE_MIN_WIDTH
import dev.miubridge.ui.LiveToggle
import dev.miubridge.ui.rememberLiveViewEnabled
import dev.miubridge.ui.ScreenWatchDialog
import kotlinx.coroutines.launch

/** The on-phone agent's chat: one session, live steps as it works, steering while it runs. */
class AgentActivity : ComponentActivity() {
    private val agent: AgentSession get() = (application as App).agent

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { MiuBridgeTheme { Chat() } }
    }

    /** What one row of the conversation shows: a single entry, or a run of phone steps folded together. */
    private sealed interface Item {
        class One(val entry: Entry, val live: Boolean) : Item
        class Steps(val steps: List<Entry>, val live: Boolean) : Item
    }

    private fun items(done: List<Entry>, live: List<Entry>): List<Item> {
        val out = ArrayList<Item>()
        var run = ArrayList<Entry>()
        var runLive = false
        fun flush() { if (run.isNotEmpty()) { out += Item.Steps(run, runLive); run = ArrayList(); runLive = false } }
        (done.map { it to false } + live.map { it to true }).forEach { (e, isLive) ->
            if (e.kind == Kind.TOOL || e.kind == Kind.TOOL_ERROR) { run += e; runLive = runLive || isLive }
            // The model's notes between two steps belong to the same stretch of work.
            else if (e.kind == Kind.THINKING && run.isNotEmpty()) Unit
            else { flush(); out += Item.One(e, isLive) }
        }
        flush()
        return out
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun Chat() {
        val state by agent.state.collectAsState()
        var config by remember { mutableStateOf(agent.config()) }
        var menuOpen by remember { mutableStateOf(false) }
        var modelSheet by remember { mutableStateOf(false) }
        var watching by remember { mutableStateOf(false) }
        var draft by remember { mutableStateOf("") }
        val liveView = rememberLiveViewEnabled()
        val rows = items(state.lines, state.live)

        Scaffold(
            topBar = {
                Column {
                    TopAppBar(
                        title = {
                            Column {
                                Text(stringResource(R.string.agent_title))
                                Text(
                                    statusText(state),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                                )
                            }
                        },
                        navigationIcon = { IconButton(onClick = { finish() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.cd_back)) } },
                        actions = {
                            LiveToggle((application as App).phone, liveView)
                            IconButton(onClick = { menuOpen = true }) { Icon(Icons.Filled.MoreVert, stringResource(R.string.cd_more)) }
                            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                                DropdownMenuItem(text = { Text(stringResource(R.string.menu_new)) }, onClick = { menuOpen = false; agent.newSession() })
                                DropdownMenuItem(text = { Text(stringResource(R.string.menu_compact)) }, onClick = { menuOpen = false; agent.compact() })
                                DropdownMenuItem(text = { Text(stringResource(R.string.menu_model)) }, onClick = { menuOpen = false; modelSheet = true })
                            }
                        },
                    )
                    if (state.running) LinearProgressIndicator(Modifier.fillMaxWidth())
                }
            },
            bottomBar = { if (config.ready) Composer(draft, { draft = it }, state.running, state.queued) },
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
        ) { padding ->
            val listState = rememberLazyListState()
            // Follow the conversation as it grows.
            LaunchedEffect(rows.size, state.live) { if (rows.isNotEmpty()) listState.scrollToItem(rows.size - 1, Int.MAX_VALUE / 2) }
          BoxWithConstraints(Modifier.fillMaxSize().padding(padding)) {
           val twoPane = maxWidth >= TWO_PANE_MIN_WIDTH && liveView.value
           Row {
            Box(Modifier.weight(1f).fillMaxHeight()) {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (!config.ready) item { ModelPrompt { modelSheet = true } }
                else if (rows.isEmpty()) item { EmptyState(Modifier.fillParentMaxHeight(0.9f)) { draft = it } }
                items(rows.size) { i ->
                    when (val row = rows[i]) {
                        is Item.One -> EntryView(row.entry, row.live)
                        is Item.Steps -> StepsCard(row.steps, working = state.running && i == rows.lastIndex) { watching = true }
                    }
                }
            }
            if (!twoPane) LiveThumbnail((application as App).phone, liveView.value, Modifier.align(Alignment.TopEnd).padding(top = 8.dp, end = 16.dp))
            }
            if (twoPane) LivePane((application as App).phone, Modifier.weight(0.8f).fillMaxHeight().padding(end = 16.dp, bottom = 12.dp))
           }
          }
        }

        if (modelSheet) ModelSheet(config, onDismiss = { modelSheet = false }) { agent.save(it); config = agent.config(); modelSheet = false }
        if (watching) ScreenWatchDialog((application as App).phone) { watching = false }
    }

    @Composable
    private fun statusText(state: AgentSession.State): String {
        val tool = toolLabel(state.status)
        return when {
            state.running && tool != null -> tool
            state.running -> state.status.ifBlank { stringResource(R.string.agent_working) }
            else -> state.status.ifBlank { stringResource(R.string.agent_idle) }
        }
    }

    // ---- composer ----

    @Composable
    private fun Composer(draft: String, onDraft: (String) -> Unit, running: Boolean, queued: List<String>) {
        fun take(): String? = draft.trim().takeIf { it.isNotEmpty() }?.also { onDraft("") }
        Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
            Column(Modifier.windowInsetsPadding(WindowInsets.ime.union(WindowInsets.navigationBars)).padding(horizontal = 12.dp, vertical = 8.dp)) {
                // The buffer: what the owner sent while it was working. Each waits for the run to finish
                // unless inserted now (the agent then reads it before its next step) or taken back.
                queued.forEachIndexed { i, text ->
                    Surface(color = MaterialTheme.colorScheme.surfaceContainerHighest, shape = MaterialTheme.shapes.large, modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp)) {
                        Row(Modifier.padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
                                Text(stringResource(R.string.queued_label), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(text, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            }
                            TextButton(onClick = { agent.insertNow(i) }) { Text(stringResource(R.string.chip_interrupt)) }
                            IconButton(onClick = { agent.dropQueued(i) }) { Icon(Icons.Filled.Close, stringResource(R.string.cd_remove), tint = MaterialTheme.colorScheme.onSurfaceVariant) }
                        }
                    }
                }
                Row(verticalAlignment = Alignment.Bottom) {
                    TextField(
                        value = draft, onValueChange = onDraft,
                        placeholder = { Text(stringResource(if (running) R.string.composer_hint_running else R.string.composer_hint)) },
                        maxLines = 4,
                        shape = RoundedCornerShape(28.dp),
                        colors = TextFieldDefaults.colors(
                            focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent,
                            focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                        ),
                        modifier = Modifier.weight(1f),
                    )
                    if (running) {
                        Spacer(Modifier.width(8.dp))
                        FilledTonalIconButton(
                            onClick = { agent.abort() },
                            colors = IconButtonDefaults.filledTonalIconButtonColors(
                                containerColor = MaterialTheme.colorScheme.errorContainer, contentColor = MaterialTheme.colorScheme.onErrorContainer,
                            ),
                            modifier = Modifier.size(56.dp),
                        ) { Icon(AppIcons.Stop, stringResource(R.string.action_stop_short)) }
                    }
                    Spacer(Modifier.width(8.dp))
                    FilledIconButton(
                        onClick = { take()?.let { if (running) agent.followUp(it) else agent.prompt(it) } },
                        enabled = draft.isNotBlank(), modifier = Modifier.size(56.dp),
                    ) { Icon(Icons.AutoMirrored.Filled.Send, stringResource(R.string.cd_send)) }
                }
            }
        }
    }

    // ---- empty and model states ----

    @Composable
    private fun EmptyState(modifier: Modifier, onExample: (String) -> Unit) {
        Column(modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Box(Modifier.size(72.dp).background(MaterialTheme.colorScheme.primaryContainer, CircleShape), contentAlignment = Alignment.Center) {
                Icon(AppIcons.Sparkle, null, Modifier.size(36.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer)
            }
            Text(stringResource(R.string.empty_title), style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(top = 20.dp))
            Text(
                stringResource(R.string.empty_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 8.dp, bottom = 20.dp),
            )
            listOf(R.string.example_1, R.string.example_2, R.string.example_3).forEach { id ->
                val example = stringResource(id)
                SuggestionChip(onClick = { onExample(example) }, label = { Text(example) })
            }
        }
    }

    @Composable
    private fun ModelPrompt(onSetUp: () -> Unit) {
        Column(Modifier.fillMaxWidth().padding(top = 48.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.size(72.dp).background(MaterialTheme.colorScheme.primaryContainer, CircleShape), contentAlignment = Alignment.Center) {
                Icon(AppIcons.Sparkle, null, Modifier.size(36.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer)
            }
            Text(stringResource(R.string.model_setup_title), style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(top = 20.dp))
            Text(
                stringResource(R.string.model_setup_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 8.dp, bottom = 20.dp, start = 16.dp, end = 16.dp),
            )
            Button(onClick = onSetUp) { Text(stringResource(R.string.model_setup_action)) }
        }
    }

    /** Provider, key and model. The model list is read from the service with the key just typed. */
    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun ModelSheet(current: AgentSession.ModelConfig, onDismiss: () -> Unit, onSave: (AgentSession.ModelConfig) -> Unit) {
        val scope = rememberCoroutineScope()
        var provider by remember { mutableStateOf(current.provider) }
        var key by remember { mutableStateOf(current.apiKey) }
        var baseUrl by remember { mutableStateOf(current.baseUrl) }
        var model by remember { mutableStateOf(current.model) }
        var models by remember { mutableStateOf<List<String>>(emptyList()) }
        var loading by remember { mutableStateOf(false) }
        var failed by remember { mutableStateOf(false) }
        var providerMenu by remember { mutableStateOf(false) }
        var modelMenu by remember { mutableStateOf(false) }
        var effort by remember { mutableStateOf(current.effort) }
        val draft = AgentSession.ModelConfig(provider, baseUrl.trim().trimEnd('/'), model.trim(), key.trim(), effort)

        @Composable fun providerName(p: LlmClient.Provider) = if (p.id == LlmClient.CUSTOM) stringResource(R.string.provider_custom) else p.name

        ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
            Column(
                Modifier.verticalScroll(rememberScrollState()).padding(start = 24.dp, end = 24.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text(stringResource(R.string.menu_model), style = MaterialTheme.typography.headlineSmall)

                ExposedDropdownMenuBox(expanded = providerMenu, onExpandedChange = { providerMenu = it }) {
                    OutlinedTextField(
                        value = providerName(provider), onValueChange = {}, readOnly = true,
                        label = { Text(stringResource(R.string.model_provider)) },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(providerMenu) },
                        modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable).fillMaxWidth(),
                    )
                    ExposedDropdownMenu(expanded = providerMenu, onDismissRequest = { providerMenu = false }) {
                        LlmClient.PROVIDERS.forEach { p ->
                            DropdownMenuItem(text = { Text(providerName(p)) }, onClick = {
                                providerMenu = false
                                // Each provider keeps its own key and model.
                                val saved = agent.config(p.id)
                                provider = p; key = saved.apiKey; baseUrl = saved.baseUrl; model = saved.model; effort = saved.effort
                                models = emptyList(); failed = false
                            })
                        }
                    }
                }

                if (provider.id == LlmClient.CUSTOM) OutlinedTextField(
                    value = baseUrl, onValueChange = { baseUrl = it },
                    label = { Text(stringResource(R.string.model_base_url)) },
                    placeholder = { Text("https://api.example.com/v1") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    modifier = Modifier.fillMaxWidth(),
                )

                OutlinedTextField(
                    value = key, onValueChange = { key = it },
                    label = { Text(stringResource(R.string.model_key)) },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth(),
                )

                ExposedDropdownMenuBox(expanded = modelMenu && models.isNotEmpty(), onExpandedChange = { modelMenu = it }) {
                    OutlinedTextField(
                        value = model, onValueChange = { model = it },
                        label = { Text(stringResource(R.string.model_name)) },
                        singleLine = true,
                        trailingIcon = { if (models.isNotEmpty()) ExposedDropdownMenuDefaults.TrailingIcon(modelMenu) },
                        supportingText = { if (failed) Text(stringResource(R.string.model_fetch_failed), color = MaterialTheme.colorScheme.error) },
                        modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryEditable).fillMaxWidth(),
                    )
                    ExposedDropdownMenu(expanded = modelMenu && models.isNotEmpty(), onDismissRequest = { modelMenu = false }, modifier = Modifier.heightIn(max = 320.dp)) {
                        models.forEach { m -> DropdownMenuItem(text = { Text(m) }, onClick = { model = m; modelMenu = false }) }
                    }
                }

                Column {
                    Text(stringResource(R.string.model_effort), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 8.dp))
                    val levels = listOf("" to R.string.effort_default, "low" to R.string.effort_low, "medium" to R.string.effort_medium, "high" to R.string.effort_high)
                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                        levels.forEachIndexed { i, (value, label) ->
                            SegmentedButton(selected = effort == value, onClick = { effort = value }, shape = SegmentedButtonDefaults.itemShape(i, levels.size), icon = {}) {
                                Text(stringResource(label), maxLines = 1)
                            }
                        }
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(
                        onClick = {
                            loading = true; failed = false
                            scope.launch {
                                val list = runCatching { agent.client(draft.copy(model = draft.model.ifBlank { "-" })).listModels() }.getOrNull()
                                loading = false
                                if (list.isNullOrEmpty()) failed = true else { models = list; modelMenu = true }
                            }
                        },
                        enabled = key.isNotBlank() && draft.baseUrl.isNotBlank() && !loading,
                        modifier = Modifier.weight(1f),
                    ) {
                        if (loading) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) else Text(stringResource(R.string.model_fetch))
                    }
                    Button(onClick = { onSave(draft) }, enabled = draft.ready, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.action_save)) }
                }
            }
        }
    }

    // ---- transcript ----

    @Composable
    private fun EntryView(entry: Entry, live: Boolean) {
        val scheme = MaterialTheme.colorScheme
        when (entry.kind) {
            Kind.USER -> Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
                Bubble(AnnotatedString(entry.text), scheme.primary, scheme.onPrimary, RoundedCornerShape(20.dp, 20.dp, 4.dp, 20.dp))
            }
            Kind.AGENT -> Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterStart) {
                Bubble(markdown(entry.text), scheme.surfaceContainerHigh, scheme.onSurface, RoundedCornerShape(20.dp, 20.dp, 20.dp, 4.dp))
            }
            Kind.THINKING -> Thinking(entry.text, live)
            Kind.ERROR -> Surface(color = scheme.errorContainer, contentColor = scheme.onErrorContainer, shape = MaterialTheme.shapes.medium) {
                Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Warning, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(10.dp))
                    Text(friendlyError(entry.text), style = MaterialTheme.typography.bodyMedium)
                }
            }
            Kind.STOPPED -> Marker(stringResource(R.string.entry_stopped))
            Kind.COMPACTED -> Marker(stringResource(R.string.entry_compacted))
            Kind.TOOL, Kind.TOOL_ERROR -> Unit // shown inside a StepsCard
        }
    }

    @Composable
    private fun Marker(text: String) {
        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            HorizontalDivider(Modifier.weight(1f))
            Text(text, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.outline, modifier = Modifier.padding(horizontal = 12.dp))
            HorizontalDivider(Modifier.weight(1f))
        }
    }

    @Composable
    private fun Bubble(text: AnnotatedString, color: Color, onColor: Color, shape: RoundedCornerShape) {
        Surface(color = color, contentColor = onColor, shape = shape, modifier = Modifier.widthIn(max = 320.dp)) {
            SelectionContainer { Text(text, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) }
        }
    }

    /** The model's reasoning: two lines by default, tap to read it all. */
    @Composable
    private fun Thinking(text: String, live: Boolean) {
        var open by remember { mutableStateOf(false) }
        Column(Modifier.fillMaxWidth().clip(MaterialTheme.shapes.small).clickable { open = !open }.padding(horizontal = 4.dp, vertical = 2.dp)) {
            Text(stringResource(R.string.thinking_label), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            Text(
                text,
                style = MaterialTheme.typography.bodySmall, fontStyle = FontStyle.Italic,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = if (open) Int.MAX_VALUE else 1, overflow = TextOverflow.Ellipsis,
            )
        }
    }

    /**
     * A run of phone actions folded into one line: how many steps, and the current one while it
     * works. Opens to the individual steps; the phone icon shows the background screen live.
     */
    @Composable
    private fun StepsCard(steps: List<Entry>, working: Boolean, onWatch: () -> Unit) {
        var open by remember { mutableStateOf(false) }
        val scheme = MaterialTheme.colorScheme
        val tools = steps.filter { it.kind == Kind.TOOL }
        val current = tools.lastOrNull()?.let { toolLabel(it.text) ?: it.text }.orEmpty()
        Surface(color = scheme.surfaceContainerLow, shape = MaterialTheme.shapes.large, modifier = Modifier.fillMaxWidth()) {
            Column {
                Row(
                    Modifier.fillMaxWidth().clickable { open = !open }.padding(start = 16.dp, end = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (working) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    else Icon(Icons.Filled.Check, null, Modifier.size(18.dp), tint = scheme.tertiary)
                    Spacer(Modifier.width(12.dp))
                    Text(
                        if (working) stringResource(R.string.steps_running, tools.size, current) else stringResource(R.string.steps_summary, tools.size),
                        style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    if (working) IconButton(onClick = onWatch) { Icon(AppIcons.Smartphone, stringResource(R.string.cd_watch), tint = scheme.primary) }
                    IconButton(onClick = { open = !open }) {
                        Icon(if (open) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown, stringResource(if (open) R.string.cd_collapse else R.string.cd_expand), tint = scheme.onSurfaceVariant)
                    }
                }
                AnimatedVisibility(open) {
                    Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        steps.forEach { step ->
                            if (step.kind == Kind.TOOL_ERROR) {
                                Text(step.text, style = MaterialTheme.typography.bodySmall, color = scheme.error, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 24.dp))
                            } else Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(AppIcons.Smartphone, null, Modifier.size(14.dp), tint = scheme.primary)
                                Spacer(Modifier.width(10.dp))
                                Text(toolLabel(step.text) ?: step.text, style = MaterialTheme.typography.bodyMedium)
                                if (step.detail.isNotEmpty() && step.detail != "{}") Text(
                                    step.detail,
                                    style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace,
                                    color = scheme.outline, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.padding(start = 8.dp),
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun toolLabel(name: String): String? = when (name) {
        "phone_apps" -> R.string.tool_phone_apps
        "phone_launch" -> R.string.tool_phone_launch
        "phone_open_url" -> R.string.tool_phone_open_url
        "phone_snapshot" -> R.string.tool_phone_snapshot
        "phone_screenshot" -> R.string.tool_phone_screenshot
        "phone_tap" -> R.string.tool_phone_tap
        "phone_long_press" -> R.string.tool_phone_long_press
        "phone_swipe" -> R.string.tool_phone_swipe
        "phone_type" -> R.string.tool_phone_type
        "phone_key" -> R.string.tool_phone_key
        "phone_handoff" -> R.string.tool_phone_handoff
        "phone_script" -> R.string.tool_phone_script
        "phone_skill_read" -> R.string.tool_phone_skill_read
        "phone_skill_learn" -> R.string.tool_phone_skill_learn
        "bash" -> R.string.tool_bash
        "read" -> R.string.tool_read
        "write" -> R.string.tool_write
        "edit" -> R.string.tool_edit
        else -> null
    }?.let { stringResource(it) }

    /** The common failures in the owner's words; anything else is shown as it came. */
    @Composable
    private fun friendlyError(message: String): String = when {
        message.startsWith("401") || message.startsWith("403") || "Authentication" in message -> stringResource(R.string.err_llm_key)
        message.startsWith("402") || "Insufficient Balance" in message -> stringResource(R.string.err_llm_balance)
        message.startsWith("429") -> stringResource(R.string.err_llm_busy)
        message.startsWith("404") || "model" in message.lowercase() && "not" in message.lowercase() -> stringResource(R.string.err_llm_model)
        "UnknownHost" in message || "timeout" in message.lowercase() || "Unable to resolve" in message -> stringResource(R.string.err_llm_network)
        else -> message
    }

    /** Enough Markdown for chat answers: **bold**, `code`, headings and bullets. */
    private fun markdown(text: String): AnnotatedString = buildAnnotatedString {
        text.lines().forEachIndexed { i, raw ->
            if (i > 0) append('\n')
            var line = raw
            val heading = Regex("^#{1,6}\\s+").find(line)
            if (heading != null) line = line.removePrefix(heading.value)
            line = Regex("^(\\s*)[-*]\\s+").replace(line) { "${it.groupValues[1]}• " }
            var bold = heading != null
            var code = false
            var j = 0
            while (j < line.length) {
                when {
                    line.startsWith("**", j) && heading == null -> { bold = !bold; j += 2 }
                    line[j] == '`' -> { code = !code; j++ }
                    else -> {
                        val style = SpanStyle(fontWeight = if (bold) FontWeight.Bold else null, fontFamily = if (code) FontFamily.Monospace else null)
                        withStyle(style) { append(line[j]) }
                        j++
                    }
                }
            }
        }
    }
}
