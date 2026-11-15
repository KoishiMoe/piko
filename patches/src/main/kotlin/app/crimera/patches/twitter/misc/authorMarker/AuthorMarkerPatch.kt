/*
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 *
 * See the included NOTICE file for GPLv3 §7(b) terms that apply to this code.
 */

package app.crimera.patches.twitter.misc.authorMarker

import app.crimera.patches.twitter.entity.entityGenerator
import app.crimera.patches.twitter.entity.twitterUser.TwitterUserToStringFingerprint
import app.crimera.patches.twitter.entity.twitterUser.twitterUserEntity
import app.crimera.patches.twitter.misc.moreProfileInfo.SetUserNameOnUserHeaderFingerprint
import app.crimera.patches.twitter.misc.settings.settingsPatch
import app.crimera.patches.twitter.misc.shareMenu.nativeShareMenu.nativeShareMenuPatch
import app.crimera.patches.twitter.utils.Constants.COMPATIBILITY_X
import app.crimera.patches.twitter.utils.enableSettings
import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.getReference
import app.morphe.patcher.util.registersUsed
import app.morphe.util.indexOfFirstInstruction
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference

private const val HELPER_CLASS =
    "Lapp/morphe/extension/twitter/patches/author/AuthorMarkerHelper;"

@Suppress("unused")
val authorMarkerPatch =
    bytecodePatch(
        name = "Author marker",
        description = "Marks followed and manually marked accounts across posts, quotes, and profiles.",
    ) {
        compatibleWith(COMPATIBILITY_X)
        dependsOn(settingsPatch, entityGenerator, twitterUserEntity, nativeShareMenuPatch)

        execute {
            // 1. Timeline, focal detail, and quoted tweet headers: inject marker into TweetHeaderView badge slot
            fun injectTweetBadgeHook(
                fp: Fingerprint,
                badgeCallMatchIndex: Int,
                badgeMoveMatchIndex: Int,
            ) {
                with(fp) {
                    val tweetReg = instructionMatches[badgeCallMatchIndex].instruction.registersUsed[0]
                    val moveIndex = instructionMatches[badgeMoveMatchIndex].index
                    val badgesReg = instructionMatches[badgeMoveMatchIndex].instruction.registersUsed[0]
                    if (tweetReg >= 16 || badgesReg >= 16) {
                        throw PatchException(
                            "Expected 4-bit registers in ${method.definingClass}->${method.name}, got v$tweetReg, v$badgesReg",
                        )
                    }
                    method.addInstructions(
                        moveIndex + 1,
                        """
                        invoke-static {v$badgesReg, v$tweetReg}, $HELPER_CLASS->appendTweetBadgeIfMarked(Ljava/util/ArrayList;Ljava/lang/Object;)V
                        """.trimIndent(),
                    )
                }
            }

            injectTweetBadgeHook(TweetHeaderBindFingerprint, 0, 1)
            injectTweetBadgeHook(FocalTweetHeaderBindFingerprint, 0, 1)
            injectTweetBadgeHook(QuoteViewBindFingerprint, 1, 2)

            // 2. Native UsernameBadge factory: return golden star badge span for MarkerBadgeType sentinel
            UsernameBadgeCreateFingerprint.method.addInstructionsWithLabels(
                0,
                """
                move-object/from16 v0, p0
                move-object/from16 v1, p1
                invoke-static {v0, v1}, $HELPER_CLASS->createMarkerBadgeIfCustom(Landroid/view/View;Ljava/lang/Object;)Lcom/twitter/ui/user/j;
                move-result-object v0
                if-eqz v0, :cond_custom_badge_skip
                return-object v0
                :cond_custom_badge_skip
                nop
                """.trimIndent(),
            )

            val twitterUserClassName = TwitterUserToStringFingerprint.classDef.type

            // 3. Profile header @username
            val userHeaderFields = SetUserNameOnUserHeaderFingerprint.classDef.fields
            val profileInfoField = userHeaderFields.first { it.type.startsWith("Lcom/twitter/profiles/") }
            val userFieldFromProfileInfo =
                mutableClassDefBy { it.type == profileInfoField.type }.fields.first {
                    it.type == twitterUserClassName
                }

            SetUserNameOnUserHeaderFingerprint.method.apply {
                val userNameObjectIndex = indexOfFirstInstruction(Opcode.MOVE_RESULT_OBJECT)
                addInstructions(
                    userNameObjectIndex + 1,
                    """
                    iget-object v1, p0, $profileInfoField
                    iget-object v1, v1, $userFieldFromProfileInfo
                    invoke-static {p1, v1}, $HELPER_CLASS->formatProfileUsername(Ljava/lang/String;Ljava/lang/Object;)Ljava/lang/String;
                    move-result-object p1
                    """.trimIndent(),
                )
            }

            // 4. Profile toolbar menu (add top item, sync visibility/title, handle clicks & refresh header)
            val profileMenuClickMethod = ProfileMenuClickFingerprint.method
            val p0ClassDef = mutableClassDefBy { it.type == ProfileMenuClickFingerprint.classDef.type }

            val clickInsns = profileMenuClickMethod.implementation?.instructions?.toList()
                ?: throw PatchException("ProfileMenuClick instructions missing")

            val profileUserField =
                clickInsns
                    .firstOrNull { ins ->
                        ins.opcode == Opcode.IGET_OBJECT &&
                            ins.getReference<FieldReference>()?.let {
                                it.definingClass == p0ClassDef.type && it.type == twitterUserClassName
                            } == true
                    }?.getReference<FieldReference>()
                    ?: throw PatchException("Profile user field not found in profile controller")

            val p0Methods = p0ClassDef.methods
            val onCreateMenuMethod =
                p0Methods.firstOrNull {
                    it.parameterTypes.size == 2 &&
                        it.parameterTypes[1] == "Landroid/view/Menu;" &&
                        it.returnType == "Z"
                } ?: throw PatchException("Profile onCreateMenu method not found")

            val navInterfaceType = onCreateMenuMethod.parameterTypes[0].toString()

            val refreshHeaderMethod =
                p0Methods.firstOrNull { m ->
                    val insns = m.implementation?.instructions?.toList()
                    m.parameterTypes.isEmpty() &&
                        m.returnType == "V" &&
                        insns != null &&
                        insns.size <= 40 &&
                        insns.any { ins ->
                            ins.opcode == Opcode.CONST_STRING &&
                                ins.getReference<StringReference>()?.string == "displayState"
                        }
                } ?: throw PatchException("Profile refreshHeader method not found")

            val updateMenuMethod =
                p0Methods.firstOrNull { m ->
                    val insns = m.implementation?.instructions ?: return@firstOrNull false
                    m.parameterTypes.isEmpty() &&
                        m.returnType == "V" &&
                        insns.any { ins ->
                            ins.opcode == Opcode.INVOKE_INTERFACE &&
                                ins.getReference<MethodReference>()?.let {
                                    it.parameterTypes == listOf(p0ClassDef.type) && it.returnType == "Z"
                                } == true
                        }
                } ?: throw PatchException("Profile updateMenu method not found")

            // Inject onCreateMenu hook right after menu inflation via navInterfaceType.e(I, Menu)
            val createInsns = onCreateMenuMethod.implementation?.instructions?.toList()
                ?: throw PatchException("Profile onCreateMenu instructions missing")
            val inflateIndex =
                createInsns.indexOfFirst { ins ->
                    ins.opcode == Opcode.INVOKE_INTERFACE &&
                        ins.getReference<MethodReference>()?.let {
                            it.definingClass == navInterfaceType &&
                                it.parameterTypes.size == 2 &&
                                it.parameterTypes[0] == "I" &&
                                it.parameterTypes[1] == "Landroid/view/Menu;"
                        } == true
                }
            if (inflateIndex < 0) {
                throw PatchException("Menu inflate call not found in onCreateMenu")
            }
            onCreateMenuMethod.addInstructions(
                inflateIndex + 1,
                """
                move-object/from16 v0, p2
                move-object/from16 v1, p0
                iget-object v1, v1, $profileUserField
                invoke-static {v0, v1}, $HELPER_CLASS->onCreateProfileMenu(Landroid/view/Menu;Ljava/lang/Object;)V
                """.trimIndent(),
            )

            // Resolve menu-update Runnable.run() that casts to p0ClassDef
            val updateInsns = updateMenuMethod.implementation?.instructions?.toList()
                ?: throw PatchException("Profile updateMenu instructions missing")
            val runMethod =
                updateInsns
                    .asSequence()
                    .filter { ins ->
                        ins.opcode == Opcode.NEW_INSTANCE &&
                            ins.getReference<TypeReference>()?.type?.startsWith("Lcom/twitter/app/profiles/") == true
                    }.mapNotNull { ins ->
                        val runnableType = ins.getReference<TypeReference>()!!.type
                        val cls = mutableClassDefBy { it.type == runnableType }
                        val candidate =
                            cls.methods.firstOrNull {
                                it.name == "run" && it.parameterTypes.isEmpty() && it.returnType == "V"
                            } ?: return@mapNotNull null
                        val castsController =
                            candidate.implementation?.instructions?.any { ri ->
                                ri.opcode == Opcode.CHECK_CAST &&
                                    ri.getReference<TypeReference>()?.type == p0ClassDef.type
                            } == true
                        if (castsController) candidate else null
                    }.firstOrNull()
                    ?: throw PatchException("Profile menu runnable.run() not found")

            val runInsns = runMethod.implementation?.instructions?.toList()
                ?: throw PatchException("Profile menu runnable.run instructions missing")
            val castIndex =
                runInsns.indexOfFirst { ins ->
                    ins.opcode == Opcode.CHECK_CAST &&
                        ins.getReference<TypeReference>()?.type == p0ClassDef.type
                }
            if (castIndex < 0) {
                throw PatchException("Controller check-cast not found in profile menu runnable")
            }

            val p0Reg = runInsns[castIndex].registersUsed[0]
            val navMoveIndex =
                ((castIndex + 1)..minOf(castIndex + 8, runInsns.lastIndex)).firstOrNull { idx ->
                    val ins = runInsns[idx]
                    ins.opcode == Opcode.INVOKE_VIRTUAL &&
                        ins.getReference<MethodReference>()?.returnType == navInterfaceType &&
                        idx + 1 <= runInsns.lastIndex &&
                        runInsns[idx + 1].opcode == Opcode.MOVE_RESULT_OBJECT
                } ?: throw PatchException("Navigation wrapper getter not found in profile menu runnable")
            val navFReg = runInsns[navMoveIndex + 1].registersUsed[0]

            val igetUserIndex =
                ((navMoveIndex + 2)..minOf(navMoveIndex + 10, runInsns.lastIndex)).firstOrNull { idx ->
                    val ins = runInsns[idx]
                    ins.opcode == Opcode.IGET_OBJECT &&
                        ins.getReference<FieldReference>()?.let {
                            it.definingClass == p0ClassDef.type && it.type == twitterUserClassName
                        } == true
                } ?: throw PatchException("User field read not found in profile menu runnable")
            val userReg = runInsns[igetUserIndex].registersUsed[0]

            val findItemMethod =
                runInsns
                    .firstOrNull { ins ->
                        ins.opcode == Opcode.INVOKE_INTERFACE &&
                            ins.getReference<MethodReference>()?.let {
                                it.definingClass == navInterfaceType &&
                                    it.parameterTypes == listOf("I") &&
                                    it.returnType == "Landroid/view/MenuItem;"
                            } == true
                    }?.getReference<MethodReference>()
                    ?: throw PatchException("findItem method reference not found in profile menu runnable")

            val scratchReg =
                (0..15).firstOrNull { r -> r != p0Reg && r != navFReg && r != userReg }
                    ?: throw PatchException("No free 4-bit scratch register in profile menu runnable")

            runMethod.addInstructions(
                igetUserIndex + 1,
                """
                const v$scratchReg, ${0x7f7f0101}
                invoke-interface {v$navFReg, v$scratchReg}, $findItemMethod
                move-result-object v$scratchReg
                invoke-static {v$scratchReg, v$userReg}, $HELPER_CLASS->updateProfileMenuItem(Landroid/view/MenuItem;Ljava/lang/Object;)V
                """.trimIndent(),
            )

            // Inject click handler at entry of p0.j(MenuItem)Z using only v0 and v1 before :cond_author_marker_skip
            profileMenuClickMethod.addInstructionsWithLabels(
                0,
                """
                move-object/from16 v0, p1
                invoke-static {v0}, $HELPER_CLASS->isProfileMenuMarkerItem(Landroid/view/MenuItem;)Z
                move-result v0
                if-eqz v0, :cond_author_marker_skip
                move-object/from16 v0, p0
                iget-object v1, v0, $profileUserField
                invoke-static {v1}, $HELPER_CLASS->onProfileMenuItemClicked(Ljava/lang/Object;)Z
                move-result v1
                if-eqz v1, :cond_author_marker_done
                invoke-virtual {v0}, $refreshHeaderMethod
                invoke-virtual {v0}, $updateMenuMethod
                :cond_author_marker_done
                const/4 v0, 0x1
                return v0
                :cond_author_marker_skip
                nop
                """.trimIndent(),
            )

            enableSettings("authorMarker")
        }
    }
