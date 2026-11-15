/*
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 *
 * See the included NOTICE file for GPLv3 §7(b) terms that apply to this code.
 */

package app.crimera.patches.twitter.misc.authorMarker

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.InstructionLocation.MatchAfterImmediately
import app.morphe.patcher.methodCall
import app.morphe.patcher.opcode
import com.android.tools.smali.dexlib2.Opcode

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
                opcode = Opcode.INVOKE_STATIC,
                definingClass = "Lcom/twitter/util/u;",
                parameters = listOf("Ljava/lang/String;"),
                returnType = "Ljava/lang/String;",
            ),
            opcode(
                opcode = Opcode.MOVE_RESULT_OBJECT,
                location = MatchAfterImmediately(),
            ),
            methodCall(
                opcode = Opcode.INVOKE_DIRECT,
                definingClass = "this",
                name = "getTimestampFromQuotedTweet",
                location = MatchAfterImmediately(),
            ),
        ),
)

internal object ProfileMenuClickFingerprint : Fingerprint(
    definingClass = "Lcom/twitter/app/profiles/",
    parameters = listOf("Landroid/view/MenuItem;"),
    returnType = "Z",
    strings = listOf(":user::share_via"),
)
