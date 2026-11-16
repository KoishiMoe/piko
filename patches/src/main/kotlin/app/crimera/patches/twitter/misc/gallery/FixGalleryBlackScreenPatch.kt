/*
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 *
 * See the included NOTICE file for GPLv3 §7(b) terms that apply to this code.
 */

package app.crimera.patches.twitter.misc.gallery

import app.crimera.patches.twitter.misc.settings.settingsPatch
import app.crimera.patches.twitter.utils.Constants.COMPATIBILITY_X
import app.crimera.patches.twitter.utils.Constants.PATCHES_DESCRIPTOR
import app.crimera.patches.twitter.utils.Constants.PREF_DESCRIPTOR
import app.crimera.patches.twitter.utils.enableSettings
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.extensions.InstructionExtensions.instructions
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.getReference
import app.morphe.patcher.util.smali.ExternalLabel
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

private const val GALLERY_HELPER_DESCRIPTOR = "${PATCHES_DESCRIPTOR}/gallery/GalleryHelper;"
private const val TOOLBAR_TYPE = "Landroidx/appcompat/widget/Toolbar;"

@Suppress("unused")
val fixGalleryBlackScreenPatch =
    bytecodePatch(
        name = "Fix gallery black screen",
        description = "Fixes 10th+ image black screen and broken tap/zoom controls in gallery, and shows a page counter",
    ) {
        compatibleWith(COMPATIBILITY_X)
        dependsOn(settingsPatch)

        execute {
            // Part A: Fix recycled photo ViewHolder stripping its FrescoMediaImageView and permanently disposing CompositeDisposable.
            val compositeDisposableClass = CompositeDisposableFingerprint.classDef
            val clearDisposablesMethod =
                compositeDisposableClass.methods.firstOrNull { method ->
                    !AccessFlags.CONSTRUCTOR.isSet(method.accessFlags) &&
                        !AccessFlags.STATIC.isSet(method.accessFlags) &&
                        method.returnType == "V" &&
                        method.parameters.isEmpty() &&
                        method.name != "dispose"
                } ?: throw PatchException("Failed to find CompositeDisposable clear method")

            val photoHolderClass = GalleryPhotoViewHolderBindFingerprint.classDef
            val disposablesField =
                photoHolderClass.fields.firstOrNull { it.type == compositeDisposableClass.type }
                    ?: throw PatchException("Failed to find CompositeDisposable field on gallery photo ViewHolder")

            val clearImageMethod =
                photoHolderClass.methods.firstOrNull { method ->
                    !AccessFlags.CONSTRUCTOR.isSet(method.accessFlags) &&
                        !AccessFlags.STATIC.isSet(method.accessFlags) &&
                        method.returnType == "V" &&
                        method.parameters.isEmpty() &&
                        method.implementation?.instructions?.any { ins ->
                            val ref = ins.getReference<MethodReference>()
                            ref?.definingClass == "Lcom/twitter/media/ui/image/j;" ||
                                ref?.definingClass == "Lcom/twitter/media/ui/fresco/FrescoMediaImageView;"
                        } == true
                } ?: throw PatchException("Failed to find gallery photo ViewHolder clearImage method")

            val recycleMethod =
                photoHolderClass.methods.firstOrNull { method ->
                    !AccessFlags.CONSTRUCTOR.isSet(method.accessFlags) &&
                        !AccessFlags.STATIC.isSet(method.accessFlags) &&
                        method.returnType == "V" &&
                        method.parameters.isEmpty() &&
                        method.implementation?.instructions?.any { ins ->
                            ins.getReference<MethodReference>()?.name == "removeAllViews"
                        } == true
                } ?: throw PatchException("Failed to find gallery photo ViewHolder recycle method")

            recycleMethod.apply {
                val originalStart = getInstruction(0)
                addInstructionsWithLabels(
                    0,
                    """
                        invoke-static {}, $PREF_DESCRIPTOR;->fixGalleryBlackScreen()Z
                        move-result v0
                        if-eqz v0, :original
                        invoke-virtual {p0}, $clearImageMethod
                        iget-object v0, p0, $disposablesField
                        invoke-virtual {v0}, $clearDisposablesMethod
                        return-void
                    """.trimIndent(),
                    ExternalLabel("original", originalStart),
                )
            }

            // Part B: Show centered "x / N" page counter in gallery toolbar on page selection.
            val (holderToGalleryField, galleryClass, adapterField, adapterClass) = resolveGalleryHost()
            GalleryOnPageSelectedFingerprint.method.apply {
                val getItemCountMethod =
                    adapterClass.methods.firstOrNull {
                        it.name == "getItemCount" && it.parameters.isEmpty() && it.returnType == "I"
                    } ?: throw PatchException("Failed to find getItemCount() on gallery adapter")

                val (chromeField, toolbarField) =
                    galleryClass.fields
                        .firstNotNullOfOrNull { field ->
                            val candidateClass =
                                if (field.type.startsWith("Lcom/twitter/app/gallery/")) {
                                    mutableClassDefBy { it.type == field.type }
                                } else {
                                    null
                                }
                            val toolbar = candidateClass?.fields?.firstOrNull { it.type == TOOLBAR_TYPE }
                            if (toolbar != null) field to toolbar else null
                        } ?: throw PatchException("Failed to find gallery chrome Toolbar field")

                val methodImpl =
                    implementation
                        ?: throw PatchException("Gallery onPageSelected method has no implementation")
                val parameterRegisterCount =
                    (if (AccessFlags.STATIC.isSet(accessFlags)) 0 else 1) +
                        parameters.sumOf { if (it.type == "J" || it.type == "D") 2 else 1 }
                if (methodImpl.registerCount - parameterRegisterCount < 3) {
                    throw PatchException("Not enough local registers in gallery onPageSelected")
                }

                // Inject at entry before early returns (e.g. empty adapter or unbound ViewHolder on initial open/restore).
                // Read $adapterField before $chromeField so resolveGalleryHost() keeps matching $adapterField
                // as the first IGET_OBJECT on galleryClass even if another patch runs after this one.
                val originalStart = getInstruction(0)
                addInstructionsWithLabels(
                    0,
                    """
                        move-object/from16 v0, p0
                        iget-object v1, v0, $holderToGalleryField
                        if-eqz v1, :original
                        iget-object v2, v1, $adapterField
                        iget-object v0, v1, $chromeField
                        if-eqz v0, :original
                        iget-object v0, v0, $toolbarField
                        if-eqz v0, :original
                        if-eqz v2, :skip_count
                        invoke-virtual {v2}, $getItemCountMethod
                        move-result v2
                        goto :call_counter
                        :skip_count
                        const/4 v2, 0x0
                        :call_counter
                        move/from16 v1, p1
                        invoke-static {v0, v1, v2}, $GALLERY_HELPER_DESCRIPTOR->updatePageCounter(${TOOLBAR_TYPE}II)V
                    """.trimIndent(),
                    ExternalLabel("original", originalStart),
                )
            }

            enableSettings("fixGalleryBlackScreen")
            enableSettings("galleryPageCounter")
        }
    }
