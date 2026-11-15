/*
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 *
 * See the included NOTICE file for GPLv3 §7(b) terms that apply to this code.
 */

package app.crimera.patches.twitter.timeline.multiImageBadge

import app.morphe.patcher.Fingerprint

internal object TweetMediaViewSetMediaItemsFingerprint : Fingerprint(
    definingClass = "Lcom/twitter/media/ui/image/TweetMediaView;",
    name = "setMediaItems",
    returnType = "V",
    parameters = listOf("Ljava/util/List;"),
)
