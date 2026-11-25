/*
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 *
 * See the included NOTICE file for GPLv3 §7(b) terms that apply to this code.
 */

package app.crimera.patches.twitter.timeline.batchModeration

import app.crimera.patches.twitter.utils.Constants.SSTS_DESCRIPTOR
import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.instructions
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableClass
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference

internal object TweetDetailUpdateItemsFingerprint : Fingerprint(
    definingClass = "Lcom/twitter/tweetdetail/",
    returnType = "V",
    parameters = listOf("Lcom/twitter/model/common/collection/e;"),
    strings = listOf("arg_track_notification_render_time"),
)

internal object ActivityBackPressedFingerprint : Fingerprint(
    definingClass = "Lcom/twitter/app/common/inject/",
    name = "onBackPressed",
    returnType = "V",
    parameters = emptyList(),
    custom = { method, _ ->
        method.implementation?.instructions?.any { ins ->
            val ref = (ins as? ReferenceInstruction)?.reference as? MethodReference
            ref?.name == "goBack"
        } == true
    },
)

internal object BlockDialogPositiveFingerprint : Fingerprint(
    definingClass = "Lcom/twitter/tweet/action/legacy/",
    returnType = "V",
    parameters = listOf("Landroid/app/Dialog;", "I", "I"),
    strings = listOf("block_dialog", "block", "cancel"),
)

internal object BlockRequestFingerprint : Fingerprint(
    definingClass = "Lcom/twitter/api/legacy/request/safety/",
    strings = listOf("/1.1/blocks/create.json", "/1.1/blocks/destroy.json"),
)

internal object MuteRequestFingerprint : Fingerprint(
    definingClass = "Lcom/twitter/api/legacy/request/safety/",
    strings = listOf("/1.1/mutes/users/create.json"),
)

internal object TouchInterceptConstraintFingerprint : Fingerprint(
    definingClass = "Lcom/twitter/ui/widget/touchintercept/TouchInterceptingConstraintLayout;",
    name = "onInterceptTouchEvent",
    returnType = "Z",
    parameters = listOf("Landroid/view/MotionEvent;"),
)

internal object TouchEventConstraintFingerprint : Fingerprint(
    definingClass = "Lcom/twitter/ui/widget/touchintercept/TouchInterceptingConstraintLayout;",
    name = "onTouchEvent",
    returnType = "Z",
    parameters = listOf("Landroid/view/MotionEvent;"),
)

internal object BatchModerationConfigFingerprint : Fingerprint(
    definingClass = "Lapp/morphe/extension/twitter/patches/timeline/moderation/BatchModerationBridge;",
    name = "getPatchConfig",
    returnType = "Ljava/lang/String;",
    parameters = emptyList(),
)

private fun Opcode.isInvokeVirtual(): Boolean =
    this == Opcode.INVOKE_VIRTUAL || this == Opcode.INVOKE_VIRTUAL_RANGE

private fun Opcode.isInvokeInterface(): Boolean =
    this == Opcode.INVOKE_INTERFACE || this == Opcode.INVOKE_INTERFACE_RANGE

private fun Opcode.isInvokeStatic(): Boolean =
    this == Opcode.INVOKE_STATIC || this == Opcode.INVOKE_STATIC_RANGE

internal data class BatchModerationTargets(
    val updateItemsMethod: MutableMethod,
    val prepareMenuMethod: MutableMethod,
    val optionsSelectedMethod: MutableMethod,
    val attachAdapterMethod: MutableMethod,
    val onDestroyMethod: MutableMethod,
    val itemBindConsumerMethod: MutableMethod,
    val itemBindConsumerClass: MutableClass,
    val itemBindControllerField: FieldReference,
    val blockToastMethod: MutableMethod,
    val httpResultClassType: String,
    val httpResultSuccessFieldName: String,
    val configString: String,
)

private fun descriptorToJavaName(descriptor: String): String =
    if (descriptor.startsWith("L") && descriptor.endsWith(";")) {
        descriptor.substring(1, descriptor.length - 1).replace('/', '.')
    } else {
        descriptor
    }

context(patchContext: BytecodePatchContext)
internal fun resolveBatchModerationTargets(): BatchModerationTargets {
    val w0Class = TweetDetailUpdateItemsFingerprint.classDef
    val updateItemsMethod = TweetDetailUpdateItemsFingerprint.method

    val prepareMenuMethod =
        w0Class.methods.firstOrNull {
            it.returnType == "Z" && it.parameters.size == 2 && it.parameters[1].toString() == "Landroid/view/Menu;"
        } ?: throw PatchException("Failed to resolve TweetDetail onCreateOptionsMenu")

    val optionsSelectedMethod =
        w0Class.methods.firstOrNull {
            it.returnType == "Z" && it.parameters.size == 1 && it.parameters[0].toString() == "Landroid/view/MenuItem;"
        } ?: throw PatchException("Failed to resolve TweetDetail onOptionsItemSelected")

    val attachAdapterMethod =
        w0Class.methods.firstOrNull {
            it.returnType == "V" && it.parameters.size == 1 &&
                it.parameters[0].toString().startsWith("Lcom/twitter/ui/adapters/itembinders/")
        } ?: throw PatchException("Failed to resolve TweetDetail adapter attach method")

    val onDestroyMethod =
        w0Class.methods.firstOrNull { method ->
            method.returnType == "V" && method.parameters.isEmpty() &&
                method.implementation?.instructions?.any { ins ->
                    val ref = (ins as? ReferenceInstruction)?.reference as? MethodReference
                    ref?.definingClass == "Lio/reactivex/disposables/b;" && ref.name == "dispose"
                } == true
        } ?: throw PatchException("Failed to resolve TweetDetail onDestroy method")

    val c0Class =
        w0Class.superclass?.let { superType ->
            patchContext.mutableClassDefBy(superType)
        } ?: throw PatchException("Failed to resolve c0 superclass")

    val a0Class =
        c0Class.superclass?.let { superType ->
            patchContext.mutableClassDefBy(superType)
        } ?: throw PatchException("Failed to resolve a0 superclass")

    // Controller / host / cache / adapter fields on w0 -> c0 -> a0
    val ctrlActivityField =
        a0Class.fields.firstOrNull { it.type == "Lcom/twitter/app/common/inject/p;" }
            ?: throw PatchException("Failed to resolve controller activity field")

    val ctrlViewHostField =
        a0Class.fields.firstOrNull { it.type == "Lcom/twitter/app/legacy/list/i0;" }
            ?: throw PatchException("Failed to resolve controller viewHost field")

    val viewHostClass = patchContext.mutableClassDefBy(ctrlViewHostField.type)

    var hostRecyclerHelperField: com.android.tools.smali.dexlib2.iface.Field? = null
    var recyclerHelperClass: MutableClass? = null
    var recyclerHelperViewField: com.android.tools.smali.dexlib2.iface.Field? = null
    for (field in viewHostClass.fields) {
        if (!field.type.startsWith("L")) continue
        val candidate = patchContext.classDefByOrNull(field.type) ?: continue
        val rvFields = candidate.fields.filter { it.type == "Landroidx/recyclerview/widget/RecyclerView;" }
        if (rvFields.size == 1) {
            hostRecyclerHelperField = field
            recyclerHelperClass = patchContext.mutableClassDefBy(candidate.type)
            recyclerHelperViewField = rvFields[0]
            break
        }
    }
    if (hostRecyclerHelperField == null || recyclerHelperClass == null || recyclerHelperViewField == null) {
        throw PatchException("Failed to resolve RecyclerView helper fields on list host")
    }

    val userCacheType = "Lcom/twitter/cache/twitteruser/a;"
    val ctrlUserCacheField =
        c0Class.fields.firstOrNull { it.type == userCacheType }
            ?: throw PatchException("Failed to resolve userCache field on c0")
    val userCacheClass = patchContext.mutableClassDefBy(userCacheType)
    val userCacheHasFlagMethod =
        userCacheClass.methods.firstOrNull { method ->
            method.returnType == "Z" &&
                method.parameters.map { it.toString() } == listOf("I", "J") &&
                method.implementation?.instructions?.any {
                    it.opcode == Opcode.AND_INT_2ADDR || it.opcode == Opcode.AND_INT
                } == true
        } ?: throw PatchException("Failed to resolve userCache hasFlag method")

    val adapterType = attachAdapterMethod.parameters[0].toString()
    val ctrlAdapterField =
        c0Class.fields.firstOrNull { it.type == adapterType }
            ?: throw PatchException("Failed to resolve adapter field on c0")

    val ctrlItemsMethod =
        c0Class.methods.firstOrNull {
            it.parameters.isEmpty() && it.returnType == "Lcom/twitter/model/common/collection/e;"
        } ?: throw PatchException("Failed to resolve getItems method on c0")

    // Nav bar -> Toolbar
    val navClassType = prepareMenuMethod.parameters[0].toString()
    val navClass = patchContext.mutableClassDefBy(navClassType)
    val navGetBarMethod =
        navClass.methods.firstOrNull { method ->
            method.parameters.isEmpty() && method.returnType.startsWith("Lcom/twitter/ui/navigation/") &&
                run {
                    val retClass = patchContext.classDefByOrNull(method.returnType)
                    retClass?.methods?.any {
                        it.parameters.isEmpty() && it.returnType == "Landroidx/appcompat/widget/Toolbar;"
                    } == true
                }
        } ?: throw PatchException("Failed to resolve nav getBar method")
    val navBarClass = patchContext.mutableClassDefBy(navGetBarMethod.returnType)
    val barGetToolbarMethod =
        navBarClass.methods.firstOrNull {
            it.parameters.isEmpty() && it.returnType == "Landroidx/appcompat/widget/Toolbar;"
        } ?: throw PatchException("Failed to resolve bar getView() Toolbar method")

    // Focal helper & collection methods from w0.U
    val updateInsns = updateItemsMethod.implementation!!.instructions.toList()
    val focalHelperFieldRef =
        updateInsns
            .filter { it.opcode == Opcode.IGET_OBJECT }
            .mapNotNull { (it as ReferenceInstruction).reference as? FieldReference }
            .firstOrNull { it.definingClass == w0Class.type && it.type.startsWith("Lcom/twitter/tweetdetail/") }
            ?: throw PatchException("Failed to resolve focal helper field in w0.U")

    val focalHelperClass = patchContext.mutableClassDefBy(focalHelperFieldRef.type)

    val focalFindCall =
        updateInsns
            .filter { it.opcode.isInvokeInterface() }
            .mapNotNull { (it as ReferenceInstruction).reference as? MethodReference }
            .firstOrNull {
                it.parameterTypes.map { p -> p.toString() } == listOf("Lcom/twitter/model/common/collection/e;")
            } ?: throw PatchException("Failed to resolve focal finder interface call in w0.U")

    val timelineTweetType = focalFindCall.returnType
    val timelineTweetClass = patchContext.mutableClassDefBy(timelineTweetType)
    val focalHelperItemField =
        focalHelperClass.fields.firstOrNull { it.type == timelineTweetType }
            ?: throw PatchException("Failed to resolve focal item field on e1")

    val timelineTweetCoreField =
        timelineTweetClass.fields.firstOrNull { it.type == "Lcom/twitter/model/core/e;" }
            ?: throw PatchException("Failed to resolve tweet core field on timeline tweet")

    // Find concrete implementation of focalFinder interface (`h1`) that calls `h.f` and `collection.getSize()`
    var focalImplInsns: List<com.android.tools.smali.dexlib2.iface.instruction.Instruction>? = null
    patchContext.classDefForEach { cd ->
        if (focalImplInsns == null && cd.interfaces.contains(focalFindCall.definingClass)) {
            val m =
                cd.methods.firstOrNull {
                    it.name == focalFindCall.name &&
                        it.parameters.map { p -> p.toString() } == listOf("Lcom/twitter/model/common/collection/e;")
                }
            val insns = m?.implementation?.instructions?.toList()
            if (insns != null &&
                insns.any { ins ->
                    ins.opcode.isInvokeStatic() &&
                        ((ins as? ReferenceInstruction)?.reference as? MethodReference)?.let { ref ->
                            ref.parameterTypes.map { p -> p.toString() } ==
                                listOf("Lcom/twitter/model/common/collection/e;", "I") &&
                                ref.returnType == "Z"
                        } == true
                }
            ) {
                focalImplInsns = insns
            }
        }
    }
    val resolvedFocalImplInsns =
        focalImplInsns ?: throw PatchException("Failed to find concrete focal finder implementation")
    val collectionSizeCall =
        resolvedFocalImplInsns
            .filter { it.opcode.isInvokeVirtual() }
            .mapNotNull { (it as ReferenceInstruction).reference as? MethodReference }
            .firstOrNull {
                it.definingClass == "Lcom/twitter/model/common/collection/e;" &&
                    it.parameterTypes.isEmpty() && it.returnType == "I"
            } ?: throw PatchException("Failed to resolve collection getSize()")

    val collectionGetCall =
        resolvedFocalImplInsns
            .filter { it.opcode.isInvokeVirtual() }
            .mapNotNull { (it as ReferenceInstruction).reference as? MethodReference }
            .firstOrNull {
                it.definingClass == "Lcom/twitter/model/common/collection/e;" &&
                    it.parameterTypes.map { p -> p.toString() } == listOf("I") &&
                    it.returnType == "Ljava/lang/Object;"
            } ?: throw PatchException("Failed to resolve collection get(int)")

    val isFocalStaticCall =
        resolvedFocalImplInsns
            .filter { it.opcode.isInvokeStatic() }
            .mapNotNull { (it as ReferenceInstruction).reference as? MethodReference }
            .firstOrNull {
                it.parameterTypes.map { p -> p.toString() } == listOf("Lcom/twitter/model/common/collection/e;", "I") &&
                    it.returnType == "Z"
            } ?: throw PatchException("Failed to resolve isFocal static helper call")

    // Bind consumer (`m0`) from attachAdapterMethod (`w0.w0`)
    val attachInsns = attachAdapterMethod.implementation!!.instructions.toList()
    val itemBindConsumerType =
        attachInsns
            .filter { it.opcode == Opcode.NEW_INSTANCE }
            .mapNotNull { (it as ReferenceInstruction).reference as? TypeReference }
            .map { it.type }
            .firstOrNull { type ->
                val cd = patchContext.classDefByOrNull(type)
                cd != null && cd.fields.any { it.type == w0Class.type }
            } ?: throw PatchException("Failed to resolve item bind consumer class m0")

    val itemBindConsumerClass = patchContext.mutableClassDefBy(itemBindConsumerType)
    val itemBindControllerFieldRaw =
        itemBindConsumerClass.fields.firstOrNull { it.type == w0Class.type }
            ?: throw PatchException("Failed to resolve controller field on m0")
    val itemBindConsumerMethod =
        itemBindConsumerClass.methods.firstOrNull {
            it.name != "<init>" && it.returnType == "V" &&
                it.parameters.map { p -> p.toString() } == listOf("Ljava/lang/Object;")
        } ?: throw PatchException("Failed to resolve m0 accept(Object)")

    val consumerInsns = itemBindConsumerMethod.implementation!!.instructions.toList()
    val bindEventType =
        consumerInsns
            .filter { it.opcode == Opcode.CHECK_CAST }
            .mapNotNull { (it as ReferenceInstruction).reference as? TypeReference }
            .firstOrNull()?.type
            ?: throw PatchException("Failed to resolve bind event type e\$b")
    val bindEventClass = patchContext.mutableClassDefBy(bindEventType)

    var bindEventHolderField: com.android.tools.smali.dexlib2.iface.Field? = null
    var viewHolderClass: MutableClass? = null
    var holderGetViewMethod: com.android.tools.smali.dexlib2.iface.Method? = null
    for (f in bindEventClass.fields) {
        if (!f.type.startsWith("L")) continue
        val cd = patchContext.classDefByOrNull(f.type) ?: continue
        val vm = cd.methods.firstOrNull { it.parameters.isEmpty() && it.returnType == "Landroid/view/View;" }
        if (vm != null) {
            bindEventHolderField = f
            viewHolderClass = patchContext.mutableClassDefBy(cd.type)
            holderGetViewMethod = vm
            break
        }
    }
    if (bindEventHolderField == null || viewHolderClass == null || holderGetViewMethod == null) {
        throw PatchException("Failed to resolve bind event viewHolder & getView")
    }
    val bindEventItemField =
        bindEventClass.fields.firstOrNull { it.type == "Ljava/lang/Object;" }
            ?: throw PatchException("Failed to resolve bind event item field")

    // Safety request / HTTP / tweet core / cache methods from BlockDialogPositiveFingerprint (k1.y1)
    val blockDlgMethod = BlockDialogPositiveFingerprint.method
    val blockDlgInsns = blockDlgMethod.implementation!!.instructions.toList()

    val blockReqClass = BlockRequestFingerprint.classDef
    val muteReqClass = MuteRequestFingerprint.classDef

    val httpControllerGetCall =
        blockDlgInsns
            .filter { it.opcode.isInvokeStatic() }
            .mapNotNull { (it as ReferenceInstruction).reference as? MethodReference }
            .firstOrNull { it.parameterTypes.isEmpty() && it.returnType.startsWith("Lcom/twitter/async/http/") }
            ?: throw PatchException("Failed to resolve async http controller getter")

    val requestSetListenerCall =
        blockDlgInsns
            .filter { it.opcode.isInvokeVirtual() }
            .mapNotNull { (it as ReferenceInstruction).reference as? MethodReference }
            .firstOrNull {
                it.parameterTypes.size == 1 && it.returnType == "V" &&
                    it.parameterTypes[0].toString().startsWith("Lcom/twitter/async/operation/")
            } ?: throw PatchException("Failed to resolve request setListener method")

    val httpControllerEnqueueCall =
        blockDlgInsns
            .filter { it.opcode.isInvokeVirtual() }
            .mapNotNull { (it as ReferenceInstruction).reference as? MethodReference }
            .firstOrNull {
                it.definingClass == httpControllerGetCall.returnType &&
                    it.parameterTypes.size == 1 &&
                    it.returnType == it.parameterTypes[0].toString()
            } ?: throw PatchException("Failed to resolve http controller enqueue method")

    val tweetClassType = "Lcom/twitter/model/core/e;"
    val tweetClass = patchContext.mutableClassDefBy(tweetClassType)

    val tweetAuthorIdCall =
        blockDlgInsns
            .filter { it.opcode.isInvokeVirtual() }
            .mapNotNull { (it as ReferenceInstruction).reference as? MethodReference }
            .firstOrNull { it.definingClass == tweetClassType && it.parameterTypes.isEmpty() && it.returnType == "J" }
            ?: throw PatchException("Failed to resolve tweet getAuthorId method")

    val tweetAdContentField =
        blockDlgInsns
            .filter { it.opcode == Opcode.IGET_OBJECT }
            .mapNotNull { (it as ReferenceInstruction).reference as? FieldReference }
            .firstOrNull { it.definingClass == tweetClassType && it.type.startsWith("Lcom/twitter/model/core/entity/ad/") }
            ?: throw PatchException("Failed to resolve tweet adContent field")

    // Callback class l1 instantiated in k1.y1 (has field of tweetClassType and field of LegacyActions)
    val l1Type =
        blockDlgInsns
            .filter { it.opcode == Opcode.NEW_INSTANCE }
            .mapNotNull { (it as ReferenceInstruction).reference as? TypeReference }
            .map { it.type }
            .firstOrNull { type ->
                type != blockReqClass.type &&
                    patchContext.classDefByOrNull(type)?.fields?.any { it.type == tweetClassType } == true
            } ?: throw PatchException("Failed to resolve l1 block callback class")

    val l1Class = patchContext.mutableClassDefBy(l1Type)
    val l1CompleteMethod =
        l1Class.methods.firstOrNull {
            it.name != "<init>" && it.returnType == "V" && it.parameters.size == 1
        } ?: throw PatchException("Failed to resolve l1 onComplete method")
    val l1Insns = l1CompleteMethod.implementation!!.instructions.toList()

    val tweetAuthorHandleCall =
        l1Insns
            .filter { it.opcode.isInvokeVirtual() }
            .mapNotNull { (it as ReferenceInstruction).reference as? MethodReference }
            .firstOrNull {
                it.definingClass == tweetClassType && it.parameterTypes.isEmpty() && it.returnType == "Ljava/lang/String;"
            } ?: throw PatchException("Failed to resolve tweet handle method")

    val requestGetResultCall =
        l1Insns
            .filter { it.opcode.isInvokeVirtual() }
            .mapNotNull { (it as ReferenceInstruction).reference as? MethodReference }
            .firstOrNull {
                it.parameterTypes.isEmpty() && it.returnType.startsWith("Lcom/twitter/async/http/")
            } ?: throw PatchException("Failed to resolve request getResult() method")

    val n1HandlerCall =
        l1Insns
            .filter { it.opcode.isInvokeVirtual() }
            .mapNotNull { (it as ReferenceInstruction).reference as? MethodReference }
            .firstOrNull { method ->
                method.parameterTypes.map { it.toString() } == listOf(
                    "J",
                    "Ljava/lang/String;",
                    requestGetResultCall.returnType,
                    userCacheType,
                )
            } ?: throw PatchException("Failed to resolve n1 block handler method")

    val n1HandlerClass = patchContext.mutableClassDefBy(n1HandlerCall.definingClass)
    val n1HandlerMethod =
        n1HandlerClass.methods.firstOrNull {
            it.name == n1HandlerCall.name &&
                it.parameters.map { p -> p.toString() } == n1HandlerCall.parameterTypes.map { p -> p.toString() }
        } ?: throw PatchException("Failed to open n1 block handler body")
    val n1HandlerInsns = n1HandlerMethod.implementation!!.instructions.toList()

    val httpResultSuccessField =
        n1HandlerInsns
            .filter { it.opcode == Opcode.IGET_BOOLEAN }
            .mapNotNull { (it as ReferenceInstruction).reference as? FieldReference }
            .firstOrNull { it.definingClass == requestGetResultCall.returnType }
            ?: throw PatchException("Failed to resolve httpResult success field")

    val userCacheAddFlagsCall =
        n1HandlerInsns
            .filter { it.opcode.isInvokeVirtual() }
            .mapNotNull { (it as ReferenceInstruction).reference as? MethodReference }
            .firstOrNull {
                it.definingClass == userCacheType && it.parameterTypes.map { p -> p.toString() } == listOf("I", "J")
            } ?: throw PatchException("Failed to resolve userCache addFlags method")

    val blockToastMethod =
        blockReqClass.methods.firstOrNull { method ->
            method.returnType == "V" &&
                method.parameters.map { it.toString() } == listOf(requestGetResultCall.returnType) &&
                method.implementation?.instructions?.any { ins ->
                    val ref = (ins as? ReferenceInstruction)?.reference as? MethodReference
                    ref?.definingClass == "Lcom/twitter/util/android/a0;"
                } == true
        } ?: throw PatchException("Failed to resolve BlockRequest toast method")

    // Tweet core data -> author -> friendship flags + getId() + getName()
    val tweetCoreDataField =
        tweetClass.fields.firstOrNull { it.type == "Lcom/twitter/model/core/d;" }
            ?: throw PatchException("Failed to resolve tweet core data field")
    val tweetCoreDataClass = patchContext.mutableClassDefBy(tweetCoreDataField.type)
    val tweetDataAuthorField =
        tweetCoreDataClass.fields.firstOrNull { it.type == "Lcom/twitter/model/core/z;" }
            ?: throw PatchException("Failed to resolve tweet author data field")
    val tweetAuthorClass = patchContext.mutableClassDefBy(tweetDataAuthorField.type)
    val tweetAuthorFriendshipField =
        tweetAuthorClass.fields.firstOrNull {
            it.type == "I" && (it.accessFlags and AccessFlags.FINAL.value) == 0
        } ?: throw PatchException("Failed to resolve tweet author friendship field")

    val tweetGetIdMethod =
        tweetClass.methods.firstOrNull {
            it.name == "getId" && it.parameters.isEmpty() && it.returnType == "J"
        } ?: throw PatchException("Failed to resolve Tweet getId()")

    val tweetAuthorNameMethod =
        tweetClass.methods.firstOrNull {
            it.name == "getName" && it.parameters.isEmpty() && it.returnType == "Ljava/lang/String;"
        } ?: throw PatchException("Failed to resolve Tweet getName()")

    val itemBindControllerRef =
        com.android.tools.smali.dexlib2.immutable.reference.ImmutableFieldReference(
            itemBindControllerFieldRaw.definingClass,
            itemBindControllerFieldRaw.name,
            itemBindControllerFieldRaw.type,
        )

    val packedConfig =
        buildString {
            appendLine("ctrlClass=${descriptorToJavaName(w0Class.type)}")
            appendLine("viewHostClass=${descriptorToJavaName(viewHostClass.type)}")
            appendLine("recyclerHelperClass=${descriptorToJavaName(recyclerHelperClass.type)}")
            appendLine("focalHelperClass=${descriptorToJavaName(focalHelperClass.type)}")
            appendLine("navClass=${descriptorToJavaName(navClass.type)}")
            appendLine("navBarClass=${descriptorToJavaName(navBarClass.type)}")
            appendLine("bindEventClass=${descriptorToJavaName(bindEventClass.type)}")
            appendLine("viewHolderClass=${descriptorToJavaName(viewHolderClass.type)}")
            appendLine("collectionClass=com.twitter.model.common.collection.e")
            appendLine("focalFinderClass=${descriptorToJavaName(isFocalStaticCall.definingClass)}")
            appendLine("timelineTweetClass=${descriptorToJavaName(timelineTweetClass.type)}")
            appendLine("tweetClass=${descriptorToJavaName(tweetClass.type)}")
            appendLine("tweetCoreDataClass=${descriptorToJavaName(tweetCoreDataClass.type)}")
            appendLine("tweetDataAuthorClass=${descriptorToJavaName(tweetAuthorClass.type)}")
            appendLine("adContentClass=${descriptorToJavaName(tweetAdContentField.type)}")
            appendLine("blockReqClass=${descriptorToJavaName(blockReqClass.type)}")
            appendLine("muteReqClass=${descriptorToJavaName(muteReqClass.type)}")
            appendLine("httpControllerClass=${descriptorToJavaName(httpControllerGetCall.returnType)}")
            appendLine("requestBaseClass=${descriptorToJavaName(requestSetListenerCall.definingClass)}")
            appendLine("asyncHttpReqClass=${descriptorToJavaName(httpControllerEnqueueCall.parameterTypes[0].toString())}")
            appendLine("asyncOpClass=${descriptorToJavaName(l1CompleteMethod.parameters[0].toString())}")
            appendLine("httpResultClass=${descriptorToJavaName(requestGetResultCall.returnType)}")
            appendLine("listenerInterface=${descriptorToJavaName(requestSetListenerCall.parameterTypes[0].toString())}")
            appendLine("userCacheClass=${descriptorToJavaName(userCacheType)}")

            appendLine("ctrlActivityField=${ctrlActivityField.name}")
            appendLine("ctrlUserCacheField=${ctrlUserCacheField.name}")
            appendLine("ctrlViewHostField=${ctrlViewHostField.name}")
            appendLine("hostRecyclerHelperField=${hostRecyclerHelperField.name}")
            appendLine("recyclerHelperViewField=${recyclerHelperViewField.name}")
            appendLine("ctrlAdapterField=${ctrlAdapterField.name}")
            appendLine("ctrlFocalHelperField=${focalHelperFieldRef.name}")
            appendLine("focalHelperItemField=${focalHelperItemField.name}")
            appendLine("bindEventHolderField=${bindEventHolderField.name}")
            appendLine("bindEventItemField=${bindEventItemField.name}")
            appendLine("timelineTweetCoreField=${timelineTweetCoreField.name}")
            appendLine("tweetCoreDataField=${tweetCoreDataField.name}")
            appendLine("tweetDataAuthorField=${tweetDataAuthorField.name}")
            appendLine("tweetAuthorFriendshipField=${tweetAuthorFriendshipField.name}")
            appendLine("tweetAdContentField=${tweetAdContentField.name}")
            appendLine("httpResultSuccessField=${httpResultSuccessField.name}")

            appendLine("ctrlItemsMethod=${ctrlItemsMethod.name}")
            appendLine("navGetBarMethod=${navGetBarMethod.name}")
            appendLine("barGetToolbarMethod=${barGetToolbarMethod.name}")
            appendLine("holderGetViewMethod=${holderGetViewMethod.name}")
            appendLine("collectionSizeMethod=${collectionSizeCall.name}")
            appendLine("collectionGetMethod=${collectionGetCall.name}")
            appendLine("focalFinderMethod=${isFocalStaticCall.name}")
            appendLine("tweetGetIdMethod=${tweetGetIdMethod.name}")
            appendLine("tweetAuthorIdMethod=${tweetAuthorIdCall.name}")
            appendLine("tweetAuthorHandleMethod=${tweetAuthorHandleCall.name}")
            appendLine("tweetAuthorNameMethod=${tweetAuthorNameMethod.name}")
            appendLine("httpControllerGetMethod=${httpControllerGetCall.name}")
            appendLine("httpControllerEnqueueMethod=${httpControllerEnqueueCall.name}")
            appendLine("requestSetListenerMethod=${requestSetListenerCall.name}")
            appendLine("requestGetResultMethod=${requestGetResultCall.name}")
            appendLine("userCacheAddFlagsMethod=${userCacheAddFlagsCall.name}")
            appendLine("userCacheHasFlagMethod=${userCacheHasFlagMethod.name}")
            appendLine("listenerCompleteMethod=${l1CompleteMethod.name}")
        }

    return BatchModerationTargets(
        updateItemsMethod = updateItemsMethod,
        prepareMenuMethod = prepareMenuMethod,
        optionsSelectedMethod = optionsSelectedMethod,
        attachAdapterMethod = attachAdapterMethod,
        onDestroyMethod = onDestroyMethod,
        itemBindConsumerMethod = itemBindConsumerMethod,
        itemBindConsumerClass = itemBindConsumerClass,
        itemBindControllerField = itemBindControllerRef,
        blockToastMethod = blockToastMethod,
        httpResultClassType = requestGetResultCall.returnType,
        httpResultSuccessFieldName = httpResultSuccessField.name,
        configString = packedConfig,
    )
}
