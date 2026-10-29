package me.bers.spiazzetta.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Eco
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import me.bers.spiazzetta.R
import me.bers.spiazzetta.domain.ChronoProgram
import me.bers.spiazzetta.domain.ChronoWeekday
import me.bers.spiazzetta.domain.clockToMinutes
import me.bers.spiazzetta.domain.DashboardLayout
import me.bers.spiazzetta.domain.DashboardSection
import me.bers.spiazzetta.domain.PelletLevel
import me.bers.spiazzetta.domain.StoveCapabilities
import me.bers.spiazzetta.domain.StoveDetails
import me.bers.spiazzetta.domain.StoveOperatingState
import me.bers.spiazzetta.domain.StoveSnapshot
import me.bers.spiazzetta.domain.StoveWarning
import me.bers.spiazzetta.ui.AppUiState
import me.bers.spiazzetta.ui.CommandEvent
import me.bers.spiazzetta.ui.components.RadialStatus
import me.bers.spiazzetta.ui.components.SegmentedSelector
import me.bers.spiazzetta.ui.components.StatusPill

/** Callbacks the dashboard needs. Kept explicit so the screen has no ViewModel dependency. */
data class DashboardActions(
    val onPower: (Boolean) -> Unit,
    val onSetpoint: (Int) -> Unit,
    val onFanSpeed: (Int) -> Unit,
    val onPowerLevel: (Int) -> Unit,
    val onToggleEco: (Boolean) -> Unit,
    val onNaturalMode: (Boolean) -> Unit,
    val onDuctRight: (Int) -> Unit,
    val onDuctLeft: (Int) -> Unit,
    val onMultifire: (Int) -> Unit,
    val onRefresh: () -> Unit,
    val onDisconnect: () -> Unit,
    val onClearEvents: () -> Unit,
    val onToggleLanguage: () -> Unit,
    val onCustomise: () -> Unit,
    val onChronoEnabled: (Int, Boolean) -> Unit,
    val onEditChrono: (Int) -> Unit,
    val onDisableAllPrograms: () -> Unit,
    val onOpenRegisters: () -> Unit,
    val onQuality: (lowBand: Boolean, value: Int) -> Unit,
)

@Composable
fun DashboardScreen(
    state: AppUiState,
    actions: DashboardActions,
    modifier: Modifier = Modifier,
) {
    val snapshot = state.snapshot
    val capabilities = snapshot?.capabilities
    val details = snapshot?.details
    val isOn = snapshot != null && snapshot.state != StoveOperatingState.OFF
    val alarms = snapshot?.state == StoveOperatingState.ALARM
    val controlsEnabled = state.busySettingKey == null && !state.readOnly
    val haptics = LocalHapticFeedback.current

    var tick by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1_000)
            tick = System.currentTimeMillis()
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Header(
                state = state,
                snapshot = snapshot,
                now = tick,
                alarms = alarms,
                onRefresh = actions.onRefresh,
                onToggleLanguage = actions.onToggleLanguage,
                onCustomise = actions.onCustomise,
            )

            details?.warnings?.takeIf { it.isNotEmpty() }?.let { warnings ->
                WarningChips(warnings = warnings)
            }

            HeroCard(
                snapshot = snapshot,
                alarms = alarms,
                canPower = capabilities?.canPower == true,
                controlsEnabled = controlsEnabled,
                isOn = isOn,
                onPower = { on ->
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    actions.onPower(on)
                },
            )

            // The user decides what appears and in which order (see DashboardSection).
            state.layout.visible().forEach { section ->
                when (section) {
                    DashboardSection.TEMPERATURE -> TemperatureCard(
                        snapshot = snapshot,
                        range = capabilities?.setpointRange,
                        enabled = controlsEnabled,
                        pending = isPending(state, "setpoint"),
                        onChange = { celsius ->
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            actions.onSetpoint(celsius)
                        },
                    )
                    DashboardSection.POWER -> capabilities?.powerLevelRange?.let { range ->
                        PowerCard(
                            range = range,
                            selected = snapshot?.powerLevel,
                            enabled = controlsEnabled,
                            pending = isPending(state, "power_level"),
                            onSelect = {
                                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                actions.onPowerLevel(it)
                            },
                        )
                    }
                    DashboardSection.MULTIFIRE -> capabilities?.multifireRange?.let { range ->
                        MultifireCard(
                            range = range,
                            selected = details?.multifireLevel,
                            enabled = controlsEnabled,
                            pending = isPending(state, "multifire"),
                            onSelect = {
                                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                actions.onMultifire(it)
                            },
                        )
                    }
                    DashboardSection.NATURAL -> if (capabilities?.canNaturalMode == true) {
                        NaturalCard(
                            enabled = details?.naturalMode,
                            controlsEnabled = controlsEnabled,
                            pending = isPending(state, "natural_mode"),
                            onChange = {
                                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                actions.onNaturalMode(it)
                            },
                        )
                    }
                    DashboardSection.DUCTS -> DuctsCard(
                        capabilities = capabilities,
                        details = details,
                        enabled = controlsEnabled,
                        pending = isPending(state, "duct_right") || isPending(state, "duct_left"),
                        onRight = {
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            actions.onDuctRight(it)
                        },
                        onLeft = {
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            actions.onDuctLeft(it)
                        },
                    )
                    DashboardSection.FAN -> capabilities?.fanSpeedRange?.let { range ->
                        ControlCard(
                            title = stringResource(R.string.section_fan),
                            explanation = stringResource(R.string.explain_fan),
                            pending = isPending(state, "fan_speed"),
                        ) {
                            SegmentedSelector(
                                options = range.toList(),
                                selected = snapshot?.fanSpeed,
                                label = { it.toString() },
                                enabled = controlsEnabled,
                                onSelect = {
                                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                    actions.onFanSpeed(it)
                                },
                            )
                        }
                    }
                    DashboardSection.COMBUSTION -> capabilities?.qualityRange?.let { range ->
                        CombustionCard(
                            range = range,
                            low = details?.combustion?.powerLow,
                            high = details?.combustion?.powerHigh,
                            enabled = controlsEnabled,
                            pending = isPending(state, "quality_low") || isPending(state, "quality_high"),
                            onLow = {
                                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                actions.onQuality(true, it)
                            },
                            onHigh = {
                                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                actions.onQuality(false, it)
                            },
                        )
                    }
                    DashboardSection.STOVE_STATUS -> StoveStatusCard(
                        details = details,
                        onShowRegisters = actions.onOpenRegisters,
                    )
                    DashboardSection.CHRONO -> details?.chronoPrograms?.takeIf { it.isNotEmpty() }?.let { programs ->
                        WeeklyProgramCard(
                            programs = programs,
                            controlsEnabled = controlsEnabled,
                            onToggle = actions.onChronoEnabled,
                            onEdit = { program ->
                                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                actions.onEditChrono(program.index)
                            },
                            onDisableAll = actions.onDisableAllPrograms,
                        )
                    }
                }
            }

            Spacer(Modifier.height(24.dp))

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (capabilities?.canEco == true) {
                    ActionPill(
                        icon = Icons.Filled.Eco,
                        text = stringResource(R.string.dashboard_eco),
                        enabled = controlsEnabled,
                        filled = false,
                        active = snapshot?.ecoEnabled == true,
                        onClick = {
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            actions.onToggleEco(snapshot?.ecoEnabled != true)
                        },
                    )
                }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = actions.onDisconnect) { Text(stringResource(R.string.action_disconnect)) }
            }

            if (state.readOnly) {
                Text(
                    text = stringResource(R.string.error_read_only_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // Room for the floating activity panel so nothing hides behind it.
            Spacer(Modifier.height(104.dp))
        }

        ActivityOverlay(
            events = state.events,
            onClear = actions.onClearEvents,
            now = tick,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(horizontal = 16.dp, vertical = 16.dp),
        )
    }

}

@Composable
private fun Header(
    state: AppUiState,
    snapshot: StoveSnapshot?,
    now: Long,
    alarms: Boolean,
    onRefresh: () -> Unit,
    onToggleLanguage: () -> Unit,
    onCustomise: () -> Unit,
) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = state.selectedStove?.name ?: stringResource(R.string.app_name),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                StatusPill(
                    text = statusText(snapshot),
                    color = when {
                        snapshot == null -> MaterialTheme.colorScheme.onSurfaceVariant
                        alarms -> MaterialTheme.colorScheme.error
                        snapshot.state == StoveOperatingState.OFF -> MaterialTheme.colorScheme.onSurfaceVariant
                        else -> MaterialTheme.colorScheme.tertiary
                    },
                )
                if (state.readOnly) {
                    StatusPill(
                        text = stringResource(R.string.readonly_pill),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.height(4.dp))
            Text(
                text = updatedText(state.lastRefreshMillis, now) + cloudCodeSuffix(snapshot),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (state.refreshing) {
                CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
            } else {
                IconButton(onClick = onRefresh) {
                    Icon(
                        Icons.Filled.Refresh,
                        contentDescription = stringResource(R.string.action_refresh),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            IconButton(onClick = onCustomise) {
                Icon(
                    Icons.Filled.Tune,
                    contentDescription = stringResource(R.string.action_customise),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = onToggleLanguage) {
                Icon(
                    Icons.Filled.Translate,
                    contentDescription = stringResource(R.string.action_language),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun HeroCard(
    snapshot: StoveSnapshot?,
    alarms: Boolean,
    canPower: Boolean,
    controlsEnabled: Boolean,
    isOn: Boolean,
    onPower: (Boolean) -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(28.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(vertical = 22.dp, horizontal = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            RadialStatus(
                progress = setpointProgress(snapshot),
                centerTop = snapshot?.roomTemperature?.let { formatTemperature(it) } ?: "--",
                centerBottom = stringResource(R.string.dashboard_room_temperature),
                accent = if (alarms) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (snapshot?.state == StoveOperatingState.ON) {
                    Text(text = "\uD83D\uDD25", fontSize = 18.sp)
                }
                Text(
                    text = situationText(snapshot),
                    style = MaterialTheme.typography.titleLarge,
                    color = if (alarms) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                )
            }
            summaryLine(snapshot)?.let { summary ->
                Spacer(Modifier.height(4.dp))
                Text(
                    text = summary,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (canPower) {
                Spacer(Modifier.height(16.dp))
                ActionPill(
                    icon = Icons.Filled.PowerSettingsNew,
                    text = stringResource(if (isOn) R.string.action_power_off else R.string.action_power_on),
                    enabled = controlsEnabled,
                    filled = true,
                    onClick = { onPower(!isOn) },
                )
            }
        }
    }
}

@Composable
private fun TemperatureCard(
    snapshot: StoveSnapshot?,
    range: IntRange?,
    enabled: Boolean,
    pending: Boolean,
    onChange: (Int) -> Unit,
) {
    ControlCard(
        title = stringResource(R.string.dashboard_setpoint),
        explanation = stringResource(R.string.explain_setpoint),
        pending = pending,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = snapshot?.setpoint?.let { it.toString() + DEGREES } ?: "--",
                style = MaterialTheme.typography.displaySmall,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.weight(1f))
            StepButton(
                icon = Icons.Filled.Remove,
                description = stringResource(R.string.action_decrease),
                enabled = enabled && range != null && snapshot?.setpoint != null && snapshot.setpoint > range.first,
                onClick = {
                    snapshot?.setpoint?.let { current -> range?.let { onChange((current - 1).coerceAtLeast(it.first)) } }
                },
            )
            Spacer(Modifier.size(10.dp))
            StepButton(
                icon = Icons.Filled.Add,
                description = stringResource(R.string.action_increase),
                enabled = enabled && range != null && snapshot?.setpoint != null && snapshot.setpoint < range.last,
                onClick = {
                    snapshot?.setpoint?.let { current -> range?.let { onChange((current + 1).coerceAtMost(it.last)) } }
                },
            )
        }
    }
}

@Composable
private fun PowerCard(
    range: IntRange,
    selected: Int?,
    enabled: Boolean,
    pending: Boolean,
    onSelect: (Int) -> Unit,
) {
    ControlCard(
        title = stringResource(R.string.dashboard_power_level),
        explanation = stringResource(R.string.explain_power),
        pending = pending,
    ) {
        SegmentedSelector(
            options = range.toList(),
            selected = selected,
            label = { it.toString() },
            enabled = enabled,
            onSelect = onSelect,
        )
    }
}

@Composable
private fun MultifireCard(
    range: IntRange,
    selected: Int?,
    enabled: Boolean,
    pending: Boolean,
    onSelect: (Int) -> Unit,
) {
    ControlCard(
        title = stringResource(R.string.dashboard_multifire),
        explanation = stringResource(R.string.explain_multifire_long),
        pending = pending,
    ) {
        SegmentedSelector(
            options = range.toList(),
            selected = selected,
            label = { it.toString() },
            enabled = enabled,
            onSelect = onSelect,
        )
    }
}

/** Natural convection belongs to the ventilation system, so it gets its own card. */
@Composable
private fun NaturalCard(
    enabled: Boolean?,
    controlsEnabled: Boolean,
    pending: Boolean,
    onChange: (Boolean) -> Unit,
) {
    ControlCard(
        title = stringResource(R.string.section_natural),
        explanation = stringResource(R.string.explain_natural_long),
        pending = pending,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(R.string.dashboard_natural_mode),
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f),
            )
            Switch(
                checked = enabled == true,
                onCheckedChange = { onChange(it) },
                enabled = controlsEnabled && enabled != null,
            )
        }
    }
}

/** The ducting outlets: a separate system, and one many users never use. */
@Composable
private fun DuctsCard(
    capabilities: StoveCapabilities?,
    details: StoveDetails?,
    enabled: Boolean,
    pending: Boolean,
    onRight: (Int) -> Unit,
    onLeft: (Int) -> Unit,
) {
    val rightRange = capabilities?.ductRightRange
    val leftRange = capabilities?.ductLeftRange
    if (rightRange == null && leftRange == null) return

    ControlCard(
        title = stringResource(R.string.section_ducts),
        explanation = stringResource(R.string.explain_ducts),
        pending = pending,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            if (rightRange != null) {
                DuctStepper(
                    label = stringResource(R.string.dashboard_duct_right),
                    value = details?.ductRightSetpoint,
                    range = rightRange,
                    enabled = enabled,
                    onChange = onRight,
                )
            }
            if (leftRange != null) {
                DuctStepper(
                    label = stringResource(R.string.dashboard_duct_left),
                    value = details?.ductLeftSetpoint,
                    range = leftRange,
                    enabled = enabled,
                    onChange = onLeft,
                )
            }
        }
    }
}

@Composable
fun DashboardLayoutDialog(
    layout: DashboardLayout,
    onClose: () -> Unit,
    onMove: (DashboardSection, Int) -> Unit,
    onToggle: (DashboardSection) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onClose,
        properties = DialogProperties(dismissOnClickOutside = false),
        title = { Text(stringResource(R.string.customise_title)) },
        text = {
            Column {
                Text(
                    text = stringResource(R.string.customise_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                layout.order.forEachIndexed { index, section ->
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                        Text(
                            text = sectionLabel(section),
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.weight(1f),
                        )
                        IconButton(
                            onClick = { onMove(section, -1) },
                            enabled = index > 0,
                            modifier = Modifier.size(36.dp),
                        ) {
                            Icon(Icons.Filled.KeyboardArrowUp, contentDescription = stringResource(R.string.customise_up))
                        }
                        IconButton(
                            onClick = { onMove(section, 1) },
                            enabled = index < layout.order.lastIndex,
                            modifier = Modifier.size(36.dp),
                        ) {
                            Icon(Icons.Filled.KeyboardArrowDown, contentDescription = stringResource(R.string.customise_down))
                        }
                        Switch(
                            checked = section !in layout.hidden,
                            onCheckedChange = { onToggle(section) },
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onClose) { Text(stringResource(R.string.customise_close)) }
        },
    )
}

@Composable
private fun CombustionCard(
    range: IntRange,
    low: Int?,
    high: Int?,
    enabled: Boolean,
    pending: Boolean,
    onLow: (Int) -> Unit,
    onHigh: (Int) -> Unit,
) {
    ControlCard(
        title = stringResource(R.string.section_combustion),
        explanation = stringResource(R.string.explain_combustion),
        pending = pending,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            CorrectionRow(
                label = stringResource(R.string.quality_low),
                value = low,
                range = range,
                enabled = enabled,
                onChange = onLow,
            )
            CorrectionRow(
                label = stringResource(R.string.quality_high),
                value = high,
                range = range,
                enabled = enabled,
                onChange = onHigh,
            )
        }
    }
}

@Composable
private fun CorrectionRow(
    label: String,
    value: Int?,
    range: IntRange,
    enabled: Boolean,
    onChange: (Int) -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(text = label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Text(
            text = value?.let { if (it > 0) "+" + it else it.toString() } ?: DASH,
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.size(10.dp))
        StepButton(
            icon = Icons.Filled.Remove,
            description = stringResource(R.string.action_decrease),
            enabled = enabled && value != null && value > range.first,
            onClick = { value?.let { onChange((it - 1).coerceAtLeast(range.first)) } },
        )
        Spacer(Modifier.size(8.dp))
        StepButton(
            icon = Icons.Filled.Add,
            description = stringResource(R.string.action_increase),
            enabled = enabled && value != null && value < range.last,
            onClick = { value?.let { onChange((it + 1).coerceAtMost(range.last)) } },
        )
    }
}

@Composable
private fun sectionLabel(section: DashboardSection): String = stringResource(
    when (section) {
        DashboardSection.TEMPERATURE -> R.string.section_temperature
        DashboardSection.POWER -> R.string.section_power
        DashboardSection.FAN -> R.string.section_fan
        DashboardSection.COMBUSTION -> R.string.section_combustion
        DashboardSection.MULTIFIRE -> R.string.section_multifire
        DashboardSection.NATURAL -> R.string.section_natural
        DashboardSection.DUCTS -> R.string.section_ducts
        DashboardSection.STOVE_STATUS -> R.string.section_status
        DashboardSection.CHRONO -> R.string.section_chrono
    },
)

/**
 * Floating, bottom-anchored activity panel. It overlays the content instead of pushing it, so the
 * buttons never jump when a command starts or finishes. Entries expire on their own after a few
 * seconds (see AppViewModel.scheduleExpiry).
 */
@Composable
private fun ActivityOverlay(
    events: List<CommandEvent>,
    onClear: () -> Unit,
    now: Long,
    modifier: Modifier = Modifier,
) {
    AnimatedVisibility(
        visible = events.isNotEmpty(),
        enter = androidx.compose.animation.fadeIn() +
            androidx.compose.animation.slideInVertically(initialOffsetY = { it / 2 }),
        exit = androidx.compose.animation.fadeOut() +
            androidx.compose.animation.slideOutVertically(targetOffsetY = { it / 2 }),
        modifier = modifier,
    ) {
        Surface(
            shape = RoundedCornerShape(22.dp),
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            shadowElevation = 10.dp,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = stringResource(R.string.activity_title),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = onClear) { Text(stringResource(R.string.activity_clear)) }
                }
                events.take(2).forEach { event ->
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        when (event.status) {
                            CommandEvent.Status.PENDING -> CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.primary,
                            )
                            CommandEvent.Status.CONFIRMED -> Icon(
                                Icons.Filled.Check,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.tertiary,
                                modifier = Modifier.size(18.dp),
                            )
                            CommandEvent.Status.FAILED -> Icon(
                                Icons.Filled.Close,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                        Spacer(Modifier.size(12.dp))
                        Row(modifier = Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = settingLabel(event.settingKey) + " " + event.value,
                                style = MaterialTheme.typography.bodyLarge,
                                modifier = Modifier.weight(1f),
                            )
                            Text(
                                text = when (event.status) {
                                    CommandEvent.Status.PENDING -> stringResource(R.string.activity_pending)
                                    CommandEvent.Status.CONFIRMED -> stringResource(R.string.activity_confirmed) +
                                        " · " + elapsed(event.startedAtMillis, event.finishedAtMillis ?: now)
                                    CommandEvent.Status.FAILED -> attemptsLabel(event.attempts)
                                },
                                style = MaterialTheme.typography.labelMedium,
                                color = when (event.status) {
                                    CommandEvent.Status.PENDING -> MaterialTheme.colorScheme.primary
                                    CommandEvent.Status.CONFIRMED -> MaterialTheme.colorScheme.tertiary
                                    CommandEvent.Status.FAILED -> MaterialTheme.colorScheme.error
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ControlCard(
    title: String,
    explanation: String,
    pending: Boolean,
    content: @Composable () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(18.dp)) {
            Text(text = title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(4.dp))
            Text(
                text = explanation,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(14.dp))
            content()
            AnimatedVisibility(visible = pending) {
                Column {
                    Spacer(Modifier.height(12.dp))
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = stringResource(R.string.activity_waiting),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }
    }
}

@Composable
private fun StepButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    description: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    FilledIconButton(onClick = onClick, enabled = enabled, modifier = Modifier.size(52.dp)) {
        Icon(icon, contentDescription = description)
    }
}

/** A labelled action: icon plus words, so nothing has to be guessed. */
@Composable
private fun ActionPill(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    text: String,
    enabled: Boolean,
    filled: Boolean,
    active: Boolean = false,
    onClick: () -> Unit,
) {
    val container = when {
        filled -> MaterialTheme.colorScheme.primary
        active -> MaterialTheme.colorScheme.primaryContainer
        else -> MaterialTheme.colorScheme.surface
    }
    val content = when {
        filled -> MaterialTheme.colorScheme.onPrimary
        active -> MaterialTheme.colorScheme.onPrimaryContainer
        else -> MaterialTheme.colorScheme.onSurface
    }
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(50),
        color = container,
        contentColor = content,
        border = if (filled || active) null else BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp))
            Text(text = text, style = MaterialTheme.typography.labelLarge)
        }
    }
}

@Composable
private fun statusText(snapshot: StoveSnapshot?): String {
    if (snapshot == null) return stringResource(R.string.status_connecting)
    if (snapshot.state == StoveOperatingState.ALARM) {
        return snapshot.alarmLabel ?: stringResource(R.string.state_alarm)
    }
    return when (snapshot.state) {
        StoveOperatingState.OFF -> stringResource(R.string.state_off)
        StoveOperatingState.ON -> snapshot.stateLabel ?: stringResource(R.string.state_working)
        StoveOperatingState.ALARM -> stringResource(R.string.state_alarm)
        StoveOperatingState.UNKNOWN -> snapshot.stateLabel ?: stringResource(R.string.state_unknown)
    }
}

/**
 * The stove's own terse status code (Micronova sends things like "NO AL"), kept visible but demoted
 * to a subtitle so it reads as information rather than a mysterious badge.
 */
@Composable
private fun cloudCodeSuffix(snapshot: StoveSnapshot?): String {
    val label = snapshot?.stateLabel?.trim().orEmpty()
    if (label.isEmpty() || label.equals(statusText(snapshot), ignoreCase = true)) return ""
    return " · " + label
}

/** One sentence that answers "what is the stove doing about my target right now?". */
@Composable
private fun situationText(snapshot: StoveSnapshot?): String {
    if (snapshot == null) return stringResource(R.string.status_connecting)
    if (snapshot.state == StoveOperatingState.OFF) return stringResource(R.string.state_off)
    val room = snapshot.roomTemperature
    val setpoint = snapshot.setpoint
    if (room != null && setpoint != null) {
        val delta = setpoint - room
        return if (delta <= 0.2) {
            stringResource(R.string.status_at_target)
        } else {
            stringResource(R.string.status_heating, String.format(java.util.Locale.US, "%.1f", delta))
        }
    }
    return statusText(snapshot)
}

@Composable
private fun updatedText(lastRefreshMillis: Long?, now: Long): String {
    if (lastRefreshMillis == null) return stringResource(R.string.status_connecting)
    val seconds = ((now - lastRefreshMillis) / 1000).coerceAtLeast(0)
    return when {
        seconds < 2 -> stringResource(R.string.status_updated_now)
        seconds < 60 -> stringResource(R.string.status_updated_seconds, seconds.toInt())
        else -> stringResource(R.string.status_updated_minutes, (seconds / 60).toInt())
    }
}

private fun elapsed(start: Long, end: Long): String {
    val seconds = ((end - start) / 1000).coerceAtLeast(0)
    return if (seconds < 1) "<1s" else seconds.toString() + "s"
}

@Composable
private fun attemptsLabel(attempts: Int): String = stringResource(R.string.activity_attempts, attempts)

@Composable
private fun settingLabel(key: String): String = stringResource(
    when (key) {
        "fan_speed" -> R.string.setting_fan_speed
        "setpoint" -> R.string.setting_setpoint
        "power_level" -> R.string.setting_power_level
        "power" -> R.string.setting_power
        "eco" -> R.string.setting_eco
        "natural_mode" -> R.string.setting_natural_mode
        "duct_right" -> R.string.setting_duct_right
        "duct_left" -> R.string.setting_duct_left
        "multifire" -> R.string.setting_multifire
        else -> R.string.app_name
    },
)

/** A compact at-a-glance summary: what the fan and the flame are doing. */
@Composable
private fun summaryLine(snapshot: StoveSnapshot?): String? {
    if (snapshot == null) return null
    val parts = buildList {
        snapshot.fanSpeed?.let { add(stringResource(R.string.setting_fan_speed) + " " + it) }
        snapshot.powerLevel?.let { add(stringResource(R.string.setting_power_level) + " " + it) }
        snapshot.details.multifireLevel?.let { add(stringResource(R.string.setting_multifire) + " " + it) }
    }
    return parts.takeIf { it.isNotEmpty() }?.joinToString(" · ")
}

private fun isPending(state: AppUiState, key: String): Boolean =
    state.pendingEvent?.settingKey == key

private fun formatTemperature(value: Double): String =
    String.format(java.util.Locale.US, "%.1f", value) + DEGREES

private fun formatWholeTemperature(value: Double): String = Math.round(value).toString() + DEGREES

private fun setpointProgress(snapshot: StoveSnapshot?): Float {
    val room = snapshot?.roomTemperature ?: return 0f
    val setpoint = snapshot.setpoint ?: return 0f
    val min = snapshot.capabilities.setpointRange?.first ?: 7
    if (setpoint <= min) return 0f
    return ((room - min) / (setpoint - min)).toFloat()
}

/** Whatever the stove itself flagged, shown before any control so it cannot be missed. */
@Composable
private fun WarningChips(warnings: List<StoveWarning>) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        warnings.chunked(2).forEach { pair ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                pair.forEach { warning ->
                    StatusPill(
                        text = warningLabel(warning),
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.weight(1f),
                    )
                }
                if (pair.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun warningLabel(warning: StoveWarning): String = stringResource(
    when (warning.kind) {
        StoveWarning.Kind.POWER_FAILURE -> R.string.warning_power_failure
        StoveWarning.Kind.POWER_FAILURE_RESTART -> R.string.warning_power_failure_restart
        StoveWarning.Kind.AUGER_LOAD -> R.string.warning_auger_load
        StoveWarning.Kind.SERVICE_DUE -> R.string.warning_service_due
    },
)

/** One ducting zone: its own setpoint, stepped within the range the stove reported. */
@Composable
private fun DuctStepper(
    label: String,
    value: Int?,
    range: IntRange,
    enabled: Boolean,
    onChange: (Int) -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(text = label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Text(
            text = value?.let { it.toString() + DEGREES } ?: "--",
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.size(10.dp))
        StepButton(
            icon = Icons.Filled.Remove,
            description = stringResource(R.string.action_decrease),
            enabled = enabled && value != null && value > range.first,
            onClick = { value?.let { onChange((it - 1).coerceAtLeast(range.first)) } },
        )
        Spacer(Modifier.size(8.dp))
        StepButton(
            icon = Icons.Filled.Add,
            description = stringResource(R.string.action_increase),
            enabled = enabled && value != null && value < range.last,
            onClick = { value?.let { onChange((it + 1).coerceAtMost(range.last)) } },
        )
    }
}

/** Read-only facts the stove reports about itself, in one calm card. */
@Composable
private fun StoveStatusCard(details: StoveDetails?, onShowRegisters: () -> Unit) {
    if (details == null) return
    val hasRows = details.flueTemperature != null ||
        details.pelletLevel != PelletLevel.UNKNOWN ||
        details.externalThermostat != null ||
        details.stoveClock != null ||
        details.serviceHours != null ||
        details.firmware != null
    if (!hasRows) return

    InfoCard(
        title = stringResource(R.string.dashboard_stove_status),
        explanation = stringResource(R.string.explain_stove_status),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            details.flueTemperature?.let { temperature ->
                InfoRow(stringResource(R.string.state_flue_temperature), formatWholeTemperature(temperature))
            }
            if (details.pelletLevel != PelletLevel.UNKNOWN) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = stringResource(R.string.state_pellet),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    StatusPill(
                        text = pelletLabel(details.pelletLevel),
                        color = when (details.pelletLevel) {
                            PelletLevel.OK -> MaterialTheme.colorScheme.tertiary
                            PelletLevel.LOW, PelletLevel.EMPTY -> MaterialTheme.colorScheme.error
                            PelletLevel.UNKNOWN -> MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
            }
            details.externalThermostat?.let { InfoRow(stringResource(R.string.state_thermostat), it) }
            details.stoveClock?.let { InfoRow(stringResource(R.string.state_clock), it) }
            details.serviceHours?.let { hours ->
                val threshold = details.serviceThreshold
                InfoRow(
                    label = stringResource(R.string.state_service),
                    value = if (threshold != null) {
                        stringResource(R.string.state_service_value, hours, threshold)
                    } else {
                        stringResource(R.string.state_service_hours, hours)
                    },
                    highlight = details.serviceDue,
                )
            }
            details.firmware?.let { InfoRow(stringResource(R.string.state_firmware), it) }
            TextButton(onClick = onShowRegisters) { Text(stringResource(R.string.registers_open)) }
        }
    }
}

/** The weekly programmer: switch a programme on or off right here, or open it to edit it. */
@Composable
private fun WeeklyProgramCard(
    programs: List<ChronoProgram>,
    controlsEnabled: Boolean,
    onToggle: (Int, Boolean) -> Unit,
    onEdit: (ChronoProgram) -> Unit,
    onDisableAll: () -> Unit,
) {
    InfoCard(
        title = stringResource(R.string.dashboard_chrono),
        explanation = stringResource(R.string.explain_chrono_edit),
    ) {
        Column {
            programs.sortedBy { it.index }.forEach { program ->
                EditableChronoRow(
                    program = program,
                    controlsEnabled = controlsEnabled,
                    onToggle = { onToggle(program.index, it) },
                    onEdit = { onEdit(program) },
                )
            }
            if (programs.any { it.enabled }) {
                Spacer(Modifier.height(6.dp))
                TextButton(onClick = onDisableAll, enabled = controlsEnabled) {
                    Text(stringResource(R.string.chrono_disable_all))
                }
            }
        }
    }
}

@Composable
private fun EditableChronoRow(
    program: ChronoProgram,
    controlsEnabled: Boolean,
    onToggle: (Boolean) -> Unit,
    onEdit: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val textColor = if (program.enabled) colors.onSurface else colors.onSurfaceVariant.copy(alpha = 0.55f)
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = stringResource(R.string.chrono_program, program.index),
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = if (program.enabled) colors.primary else textColor,
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.chrono_range, program.start ?: DASH, program.stop ?: DASH),
                style = MaterialTheme.typography.bodyLarge,
                color = textColor,
            )
            Text(
                text = chronoSummary(program),
                style = MaterialTheme.typography.bodySmall,
                color = colors.onSurfaceVariant,
            )
        }
        TextButton(onClick = onEdit, enabled = controlsEnabled) {
            Text(stringResource(R.string.chrono_edit))
        }
        Switch(
            checked = program.enabled,
            onCheckedChange = onToggle,
            enabled = controlsEnabled,
        )
    }
}

@Composable
private fun chronoSummary(program: ChronoProgram): String {
    val parts = mutableListOf<String>()
    program.temperature?.let { parts += it.toString() + DEGREES }
    program.power?.let { parts += stringResource(R.string.chrono_power_label) + " " + it }
    if (program.daysMask != 0) parts += chronoDaysLabel(program.daysMask)
    return parts.joinToString(" · ")
}

@Composable
private fun chronoDaysLabel(mask: Int): String {
    val labels = listOf(
        ChronoWeekday.MONDAY to R.string.day_mon,
        ChronoWeekday.TUESDAY to R.string.day_tue,
        ChronoWeekday.WEDNESDAY to R.string.day_wed,
        ChronoWeekday.THURSDAY to R.string.day_thu,
        ChronoWeekday.FRIDAY to R.string.day_fri,
        ChronoWeekday.SATURDAY to R.string.day_sat,
        ChronoWeekday.SUNDAY to R.string.day_sun,
    )
    val parts = mutableListOf<String>()
    for ((flag, labelRes) in labels) {
        if (mask and flag != 0) parts += stringResource(labelRes)
    }
    return parts.joinToString(" ")
}

/** Full editor for one weekly programme: times, temperature, power and weekdays. */
@Composable
fun ChronoEditorDialog(
    program: ChronoProgram,
    startRange: IntRange,
    temperatureRange: IntRange,
    powerRange: IntRange?,
    enabled: Boolean,
    onClose: () -> Unit,
    onTime: (start: Boolean, rawSteps: Int) -> Unit,
    onTemperature: (Int) -> Unit,
    onPower: (Int) -> Unit,
    onDays: (Int) -> Unit,
    onDisable: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onClose,
        properties = DialogProperties(dismissOnClickOutside = false),
        title = { Text(stringResource(R.string.chrono_editor_title, program.index)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                ChronoTimeRow(
                    label = stringResource(R.string.chrono_start),
                    clock = program.start,
                    enabled = enabled,
                    onChange = { onTime(true, it) },
                )
                ChronoTimeRow(
                    label = stringResource(R.string.chrono_stop),
                    clock = program.stop,
                    enabled = enabled,
                    onChange = { onTime(false, it) },
                )
                StepperRow(
                    label = stringResource(R.string.chrono_temperature_label),
                    value = program.temperature?.let { it.toString() + DEGREES } ?: DASH,
                    enabled = enabled && program.temperature != null,
                    onDecrease = { program.temperature?.let { onTemperature((it - 1).coerceAtLeast(temperatureRange.first)) } },
                    onIncrease = { program.temperature?.let { onTemperature((it + 1).coerceAtMost(temperatureRange.last)) } },
                )
                if (powerRange != null) {
                    Text(
                        text = stringResource(R.string.chrono_power_label),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    SegmentedSelector(
                        options = powerRange.toList(),
                        selected = program.power,
                        label = { it.toString() },
                        enabled = enabled,
                        onSelect = onPower,
                    )
                }
                Text(
                    text = stringResource(R.string.chrono_days),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                ChronoDayPicker(mask = program.daysMask, enabled = enabled, onChange = onDays)
                Spacer(Modifier.height(4.dp))
                TextButton(onClick = onDisable, enabled = enabled && program.enabled) {
                    Text(stringResource(R.string.chrono_disable_one))
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onClose) { Text(stringResource(R.string.customise_close)) }
        },
    )
}

@Composable
private fun ChronoTimeRow(
    label: String,
    clock: String?,
    enabled: Boolean,
    onChange: (Int) -> Unit,
) {
    val minutes = clockToMinutes(clock)
    val steps = minutes?.div(10)
    StepperRow(
        label = label,
        value = clock ?: DASH,
        enabled = enabled && steps != null,
        onDecrease = { steps?.let { onChange((it - 1).coerceAtLeast(0)) } },
        onIncrease = { steps?.let { onChange((it + 1).coerceAtMost(143)) } },
    )
}

@Composable
private fun StepperRow(
    label: String,
    value: String,
    enabled: Boolean,
    onDecrease: () -> Unit,
    onIncrease: () -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(text = label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Text(text = value, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.size(10.dp))
        StepButton(
            icon = Icons.Filled.Remove,
            description = stringResource(R.string.action_decrease),
            enabled = enabled,
            onClick = onDecrease,
        )
        Spacer(Modifier.size(8.dp))
        StepButton(
            icon = Icons.Filled.Add,
            description = stringResource(R.string.action_increase),
            enabled = enabled,
            onClick = onIncrease,
        )
    }
}

@Composable
private fun ChronoDayPicker(mask: Int, enabled: Boolean, onChange: (Int) -> Unit) {
    val days = listOf(
        ChronoWeekday.MONDAY to R.string.day_mon,
        ChronoWeekday.TUESDAY to R.string.day_tue,
        ChronoWeekday.WEDNESDAY to R.string.day_wed,
        ChronoWeekday.THURSDAY to R.string.day_thu,
        ChronoWeekday.FRIDAY to R.string.day_fri,
        ChronoWeekday.SATURDAY to R.string.day_sat,
        ChronoWeekday.SUNDAY to R.string.day_sun,
    )
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        days.forEach { (flag, labelRes) ->
            val selected = mask and flag != 0
            Surface(
                onClick = { onChange(if (selected) mask and flag.inv() else mask or flag) },
                enabled = enabled,
                shape = RoundedCornerShape(12.dp),
                color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                contentColor = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            ) {
                Box(modifier = Modifier.padding(vertical = 10.dp), contentAlignment = Alignment.Center) {
                    Text(stringResource(labelRes), style = MaterialTheme.typography.labelLarge)
                }
            }
        }
    }
}

@Composable
private fun ChronoRow(program: ChronoProgram) {    val colors = MaterialTheme.colorScheme
    val textColor = if (program.enabled) colors.onSurface else colors.onSurfaceVariant.copy(alpha = 0.55f)
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            text = stringResource(R.string.chrono_program, program.index),
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = if (program.enabled) colors.primary else textColor,
        )
        Text(
            text = stringResource(R.string.chrono_range, program.start ?: DASH, program.stop ?: DASH),
            style = MaterialTheme.typography.bodyLarge,
            color = textColor,
            modifier = Modifier.weight(1f),
        )
        program.temperature?.let { temperature ->
            Text(text = temperature.toString() + DEGREES, style = MaterialTheme.typography.bodyLarge, color = textColor)
        }
        program.power?.let { power ->
            Text(
                text = stringResource(R.string.chrono_program, power),
                style = MaterialTheme.typography.labelMedium,
                color = colors.onSurfaceVariant.copy(alpha = if (program.enabled) 1f else 0.55f),
            )
        }
    }
}

/** A card with a title and an explanation but no controls, used for what the stove reports. */
@Composable
private fun InfoCard(title: String, explanation: String, content: @Composable () -> Unit) {
    Surface(
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(18.dp)) {
            Text(text = title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(4.dp))
            Text(
                text = explanation,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(14.dp))
            content()
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String, highlight: Boolean = false) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = if (highlight) FontWeight.SemiBold else FontWeight.Normal,
            color = if (highlight) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun pelletLabel(level: PelletLevel): String = stringResource(
    when (level) {
        PelletLevel.OK -> R.string.pellet_ok
        PelletLevel.LOW -> R.string.pellet_low
        PelletLevel.EMPTY -> R.string.pellet_empty
        PelletLevel.UNKNOWN -> R.string.state_unknown
    },
)

/** Placeholder shown for a program field the stove leaves empty. */
private const val DASH = "\u2014"

private const val DEGREES = "\u00B0"
