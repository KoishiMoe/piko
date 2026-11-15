/*
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 *
 * See the included NOTICE file for GPLv3 §7(b) terms that apply to this code.
 */

package app.crimera.patches.twitter.timeline.multiImageBadge

import app.crimera.patches.twitter.misc.settings.settingsPatch
import app.crimera.patches.twitter.utils.Constants.COMPATIBILITY_X
import app.crimera.patches.twitter.utils.Constants.PATCHES_DESCRIPTOR
import app.crimera.patches.twitter.utils.enableSettings
import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.extensions.InstructionExtensions.instructions
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import com.android.tools.smali.dexlib2.Opcode

private const val GALLERY_HELPER_DESCRIPTOR = "${PATCHES_DESCRIPTOR}/gallery/GalleryHelper;"

@Suppress("unused")
val multiImageBadgePatch =
    bytecodePatch(
        name = "Multi-image post badge",
        description = "Shows a +N badge on the 4th grid cell when a post has more than 4 images",
    ) {
        compatibleWith(COMPATIBILITY_X)
        dependsOn(settingsPatch)

        execute {
            TweetMediaViewSetMediaItemsFingerprint.method.apply {
                val returnIndices =
                    instructions
                        .mapIndexedNotNull { index, ins ->
                            if (ins.opcode == Opcode.RETURN_VOID) index else null
                        }
                if (returnIndices.isEmpty()) {
                    throw PatchException("Failed to find RETURN_VOID in TweetMediaView.setMediaItems")
                }

                // Replace each `return-void` in-place so existing branch labels jumping to it
                // land on the badge update instead of skipping over it.
                returnIndices.sortedDescending().forEach { retIndex ->
                    replaceInstruction(
                        retIndex,
                        "invoke-static/range {p0 .. p1}, $GALLERY_HELPER_DESCRIPTOR->updateMultiImageBadge(Landroid/view/ViewGroup;Ljava/util/List;)V",
                    )
                    addInstruction(retIndex + 1, "return-void")
                }
            }

            enableSettings("showMultiImageBadge")
        }
    }
