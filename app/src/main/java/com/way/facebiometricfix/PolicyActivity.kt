package com.way.facebiometricfix

import android.os.Build
import android.os.Bundle
import android.widget.ImageView
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import java.util.Locale

class PolicyActivity : ComponentActivity() {
    private lateinit var app: PolicyApplication
    private lateinit var store: PolicyStore

    private var candidates by mutableStateOf<List<BiometricCandidateApp>>(emptyList())
    private var scanning by mutableStateOf(false)
    private var frameworkConnected by mutableStateOf(false)
    private var defaultMode by mutableStateOf(BiometricPolicyMode.ANY)
    private var policyRevision by mutableStateOf(0)

    private val serviceListener: (Boolean) -> Unit = { connected ->
        runOnUiThread {
            frameworkConnected = connected
            defaultMode = store.defaultMode()
            policyRevision++
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        app = PolicyApplication.from(this)
        store = app.policyStore
        frameworkConnected = app.isFrameworkConnected()
        defaultMode = store.defaultMode()

        enableEdgeToEdge()

        setContent {
            FaceBiometricFixTheme {
                PolicyManagerScreen()
            }
        }

        app.addServiceListener(serviceListener)
        startScan()
    }

    override fun onDestroy() {
        app.removeServiceListener(serviceListener)
        super.onDestroy()
    }

    private fun startScan() {
        if (scanning) return
        scanning = true

        Thread({
            val result = runCatching {
                BiometricAppScanner.scan(this)
            }.getOrDefault(emptyList())

            store.updateManagedPackages(
                result.asSequence()
                    .filterNot { it.isSystemApp }
                    .map { it.packageName }
                    .toSet()
            )

            runOnUiThread {
                candidates = result
                scanning = false
                defaultMode = store.defaultMode()
                policyRevision++
            }
        }, "FaceBiometricFix-app-scan").start()
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun PolicyManagerScreen() {
        var query by rememberSaveable { mutableStateOf("") }
        var showSystemApps by rememberSaveable { mutableStateOf(false) }
        var configuredOnly by rememberSaveable { mutableStateOf(false) }
        var selectedPackages by remember { mutableStateOf<Set<String>>(emptySet()) }
        var editTarget by remember { mutableStateOf<BiometricCandidateApp?>(null) }
        var batchMode by remember { mutableStateOf<BiometricPolicyMode?>(null) }

        policyRevision

        val density = LocalDensity.current
        val imeVisible = WindowInsets.ime.getBottom(density) > 0

        val visibleApps = remember(
            candidates,
            query,
            showSystemApps,
            configuredOnly,
            policyRevision,
        ) {
            val needle = query.trim().lowercase(Locale.getDefault())
            candidates.filter { candidate ->
                val systemMatch = showSystemApps || !candidate.isSystemApp
                val queryMatch =
                    needle.isBlank() ||
                        candidate.label.lowercase(Locale.getDefault()).contains(needle) ||
                        candidate.packageName.lowercase(Locale.ROOT).contains(needle)
                val configuredMatch =
                    !configuredOnly || store.explicitModeFor(candidate.packageName) != null

                systemMatch && queryMatch && configuredMatch
            }
        }

        selectedPackages = selectedPackages.intersect(
            candidates.mapTo(HashSet()) { it.packageName }
        )

        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Column {
                            Text(
                                text = "生物认证策略",
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.SemiBold,
                            )
                            if (!imeVisible) {
                                Text(
                                    text = "候选 ${candidates.size} · 当前 ${visibleApps.size} · 已单独设置 ${store.configuredCount()}",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface,
                    ),
                )
            },
            bottomBar = {
                if (selectedPackages.isNotEmpty() && !imeVisible) {
                    BatchActionBar(
                        selectedCount = selectedPackages.size,
                        selectedMode = batchMode,
                        onModeSelected = { batchMode = it },
                        onApply = {
                            store.setModes(selectedPackages, batchMode)
                            selectedPackages = emptySet()
                            policyRevision++
                        },
                        onClearSelection = {
                            selectedPackages = emptySet()
                        },
                    )
                }
            },
            containerColor = MaterialTheme.colorScheme.surface,
        ) { innerPadding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
            ) {
                SearchAndFilters(
                    query = query,
                    onQueryChange = { query = it },
                    showSystemApps = showSystemApps,
                    onShowSystemAppsChange = { showSystemApps = it },
                    configuredOnly = configuredOnly,
                    onConfiguredOnlyChange = { configuredOnly = it },
                    scanning = scanning,
                    onRescan = ::startScan,
                    visibleCount = visibleApps.size,
                    selectedCount = selectedPackages.size,
                    onSelectVisible = {
                        selectedPackages = selectedPackages + visibleApps.map { it.packageName }
                    },
                    onClearSelection = {
                        selectedPackages = emptySet()
                    },
                )

                if (!imeVisible) {
                    Spacer(Modifier.height(8.dp))
                    ConnectionBanner(frameworkConnected)

                    Spacer(Modifier.height(10.dp))
                    DefaultPolicyCard(
                        current = defaultMode,
                        onSelect = { mode ->
                            store.setDefaultMode(mode)
                            defaultMode = mode
                            policyRevision++
                        },
                    )

                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "扫描依据为应用声明的 USE_BIOMETRIC / USE_FINGERPRINT 权限；这表示应用具备调用生物识别的能力，不代表其内部认证功能一定已开启。",
                        modifier = Modifier.padding(horizontal = 16.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                }

                if (visibleApps.isEmpty()) {
                    EmptyState(
                        scanning = scanning,
                        modifier = Modifier.weight(1f),
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                        contentPadding = PaddingValues(
                            start = 12.dp,
                            end = 12.dp,
                            top = 4.dp,
                            bottom = if (selectedPackages.isNotEmpty()) 96.dp else 20.dp,
                        ),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(
                            items = visibleApps,
                            key = { it.packageName },
                        ) { candidate ->
                            AppPolicyCard(
                                candidate = candidate,
                                selected = candidate.packageName in selectedPackages,
                                explicitMode = store.explicitModeFor(candidate.packageName),
                                effectiveMode = store.effectiveModeFor(candidate.packageName),
                                onSelectionChange = { checked ->
                                    selectedPackages =
                                        if (checked) {
                                            selectedPackages + candidate.packageName
                                        } else {
                                            selectedPackages - candidate.packageName
                                        }
                                },
                                onOpen = { editTarget = candidate },
                            )
                        }
                    }
                }
            }
        }

        editTarget?.let { target ->
            AppPolicySheet(
                app = target,
                explicitMode = store.explicitModeFor(target.packageName),
                defaultMode = defaultMode,
                onDismiss = { editTarget = null },
                onSelect = { mode ->
                    store.setMode(target.packageName, mode)
                    policyRevision++
                    editTarget = null
                },
            )
        }
    }

    @Composable
    private fun SearchAndFilters(
        query: String,
        onQueryChange: (String) -> Unit,
        showSystemApps: Boolean,
        onShowSystemAppsChange: (Boolean) -> Unit,
        configuredOnly: Boolean,
        onConfiguredOnlyChange: (Boolean) -> Unit,
        scanning: Boolean,
        onRescan: () -> Unit,
        visibleCount: Int,
        selectedCount: Int,
        onSelectVisible: () -> Unit,
        onClearSelection: () -> Unit,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedTextField(
                value = query,
                onValueChange = onQueryChange,
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text("搜索应用或包名") },
                shape = RoundedCornerShape(28.dp),
                keyboardOptions = KeyboardOptions(
                    imeAction = ImeAction.Search,
                    autoCorrectEnabled = false,
                ),
                trailingIcon = {
                    if (query.isNotEmpty()) {
                        TextButton(onClick = { onQueryChange("") }) {
                            Text("清除")
                        }
                    }
                },
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FilterChip(
                    selected = showSystemApps,
                    onClick = { onShowSystemAppsChange(!showSystemApps) },
                    label = { Text(if (showSystemApps) "含系统应用" else "普通应用") },
                )
                FilterChip(
                    selected = configuredOnly,
                    onClick = { onConfiguredOnlyChange(!configuredOnly) },
                    label = { Text("仅已单独设置") },
                )
                FilterChip(
                    selected = false,
                    onClick = onSelectVisible,
                    label = { Text("全选当前 $visibleCount") },
                    enabled = visibleCount > 0,
                )
                if (selectedCount > 0) {
                    FilterChip(
                        selected = true,
                        onClick = onClearSelection,
                        label = { Text("已选 $selectedCount · 清空") },
                    )
                }
                FilterChip(
                    selected = scanning,
                    onClick = onRescan,
                    enabled = !scanning,
                    label = { Text(if (scanning) "扫描中…" else "重新扫描") },
                )
            }
        }
    }

    @Composable
    private fun ConnectionBanner(connected: Boolean) {
        Surface(
            modifier = Modifier.padding(horizontal = 12.dp),
            shape = RoundedCornerShape(20.dp),
            color =
                if (connected) {
                    MaterialTheme.colorScheme.secondaryContainer
                } else {
                    MaterialTheme.colorScheme.surfaceVariant
                },
        ) {
            Text(
                text =
                    if (connected) {
                        "LSPosed 配置通道已连接 · 新认证会话实时生效"
                    } else {
                        "正在等待 LSPosed 配置通道 · 修改会先保存在本机"
                    },
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 11.dp),
                style = MaterialTheme.typography.bodyMedium,
                color =
                    if (connected) {
                        MaterialTheme.colorScheme.onSecondaryContainer
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
            )
        }
    }

    @Composable
    private fun DefaultPolicyCard(
        current: BiometricPolicyMode,
        onSelect: (BiometricPolicyMode) -> Unit,
    ) {
        Card(
            modifier = Modifier.padding(horizontal = 12.dp),
            shape = RoundedCornerShape(28.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            ),
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    text = "默认认证方式",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = "应用于扫描到且未单独设置的普通应用。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    defaultChoices().forEach { choice ->
                        FilterChip(
                            selected = current == choice.mode,
                            onClick = { onSelect(choice.mode) },
                            label = { Text(choice.label) },
                        )
                    }
                }
            }
        }
    }

    @Composable
    private fun AppPolicyCard(
        candidate: BiometricCandidateApp,
        selected: Boolean,
        explicitMode: BiometricPolicyMode?,
        effectiveMode: BiometricPolicyMode,
        onSelectionChange: (Boolean) -> Unit,
        onOpen: () -> Unit,
    ) {
        Card(
            onClick = onOpen,
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(
                containerColor =
                    if (selected) {
                        MaterialTheme.colorScheme.primaryContainer
                    } else {
                        MaterialTheme.colorScheme.surfaceContainer
                    },
            ),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(
                    checked = selected,
                    onCheckedChange = onSelectionChange,
                )

                Spacer(Modifier.width(6.dp))

                AndroidView(
                    factory = { context ->
                        ImageView(context).apply {
                            scaleType = ImageView.ScaleType.CENTER_CROP
                        }
                    },
                    update = { view ->
                        view.setImageDrawable(candidate.icon)
                    },
                    modifier = Modifier
                        .size(50.dp)
                        .clip(RoundedCornerShape(16.dp)),
                )

                Spacer(Modifier.width(12.dp))

                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text(
                        text = candidate.label,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = candidate.packageName,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = BiometricAppScanner.evidenceLabel(candidate),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text =
                            if (explicitMode == null) {
                                "跟随默认 · ${modeLabel(effectiveMode)}"
                            } else {
                                "单独设置 · ${modeLabel(explicitMode)}"
                            },
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                Spacer(Modifier.width(8.dp))

                Text(
                    text = "设置",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun AppPolicySheet(
        app: BiometricCandidateApp,
        explicitMode: BiometricPolicyMode?,
        defaultMode: BiometricPolicyMode,
        onDismiss: () -> Unit,
        onSelect: (BiometricPolicyMode?) -> Unit,
    ) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

        ModalBottomSheet(
            onDismissRequest = onDismiss,
            sheetState = sheetState,
            shape = RoundedCornerShape(topStart = 32.dp, topEnd = 32.dp),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 24.dp),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    AndroidView(
                        factory = { context -> ImageView(context) },
                        update = { it.setImageDrawable(app.icon) },
                        modifier = Modifier
                            .size(52.dp)
                            .clip(RoundedCornerShape(16.dp)),
                    )
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = app.label,
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = app.packageName,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }

                HorizontalDivider()

                PolicyOptionRow(
                    title = "跟随默认策略",
                    subtitle = "当前默认：${modeLabel(defaultMode)}",
                    selected = explicitMode == null,
                    onClick = { onSelect(null) },
                )

                allExplicitChoices().forEach { choice ->
                    PolicyOptionRow(
                        title = choice.label,
                        subtitle = choice.description,
                        selected = explicitMode == choice.mode,
                        onClick = { onSelect(choice.mode) },
                    )
                }
            }
        }
    }

    @Composable
    private fun PolicyOptionRow(
        title: String,
        subtitle: String,
        selected: Boolean,
        onClick: () -> Unit,
    ) {
        ListItem(
            headlineContent = { Text(title) },
            supportingContent = { Text(subtitle) },
            leadingContent = {
                RadioButton(
                    selected = selected,
                    onClick = onClick,
                )
            },
            modifier = Modifier.fillMaxWidth(),
        )
    }

    @Composable
    private fun BatchActionBar(
        selectedCount: Int,
        selectedMode: BiometricPolicyMode?,
        onModeSelected: (BiometricPolicyMode?) -> Unit,
        onApply: () -> Unit,
        onClearSelection: () -> Unit,
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .imePadding(),
            tonalElevation = 6.dp,
            shadowElevation = 8.dp,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "已选择 $selectedCount 个应用",
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    TextButton(onClick = onClearSelection) {
                        Text("取消选择")
                    }
                    Button(onClick = onApply) {
                        Text("应用")
                    }
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    FilterChip(
                        selected = selectedMode == null,
                        onClick = { onModeSelected(null) },
                        label = { Text("跟随默认") },
                    )
                    allExplicitChoices().forEach { choice ->
                        FilterChip(
                            selected = selectedMode == choice.mode,
                            onClick = { onModeSelected(choice.mode) },
                            label = { Text(choice.label) },
                        )
                    }
                }
            }
        }
    }

    @Composable
    private fun EmptyState(
        scanning: Boolean,
        modifier: Modifier = Modifier,
    ) {
        Box(
            modifier = modifier.fillMaxWidth(),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = if (scanning) "正在扫描应用…" else "没有符合当前筛选条件的应用",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    private data class PolicyChoice(
        val mode: BiometricPolicyMode,
        val label: String,
        val description: String,
    )

    private fun defaultChoices(): List<PolicyChoice> = listOf(
        PolicyChoice(
            BiometricPolicyMode.ANY,
            "系统默认（人脸或指纹）",
            "保持 Android 原生的单因素认证行为。",
        ),
        PolicyChoice(
            BiometricPolicyMode.FACE_ONLY,
            "人脸认证",
            "仅接受人脸识别。",
        ),
        PolicyChoice(
            BiometricPolicyMode.FINGERPRINT_ONLY,
            "指纹认证",
            "仅接受指纹识别。",
        ),
        PolicyChoice(
            BiometricPolicyMode.FACE_AND_FINGERPRINT,
            "双重认证（人脸 + 指纹）",
            "必须同时完成人脸和指纹认证。",
        ),
    )

    private fun allExplicitChoices(): List<PolicyChoice> = defaultChoices()

    private fun modeLabel(mode: BiometricPolicyMode): String =
        when (mode) {
            BiometricPolicyMode.ANY -> "系统默认（人脸或指纹）"
            BiometricPolicyMode.FACE_ONLY -> "人脸认证"
            BiometricPolicyMode.FINGERPRINT_ONLY -> "指纹认证"
            BiometricPolicyMode.FACE_AND_FINGERPRINT -> "双重认证（人脸 + 指纹）"
        }
}

@Composable
private fun FaceBiometricFixTheme(
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val dark = androidx.compose.foundation.isSystemInDarkTheme()

    val colors =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (dark) {
                dynamicDarkColorScheme(context)
            } else {
                dynamicLightColorScheme(context)
            }
        } else {
            if (dark) darkColorScheme() else lightColorScheme()
        }

    MaterialTheme(
        colorScheme = colors,
        shapes = androidx.compose.material3.Shapes(
            extraSmall = RoundedCornerShape(8.dp),
            small = RoundedCornerShape(12.dp),
            medium = RoundedCornerShape(20.dp),
            large = RoundedCornerShape(28.dp),
            extraLarge = RoundedCornerShape(36.dp),
        ),
        content = content,
    )
}
