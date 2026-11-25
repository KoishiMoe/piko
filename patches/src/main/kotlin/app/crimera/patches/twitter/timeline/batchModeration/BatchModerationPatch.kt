/*
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 *
 * See the included NOTICE file for GPLv3 §7(b) terms that apply to this code.
 */

package app.crimera.patches.twitter.timeline.batchModeration

import app.crimera.patches.twitter.misc.settings.settingsPatch
import app.crimera.patches.twitter.utils.Constants.COMPATIBILITY_X
import app.crimera.patches.twitter.utils.Constants.PATCHES_DESCRIPTOR
import app.crimera.patches.twitter.utils.enableSettings
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.extensions.InstructionExtensions.instructions
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstructions
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.findFreeRegister
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patcher.util.smali.ExternalLabel
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

private const val MANAGER = "$PATCHES_DESCRIPTOR/timeline/moderation/BatchModerationManager;"

private fun MutableMethod.injectTouchInterceptor(extensionMethodName: String) {
    val insns = implementation?.instructions?.toList()
        ?: throw PatchException("Missing implementation for ${definingClass}->$name")
    val originalStart = getInstruction(0)
    val scratch = findFreeRegister(0)
    val p0 = implementation!!.registerCount - 2
    val p1 = implementation!!.registerCount - 1

    addInstructionsWithLabels(
        0,
        """
        invoke-static {v$p0, v$p1}, $MANAGER->$extensionMethodName(Landroid/view/View;Landroid/view/MotionEvent;)Z
        move-result v$scratch
        if-eqz v$scratch, :continue
        return v$scratch
        """.trimIndent(),
        ExternalLabel("continue", originalStart),
    )
    if (insns.isEmpty()) throw PatchException("Empty instructions in $name")
}

private fun String.escapeSmaliString(): String =
    buildString(length + 8) {
        for (ch in this@escapeSmaliString) {
            when (ch) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> append(ch)
            }
        }
    }

@Suppress("unused")
val batchModerationPatch =
    bytecodePatch(
        name = "Batch mute and block in replies",
        description = "Adds a multi-select mode in tweet reply sections to batch mute or block commenters.",
    ) {
        compatibleWith(COMPATIBILITY_X)
        dependsOn(
            batchModerationResourcePatch,
            settingsPatch,
        )

        execute {
            val targets = resolveBatchModerationTargets()

            // 1. Supply resolved class/field/method config via BatchModerationBridge.getPatchConfig()
            BatchModerationConfigFingerprint.method.replaceInstructions(
                0,
                """
                const-string v0, "${targets.configString.escapeSmaliString()}"
                return-object v0
                """.trimIndent(),
            )

            // 2. Hook TweetDetail toolbar menu creation (after MenuInflater inflates menu)
            targets.prepareMenuMethod.apply {
                val insns = instructions
                val inflateIdx =
                    insns.indexOfFirst { ins ->
                        (ins.opcode == Opcode.INVOKE_INTERFACE || ins.opcode == Opcode.INVOKE_INTERFACE_RANGE) &&
                            ((ins as? ReferenceInstruction)?.reference as? MethodReference)?.parameterTypes?.map {
                                it.toString()
                            } == listOf("I", "Landroid/view/Menu;")
                    }
                if (inflateIdx < 0) {
                    throw PatchException("Failed to find menu inflate call in TweetDetail prepareMenu")
                }
                val invokeInsn = insns[inflateIdx]
                val (navReg, menuReg) =
                    when (invokeInsn) {
                        is FiveRegisterInstruction -> invokeInsn.registerC to invokeInsn.registerE
                        is RegisterRangeInstruction -> invokeInsn.startRegister to (invokeInsn.startRegister + 2)
                        else -> throw PatchException("Unexpected inflate instruction format")
                    }
                val totalRegs = implementation!!.registerCount
                val paramSlots = 3 // (this, navComponent, Menu)
                val thisReg = totalRegs - paramSlots
                addInstructions(
                    inflateIdx + 1,
                    "invoke-static {v$thisReg, v$navReg, v$menuReg}, $MANAGER->onPrepareOptionsMenu(Ljava/lang/Object;Ljava/lang/Object;Landroid/view/Menu;)V",
                )
            }

            // 3. Hook TweetDetail menu selection
            targets.optionsSelectedMethod.apply {
                val originalStart = getInstruction(0)
                val scratch = findFreeRegister(0)
                val thisReg = implementation!!.registerCount - 2
                val itemReg = implementation!!.registerCount - 1
                addInstructionsWithLabels(
                    0,
                    """
                    invoke-static {v$thisReg, v$itemReg}, $MANAGER->onOptionsItemSelected(Ljava/lang/Object;Landroid/view/MenuItem;)Z
                    move-result v$scratch
                    if-eqz v$scratch, :continue
                    return v$scratch
                    """.trimIndent(),
                    ExternalLabel("continue", originalStart),
                )
            }

            // 4. Capture TweetDetail RecyclerView.Adapter
            targets.attachAdapterMethod.apply {
                val thisReg = implementation!!.registerCount - 2
                val adapterReg = implementation!!.registerCount - 1
                addInstructions(
                    0,
                    "invoke-static {v$thisReg, v$adapterReg}, $MANAGER->onAdapterAttached(Ljava/lang/Object;Ljava/lang/Object;)V",
                )
            }

            // 5. Index newly loaded reply collection before adapter notifies
            targets.updateItemsMethod.apply {
                val thisReg = implementation!!.registerCount - 2
                val itemsReg = implementation!!.registerCount - 1
                addInstructions(
                    0,
                    "invoke-static {v$thisReg, v$itemsReg}, $MANAGER->onTimelineLoaded(Ljava/lang/Object;Ljava/lang/Object;)V",
                )
            }

            // 6. Track bound TweetDetail row views & selection state via item bind consumer (m0.a)
            targets.itemBindConsumerMethod.apply {
                val thisReg = implementation!!.registerCount - 2
                val eventReg = implementation!!.registerCount - 1
                val scratch = findFreeRegister(0)
                val ctrlField = targets.itemBindControllerField
                addInstructions(
                    0,
                    """
                    iget-object v$scratch, v$thisReg, ${ctrlField.definingClass}->${ctrlField.name}:${ctrlField.type}
                    invoke-static {v$scratch, v$eventReg}, $MANAGER->onTweetDetailItemBound(Ljava/lang/Object;Ljava/lang/Object;)V
                    """.trimIndent(),
                )
            }

            // 7. Clean up session on TweetDetail controller destroy
            targets.onDestroyMethod.apply {
                val thisReg = implementation!!.registerCount - 1
                addInstructions(
                    0,
                    "invoke-static {v$thisReg}, $MANAGER->onControllerDestroyed(Ljava/lang/Object;)V",
                )
            }

            // 8. Intercept Back button when multi-select mode is open
            ActivityBackPressedFingerprint.method.apply {
                val originalStart = getInstruction(0)
                val scratch = findFreeRegister(0)
                val thisReg = implementation!!.registerCount - 1
                addInstructionsWithLabels(
                    0,
                    """
                    invoke-static {v$thisReg}, $MANAGER->onActivityBackPressed(Landroid/app/Activity;)Z
                    move-result v$scratch
                    if-eqz v$scratch, :continue
                    return-void
                    """.trimIndent(),
                    ExternalLabel("continue", originalStart),
                )
            }

            // 9. Intercept touches on TweetView & LinearLayoutTweetView rows when multi-select is active
            TouchInterceptConstraintFingerprint.method.injectTouchInterceptor("shouldInterceptTweetTouch")
            TouchEventConstraintFingerprint.method.injectTouchInterceptor("onInterceptedTweetTouch")

            val linearTweetClass =
                classDefByOrNull("Lcom/twitter/tweetview/core/LinearLayoutTweetView;")
                    ?: throw PatchException("Failed to resolve LinearLayoutTweetView")
            val linearTouchBaseType = linearTweetClass.superclass
                ?: throw PatchException("Failed to resolve LinearLayoutTweetView superclass")
            val linearTouchBaseClass = mutableClassDefBy(linearTouchBaseType)
            val linearIntercept =
                linearTouchBaseClass.methods.firstOrNull {
                    it.name == "onInterceptTouchEvent" &&
                        it.parameters.map { p -> p.toString() } == listOf("Landroid/view/MotionEvent;") &&
                        it.returnType == "Z"
                }
            val linearTouch =
                linearTouchBaseClass.methods.firstOrNull {
                    it.name == "onTouchEvent" &&
                        it.parameters.map { p -> p.toString() } == listOf("Landroid/view/MotionEvent;") &&
                        it.returnType == "Z"
                }
            linearIntercept?.injectTouchInterceptor("shouldInterceptTweetTouch")
            linearTouch?.injectTouchInterceptor("onInterceptedTweetTouch")

            // 10. Suppress per-user "Blocked @user" toast spam when batch block requests succeed
            targets.blockToastMethod.apply {
                val originalStart = getInstruction(0)
                val paramStart = implementation!!.registerCount - 2
                val resultReg = implementation!!.registerCount - 1
                val s1 = findFreeRegister(0)
                val s2 =
                    (0 until paramStart).firstOrNull { it != s1 }
                        ?: throw PatchException("No second free register in blockToastMethod")
                addInstructionsWithLabels(
                    0,
                    """
                    iget-boolean v$s1, v$resultReg, ${targets.httpResultClassType}->${targets.httpResultSuccessFieldName}:Z
                    if-eqz v$s1, :continue
                    invoke-static {}, $MANAGER->shouldSuppressNativeBlockToast()Z
                    move-result v$s2
                    if-eqz v$s2, :continue
                    return-void
                    """.trimIndent(),
                    ExternalLabel("continue", originalStart),
                )
            }

            enableSettings("batchModeration")
        }
    }
