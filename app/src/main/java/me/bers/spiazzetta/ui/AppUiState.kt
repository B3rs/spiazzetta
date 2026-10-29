package me.bers.spiazzetta.ui

import me.bers.spiazzetta.domain.AguaException
import me.bers.spiazzetta.domain.DashboardLayout
import me.bers.spiazzetta.domain.Stove
import me.bers.spiazzetta.domain.StoveSnapshot

/** One command sent to the stove, kept so the UI can show what is happening and what happened. */
data class CommandEvent(
    val id: Long,
    val settingKey: String,
    val value: String,
    val status: Status,
    val startedAtMillis: Long,
    val finishedAtMillis: Long? = null,
    val attempts: Int = 0,
) {
    enum class Status { PENDING, CONFIRMED, FAILED }
}

/** Everything the UI needs in order to draw one frame, without any protocol knowledge. */
data class AppUiState(
    val phase: Phase = Phase.Booting,
    val stoves: List<Stove> = emptyList(),
    val selectedStoveId: String? = null,
    val snapshot: StoveSnapshot? = null,
    val busySettingKey: String? = null,
    val banner: Banner? = null,
    val lastRefreshMillis: Long? = null,
    val refreshing: Boolean = false,
    val signingIn: Boolean = false,
    val readOnly: Boolean = false,
    val events: List<CommandEvent> = emptyList(),
    /** Last transport failure, kept separate from the banner so the login screen can show it. */
    val error: AguaException? = null,
    /** Which dashboard panels are visible, and in which order. */
    val layout: DashboardLayout = DashboardLayout(),
    val editingLayout: Boolean = false,
    /** Which weekly programme the editor is open for, if any. */
    val editingChrono: Int? = null,
    /** Register browser visibility. */
    val showRegisters: Boolean = false,
    /** Null while the stored acceptance is still being read; the gate must not flash either way. */
    val disclaimerAccepted: Boolean? = null,
) {
    enum class Phase { Booting, Login, StovePicker, Dashboard }

    data class Banner(val message: BannerMessage, val isError: Boolean)

    /** A message the UI must show. Free-form text is deliberately excluded: everything is localized. */
    sealed interface BannerMessage {
        data class Confirmed(val settingKey: String, val value: String) : BannerMessage
        data class NotConfirmed(val settingKey: String, val value: String, val attempts: Int) : BannerMessage
        data class Detail(val code: String) : BannerMessage

        /** A command was refused because the app runs in read-only mode. */
        object ReadOnly : BannerMessage

        /** A transport-level failure worth a snackbar. */
        data class Transport(val exception: AguaException) : BannerMessage
    }

    val selectedStove: Stove? get() = stoves.firstOrNull { it.id == selectedStoveId }

    val pendingEvent: CommandEvent? get() = events.firstOrNull { it.status == CommandEvent.Status.PENDING }
}
