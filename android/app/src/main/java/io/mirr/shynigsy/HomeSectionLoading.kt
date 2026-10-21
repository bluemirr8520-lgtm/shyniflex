package io.mirr.shynigsy

import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/** Publish independent Home sections as they arrive; uncaught failures cancel both. */
internal suspend fun loadHomeSections(
    loadContinue: suspend () -> Unit,
    loadRecent: suspend () -> Unit,
) {
    coroutineScope {
        launch { loadContinue() }
        launch { loadRecent() }
    }
}
