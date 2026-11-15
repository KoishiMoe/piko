/*
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 *
 * See the included NOTICE file for GPLv3 §7(b) terms that apply to this code.
 */

package app.crimera.patches.twitter.misc.authorMarker

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.InstructionLocation.MatchAfterImmediately
import app.morphe.patcher.InstructionLocation.MatchAfterWithin
import app.morphe.patcher.methodCall
import app.morphe.patcher.opcode
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode

private fun tweetBadgeFetchFilters() =
    listOf(
        methodCall(
            opcode = Opcode.INVOKE_STATIC,
            definingClass = "Lcom/twitter/ui/user/l;",
            parameters = listOf("Lcom/twitter/model/core/e;"),
            returnType = "Ljava/util/ArrayList;",
        ),
        opcode(
            opcode = Opcode.MOVE_RESULT_OBJECT,
            location = MatchAfterImmediately(),
        ),
    )

internal object TweetHeaderBindFingerprint : Fingerprint(
    definingClass = "Lcom/twitter/tweetview/core/ui/tweetheader/TweetHeaderViewDelegateBinder;",
    parameters =
        listOf(
            "Lcom/twitter/model/core/e;",
            "Lcom/twitter/tweetview/core/ui/tweetheader/c;",
            "Ljava/lang/String;",
            "Ljava/lang/String;",
        ),
    returnType = "V",
    filters = tweetBadgeFetchFilters(),
)

internal object FocalTweetHeaderBindFingerprint : Fingerprint(
    definingClass = "Lcom/twitter/tweetview/focal/ui/tweetheader/FocalTweetHeaderViewDelegateBinder;",
    parameters =
        listOf(
            "Lcom/twitter/model/core/e;",
            "Lcom/twitter/tweetview/core/ui/tweetheader/c;",
            "Ljava/lang/String;",
            "Ljava/lang/String;",
        ),
    returnType = "V",
    filters = tweetBadgeFetchFilters(),
)

internal object QuoteViewBindFingerprint : Fingerprint(
    definingClass = "Lcom/twitter/tweetview/core/QuoteView;",
    parameters =
        listOf(
            "Lcom/twitter/model/core/e;",
            "Lcom/twitter/ui/renderable/i;",
            "Ljava/lang/Integer;",
        ),
    returnType = "V",
    filters =
        listOf(
            methodCall(
                opcode = Opcode.INVOKE_DIRECT,
                definingClass = "this",
                name = "getTimestampFromQuotedTweet",
            ),
            methodCall(
                opcode = Opcode.INVOKE_STATIC,
                definingClass = "Lcom/twitter/ui/user/l;",
                parameters = listOf("Lcom/twitter/model/core/e;"),
                returnType = "Ljava/util/ArrayList;",
                location = MatchAfterWithin(4),
            ),
            opcode(
                opcode = Opcode.MOVE_RESULT_OBJECT,
                location = MatchAfterImmediately(),
            ),
        ),
)

internal object UsernameBadgeCreateFingerprint : Fingerprint(
    definingClass = "Lcom/twitter/ui/user/j\$a;",
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.STATIC),
    parameters = listOf("Landroid/view/View;", "Lcom/twitter/ui/user/k;", "I"),
    returnType = "Lcom/twitter/ui/user/j;",
    strings = listOf("view", "badgeType"),
)

internal object ProfileMenuClickFingerprint : Fingerprint(
    definingClass = "Lcom/twitter/app/profiles/",
    parameters = listOf("Landroid/view/MenuItem;"),
    returnType = "Z",
    strings = listOf(":user::share_via"),
)
