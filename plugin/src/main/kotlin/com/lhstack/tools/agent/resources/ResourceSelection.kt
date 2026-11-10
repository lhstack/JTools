package com.lhstack.tools.agent.resources

/** Absence follows includeNew; explicit false always wins. */
object ResourceSelection {
    fun enabled(
        known: Collection<String>,
        overrides: Map<String, Boolean>,
        includeNew: Boolean,
    ): Set<String> = known.filter { overrides[it] ?: includeNew }.toSet()

    fun fromLists(enabled: Collection<String>, disabled: Collection<String>): Map<String, Boolean> =
        enabled.associateWith { true } + disabled.associateWith { false }
}
