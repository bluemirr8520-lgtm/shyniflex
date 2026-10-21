package io.mirr.shynigsy

import android.content.Context
import android.content.res.Configuration

/** Screen size alone must never switch tablets to the remote-only interface. */
fun Context.isTelevision(): Boolean =
    (resources.configuration.uiMode and Configuration.UI_MODE_TYPE_MASK) == Configuration.UI_MODE_TYPE_TELEVISION
