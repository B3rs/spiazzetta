package me.bers.spiazzetta

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import me.bers.spiazzetta.domain.AguaException
import me.bers.spiazzetta.ui.AppUiState
import me.bers.spiazzetta.ui.AppViewModel
import me.bers.spiazzetta.ui.screens.DashboardActions
import me.bers.spiazzetta.ui.screens.ChronoEditorDialog
import me.bers.spiazzetta.ui.screens.DashboardLayoutDialog
import me.bers.spiazzetta.ui.screens.DashboardScreen
import me.bers.spiazzetta.ui.screens.DisclaimerScreen
import me.bers.spiazzetta.ui.screens.LoginScreen
import me.bers.spiazzetta.ui.screens.RegisterBrowserDialog
import me.bers.spiazzetta.ui.screens.StovePickerScreen
import me.bers.spiazzetta.ui.theme.SpiazzettaTheme

class MainActivity : ComponentActivity() {

    /**
     * The whole activity is built on a base context already switched to the chosen language, so
     * every resource - Compose strings, snprintf formats, date formats - follows the selection.
     */
    override fun attachBaseContext(newBase: Context) {
        val tag = (newBase.applicationContext as? SpiazzettaApp)?.language?.value
            ?: SpiazzettaApp.LANGUAGE_ITALIAN
        super.attachBaseContext(newBase.withLanguage(tag))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val app = application as SpiazzettaApp
            SpiazzettaTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    Root(
                        onToggleLanguage = {
                            val next = if (app.language.value == SpiazzettaApp.LANGUAGE_ITALIAN) {
                                SpiazzettaApp.LANGUAGE_ENGLISH
                            } else {
                                SpiazzettaApp.LANGUAGE_ITALIAN
                            }
                            app.setLanguage(next)
                            recreate()
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun Root(onToggleLanguage: () -> Unit) {
    val app = LocalContext.current.applicationContext as SpiazzettaApp
    val viewModel: AppViewModel = viewModel(
        factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T =
                AppViewModel(app.repository, app.settings, readOnly = BuildConfig.READ_ONLY) as T
        },
    )
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current

    LaunchedEffect(state.banner) {
        val banner = state.banner ?: return@LaunchedEffect
        // Confirmations are already shown, and expire, in the floating activity panel; a snackbar
        // would cover it. Only failures that the panel cannot express (network, cloud, read-only)
        // are surfaced here.
        if (!banner.isError) {
            viewModel.dismissBanner()
            return@LaunchedEffect
        }
        snackbarHostState.showSnackbar(bannerText(banner, context))
        viewModel.dismissBanner()
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            val accepted = state.disclaimerAccepted
            if (accepted != true) {
                if (accepted == false) {
                    DisclaimerScreen(onAccept = viewModel::acceptDisclaimer)
                } else {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        androidx.compose.material3.CircularProgressIndicator()
                    }
                }
            } else Crossfade(targetState = state.phase, label = "phase") { phase ->
                when (phase) {
                    AppUiState.Phase.Booting -> Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) { androidx.compose.material3.CircularProgressIndicator() }
                    AppUiState.Phase.Login -> LoginScreen(
                        signingIn = state.signingIn,
                        errorText = state.error?.let { context.getString(R.string.login_error_prefix) + ": " + transportText(it) },
                        initialEmail = "",
                        onSignIn = { email, password -> viewModel.signIn(email, password) },
                    )
                    AppUiState.Phase.StovePicker -> StovePickerScreen(
                        stoves = state.stoves,
                        onSelect = viewModel::selectStove,
                        onLogout = viewModel::disconnect,
                    )
                    AppUiState.Phase.Dashboard -> DashboardScreen(
                        state = state,
                        actions = DashboardActions(
                            onPower = viewModel::setPower,
                            onSetpoint = viewModel::setSetpoint,
                            onFanSpeed = viewModel::setFanSpeed,
                            onPowerLevel = viewModel::setPowerLevel,
                            onToggleEco = viewModel::setEco,
                            onNaturalMode = viewModel::setNaturalMode,
                            onDuctRight = viewModel::setDuctRight,
                            onDuctLeft = viewModel::setDuctLeft,
                            onMultifire = viewModel::setMultifire,
                            onRefresh = viewModel::refresh,
                            onDisconnect = viewModel::disconnect,
                            onClearEvents = viewModel::clearEvents,
                            onToggleLanguage = onToggleLanguage,
                            onCustomise = {
                                if (state.editingLayout) viewModel.closeLayoutEditor() else viewModel.openLayoutEditor()
                            },
                            onChronoEnabled = viewModel::setChronoEnabled,
                            onEditChrono = viewModel::openChronoEditor,
                            onDisableAllPrograms = viewModel::disableAllPrograms,
                            onOpenRegisters = viewModel::openRegisters,
                            onQuality = viewModel::setQualityCorrection,
                        ),
                    )
                }
            }
            if (state.phase != AppUiState.Phase.Dashboard) {
                IconButton(
                    onClick = onToggleLanguage,
                    modifier = Modifier.align(Alignment.TopEnd).padding(top = 4.dp),
                ) {
                    Icon(Icons.Filled.Translate, contentDescription = stringResource(R.string.action_language))
                }
            }
        }

        if (state.editingLayout) {
            DashboardLayoutDialog(
                layout = state.layout,
                onClose = viewModel::closeLayoutEditor,
                onMove = viewModel::moveSection,
                onToggle = viewModel::toggleSection,
            )
        }

        val editingChrono = state.editingChrono
        val chronoSnapshot = state.snapshot
        if (editingChrono != null && chronoSnapshot != null) {
            val program = chronoSnapshot.details.chronoPrograms.firstOrNull { it.index == editingChrono }
            if (program == null) {
                viewModel.closeChronoEditor()
            } else {
                ChronoEditorDialog(
                    program = program,
                    startRange = 0..143,
                    temperatureRange = chronoSnapshot.capabilities.setpointRange ?: 7..30,
                    powerRange = chronoSnapshot.capabilities.powerLevelRange,
                    enabled = state.busySettingKey == null && !state.readOnly,
                    onClose = viewModel::closeChronoEditor,
                    onTime = { start, steps -> viewModel.setChronoTime(program.index, start, steps) },
                    onTemperature = { viewModel.setChronoTemperature(program.index, it) },
                    onPower = { viewModel.setChronoPower(program.index, it) },
                    onDays = { viewModel.setChronoDays(program.index, it) },
                    onDisable = { viewModel.setChronoEnabled(program.index, false) },
                )
            }
        }

        val registerSnapshot = state.snapshot
        if (state.showRegisters && registerSnapshot != null) {
            RegisterBrowserDialog(
                stoveName = state.selectedStove?.name ?: stringResource(R.string.app_name),
                registers = registerSnapshot.registers,
                onExport = { json -> shareText(context, json) },
                onClose = viewModel::closeRegisters,
            )
        }
    }
}

private fun bannerText(banner: AppUiState.Banner, context: android.content.Context): String {
    return when (val message = banner.message) {
        is AppUiState.BannerMessage.Confirmed -> context.getString(
            R.string.banner_applied,
            context.getString(settingNameRes(message.settingKey)),
            message.value,
        )
        is AppUiState.BannerMessage.NotConfirmed -> context.getString(
            R.string.banner_not_applied,
            context.getString(settingNameRes(message.settingKey)),
            message.value,
            message.attempts,
        )
        is AppUiState.BannerMessage.Detail -> message.code
        AppUiState.BannerMessage.ReadOnly -> context.getString(R.string.readonly_refused)
        is AppUiState.BannerMessage.Transport -> context.getString(transportMessageRes(message.exception))
    }
}

private fun transportMessageRes(exception: AguaException): Int = when (exception) {
    is AguaException.Network -> R.string.error_network
    is AguaException.Unauthorized -> R.string.error_unauthorized
    is AguaException.SessionExpired -> R.string.error_session_expired
    is AguaException.Server -> R.string.error_server
    is AguaException.InvalidResponse -> R.string.error_invalid_response
    is AguaException.StoveOffline -> R.string.error_stove_offline
    is AguaException.StoveRejected -> R.string.error_stove_rejected
    is AguaException.UnsupportedSetting -> R.string.error_unsupported
    is AguaException.ReadOnly -> R.string.readonly_refused
    is AguaException.Bridge -> R.string.error_bridge
}

/** Full sentence for a transport failure, including the cloud's own code when it gave one. */
private fun transportText(exception: AguaException): String = when (exception) {
    is AguaException.Bridge -> "cloud " + exception.code + (exception.text?.let { " · " + it } ?: "")
    is AguaException.Server -> "HTTP " + exception.statusCode
    is AguaException.Network -> "rete"
    is AguaException.Unauthorized -> "credenziali"
    is AguaException.SessionExpired -> "sessione scaduta"
    is AguaException.InvalidResponse -> exception.detail
    is AguaException.StoveOffline -> "stufa offline"
    is AguaException.StoveRejected -> exception.detail
    is AguaException.UnsupportedSetting -> exception.displayKey
    is AguaException.ReadOnly -> "sola lettura"
}

private fun settingNameRes(key: String): Int = when (key) {    "fan_speed" -> R.string.setting_fan_speed
    "setpoint" -> R.string.setting_setpoint
    "power_level" -> R.string.setting_power_level
    "power" -> R.string.setting_power
    "eco" -> R.string.setting_eco
    "natural_mode" -> R.string.setting_natural_mode
    "duct_right" -> R.string.setting_duct_right
    "duct_left" -> R.string.setting_duct_left
    "multifire" -> R.string.setting_multifire
    else -> R.string.app_name
}

private fun shareText(context: android.content.Context, body: String) {
    val subject = context.getString(R.string.registers_share_title)
    val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(android.content.Intent.EXTRA_SUBJECT, subject)
        putExtra(android.content.Intent.EXTRA_TEXT, body)
    }
    context.startActivity(android.content.Intent.createChooser(intent, subject))
}
