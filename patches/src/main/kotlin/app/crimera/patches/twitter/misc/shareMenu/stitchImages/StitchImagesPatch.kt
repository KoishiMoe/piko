/*
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 *
 * See the included NOTICE file for GPLv3 §7(b) terms that apply to this code.
 */

package app.crimera.patches.twitter.misc.shareMenu.stitchImages

import app.crimera.patches.twitter.entity.decoder.TWEET_ITEM_CLASS_NAME
import app.crimera.patches.twitter.entity.entityGenerator
import app.crimera.patches.twitter.misc.gallery.resolveGalleryHost
import app.crimera.patches.twitter.misc.settings.settingsPatch
import app.crimera.patches.twitter.misc.shareMenu.hooks.shareMenuButtonInjection
import app.crimera.patches.twitter.misc.shareMenu.hooks.shareMenuButtonOnClickHook
import app.crimera.patches.twitter.utils.Constants.COMPATIBILITY_X
import app.crimera.patches.twitter.utils.Constants.PATCHES_DESCRIPTOR
import app.crimera.patches.twitter.utils.versionCheckPatch
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.extensions.InstructionExtensions.instructions
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.smali.ExternalLabel
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction

private const val GALLERY_HELPER_DESCRIPTOR = "${PATCHES_DESCRIPTOR}/gallery/GalleryHelper;"

@Suppress("unused")
val stitchImagesPatch =
    bytecodePatch(
        name = "Stitch images",
        description = "Adds a seamless horizontal/vertical stitched viewer for multi-image posts in the gallery and share menu",
    ) {
        compatibleWith(COMPATIBILITY_X)
        dependsOn(
            settingsPatch,
            entityGenerator,
            versionCheckPatch,
            shareMenuButtonOnClickHook,
        )

        execute {
            // 1. Add Stitch images action to post Share menu.
            shareMenuButtonInjection(
                actionName = "StitchImages",
                prefFunctionName = "enableStitchViewer",
                stringId = "piko_title_stitch_viewer",
                iconId = "ic_vector_image_collection",
                statusFunctionName = "stitchViewer",
            )

            // 2. Hook GalleryActivity host controller to expose Stitch images in the gallery toolbar.
            val (_, galleryClass, adapterField, adapterClass) = resolveGalleryHost()

            val itemsListField =
                adapterClass.fields.firstOrNull { it.type == "Ljava/util/List;" }
                    ?: throw PatchException("Failed to find gallery items List field on adapter")

            val tweetField =
                galleryClass.fields.firstOrNull { it.type == TWEET_ITEM_CLASS_NAME }
                    ?: throw PatchException("Failed to find tweet field on gallery host")

            val onCreateMenuMethod =
                galleryClass.methods.firstOrNull {
                    it.parameters.map { p -> p.toString() } ==
                        listOf("Lcom/twitter/ui/navigation/f;", "Landroid/view/Menu;") &&
                        it.returnType == "Z"
                } ?: throw PatchException("Failed to find gallery onCreateMenu method")

            val onPrepareMenuMethod =
                galleryClass.methods.firstOrNull {
                    it.parameters.map { p -> p.toString() } ==
                        listOf("Lcom/twitter/ui/navigation/f;") &&
                        it.returnType == "I"
                } ?: throw PatchException("Failed to find gallery onPrepareMenu method")

            val onMenuItemSelectedMethod =
                galleryClass.methods.firstOrNull {
                    it.parameters.map { p -> p.toString() } ==
                        listOf("Landroid/view/MenuItem;") &&
                        it.returnType == "Z"
                } ?: throw PatchException("Failed to find gallery onMenuItemSelected method")

            onCreateMenuMethod.apply {
                val returnIndices =
                    instructions
                        .mapIndexedNotNull { idx, ins -> if (ins.opcode == Opcode.RETURN) idx else null }
                        .sortedDescending()
                if (returnIndices.isEmpty()) {
                    throw PatchException("Failed to find RETURN in gallery onCreateMenu")
                }
                returnIndices.forEach { idx ->
                    addInstructions(
                        idx,
                        """
                            invoke-static/range {p2 .. p2}, $GALLERY_HELPER_DESCRIPTOR->onCreateGalleryMenu(Landroid/view/Menu;)V
                        """.trimIndent(),
                    )
                }
            }

            onPrepareMenuMethod.apply {
                val returnIndices =
                    instructions
                        .mapIndexedNotNull { idx, ins -> if (ins.opcode == Opcode.RETURN) idx else null }
                        .sortedDescending()
                if (returnIndices.isEmpty()) {
                    throw PatchException("Failed to find RETURN in gallery onPrepareMenu")
                }
                returnIndices.forEach { idx ->
                    val retReg = (getInstruction(idx) as OneRegisterInstruction).registerA
                    val scratch = (0..15).filter { it != retReg }.take(3)
                    val r0 = scratch[0]
                    val r1 = scratch[1]
                    val r2 = scratch[2]
                    addInstructionsWithLabels(
                        idx,
                        """
                            move-object/from16 v$r0, p0
                            iget-object v$r1, v$r0, $adapterField
                            if-eqz v$r1, :piko_no_adapter_$idx
                            iget-object v$r1, v$r1, $itemsListField
                            :piko_no_adapter_$idx
                            iget-object v$r2, v$r0, $tweetField
                            move-object/from16 v$r0, p1
                            invoke-static {v$r0, v$r1, v$r2}, $GALLERY_HELPER_DESCRIPTOR->onPrepareGalleryMenu(Ljava/lang/Object;Ljava/util/List;Ljava/lang/Object;)V
                        """.trimIndent(),
                    )
                }
            }

            onMenuItemSelectedMethod.apply {
                val originalStart = getInstruction(0)
                addInstructionsWithLabels(
                    0,
                    """
                        move-object/from16 v0, p0
                        iget-object v1, v0, $adapterField
                        if-eqz v1, :no_adapter
                        iget-object v1, v1, $itemsListField
                        :no_adapter
                        iget-object v2, v0, $tweetField
                        move-object/from16 v0, p1
                        invoke-static {v0, v1, v2}, $GALLERY_HELPER_DESCRIPTOR->onGalleryMenuItemSelected(Landroid/view/MenuItem;Ljava/util/List;Ljava/lang/Object;)Z
                        move-result v0
                        if-eqz v0, :original
                        const/4 v0, 0x1
                        return v0
                    """.trimIndent(),
                    ExternalLabel("original", originalStart),
                )
            }
        }
    }
