/*
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 *
 * See the included NOTICE file for GPLv3 §7(b) terms that apply to this code.
 */

package app.crimera.patches.twitter.timeline.history

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.methodCall

internal object HeaderViewDelegateBinderFingerprint : Fingerprint(
    definingClass = "Lcom/twitter/tweetview/core/ui/tweetheader/TweetHeaderViewDelegateBinder;",
    parameters = listOf(
        "Lcom/twitter/model/core/e;",
        "Lcom/twitter/tweetview/core/ui/tweetheader/c;",
        "Ljava/lang/String;",
        "Ljava/lang/String;",
    ),
    returnType = "V",
)

internal object FocalHeaderViewDelegateBinderFingerprint : Fingerprint(
    definingClass = "Lcom/twitter/tweetview/focal/ui/tweetheader/FocalTweetHeaderViewDelegateBinder;",
    parameters = listOf(
        "Lcom/twitter/model/core/e;",
        "Lcom/twitter/tweetview/core/ui/tweetheader/c;",
        "Ljava/lang/String;",
        "Ljava/lang/String;",
    ),
    returnType = "V",
)

internal object QuoteTweetViewBindFingerprint : Fingerprint(
    definingClass = "Lcom/twitter/tweetview/core/QuoteView;",
    parameters = listOf(
        "Lcom/twitter/model/core/e;",
        "Lcom/twitter/ui/renderable/i;",
        "Ljava/lang/Integer;",
    ),
    returnType = "V",
)

internal object TimelineFetchTriggerFingerprint : Fingerprint(
    definingClass = "Lcom/twitter/app/common/timeline/c0;",
    parameters = listOf("I"),
    returnType = "V",
    filters = listOf(
        methodCall(
            definingClass = "Lcom/twitter/model/timeline/f0;",
            name = "<init>",
            parameters = listOf("I"),
        ),
    ),
)
