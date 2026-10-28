package io.mirr.shynigsy.ui

/** Late shelves may take initial focus only while the remote has not been used. */
internal fun shouldFocusInitialHomeShelf(
    television: Boolean,
    shelfAttached: Boolean,
    alreadyRequested: Boolean,
    userInteracted: Boolean,
    drawerOpen: Boolean,
): Boolean = television && shelfAttached && !alreadyRequested && !userInteracted && !drawerOpen
