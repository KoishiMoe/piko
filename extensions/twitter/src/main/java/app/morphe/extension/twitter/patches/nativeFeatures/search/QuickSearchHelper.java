/*
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 *
 * See the included NOTICE file for GPLv3 §7(b) terms that apply to this code.
 */

package app.morphe.extension.twitter.patches.nativeFeatures.search;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.text.TextUtils;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import app.morphe.extension.crimera.PikoUtils;
import app.morphe.extension.shared.StringRef;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.twitter.Pref;
import app.morphe.extension.twitter.entity.Tweet;

/**
 * Query builder and native in-app Search launcher for post author quick searches.
 */
public final class QuickSearchHelper {

    public static final String SEARCH_ACTION = "com.twitter.android.action.SEARCH";
    public static final String SEARCH_ACTIVITY_CLASS = "com.twitter.android.search.implementation.results.SearchActivity";
    public static final int SEARCH_TYPE_LATEST = 1;
    public static final String DEFAULT_EARLY_FALLBACK_DATE = "2022-10-01";

    private static final Pattern TOKEN_PATTERN = Pattern.compile("\"([^\"]+)\"|([^\\s,、，]+)");
    private static final Pattern ISO_DATE_PATTERN = Pattern.compile("^\\d{4}-\\d{2}-\\d{2}$");

    private QuickSearchHelper() {
    }

    public static void openFromTweet(Context context, Object tweetObject) {
        try {
            Tweet tweet = new Tweet(tweetObject);
            String username = tweet.getTweetUsername();
            if (username == null) {
                showToast(context, StringRef.str("piko_author_lookup_no_username"));
                return;
            }
            String cleanHandle = stripAt(username);
            if (cleanHandle.isEmpty()) {
                showToast(context, StringRef.str("piko_author_lookup_no_username"));
                return;
            }

            long userId = -1L;
            try {
                long origId = tweet.getTweetOriginalAuthorId();
                if (origId > 0L) {
                    userId = origId;
                } else {
                    Long id = tweet.getTweetUserId();
                    if (id != null && id > 0L) {
                        userId = id;
                    }
                }
            } catch (Throwable ignored) {
            }

            Context launchContext = context != null ? context : Utils.getContext();
            if (launchContext == null) {
                return;
            }
            QuickSearchSheet.show(launchContext, userId, cleanHandle);
        } catch (Throwable t) {
            PikoUtils.logger(t);
        }
    }

    public static String stripAt(String username) {
        if (username == null) {
            return "";
        }
        String trimmed = username.trim();
        while (trimmed.startsWith("@")) {
            trimmed = trimmed.substring(1).trim();
        }
        return trimmed;
    }

    public static List<String> parseKeywordTokens(String raw) {
        List<String> tokens = new ArrayList<>();
        if (raw == null) {
            return tokens;
        }
        String trimmed = raw.trim();
        if (trimmed.isEmpty()) {
            return tokens;
        }
        Matcher matcher = TOKEN_PATTERN.matcher(trimmed);
        while (matcher.find()) {
            String quoted = matcher.group(1);
            String plain = matcher.group(2);
            if (quoted != null && !quoted.trim().isEmpty()) {
                tokens.add("\"" + quoted.trim() + "\"");
            } else if (plain != null && !plain.isEmpty() && !"OR".equalsIgnoreCase(plain)) {
                tokens.add(plain);
            }
        }
        return tokens;
    }

    public static List<String> parseCustomChips(String raw) {
        List<String> chips = new ArrayList<>();
        if (raw == null || raw.trim().isEmpty()) {
            return chips;
        }
        String[] parts = raw.split("[;\\n\\r]+");
        Set<String> seen = new LinkedHashSet<>();
        for (String part : parts) {
            String trimmed = part != null ? part.trim() : "";
            if (!trimmed.isEmpty() && seen.add(trimmed)) {
                chips.add(trimmed);
            }
        }
        return chips;
    }

    public static String sanitizeFallbackDate(String candidate) {
        if (candidate != null) {
            String trimmed = candidate.trim();
            if (ISO_DATE_PATTERN.matcher(trimmed).matches()) {
                return trimmed;
            }
        }
        return DEFAULT_EARLY_FALLBACK_DATE;
    }

    public static String buildSearchQuery(
            String handle,
            String customInput,
            List<String> selectedKeywordGroupStrings,
            List<String> selectedCustomChips,
            boolean useEarlyPosts,
            long resolvedCreatedAtMs,
            boolean mediaOnly,
            boolean excludeReposts
    ) {
        String cleanHandle = stripAt(handle);
        if (cleanHandle.isEmpty()) {
            return "";
        }

        Set<String> orBranches = new LinkedHashSet<>();

        String inputTrimmed = customInput != null ? customInput.trim() : "";
        if (!inputTrimmed.isEmpty()) {
            orBranches.add(formatBranch(inputTrimmed));
        }

        if (selectedKeywordGroupStrings != null) {
            for (String group : selectedKeywordGroupStrings) {
                for (String token : parseKeywordTokens(group)) {
                    orBranches.add(token);
                }
            }
        }

        if (selectedCustomChips != null) {
            for (String chip : selectedCustomChips) {
                String chipTrimmed = chip != null ? chip.trim() : "";
                if (!chipTrimmed.isEmpty()) {
                    orBranches.add(formatBranch(chipTrimmed));
                }
            }
        }

        List<String> filters = new ArrayList<>();
        if (useEarlyPosts) {
            String[] range = UserCreationLookup.buildFirstYearRange(resolvedCreatedAtMs);
            if (range != null && range.length == 2) {
                filters.add("since:" + range[0]);
                filters.add("until:" + range[1]);
            } else {
                filters.add("until:" + sanitizeFallbackDate(Pref.quickSearchEarlyFallbackDate()));
            }
        }
        if (mediaOnly) {
            filters.add("filter:media");
        }
        if (excludeReposts) {
            filters.add("-filter:retweets");
        }

        if (orBranches.isEmpty() && filters.isEmpty()) {
            return "";
        }

        List<String> parts = new ArrayList<>();
        parts.add("from:" + cleanHandle);

        if (orBranches.size() == 1) {
            parts.add(orBranches.iterator().next());
        } else if (orBranches.size() > 1) {
            parts.add("(" + TextUtils.join(" OR ", orBranches) + ")");
        }

        parts.addAll(filters);
        return TextUtils.join(" ", parts);
    }

    private static String formatBranch(String raw) {
        String trimmed = raw.trim();
        if ((trimmed.startsWith("(") && trimmed.endsWith(")"))
                || (trimmed.startsWith("\"") && trimmed.endsWith("\""))) {
            return trimmed;
        }
        if (trimmed.indexOf(' ') >= 0 || trimmed.toUpperCase(Locale.US).contains(" OR ")) {
            return "(" + trimmed + ")";
        }
        return trimmed;
    }

    public static void launchInAppSearch(Context context, String query) {
        if (query == null || query.trim().isEmpty()) {
            showToast(context, StringRef.str("piko_quick_search_empty_query"));
            return;
        }
        Context ctx = context != null ? context : Utils.getContext();
        if (ctx == null) {
            return;
        }
        try {
            Intent intent = new Intent(SEARCH_ACTION);
            intent.setClassName(ctx.getPackageName(), SEARCH_ACTIVITY_CLASS);
            intent.putExtra("query", query);
            intent.putExtra("user_query", query);
            intent.putExtra("q_source", "typed_query");
            intent.putExtra("search_type", SEARCH_TYPE_LATEST);
            if (!(ctx instanceof Activity)) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            }
            ctx.startActivity(intent);
        } catch (ActivityNotFoundException e) {
            PikoUtils.logger(e);
            try {
                Uri uri = Uri.parse("twitter://search")
                        .buildUpon()
                        .appendQueryParameter("query", query)
                        .build();
                Intent fallback = new Intent(Intent.ACTION_VIEW, uri);
                fallback.setPackage(ctx.getPackageName());
                if (!(ctx instanceof Activity)) {
                    fallback.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                }
                ctx.startActivity(fallback);
            } catch (Throwable inner) {
                PikoUtils.logger(inner);
            }
        } catch (Throwable t) {
            PikoUtils.logger(t);
        }
    }

    static void showToast(Context context, String message) {
        Context ctx = context != null ? context : Utils.getContext();
        if (ctx != null && message != null && !message.isEmpty()) {
            Toast.makeText(ctx, message, Toast.LENGTH_SHORT).show();
        }
    }
}
