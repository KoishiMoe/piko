/*
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 *
 * See the included NOTICE file for GPLv3 §7(b) terms that apply to this code.
 */

package app.morphe.extension.twitter.patches.timeline.history;

import androidx.annotation.NonNull;

/**
 * Immutable snapshot of a post observed in the view layer.
 */
public final class TimelineHistoryEntry {
    public static final int SOURCE_TIMELINE = 0;
    public static final int SOURCE_QUOTE = 1;
    public static final int SOURCE_FOCAL = 2;

    public final long tweetId;
    @NonNull
    public final String accountId;
    @NonNull
    public final String authorHandle;
    @NonNull
    public final String authorName;
    @NonNull
    public final String text;
    public final int mediaCount;
    public final int sourceType;
    public final boolean isRetweet;
    public final long firstSeenAt;
    public final long lastSeenAt;

    public TimelineHistoryEntry(
            long tweetId,
            @NonNull String accountId,
            @NonNull String authorHandle,
            @NonNull String authorName,
            @NonNull String text,
            int mediaCount,
            int sourceType,
            boolean isRetweet,
            long firstSeenAt,
            long lastSeenAt
    ) {
        this.tweetId = tweetId;
        this.accountId = accountId;
        this.authorHandle = authorHandle;
        this.authorName = authorName;
        this.text = text;
        this.mediaCount = Math.max(0, mediaCount);
        this.sourceType = sourceType;
        this.isRetweet = isRetweet;
        this.firstSeenAt = firstSeenAt;
        this.lastSeenAt = lastSeenAt;
    }

    /** Returns an in-app resolvable canonical status URL for this post. */
    @NonNull
    public String buildTweetUrl() {
        String pathUser = authorHandle.isEmpty() ? "i" : authorHandle;
        return "https://x.com/" + pathUser + "/status/" + tweetId;
    }

    /** Returns an in-app resolvable profile URL for this post author, or empty if unknown. */
    @NonNull
    public String buildAuthorUrl() {
        if (authorHandle.isEmpty()) {
            return "";
        }
        return "https://x.com/" + authorHandle;
    }
}
