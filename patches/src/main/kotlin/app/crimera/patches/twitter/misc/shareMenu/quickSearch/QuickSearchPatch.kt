/*
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 *
 * See the included NOTICE file for GPLv3 §7(b) terms that apply to this code.
 */

package app.crimera.patches.twitter.misc.shareMenu.quickSearch

import app.crimera.patches.twitter.entity.entityGenerator
import app.crimera.patches.twitter.entity.twitterUser.twitterUserEntity
import app.crimera.patches.twitter.misc.settings.settingsPatch
import app.crimera.patches.twitter.misc.shareMenu.nativeShareMenu.nativeShareMenuPatch
import app.crimera.patches.twitter.utils.Constants.COMPATIBILITY_X
import app.crimera.patches.twitter.utils.enableSettings
import app.morphe.patcher.patch.bytecodePatch

@Suppress("unused")
val quickSearchPatch =
    bytecodePatch(
        name = "Author quick search",
        description = "Adds an interactive quick search builder to the native share sheet.",
    ) {
        compatibleWith(COMPATIBILITY_X)
        dependsOn(settingsPatch, entityGenerator, twitterUserEntity, nativeShareMenuPatch)

        execute {
            enableSettings("quickSearch")
        }
    }
