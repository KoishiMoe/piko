/*
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 *
 * See the included NOTICE file for GPLv3 §7(b) terms that apply to this code.
 */

package app.crimera.patches.twitter.misc.gallery

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableClass
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference

internal data class GalleryHostInfo(
    val holderToGalleryField: FieldReference,
    val galleryClass: MutableClass,
    val adapterField: FieldReference,
    val adapterClass: MutableClass,
)

context(patchContext: BytecodePatchContext)
internal fun resolveGalleryHost(): GalleryHostInfo {
    val onPageSelected = GalleryOnPageSelectedFingerprint.method
    val instructions = onPageSelected.implementation!!.instructions
    val holderToGalleryField =
        instructions
            .firstOrNull { it.opcode == Opcode.IGET_OBJECT }
            ?.let { (it as ReferenceInstruction).reference as? FieldReference }
            ?: throw PatchException("Failed to find gallery host field in onPageSelected")

    val galleryClassType = holderToGalleryField.type
    val galleryClass = patchContext.mutableClassDefBy { it.type == galleryClassType }

    val adapterField =
        instructions
            .filter { it.opcode == Opcode.IGET_OBJECT }
            .mapNotNull { (it as ReferenceInstruction).reference as? FieldReference }
            .firstOrNull { it.definingClass == galleryClassType }
            ?: throw PatchException("Failed to find gallery adapter field in onPageSelected")

    val adapterClass = patchContext.mutableClassDefBy { it.type == adapterField.type }
    return GalleryHostInfo(holderToGalleryField, galleryClass, adapterField, adapterClass)
}

internal object GalleryPhotoViewHolderBindFingerprint : Fingerprint(
    definingClass = "Lcom/twitter/app/gallery/",
    strings = listOf("A photo item is required!"),
)

internal object CompositeDisposableFingerprint : Fingerprint(
    definingClass = "Lio/reactivex/disposables/",
    strings = listOf("A Disposable in the disposables array is null"),
)

internal object GalleryOnPageSelectedFingerprint : Fingerprint(
    definingClass = "Lcom/twitter/app/gallery/",
    returnType = "V",
    parameters = listOf("I"),
    strings = listOf("getGalleryItems(...)"),
)
