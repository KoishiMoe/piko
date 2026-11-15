/*
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 *
 * See the included NOTICE file for GPLv3 §7(b) terms that apply to this code.
 */

package app.morphe.extension.twitter.patches.nativeFeatures.shareMenu;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.widget.Toast;

import app.morphe.extension.crimera.PikoUtils;
import app.morphe.extension.shared.StringRef;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.twitter.entity.Tweet;

/**
 * Handles external browser lookup for the post author.
 */
public final class AuthorLookupHelper {

    private static final String EXTERNAL_LOOKUP_URL = "https://danbooru.donmai.us/artists";

    private AuthorLookupHelper() {
    }

    public static void openExternalAuthorLookup(Context context, Object tweetObject) {
        try {
            Tweet tweet = new Tweet(tweetObject);
            String username = tweet.getTweetUsername();
            if (username == null) {
                showToast(context, StringRef.str("piko_author_lookup_no_username"));
                return;
            }
            String cleanHandle = username.trim();
            if (cleanHandle.startsWith("@")) {
                cleanHandle = cleanHandle.substring(1).trim();
            }
            if (cleanHandle.isEmpty()) {
                showToast(context, StringRef.str("piko_author_lookup_no_username"));
                return;
            }

            Uri targetUri = Uri.parse(EXTERNAL_LOOKUP_URL)
                    .buildUpon()
                    .appendQueryParameter("search[url_matches]", "https://x.com/" + cleanHandle)
                    .build();

            Context launchContext = context != null ? context : Utils.getContext();
            if (launchContext == null) {
                return;
            }

            Intent intent = new Intent(Intent.ACTION_VIEW, targetUri);
            if (!(launchContext instanceof Activity)) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            }
            launchContext.startActivity(intent);
        } catch (ActivityNotFoundException e) {
            PikoUtils.logger(e);
        } catch (Throwable t) {
            PikoUtils.logger(t);
        }
    }

    private static void showToast(Context context, String message) {
        Context ctx = context != null ? context : Utils.getContext();
        if (ctx != null && message != null && !message.isEmpty()) {
            Toast.makeText(ctx, message, Toast.LENGTH_SHORT).show();
        }
    }
}
