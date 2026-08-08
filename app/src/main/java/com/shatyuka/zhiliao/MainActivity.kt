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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
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
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.clickable
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
import java.io.File

class MainActivity : ComponentActivity() {
    private lateinit var preferences: SharedPreferences
    private val updateManager by lazy { UpdateManager.get(this) }

    private var launcherEnabled by mutableStateOf(true)
    private var hookValues by mutableStateOf<Map<String, Boolean>>(emptyMap())
    private var checking by mutableStateOf(false)
    private var availableUpdate by mutableStateOf<UpdateInfo?>(null)
    private var updateSkipped by mutableStateOf(false)
    private var showUpdateDialog by mutableStateOf(false)
    private var showCompatibilityDialog by mutableStateOf(false)
    private var compatibilityEntries by mutableStateOf<List<CompatibilityRegistry.CatalogEntry>>(emptyList())
    private var compatibilityUrl by mutableStateOf("")
    private var remoteCompatibilityRevision by mutableStateOf<Long?>(null)
    private var errorMessage by mutableStateOf<String?>(null)
    private var downloadProgress by mutableIntStateOf(NOT_DOWNLOADING)
    private var pendingInstallApk: File? = null
    private var waitingForInstallPermission = false

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
        if (waitingForInstallPermission && canInstallPackages()) {
            waitingForInstallPermission = false
            pendingInstallApk?.let(::launchInstaller)
            pendingInstallApk = null
        }
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
                    modifier = Modifier.fillMaxSize().padding(insets),
                    contentPadding = PaddingValues(horizontal = 18.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    item { HeroCard() }
                    item { SectionTitle(stringResource(R.string.module_settings)) }
                    item { HookSettingsCard() }
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
    private fun HookSettingsCard() {
        val master = hookValues[KEY_MASTER] ?: false
        SettingsCard {
            SettingSwitch(
                title = stringResource(R.string.enable_module),
                summary = stringResource(R.string.enable_module_summary),
                checked = master,
            ) { setHookValue(KEY_MASTER, it) }
            HOOK_OPTIONS.forEach { option ->
                HorizontalDivider()
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
            }
        }
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

    private fun reloadHookValues() {
        hookValues = buildMap {
            put(KEY_MASTER, preferences.getBoolean(KEY_MASTER, false))
            HOOK_OPTIONS.forEach { put(it.key, preferences.getBoolean(it.key, it.default)) }
        }
    }

    private fun setHookValue(key: String, value: Boolean) {
        preferences.edit().putBoolean(key, value).apply()
        hookValues = hookValues + (key to value)
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
                Text(
                    buildString {
                        append(getString(R.string.new_version_format, info.versionName))
                        if (info.publishedAt.isNotEmpty()) append('\n').append(getString(R.string.published_at_format, info.publishedAt))
                        append("\n\n").append(info.changelog)
                    },
                )
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
        updateManager.check { info, error ->
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
        val lastNotified = source.getLong(KEY_NOTIFIED_COMPATIBILITY_REVISION, 0)
        if (notifyRemoteUpdate && CompatibilityRegistry.isRemoteConfigActive()
            && revision > lastNotified
        ) {
            source.edit().putLong(KEY_NOTIFIED_COMPATIBILITY_REVISION, revision).apply()
            Toast.makeText(this, R.string.compatibility_config_updated, Toast.LENGTH_LONG).show()
        }
    }

    /** Single completion path for the future cloud downloader: verify, persist, load, then notify. */
    private fun applyDownloadedCompatibilityConfig(json: String, expectedSha256: String): Boolean {
        val previousRevision = CompatibilityRegistry.getRevision()
        if (!CompatibilityRegistry.installRemoteConfig(preferences, json, expectedSha256)) return false
        loadCompatibilityConfig(
            preferences,
            notifyRemoteUpdate = CompatibilityRegistry.getRevision() > previousRevision,
        )
        return true
    }

    private fun checkCompatibilityConfig() {
        updateManager.checkCompatibilityConfig { json, sha256, error ->
            if (error == null && json != null && sha256 != null) {
                applyDownloadedCompatibilityConfig(json, sha256)
            }
        }
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
        private data class HookOption(
            val key: String,
            val title: Int,
            val summary: Int,
            val default: Boolean = false,
        )

        private const val PREFS = "module_settings"
        private const val KEY_MASTER = "switch_mainswitch"
        private const val KEY_SKIPPED_VERSION = "skipped_update_version"
        private const val KEY_NOTIFIED_COMPATIBILITY_REVISION = "notified_compatibility_revision_v1"
        private const val STATE_PENDING_APK = "pending_apk"
        private const val NOT_DOWNLOADING = -2
        private const val GITHUB_URL = "https://github.com/daxiaamu/Zhiliao"

        private val HOOK_OPTIONS = listOf(
            HookOption("switch_launchad", R.string.remove_launch_ads, R.string.remove_launch_ads_summary, true),
            HookOption("switch_feedad", R.string.remove_feed_ads, R.string.remove_feed_ads_summary, true),
            HookOption("switch_answerlistad", R.string.remove_answer_list_ads, R.string.remove_answer_list_ads_summary, true),
            HookOption("switch_commentad", R.string.remove_comment_ads, R.string.remove_comment_ads_summary, true),
            HookOption("switch_sharead", R.string.remove_share_ads, R.string.remove_share_ads_summary, true),
            HookOption("switch_answerad", R.string.remove_answer_ads, R.string.remove_answer_ads_summary, true),
            HookOption("switch_searchad", R.string.remove_search_ads, R.string.remove_search_ads_summary, true),
            HookOption("switch_video", R.string.filter_video, R.string.filter_video_summary),
            HookOption("switch_removearticle", R.string.filter_article, R.string.filter_article_summary),
            HookOption("switch_pin", R.string.filter_pin, R.string.filter_pin_summary),
            HookOption("switch_externlinkex", R.string.open_external_links, R.string.open_external_links_summary),
            HookOption("switch_autorefresh", R.string.prevent_auto_refresh, R.string.prevent_auto_refresh_summary),
            HookOption("switch_livebutton", R.string.hide_live_button, R.string.hide_live_button_summary),
            HookOption("switch_reddot", R.string.hide_red_dots, R.string.hide_red_dots_summary),
            HookOption("switch_vipbanner", R.string.hide_vip_banner, R.string.hide_vip_banner_summary),
            HookOption("switch_hotbanner", R.string.hide_hot_banner, R.string.hide_hot_banner_summary),
            HookOption("switch_feedtophot", R.string.hide_feed_top_hot, R.string.hide_feed_top_hot_summary),
            HookOption("switch_minehybrid", R.string.hide_mine_cards, R.string.hide_mine_cards_summary),
            HookOption("switch_subscribe", R.string.hide_follow_button, R.string.hide_follow_button_summary),
            HookOption("switch_vipnav", R.string.hide_vip_nav, R.string.hide_nav_summary),
            HookOption("switch_videonav", R.string.hide_video_nav, R.string.hide_nav_summary),
            HookOption("switch_friendnav", R.string.hide_follow_nav, R.string.hide_nav_summary),
            HookOption("switch_panelnav", R.string.hide_publish_nav, R.string.hide_nav_summary),
            HookOption("switch_findnav", R.string.hide_discover_nav, R.string.hide_nav_summary),
            HookOption("switch_navres", R.string.disable_nav_theme, R.string.disable_nav_theme_summary),
            HookOption("switch_nipple", R.string.flatten_bottom_nav, R.string.flatten_bottom_nav_summary),
            HookOption("switch_horizontal", R.string.horizontal_answers, R.string.horizontal_answers_summary),
            HookOption("switch_watermark", R.string.remove_web_watermark, R.string.remove_web_watermark_summary),
        )
    }
}
