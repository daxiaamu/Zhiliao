package com.shatyuka.zhiliao

import android.content.ComponentName
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.shatyuka.zhiliao.update.UpdateInfo
import com.shatyuka.zhiliao.update.UpdateManager
import com.shatyuka.zhiliao.ui.SimpleMarkdownText
import java.io.File
import java.util.regex.Pattern
import java.util.regex.PatternSyntaxException
import kotlin.math.roundToInt

class MainActivity : ComponentActivity() {
    private lateinit var preferences: SharedPreferences
    private val updateManager by lazy { UpdateManager.get(this) }

    private var launcherEnabled by mutableStateOf(true)
    private var hookValues by mutableStateOf<Map<String, Boolean>>(emptyMap())
    private var textValues by mutableStateOf<Map<String, String>>(emptyMap())
    private var horizontalSensitivity by mutableIntStateOf(DEFAULT_SENSITIVITY)
    private var checking by mutableStateOf(false)
    private var availableUpdate by mutableStateOf<UpdateInfo?>(null)
    private var updateSkipped by mutableStateOf(false)
    private var showUpdateDialog by mutableStateOf(false)
    private var showCompatibilityDialog by mutableStateOf(false)
    private var showCompatibilityReloadDialog by mutableStateOf(false)
    private var compatibilityReloadNeeded by mutableStateOf(false)
    private var compatibilityReloading by mutableStateOf(false)
    private var compatibilityEntries by mutableStateOf<List<CompatibilityRegistry.CatalogEntry>>(emptyList())
    private var compatibilityUrl by mutableStateOf("")
    private var remoteCompatibilityRevision by mutableStateOf<Long?>(null)
    private var errorMessage by mutableStateOf<String?>(null)
    private var downloadProgress by mutableIntStateOf(NOT_DOWNLOADING)
    private var pendingInstallApk: File? = null
    private var waitingForInstallPermission = false
    private val scopeRefresh = Runnable { ModulePreferences.refreshScope() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        preferences = getSharedPreferences(PREFS, MODE_PRIVATE)
        loadCompatibilityConfig(preferences, notifyRemoteUpdate = false)
        enableEdgeToEdge()
        launcherEnabled = isLauncherEnabled()
        reloadHookValues()
        ModulePreferences.connect(this) { remote ->
            runOnUiThread {
                preferences = remote
                loadCompatibilityConfig(remote, notifyRemoteUpdate = true)
                reloadHookValues()
            }
        }
        savedInstanceState?.getString(STATE_PENDING_APK)?.let { pendingInstallApk = File(it) }
        setContent { ZhiliaoApp() }
        checkCompatibilityConfig()
        checkForUpdates(manual = false)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        pendingInstallApk?.let { outState.putString(STATE_PENDING_APK, it.absolutePath) }
    }

    override fun onResume() {
        super.onResume()
        ModulePreferences.refreshScope()
        window.decorView.removeCallbacks(scopeRefresh)
        window.decorView.postDelayed(scopeRefresh, SCOPE_RECHECK_DELAY_MS)
        if (waitingForInstallPermission && canInstallPackages()) {
            waitingForInstallPermission = false
            pendingInstallApk?.let(::launchInstaller)
            pendingInstallApk = null
        }
    }

    override fun onPause() {
        window.decorView.removeCallbacks(scopeRefresh)
        super.onPause()
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun ZhiliaoApp() {
        val background = colorResource(R.color.module_background)
        val surface = colorResource(R.color.module_surface)
        val primary = colorResource(R.color.module_primary)
        val onSurface = colorResource(R.color.module_on_surface)
        val onSurfaceVariant = colorResource(R.color.module_on_surface_variant)
        val scheme = if (isSystemInDarkTheme()) {
            darkColorScheme(
                primary = primary,
                background = background,
                surface = surface,
                onSurface = onSurface,
                onSurfaceVariant = onSurfaceVariant,
            )
        } else {
            lightColorScheme(
                primary = primary,
                background = background,
                surface = surface,
                onSurface = onSurface,
                onSurfaceVariant = onSurfaceVariant,
            )
        }
        val listState = rememberLazyListState()
        val updateCardIndex = 15 + HOOK_GROUPS.size * 2
        LaunchedEffect(downloadProgress != NOT_DOWNLOADING) {
            if (downloadProgress != NOT_DOWNLOADING) {
                listState.animateScrollToItem(updateCardIndex)
            }
        }
        MaterialTheme(colorScheme = scheme) {
            Scaffold(
                containerColor = background,
                topBar = {
                    TopAppBar(
                        title = { Text(stringResource(R.string.app_name), fontWeight = FontWeight.SemiBold) },
                        actions = {
                            IconButton(onClick = { openUrl(GITHUB_URL) }) {
                                Icon(
                                    painterResource(R.drawable.ic_github),
                                    contentDescription = stringResource(R.string.github_homepage),
                                )
                            }
                        },
                        colors = TopAppBarDefaults.topAppBarColors(containerColor = background),
                    )
                },
            ) { insets ->
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize().padding(insets),
                    contentPadding = PaddingValues(horizontal = 18.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    item { HeroCard() }
                    item { SectionTitle(stringResource(R.string.module_settings)) }
                    item { MasterSettingsCard() }
                    HOOK_GROUPS.forEach { group ->
                        item(group.title) { SectionTitle(stringResource(group.title)) }
                        item("hooks-${group.title}") { HookSettingsCard(group.options) }
                    }
                    item { SectionTitle(stringResource(R.string.custom_filter)) }
                    item { CustomFilterCard() }
                    item { SectionTitle(stringResource(R.string.gesture_settings)) }
                    item { GestureSettingsCard() }
                    item { SectionTitle(stringResource(R.string.webview_settings)) }
                    item { WebViewSettingsCard() }
                    item { SectionTitle(stringResource(R.string.cleanup_settings)) }
                    item { CleanupSettingsCard() }
                    item { SectionTitle(stringResource(R.string.debug_settings)) }
                    item { HookSettingsCard(DEBUG_OPTIONS) }
                    item {
                        SettingsCard {
                            SettingSwitch(
                                title = stringResource(R.string.show_launcher_icon),
                                summary = stringResource(R.string.show_launcher_icon_summary),
                                checked = launcherEnabled,
                            ) {
                                launcherEnabled = it
                                applyLauncherVisibility(it)
                            }
                        }
                    }
                    item { SectionTitle(stringResource(R.string.update_and_about)) }
                    item { UpdateCard() }
                    item { AboutCard() }
                    item { Spacer(Modifier.height(12.dp)) }
                }
            }

            availableUpdate?.takeIf { showUpdateDialog }?.let { UpdateDialog(it) }
            if (showCompatibilityDialog) CompatibilityDialog()
            if (showCompatibilityReloadDialog) CompatibilityReloadDialog()
            errorMessage?.let { message ->
                AlertDialog(
                    onDismissRequest = { errorMessage = null },
                    title = { Text(stringResource(R.string.operation_failed)) },
                    text = { Text(message) },
                    confirmButton = {
                        TextButton(onClick = { errorMessage = null }) {
                            Text(stringResource(android.R.string.ok))
                        }
                    },
                )
            }
        }
    }

    @Composable
    private fun MasterSettingsCard() {
        val master = hookValues[KEY_MASTER] ?: false
        SettingsCard {
            SettingSwitch(
                title = stringResource(R.string.enable_module),
                summary = stringResource(R.string.enable_module_summary),
                checked = master,
            ) { setHookValue(KEY_MASTER, it) }
        }
    }

    @Composable
    private fun HookSettingsCard(options: List<HookOption>) {
        val master = hookValues[KEY_MASTER] ?: false
        SettingsCard {
            options.forEachIndexed { index, option ->
                if (index > 0) HorizontalDivider()
                SettingSwitch(
                    title = stringResource(option.title),
                    summary = stringResource(option.summary),
                    checked = hookValues[option.key] ?: option.default,
                    enabled = master,
                ) { setHookValue(option.key, it) }
            }
        }
    }

    @Composable
    private fun CustomFilterCard() {
        val master = hookValues[KEY_MASTER] ?: false
        SettingsCard {
            REGEX_OPTIONS.forEachIndexed { index, option ->
                if (index > 0) HorizontalDivider()
                SettingTextField(option, master, validateRegex = true)
            }
        }
    }

    @Composable
    private fun GestureSettingsCard() {
        val master = hookValues[KEY_MASTER] ?: false
        val horizontalEnabled = hookValues[KEY_HORIZONTAL] ?: false
        SettingsCard {
            HookSetting(GESTURE_OPTIONS.single(), master)
            HorizontalDivider()
            Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 14.dp)) {
                Text(
                    stringResource(R.string.horizontal_sensitivity_value, horizontalSensitivity),
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                )
                Text(
                    stringResource(R.string.horizontal_sensitivity_summary),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Slider(
                    value = horizontalSensitivity.toFloat(),
                    onValueChange = { updateHorizontalSensitivity(it.roundToInt()) },
                    enabled = master && horizontalEnabled,
                    valueRange = 1f..9f,
                    steps = 7,
                )
            }
        }
    }

    @Composable
    private fun WebViewSettingsCard() {
        val master = hookValues[KEY_MASTER] ?: false
        SettingsCard {
            WEBVIEW_OPTIONS.forEachIndexed { index, option ->
                if (index > 0) HorizontalDivider()
                HookSetting(option, master)
            }
            HorizontalDivider()
            SettingTextField(JS_OPTION, master, multiline = true)
        }
    }

    @Composable
    private fun CleanupSettingsCard() {
        val master = hookValues[KEY_MASTER] ?: false
        SettingsCard {
            CLEANUP_OPTIONS.forEachIndexed { index, option ->
                if (index > 0) HorizontalDivider()
                HookSetting(option, master)
            }
            HorizontalDivider()
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f).padding(end = 16.dp)) {
                    Text(stringResource(R.string.clean_once), fontWeight = FontWeight.Medium)
                    Text(
                        stringResource(R.string.clean_once_summary),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                TextButton(onClick = ::requestOneTimeClean, enabled = master) {
                    Text(stringResource(R.string.request_clean))
                }
            }
        }
    }

    @Composable
    private fun HeroCard() {
        Card(
            colors = CardDefaults.cardColors(containerColor = colorResource(R.color.module_hero)),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(20.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Icon(
                    painterResource(R.drawable.ic_cicada_outline),
                    contentDescription = null,
                    tint = colorResource(R.color.module_primary),
                    modifier = Modifier.size(48.dp),
                )
                Column(Modifier.weight(1f)) {
                    Text(
                        stringResource(R.string.app_tagline),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        stringResource(R.string.version_format, currentVersionName()),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    remoteCompatibilityRevision?.let { revision ->
                        Text(
                            stringResource(R.string.cloud_config_version_format, revision),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                if (compatibilityReloadNeeded) {
                    TextButton(
                        onClick = { showCompatibilityReloadDialog = true },
                        enabled = !compatibilityReloading,
                    ) {
                        if (compatibilityReloading) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp,
                            )
                            Spacer(Modifier.size(6.dp))
                        }
                        Text(
                            stringResource(
                                if (compatibilityReloading) {
                                    R.string.cloud_config_reloading
                                } else {
                                    R.string.cloud_config_reload
                                },
                            ),
                        )
                    }
                }
            }
        }
    }

    @Composable
    private fun CompatibilityReloadDialog() {
        AlertDialog(
            onDismissRequest = { showCompatibilityReloadDialog = false },
            icon = { Icon(Icons.Outlined.Refresh, contentDescription = null) },
            title = { Text(stringResource(R.string.cloud_config_reload_title)) },
            text = { Text(stringResource(R.string.cloud_config_reload_message)) },
            confirmButton = {
                Button(onClick = {
                    showCompatibilityReloadDialog = false
                    checkCompatibilityConfig(restartZhihuOnSuccess = true)
                }) {
                    Text(stringResource(R.string.cloud_config_reload_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { showCompatibilityReloadDialog = false }) {
                    Text(stringResource(android.R.string.cancel))
                }
            },
        )
    }

    @Composable
    private fun SectionTitle(title: String) {
        Text(
            title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(start = 4.dp, top = 4.dp),
        )
    }

    @Composable
    private fun SettingsCard(content: @Composable ColumnScope.() -> Unit) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        ) { Column(content = content) }
    }

    @Composable
    private fun SettingSwitch(
        title: String,
        summary: String,
        checked: Boolean,
        enabled: Boolean = true,
        onChecked: (Boolean) -> Unit,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f).padding(end = 16.dp)) {
                Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                Text(summary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(checked = checked, enabled = enabled, onCheckedChange = onChecked)
        }
    }

    @Composable
    private fun HookSetting(option: HookOption, master: Boolean) {
        SettingSwitch(
            title = stringResource(option.title),
            summary = stringResource(option.summary),
            checked = hookValues[option.key] ?: option.default,
            enabled = master,
        ) { setHookValue(option.key, it) }
    }

    @Composable
    private fun SettingTextField(
        option: TextOption,
        enabled: Boolean,
        validateRegex: Boolean = false,
        multiline: Boolean = false,
    ) {
        val value = textValues[option.key].orEmpty()
        val invalidRegex = validateRegex && !isValidRegex(value)
        OutlinedTextField(
            value = value,
            onValueChange = { setTextValue(option.key, it) },
            enabled = enabled,
            label = { Text(stringResource(option.title)) },
            supportingText = {
                Text(
                    if (invalidRegex) stringResource(R.string.invalid_regex)
                    else stringResource(option.summary),
                )
            },
            isError = invalidRegex,
            singleLine = !multiline,
            minLines = if (multiline) 3 else 1,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 10.dp),
        )
    }

    private fun reloadHookValues() {
        hookValues = buildMap {
            put(KEY_MASTER, preferences.getBoolean(KEY_MASTER, false))
            HOOK_OPTIONS.forEach { put(it.key, preferences.getBoolean(it.key, it.default)) }
        }
        textValues = TEXT_OPTIONS.associate { option ->
            option.key to preferences.getString(option.key, "").orEmpty()
        }
        horizontalSensitivity = preferences.getInt(KEY_SENSITIVITY, DEFAULT_SENSITIVITY)
            .coerceIn(1, 9)
    }

    private fun setHookValue(key: String, value: Boolean) {
        preferences.edit().putBoolean(key, value).apply()
        hookValues = hookValues + (key to value)
    }

    private fun setTextValue(key: String, value: String) {
        preferences.edit().putString(key, value).apply()
        textValues = textValues + (key to value)
    }

    private fun updateHorizontalSensitivity(value: Int) {
        horizontalSensitivity = value.coerceIn(1, 9)
        preferences.edit().putInt(KEY_SENSITIVITY, horizontalSensitivity).apply()
    }

    private fun requestOneTimeClean() {
        preferences.edit().putBoolean(KEY_CLEAN_ONCE, true).apply()
        Toast.makeText(this, R.string.clean_requested, Toast.LENGTH_LONG).show()
    }

    private fun isValidRegex(value: String): Boolean {
        if (value.isEmpty()) return true
        return try {
            Pattern.compile(value)
            true
        } catch (_: PatternSyntaxException) {
            false
        }
    }

    @Composable
    private fun UpdateCard() {
        val info = availableUpdate
        SettingsCard {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(18.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Icon(
                    imageVector = if (info == null) Icons.Outlined.Refresh else Icons.Outlined.Download,
                    contentDescription = null,
                    tint = if (info != null && !updateSkipped) colorResource(R.color.module_update) else MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(26.dp),
                )
                Column(Modifier.weight(1f)) {
                    Text(
                        when {
                            info == null -> stringResource(R.string.check_for_updates)
                            updateSkipped -> stringResource(R.string.update_skipped, info.versionName)
                            else -> stringResource(R.string.update_found, info.versionName)
                        },
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium,
                        color = if (info != null && !updateSkipped) colorResource(R.color.module_update) else MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        stringResource(R.string.update_security_summary),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (checking) {
                    CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                } else {
                    TextButton(onClick = { checkForUpdates(manual = true) }) {
                        Text(stringResource(R.string.check_now))
                    }
                }
            }
            if (downloadProgress != NOT_DOWNLOADING) {
                if (downloadProgress < 0) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                } else {
                    LinearProgressIndicator(
                        progress = { downloadProgress / 100f },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }

    @Composable
    private fun AboutCard() {
        SettingsCard {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { openUrl(GITHUB_URL) }
                    .padding(18.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(painterResource(R.drawable.ic_github), contentDescription = null, modifier = Modifier.size(24.dp))
                Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
                    Text(stringResource(R.string.github_homepage), fontWeight = FontWeight.Medium)
                    Text(stringResource(R.string.open_source_summary), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Icon(
                    Icons.AutoMirrored.Outlined.OpenInNew,
                    contentDescription = stringResource(R.string.open_link),
                    modifier = Modifier.size(24.dp),
                )
            }
            HorizontalDivider()
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { openUrl(ORIGINAL_AUTHOR_DONATE_URL) }
                    .padding(18.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(painterResource(R.drawable.ic_monetization), contentDescription = null, modifier = Modifier.size(24.dp))
                Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
                    Text(stringResource(R.string.original_author), fontWeight = FontWeight.Medium)
                    Text(stringResource(R.string.donate_original_author_summary), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Icon(
                    Icons.AutoMirrored.Outlined.OpenInNew,
                    contentDescription = stringResource(R.string.open_link),
                    modifier = Modifier.size(24.dp),
                )
            }
            HorizontalDivider()
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { showCompatibilityDialog = true }
                    .padding(18.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    painterResource(R.drawable.ic_cicada_outline),
                    contentDescription = null,
                    modifier = Modifier.size(24.dp),
                )
                Text(
                    stringResource(R.string.view_compatible_versions),
                    modifier = Modifier.weight(1f).padding(horizontal = 14.dp),
                    fontWeight = FontWeight.Medium,
                )
                Icon(
                    painterResource(R.drawable.ic_cicada_outline),
                    contentDescription = null,
                    modifier = Modifier.size(24.dp),
                )
            }
        }
    }

    @Composable
    private fun CompatibilityDialog() {
        AlertDialog(
            onDismissRequest = { showCompatibilityDialog = false },
            icon = { Icon(painterResource(R.drawable.ic_cicada_outline), contentDescription = null) },
            title = { Text(stringResource(R.string.compatibility_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    compatibilityEntries.forEach { entry ->
                        val versions = if (entry.minVersionCode == entry.maxVersionCode) {
                            entry.minVersionCode.toString()
                        } else {
                            "${entry.minVersionCode}–${entry.maxVersionCode}"
                        }
                        Text("${entry.displayName}：${entry.versionName}（$versions）")
                    }
                }
            },
            confirmButton = {
                Button(onClick = {
                    showCompatibilityDialog = false
                    openUrl(compatibilityUrl)
                }, enabled = compatibilityUrl.isNotEmpty()) {
                    Text(stringResource(R.string.open_compatible_versions))
                }
            },
            dismissButton = {
                TextButton(onClick = { showCompatibilityDialog = false }) {
                    Text(stringResource(android.R.string.cancel))
                }
            },
        )
    }

    @Composable
    private fun UpdateDialog(info: UpdateInfo) {
        AlertDialog(
            onDismissRequest = { showUpdateDialog = false },
            icon = { Icon(painterResource(R.drawable.ic_cicada_outline), contentDescription = null) },
            title = { Text(stringResource(R.string.update_dialog_title)) },
            text = {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 420.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    item {
                        Text(
                            buildString {
                                append(getString(R.string.new_version_format, info.versionName))
                                if (info.publishedAt.isNotEmpty()) {
                                    append('\n').append(
                                        getString(R.string.published_at_format, info.publishedAt),
                                    )
                                }
                            },
                        )
                    }
                    item {
                        SimpleMarkdownText(markdown = info.changelog)
                    }
                }
            },
            confirmButton = {
                Button(onClick = {
                    showUpdateDialog = false
                    downloadUpdate(info)
                }) { Text(stringResource(R.string.download_and_install)) }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = {
                        preferences.edit().putLong(KEY_SKIPPED_VERSION, info.versionCode).apply()
                        updateSkipped = true
                        showUpdateDialog = false
                    }) { Text(stringResource(R.string.skip_this_version)) }
                    TextButton(onClick = { showUpdateDialog = false }) {
                        Text(stringResource(R.string.ignore_update))
                    }
                }
            },
        )
    }

    private fun checkForUpdates(manual: Boolean) {
        if (checking) return
        checking = true
        updateManager.check(manual) { info, error ->
            checking = false
            if (error != null) {
                if (manual) errorMessage = getString(R.string.check_update_failed, error.message ?: error.toString())
                return@check
            }
            if (info == null || info.versionCode <= currentVersionCode()) {
                availableUpdate = null
                if (manual) Toast.makeText(this, R.string.already_latest, Toast.LENGTH_SHORT).show()
                return@check
            }
            availableUpdate = info
            updateSkipped = preferences.getLong(KEY_SKIPPED_VERSION, -1) == info.versionCode
            if (manual || (!updateSkipped && updateManager.claimAutomaticPrompt(info.versionCode))) {
                showUpdateDialog = true
            }
        }
    }

    /** Also call this after a future compatibility-config download is installed. */
    private fun loadCompatibilityConfig(source: SharedPreferences, notifyRemoteUpdate: Boolean) {
        CompatibilityRegistry.initialize(resources, source, -1)
        compatibilityEntries = CompatibilityRegistry.getAdaptedVersions()
        compatibilityUrl = CompatibilityRegistry.getCompatibilityUrl()
        val revision = CompatibilityRegistry.getRevision()
        remoteCompatibilityRevision = revision.takeIf {
            CompatibilityRegistry.isRemoteConfigActive()
        }
        compatibilityReloadNeeded =
            source.getLong(KEY_PENDING_COMPATIBILITY_RESTART_REVISION, 0) > 0
        val lastNotified = source.getLong(KEY_NOTIFIED_COMPATIBILITY_REVISION, 0)
        if (notifyRemoteUpdate && CompatibilityRegistry.isRemoteConfigActive()
            && revision > lastNotified
        ) {
            source.edit().putLong(KEY_NOTIFIED_COMPATIBILITY_REVISION, revision).apply()
            Toast.makeText(this, R.string.compatibility_config_updated, Toast.LENGTH_LONG).show()
        }
    }

    /** Single completion path for the future cloud downloader: verify, persist, load, then notify. */
    private fun applyDownloadedCompatibilityConfig(
        json: String,
        expectedSha256: String,
        notifyRemoteUpdate: Boolean = true,
    ): Boolean {
        val previousRevision = CompatibilityRegistry.getRevision()
        if (!CompatibilityRegistry.installRemoteConfig(preferences, json, expectedSha256)) return false
        loadCompatibilityConfig(
            preferences,
            notifyRemoteUpdate = notifyRemoteUpdate &&
                CompatibilityRegistry.getRevision() > previousRevision,
        )
        return true
    }

    private fun checkCompatibilityConfig(restartZhihuOnSuccess: Boolean = false) {
        if (restartZhihuOnSuccess) compatibilityReloading = true
        val previousRevision = CompatibilityRegistry.getRevision()
        updateManager.checkCompatibilityConfig { json, sha256, error ->
            val loaded = error == null && json != null && sha256 != null &&
                applyDownloadedCompatibilityConfig(
                    json,
                    sha256,
                    notifyRemoteUpdate = !restartZhihuOnSuccess,
                )
            val revisionChanged = loaded &&
                CompatibilityRegistry.getRevision() > previousRevision
            compatibilityReloading = false
            if (loaded && restartZhihuOnSuccess) {
                preferences.edit()
                    .remove(KEY_PENDING_COMPATIBILITY_RESTART_REVISION)
                    .apply()
                compatibilityReloadNeeded = false
                restartZhihu()
            } else {
                if (revisionChanged) {
                    preferences.edit().putLong(
                        KEY_PENDING_COMPATIBILITY_RESTART_REVISION,
                        CompatibilityRegistry.getRevision(),
                    ).apply()
                }
                compatibilityReloadNeeded = !loaded ||
                    preferences.getLong(KEY_PENDING_COMPATIBILITY_RESTART_REVISION, 0) > 0
                if (!loaded && restartZhihuOnSuccess) {
                    val installError = CompatibilityRegistry.getLastInstallError()
                    val detail = error?.message ?: installError.takeIf { it.isNotBlank() }
                        ?: getString(R.string.cloud_config_invalid)
                    errorMessage = getString(R.string.cloud_config_reload_failed, detail)
                }
            }
        }
    }

    private fun restartZhihu() {
        sendBroadcast(Intent(Helper.ACTION_RESTART_ZHIHU).setPackage(Helper.hookPackage))
        window.decorView.postDelayed({
            packageManager.getLaunchIntentForPackage(Helper.hookPackage)?.let { launchIntent ->
                launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(launchIntent)
            }
        }, ZHIHU_RESTART_FALLBACK_DELAY_MS)
    }

    private fun downloadUpdate(info: UpdateInfo) {
        downloadProgress = -1
        updateManager.download(info, object : UpdateManager.DownloadCallback {
            override fun onProgress(percent: Int) { downloadProgress = percent }

            override fun onComplete(apk: File?, error: Throwable?) {
                downloadProgress = NOT_DOWNLOADING
                if (error != null) {
                    errorMessage = getString(R.string.download_failed, error.message ?: error.toString())
                } else if (apk != null) {
                    install(apk)
                }
            }
        })
    }

    private fun install(apk: File) {
        if (!apk.isFile) {
            errorMessage = getString(R.string.verified_apk_missing)
            return
        }
        if (!canInstallPackages()) {
            pendingInstallApk = apk
            waitingForInstallPermission = true
            startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")))
            Toast.makeText(this, R.string.allow_unknown_sources, Toast.LENGTH_LONG).show()
            return
        }
        launchInstaller(apk)
    }

    private fun launchInstaller(apk: File) {
        runCatching {
            val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", apk)
            startActivity(Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            })
        }.onFailure { errorMessage = getString(R.string.open_installer_failed, it.message ?: it.toString()) }
    }

    private fun canInstallPackages() = packageManager.canRequestPackageInstalls()

    private fun isLauncherEnabled(): Boolean =
        packageManager.getComponentEnabledSetting(launcherComponent()) != PackageManager.COMPONENT_ENABLED_STATE_DISABLED

    private fun applyLauncherVisibility(enabled: Boolean) {
        packageManager.setComponentEnabledSetting(
            launcherComponent(),
            if (enabled) PackageManager.COMPONENT_ENABLED_STATE_ENABLED else PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
            PackageManager.DONT_KILL_APP,
        )
        Toast.makeText(this, if (enabled) R.string.launcher_shown else R.string.launcher_hidden, Toast.LENGTH_SHORT).show()
    }

    private fun launcherComponent() = ComponentName(this, "$packageName.Launcher")

    private fun currentVersionCode(): Long = runCatching {
        val info = packageManager.getPackageInfo(packageName, 0)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.longVersionCode else @Suppress("DEPRECATION") info.versionCode.toLong()
    }.getOrDefault(0)

    private fun currentVersionName(): String = runCatching {
        packageManager.getPackageInfo(packageName, 0).versionName ?: getString(R.string.unknown)
    }.getOrDefault(getString(R.string.unknown))

    private fun openUrl(url: String) = startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))

    companion object {
        private const val SCOPE_RECHECK_DELAY_MS = 750L
        private const val ZHIHU_RESTART_FALLBACK_DELAY_MS = 1_000L
        private data class HookOption(
            val key: String,
            val title: Int,
            val summary: Int,
            val default: Boolean = false,
        )

        private data class HookGroup(
            val title: Int,
            val options: List<HookOption>,
        )

        private data class TextOption(
            val key: String,
            val title: Int,
            val summary: Int,
        )

        private const val PREFS = "module_settings"
        private const val KEY_MASTER = "switch_mainswitch"
        private const val KEY_HORIZONTAL = "switch_horizontal"
        private const val KEY_SENSITIVITY = "seekbar_sensitivity"
        private const val KEY_CLEAN_ONCE = "request_clean_once"
        private const val DEFAULT_SENSITIVITY = 5
        private const val KEY_SKIPPED_VERSION = "skipped_update_version"
        private const val KEY_NOTIFIED_COMPATIBILITY_REVISION = "notified_compatibility_revision_v1"
        private const val KEY_PENDING_COMPATIBILITY_RESTART_REVISION =
            "pending_compatibility_restart_revision_v1"
        private const val STATE_PENDING_APK = "pending_apk"
        private const val NOT_DOWNLOADING = -2
        private const val GITHUB_URL = "https://github.com/daxiaamu/Zhiliao"
        private const val ORIGINAL_AUTHOR_DONATE_URL = "https://github.com/shatyuka/Zhiliao/wiki/Donate"

        private val AD_OPTIONS = listOf(
            HookOption("switch_launchad", R.string.remove_launch_ads, R.string.remove_launch_ads_summary, true),
            HookOption("switch_feedad", R.string.remove_feed_ads, R.string.remove_feed_ads_summary, true),
            HookOption("switch_answerlistad", R.string.remove_answer_list_ads, R.string.remove_answer_list_ads_summary, true),
            HookOption("switch_commentad", R.string.remove_comment_ads, R.string.remove_comment_ads_summary, true),
            HookOption("switch_sharead", R.string.remove_share_ads, R.string.remove_share_ads_summary, true),
            HookOption("switch_answerad", R.string.remove_answer_ads, R.string.remove_answer_ads_summary, true),
            HookOption("switch_searchad", R.string.remove_search_ads, R.string.remove_search_ads_summary, true),
            HookOption("switch_cashentry", R.string.hide_cash_entry, R.string.hide_cash_entry_summary, true),
        )

        private val CONTENT_OPTIONS = listOf(
            HookOption("switch_video", R.string.filter_video, R.string.filter_video_summary),
            HookOption("switch_removearticle", R.string.filter_article, R.string.filter_article_summary),
            HookOption("switch_pin", R.string.filter_pin, R.string.filter_pin_summary),
            HookOption("switch_marketcard", R.string.remove_market_card, R.string.remove_market_card_summary),
            HookOption("switch_club", R.string.remove_answer_club, R.string.remove_answer_club_summary),
            HookOption("switch_goods", R.string.remove_goods, R.string.remove_goods_summary),
            HookOption("switch_related", R.string.remove_related_search, R.string.remove_related_search_summary),
            HookOption("switch_searchwords", R.string.remove_search_words, R.string.remove_search_words_summary),
            HookOption("switch_externlink", R.string.open_external_links_in_app, R.string.open_external_links_in_app_summary),
            HookOption("switch_externlinkex", R.string.open_external_links, R.string.open_external_links_summary),
            HookOption("switch_colormode", R.string.prevent_color_mode, R.string.prevent_color_mode_summary),
            HookOption("switch_tag", R.string.show_card_type, R.string.show_card_type_summary),
            HookOption("switch_statusbar", R.string.immersive_status_bar, R.string.immersive_status_bar_summary),
            HookOption("switch_fullscreen", R.string.prevent_fullscreen, R.string.prevent_fullscreen_summary),
            HookOption("switch_thirdpartylogin", R.string.unlock_third_party_login, R.string.unlock_third_party_login_summary),
            HookOption("switch_autorefresh", R.string.prevent_auto_refresh, R.string.prevent_auto_refresh_summary),
        )

        private val UI_OPTIONS = listOf(
            HookOption("switch_livebutton", R.string.hide_live_button, R.string.hide_live_button_summary),
            HookOption("switch_reddot", R.string.hide_red_dots, R.string.hide_red_dots_summary),
            HookOption("switch_vipbanner", R.string.hide_vip_banner, R.string.hide_vip_banner_summary),
            HookOption("switch_hotbanner", R.string.hide_hot_banner, R.string.hide_hot_banner_summary),
            HookOption("switch_article", R.string.simplify_article_page, R.string.simplify_article_page_summary),
            HookOption("switch_feedtophot", R.string.hide_feed_top_hot, R.string.hide_feed_top_hot_summary),
            HookOption("switch_minehybrid", R.string.hide_mine_cards, R.string.hide_mine_cards_summary),
            HookOption("switch_subscribe", R.string.hide_follow_button, R.string.hide_follow_button_summary),
        )

        private val NAVIGATION_OPTIONS = listOf(
            HookOption("switch_vipnav", R.string.hide_vip_nav, R.string.hide_nav_summary),
            HookOption("switch_videonav", R.string.hide_video_nav, R.string.hide_nav_summary),
            HookOption("switch_friendnav", R.string.hide_follow_nav, R.string.hide_nav_summary),
            HookOption("switch_panelnav", R.string.hide_publish_nav, R.string.hide_nav_summary),
            HookOption("switch_findnav", R.string.hide_discover_nav, R.string.hide_nav_summary),
            HookOption("switch_navres", R.string.disable_nav_theme, R.string.disable_nav_theme_summary),
            HookOption("switch_nipple", R.string.flatten_bottom_nav, R.string.flatten_bottom_nav_summary),
        )

        private val GESTURE_OPTIONS = listOf(
            HookOption(KEY_HORIZONTAL, R.string.horizontal_answers, R.string.horizontal_answers_summary),
        )

        private val WEBVIEW_OPTIONS = listOf(
            HookOption("switch_watermark", R.string.remove_web_watermark, R.string.remove_web_watermark_summary),
            HookOption("switch_webview_debug", R.string.enable_webview_debug, R.string.enable_webview_debug_summary),
        )

        private val CLEANUP_OPTIONS = listOf(
            HookOption("switch_autoclean", R.string.auto_clean, R.string.auto_clean_summary),
            HookOption("switch_silenceclean", R.string.silent_clean, R.string.silent_clean_summary),
        )

        private val DEBUG_OPTIONS = listOf(
            HookOption("switch_hidetoast", R.string.hide_failure_toast, R.string.hide_failure_toast_summary),
        )

        private val HOOK_GROUPS = listOf(
            HookGroup(R.string.ad_settings, AD_OPTIONS),
            HookGroup(R.string.content_settings, CONTENT_OPTIONS),
            HookGroup(R.string.interface_settings, UI_OPTIONS),
            HookGroup(R.string.navigation_settings, NAVIGATION_OPTIONS),
        )

        private val REGEX_OPTIONS = listOf(
            TextOption("edit_title", R.string.filter_title_regex, R.string.regex_filter_summary),
            TextOption("edit_author", R.string.filter_author_regex, R.string.regex_filter_summary),
            TextOption("edit_content", R.string.filter_content_regex, R.string.regex_filter_summary),
        )

        private val JS_OPTION = TextOption("edit_js", R.string.custom_javascript, R.string.custom_javascript_summary)
        private val TEXT_OPTIONS = REGEX_OPTIONS + JS_OPTION
        private val HOOK_OPTIONS = HOOK_GROUPS.flatMap { it.options } +
            GESTURE_OPTIONS + WEBVIEW_OPTIONS + CLEANUP_OPTIONS + DEBUG_OPTIONS
    }
}
