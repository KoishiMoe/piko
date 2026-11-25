/*
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 *
 * See the included NOTICE file for GPLv3 §7(b) terms that apply to this code.
 */

package app.crimera.patches.twitter.timeline.batchModeration

import app.morphe.patcher.patch.resourcePatch
import app.morphe.util.ResourceGroup
import app.morphe.util.copyResources

internal val batchModerationResourcePatch =
    resourcePatch {
        execute {
            copyResources(
                "twitter/moderation",
                ResourceGroup("drawable", "piko_ic_batch_moderation.xml"),
            )
        }
    }
