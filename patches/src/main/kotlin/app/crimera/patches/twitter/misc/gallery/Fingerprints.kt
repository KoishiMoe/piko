/*
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 *
 * See the included NOTICE file for GPLv3 §7(b) terms that apply to this code.
 */

package app.crimera.patches.twitter.misc.gallery

import app.morphe.patcher.Fingerprint

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
