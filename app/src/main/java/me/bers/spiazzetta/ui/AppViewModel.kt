package me.bers.spiazzetta.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import me.bers.spiazzetta.data.AguaRepository
import me.bers.spiazzetta.data.SettingsStore
import me.bers.spiazzetta.domain.AguaException
import me.bers.spiazzetta.domain.CURRENT_DISCLAIMER_VERSION
import me.bers.spiazzetta.domain.ConfirmedWriter
import me.bers.spiazzetta.domain.ChronoField
import me.bers.spiazzetta.domain.DashboardSection
import me.bers.spiazzetta.domain.Stove
import me.bers.spiazzetta.domain.StoveSetting
import me.bers.spiazzetta.domain.StoveSnapshot
import me.bers.spiazzetta.domain.WriteOutcome

class AppViewModel(
    private val repository: AguaRepository,
    private val settings: SettingsStore,
    private val confirmedWriter: ConfirmedWriter = ConfirmedWriter(repository),
    private val pollIntervalMillis: Long = 5_000,
    private val readOnly: Boolean = false,
    private val clock: () -> Long = System::currentTimeMillis,
) : ViewModel() {

    private val _state = MutableStateFlow(AppUiState(readOnly = readOnly))
    val state: StateFlow<AppUiState> = _state.asStateFlow()

    private var pollJob: Job? = null

    /**
     * Bumped whenever a write starts. A read that was already in flight when the write began must not
     * overwrite the snapshot the write just confirmed - that would show the user the old value.
     */
    private var writeEpoch = 0
    private var eventSequence = 0L

    init {
        viewModelScope.launch {
            val acceptedVersion = settings.disclaimerAcceptedVersion.first()
            _state.update { it.copy(disclaimerAccepted = acceptedVersion >= CURRENT_DISCLAIMER_VERSION) }
        }
        viewModelScope.launch {
            val layout = settings.loadDashboardLayout()
            _state.update { it.copy(layout = layout) }
            val saved = settings.savedCredentials()
            if (saved == null) {
                _state.update { it.copy(phase = AppUiState.Phase.Login) }
            } else {
                signIn(saved.email, saved.password, remember = true)
            }
        }
    }

    fun acceptDisclaimer() {
        viewModelScope.launch {
            settings.acceptDisclaimer(CURRENT_DISCLAIMER_VERSION, clock())
            _state.update { it.copy(disclaimerAccepted = true) }
        }
    }

    fun signIn(email: String, password: String, remember: Boolean = true) {
        viewModelScope.launch {
            _state.update { it.copy(phase = AppUiState.Phase.Login, signingIn = true, banner = null, error = null) }
            try {
                val stoves = repository.login(email, password)
                if (remember) settings.saveCredentials(email, password)
                _state.update {
                    it.copy(stoves = stoves, phase = AppUiState.Phase.StovePicker, signingIn = false, banner = null, error = null)
                }
                if (stoves.size == 1) selectStove(stoves.first())
            } catch (e: AguaException) {
                _state.update {
                    it.copy(
                        phase = AppUiState.Phase.Login,
                        signingIn = false,
                        error = e,
                        banner = AppUiState.Banner(AppUiState.BannerMessage.Transport(e), true),
                    )
                }
            }
        }
    }

    fun selectStove(stove: Stove) {
        viewModelScope.launch {
            settings.saveLastStove(stove.id)
            _state.update { it.copy(selectedStoveId = stove.id, phase = AppUiState.Phase.Dashboard, snapshot = null) }
            refresh()
            startPolling()
        }
    }

    fun refresh() {
        val stoveId = _state.value.selectedStoveId ?: return
        viewModelScope.launch { refreshOnce(stoveId) }
    }

    private suspend fun refreshOnce(stoveId: String) {
        val epochAtStart = writeEpoch
        _state.update { it.copy(refreshing = true) }
        try {
            val snapshot = repository.readSnapshot(stoveId)
            _state.update { current ->
                val stale = current.busySettingKey != null ||
                    epochAtStart != writeEpoch ||
                    current.selectedStoveId != stoveId
                if (stale) {
                    current.copy(refreshing = false)
                } else {
                    current.copy(
                        snapshot = snapshot,
                        refreshing = false,
                        lastRefreshMillis = snapshot.receivedAtMillis,
                    )
                }
            }
        } catch (e: AguaException) {
            _state.update {
                it.copy(
                    refreshing = false,
                    banner = AppUiState.Banner(AppUiState.BannerMessage.Transport(e), true),
                )
            }
        }
    }

    private fun startPolling() {
        pollJob?.cancel()
        pollJob = viewModelScope.launch {
            while (isActive && _state.value.phase == AppUiState.Phase.Dashboard) {
                delay(pollIntervalMillis)
                val stoveId = _state.value.selectedStoveId ?: break
                if (_state.value.busySettingKey == null) {
                    refreshOnce(stoveId)
                }
            }
        }
    }

    fun setFanSpeed(level: Int) = write(StoveSetting.FanSpeed(level))

    fun setSetpoint(celsius: Int) = write(StoveSetting.Setpoint(celsius))

    fun setPowerLevel(level: Int) = write(StoveSetting.PowerLevel(level))

    fun setPower(on: Boolean) = write(StoveSetting.Powered(on))

    fun setEco(enabled: Boolean) = write(StoveSetting.Eco(enabled))

    fun setQualityCorrection(lowBand: Boolean, value: Int) =
        write(StoveSetting.QualityCorrection(lowBand, value))

    fun setChronoEnabled(program: Int, enabled: Boolean) =
        write(StoveSetting.ChronoProgramEdit(program, ChronoField.ENABLE, if (enabled) 1 else 0))

    fun setChronoTime(program: Int, start: Boolean, rawSteps: Int) =
        write(
            StoveSetting.ChronoProgramEdit(
                program,
                if (start) ChronoField.START else ChronoField.STOP,
                rawSteps,
            ),
        )

    fun setChronoTemperature(program: Int, celsius: Int) =
        write(StoveSetting.ChronoProgramEdit(program, ChronoField.TEMPERATURE, celsius))

    fun setChronoPower(program: Int, level: Int) =
        write(StoveSetting.ChronoProgramEdit(program, ChronoField.POWER, level))

    fun setChronoDays(program: Int, mask: Int) = write(StoveSetting.ChronoDays(program, mask))

    /** Turns every weekly programme off in one go. */
    fun disableAllPrograms() {
        val programs = _state.value.snapshot?.details?.chronoPrograms.orEmpty().filter { it.enabled }
        viewModelScope.launch {
            for (program in programs) {
                if (_state.value.busySettingKey != null) break
                setChronoEnabled(program.index, false)
            }
        }
    }

    fun setNaturalMode(enabled: Boolean) = write(StoveSetting.NaturalMode(enabled))

    fun setDuctRight(celsius: Int) = write(StoveSetting.DuctRight(celsius))

    fun setDuctLeft(celsius: Int) = write(StoveSetting.DuctLeft(celsius))

    fun setMultifire(level: Int) = write(StoveSetting.Multifire(level))

    private fun write(setting: StoveSetting) {
        val stoveId = _state.value.selectedStoveId ?: return
        if (readOnly) {
            _state.update { it.copy(banner = AppUiState.Banner(AppUiState.BannerMessage.ReadOnly, true)) }
            return
        }
        if (_state.value.busySettingKey != null) return

        val eventId = ++eventSequence
        val startedAt = clock()
        val pending = CommandEvent(
            id = eventId,
            settingKey = setting.displayKey,
            value = setting.displayValue(),
            status = CommandEvent.Status.PENDING,
            startedAtMillis = startedAt,
        )
        writeEpoch += 1
        _state.update {
            it.copy(
                busySettingKey = setting.displayKey,
                banner = null,
                events = (listOf(pending) + it.events).take(MAX_EVENTS),
            )
        }

        viewModelScope.launch {
            val outcome = confirmedWriter.write(stoveId, setting)
            when (outcome) {
                is WriteOutcome.Confirmed -> {
                    _state.update { current ->
                        current.copy(
                            snapshot = outcome.snapshot,
                            busySettingKey = null,
                            events = current.events.map { event ->
                                if (event.id == eventId) {
                                    event.copy(
                                        status = CommandEvent.Status.CONFIRMED,
                                        finishedAtMillis = clock(),
                                        attempts = outcome.attempts,
                                    )
                                } else {
                                    event
                                }
                            },
                            banner = AppUiState.Banner(
                                AppUiState.BannerMessage.Confirmed(setting.displayKey, setting.displayValue()),
                                isError = false,
                            ),
                        )
                    }
                    scheduleExpiry(eventId)
                }
                is WriteOutcome.NotConfirmed -> {
                    val refreshed = runCatching { repository.readSnapshot(stoveId) }.getOrNull()
                    _state.update { current ->
                        current.copy(
                            snapshot = refreshed ?: current.snapshot,
                            busySettingKey = null,
                            events = current.events.map { event ->
                                if (event.id == eventId) {
                                    event.copy(
                                        status = CommandEvent.Status.FAILED,
                                        finishedAtMillis = clock(),
                                        attempts = outcome.attempts,
                                    )
                                } else {
                                    event
                                }
                            },
                            banner = AppUiState.Banner(
                                AppUiState.BannerMessage.NotConfirmed(
                                    setting.displayKey,
                                    setting.displayValue(),
                                    outcome.attempts,
                                ),
                                isError = true,
                            ),
                        )
                    }
                    scheduleExpiry(eventId)
                }
            }
        }
    }

    fun openLayoutEditor() {
        android.util.Log.d("SpiazzettaUi", "openLayoutEditor")
        _state.update { it.copy(editingLayout = true) }
    }

    fun closeLayoutEditor() {
        _state.update { it.copy(editingLayout = false) }
    }

    fun moveSection(section: DashboardSection, offset: Int) {
        val layout = _state.value.layout.move(section, offset)
        _state.update { it.copy(layout = layout) }
        viewModelScope.launch { settings.saveDashboardLayout(layout) }
    }

    fun toggleSection(section: DashboardSection) {
        val layout = _state.value.layout.toggle(section)
        _state.update { it.copy(layout = layout) }
        viewModelScope.launch { settings.saveDashboardLayout(layout) }
    }

    fun openChronoEditor(program: Int) {
        _state.update { it.copy(editingChrono = program) }
    }

    fun closeChronoEditor() {
        _state.update { it.copy(editingChrono = null) }
    }

    fun openRegisters() {
        _state.update { it.copy(showRegisters = true) }
    }

    fun closeRegisters() {
        _state.update { it.copy(showRegisters = false) }
    }

    fun clearEvents() {
        _state.update { it.copy(events = emptyList()) }
    }

    fun dismissBanner() {
        _state.update { it.copy(banner = null) }
    }

    fun disconnect() {
        pollJob?.cancel()
        viewModelScope.launch {
            settings.clearCredentials()
            _state.value = AppUiState(phase = AppUiState.Phase.Login, readOnly = readOnly)
        }
    }

    fun showStovePicker() {
        pollJob?.cancel()
        _state.update { it.copy(phase = AppUiState.Phase.StovePicker) }
    }

    override fun onCleared() {
        pollJob?.cancel()
        super.onCleared()
    }

    /** Drops a finished event after a while so the panel never becomes a museum of old taps. */
    private fun scheduleExpiry(eventId: Long) {
        viewModelScope.launch {
            delay(EVENT_LIFETIME_MILLIS)
            _state.update { current ->
                current.copy(events = current.events.filterNot { it.id == eventId })
            }
        }
    }

    private companion object {
        const val MAX_EVENTS = 8
        const val EVENT_LIFETIME_MILLIS = 8_000L
    }
}

internal fun StoveSetting.displayValue(): String = when (this) {
    is StoveSetting.FanSpeed -> level.toString()
    is StoveSetting.Setpoint -> celsius.toString() + "\u00B0C"
    is StoveSetting.PowerLevel -> level.toString()
    is StoveSetting.Powered -> if (on) "ON" else "OFF"
    is StoveSetting.Eco -> if (enabled) "ON" else "OFF"
    is StoveSetting.NaturalMode -> if (enabled) "ON" else "OFF"
    is StoveSetting.DuctRight -> celsius.toString() + "\u00B0C"
    is StoveSetting.DuctLeft -> celsius.toString() + "\u00B0C"
    is StoveSetting.Multifire -> level.toString()
    is StoveSetting.ChronoProgramEdit -> value.toString()
    is StoveSetting.ChronoDays -> mask.toString()
    is StoveSetting.QualityCorrection -> value.toString()
}

/** Exposed for tests: converts a snapshot into the value the UI shows for a setting. */
fun StoveSnapshot.valueFor(setting: StoveSetting): String? = when (setting) {
    is StoveSetting.FanSpeed -> fanSpeed?.toString()
    is StoveSetting.Setpoint -> setpoint?.toString()
    is StoveSetting.PowerLevel -> powerLevel?.toString()
    is StoveSetting.Powered -> state.name
    is StoveSetting.Eco -> ecoEnabled?.toString()
    is StoveSetting.NaturalMode -> details.naturalMode?.toString()
    is StoveSetting.ChronoProgramEdit -> valueForChrono(setting)
    is StoveSetting.ChronoDays -> details.chronoPrograms.firstOrNull { it.index == setting.program }?.daysMask?.toString()
    is StoveSetting.QualityCorrection -> if (setting.lowBand) {
        details.combustion.powerLow?.toString()
    } else {
        details.combustion.powerHigh?.toString()
    }
    is StoveSetting.DuctRight -> details.ductRightSetpoint?.toString()
    is StoveSetting.DuctLeft -> details.ductLeftSetpoint?.toString()
    is StoveSetting.Multifire -> details.multifireLevel?.toString()
}

private fun StoveSnapshot.valueForChrono(setting: StoveSetting.ChronoProgramEdit): String? {
    val program = details.chronoPrograms.firstOrNull { it.index == setting.program } ?: return null
    return when (setting.chronoField) {
        ChronoField.ENABLE -> if (program.enabled) "ON" else "OFF"
        ChronoField.START -> program.start
        ChronoField.STOP -> program.stop
        ChronoField.TEMPERATURE -> program.temperature?.toString()
        ChronoField.POWER -> program.power?.toString()
    }
}
