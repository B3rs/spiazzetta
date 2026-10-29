package me.bers.spiazzetta.domain

/** The blocks the dashboard can show, so each user can hide and reorder them. */
enum class DashboardSection {
    TEMPERATURE,
    POWER,
    FAN,
    MULTIFIRE,
    COMBUSTION,
    NATURAL,
    DUCTS,
    STOVE_STATUS,
    CHRONO;

    companion object {
        val DEFAULT_ORDER = listOf(
            TEMPERATURE,
            POWER,
            FAN,
            NATURAL,
            DUCTS,
            MULTIFIRE,
            COMBUSTION,
            STOVE_STATUS,
            CHRONO,
        )
    }
}

/** Which sections are visible, in which order. Persisted on the device. */
data class DashboardLayout(
    val order: List<DashboardSection> = DashboardSection.DEFAULT_ORDER,
    val hidden: Set<DashboardSection> = emptySet(),
) {
    fun visible(): List<DashboardSection> = order.filterNot { it in hidden }

    fun move(section: DashboardSection, offset: Int): DashboardLayout {
        val index = order.indexOf(section)
        if (index < 0) return this
        val target = (index + offset).coerceIn(0, order.size - 1)
        if (target == index) return this
        val mutable = order.toMutableList()
        val moved = mutable.removeAt(index)
        mutable.add(target, moved)
        return copy(order = mutable)
    }

    fun toggle(section: DashboardSection): DashboardLayout =
        copy(hidden = if (section in hidden) hidden - section else hidden + section)
}
