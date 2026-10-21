package io.mirr.shynigsy

import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/** Public sections do not await supplemental work; authentication failures cancel the group. */
internal suspend fun loadHomeSections(
    loadContinue: suspend () -> Unit,
    loadRecent: suspend () -> Unit,
    loadSupplemental: suspend () -> Unit = {},
) {
    coroutineScope {
        launch { loadContinue() }
        launch { loadRecent() }
        launch { loadSupplemental() }
    }
}
