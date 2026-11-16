/*
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 *
 * See the included NOTICE file for GPLv3 §7(b) terms that apply to this code.
 */

package app.crimera.patches.twitter.timeline.history

import app.crimera.patches.twitter.entity.entityGenerator
import app.crimera.patches.twitter.misc.extension.twitterInitHook
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

private const val TIMELINE_HISTORY_MANAGER = "$PATCHES_DESCRIPTOR/timeline/history/TimelineHistoryManager;"

@Suppress("unused")
val timelineHistoryPatch =
    bytecodePatch(
        name = "Timeline history",
        description = "Records seen posts in the view layer with a local storage limit so you can recover posts lost to refreshes, crashes, or app pauses.",
    ) {
        compatibleWith(COMPATIBILITY_X)
        dependsOn(
            settingsPatch,
            entityGenerator,
            timelineHistoryResourcePatch,
        )

        execute {
            // Initialize lifecycle callbacks and crash-flush listener at end of Application.onCreate.
            twitterInitHook.fingerprint.method.apply {
                val returnIndices =
                    instructions
                        .mapIndexedNotNull { idx, ins -> if (ins.opcode == Opcode.RETURN_VOID) idx else null }
                        .sortedDescending()
                if (returnIndices.isEmpty()) {
                    throw PatchException("Failed to find RETURN_VOID in Application.onCreate")
                }
                returnIndices.forEach { retIdx ->
                    replaceInstruction(
                        retIdx,
                        "invoke-static/range {p0 .. p0}, $TIMELINE_HISTORY_MANAGER->init(Landroid/content/Context;)V",
                    )
                    addInstruction(retIdx + 1, "return-void")
                }
            }

            // Record timeline/reply/search/profile post headers once bound to view.
            HeaderViewDelegateBinderFingerprint.method.addInstruction(
                0,
                "invoke-static/range {p1 .. p2}, $TIMELINE_HISTORY_MANAGER->onTweetHeaderBound(Ljava/lang/Object;Ljava/lang/Object;)V",
            )

            // Also record focal detail posts if user visits a thread before a crash or pause.
            FocalHeaderViewDelegateBinderFingerprint.matchOrNull()?.method?.addInstruction(
                0,
                "invoke-static/range {p1 .. p2}, $TIMELINE_HISTORY_MANAGER->onFocalTweetHeaderBound(Ljava/lang/Object;Ljava/lang/Object;)V",
            )

            // Also record quoted posts embedded inside QuoteView cards.
            QuoteTweetViewBindFingerprint.matchOrNull()?.method?.addInstruction(
                0,
                "invoke-static/range {p0 .. p1}, $TIMELINE_HISTORY_MANAGER->onQuoteBound(Landroid/view/View;Ljava/lang/Object;)V",
            )

            // Synchronously capture currently visible posts right before pull-to-refresh or auto-refresh runs.
            TimelineFetchTriggerFingerprint.matchOrNull()?.method?.addInstruction(
                0,
                "invoke-static/range {p1 .. p1}, $TIMELINE_HISTORY_MANAGER->onTimelineFetch(I)V",
            )

            enableSettings("timelineHistory")
        }
    }
