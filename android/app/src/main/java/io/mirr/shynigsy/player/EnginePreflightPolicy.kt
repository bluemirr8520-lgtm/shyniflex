package io.mirr.shynigsy.player

internal enum class EngineInteraction { NONE, START_MPV, ACCEPT_MPV, FALLBACK_FROM_EXO }

/** A physical action always lands on a settled engine, not an asynchronously replacing one. */
internal fun engineInteraction(playerPresent: Boolean, selectionRunning: Boolean, mpvProbePending: Boolean,
    exoPreparing: Boolean): EngineInteraction = when {
    !playerPresent && selectionRunning -> EngineInteraction.START_MPV
    mpvProbePending -> EngineInteraction.ACCEPT_MPV
    exoPreparing -> EngineInteraction.FALLBACK_FROM_EXO
    else -> EngineInteraction.NONE
}

/** Results from a blocked decoder lookup must not revive a cancelled or replaced player. */
internal fun mayCommitMpvProbe(pending: Boolean, samePlayer: Boolean, sameGeneration: Boolean,
    sameSource: Boolean, sameAccount: Boolean, visible: Boolean, closing: Boolean): Boolean =
    pending && samePlayer && sameGeneration && sameSource && sameAccount && visible && !closing

/** Probe players never create completion/progress events until adopted. */
internal fun playbackEngineSettled(mpvProbePending: Boolean, exoPreparing: Boolean): Boolean =
    !mpvProbePending && !exoPreparing
