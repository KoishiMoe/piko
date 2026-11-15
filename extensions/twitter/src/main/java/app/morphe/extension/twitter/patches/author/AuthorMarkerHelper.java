/*
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 *
 * See the included NOTICE file for GPLv3 §7(b) terms that apply to this code.
 */

package app.morphe.extension.twitter.patches.author;

import android.app.Dialog;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.ShapeDrawable;
import android.graphics.drawable.shapes.RoundRectShape;
import android.text.TextUtils;
import android.util.Pair;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.twitter.util.user.UserIdentifier;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import app.morphe.extension.crimera.PikoUtils;
import app.morphe.extension.shared.ResourceUtils;
import app.morphe.extension.shared.StringRef;
import app.morphe.extension.shared.ui.CustomDialog;
import app.morphe.extension.shared.ui.Dim;
import app.morphe.extension.twitter.Pref;
import app.morphe.extension.twitter.Utils;
import app.morphe.extension.twitter.entity.Tweet;
import app.morphe.extension.twitter.entity.TwitterUser;
import app.morphe.extension.twitter.patches.nativeFeatures.search.UserCreationLookup;
import app.morphe.extension.twitter.settings.Settings;
import app.morphe.extension.twitter.settings.SettingsStatus;

/**
 * Handles marker evaluation, persistence, formatting, menu controls, and settings management
 * for followed and manually marked accounts.
 */
public final class AuthorMarkerHelper {

    public static final int MENU_ITEM_AUTHOR_MARKER = 0x7f7f0101;

    private static final String MARK_SUFFIX = " \u2605";
    private static final String MARK_SYMBOL = "\u2605";

    private AuthorMarkerHelper() {
    }

    public static long getCurrentUserId() {
        try {
            UserIdentifier current = UserIdentifier.getCurrent();
            if (current == null) {
                return -1L;
            }
            String raw = current.getStringId();
            if (raw == null || raw.isEmpty() || "0".equals(raw) || "-1".equals(raw)) {
                return -1L;
            }
            return Long.parseLong(raw);
        } catch (Throwable ignored) {
            return -1L;
        }
    }

    public static boolean isSelfUser(long userId) {
        if (userId <= 0L) {
            return false;
        }
        long currentId = getCurrentUserId();
        return currentId > 0L && currentId == userId;
    }

    public static Set<String> getMarkedIds() {
        try {
            Set<String> stored = Utils.getSetPref(Settings.MARKED_AUTHOR_IDS_KEY, Collections.emptySet());
            if (stored == null || stored.isEmpty()) {
                return new HashSet<>();
            }
            return new HashSet<>(stored);
        } catch (Throwable t) {
            PikoUtils.logger(t);
            return new HashSet<>();
        }
    }

    public static int getMarkedCount() {
        return getMarkedIds().size();
    }

    public static boolean isManuallyMarked(long userId) {
        if (userId <= 0L) {
            return false;
        }
        return getMarkedIds().contains(String.valueOf(userId));
    }

    private static Map<String, String> loadHandleMap() {
        Map<String, String> map = new LinkedHashMap<>();
        try {
            String json = Utils.getStringPref(Settings.MARKED_AUTHOR_HANDLES_KEY, "");
            if (json == null || json.trim().isEmpty()) {
                return map;
            }
            JSONObject obj = new JSONObject(json);
            var keys = obj.keys();
            while (keys.hasNext()) {
                String k = keys.next();
                String v = obj.optString(k, "");
                if (!k.isEmpty() && !v.isEmpty()) {
                    map.put(k, v);
                }
            }
        } catch (Throwable t) {
            PikoUtils.logger(t);
        }
        return map;
    }

    private static void saveHandleMap(Map<String, String> map) {
        try {
            JSONObject obj = new JSONObject();
            for (Map.Entry<String, String> entry : map.entrySet()) {
                obj.put(entry.getKey(), entry.getValue());
            }
            Utils.setStringPref(Settings.MARKED_AUTHOR_HANDLES_KEY, obj.toString());
        } catch (Throwable t) {
            PikoUtils.logger(t);
        }
    }

    private static String normalizeHandleForDisplay(String username) {
        if (username == null) {
            return "";
        }
        String trimmed = username.trim();
        while (trimmed.startsWith("@")) {
            trimmed = trimmed.substring(1).trim();
        }
        return trimmed;
    }

    public static void setManuallyMarked(long userId, String username, boolean mark) {
        if (userId <= 0L) {
            return;
        }
        String idStr = String.valueOf(userId);
        Set<String> ids = getMarkedIds();
        Map<String, String> handleMap = loadHandleMap();

        if (mark) {
            ids.add(idStr);
            String clean = normalizeHandleForDisplay(username);
            if (!clean.isEmpty()) {
                handleMap.put(idStr, clean);
            }
        } else {
            ids.remove(idStr);
            handleMap.remove(idStr);
        }

        Utils.setSetPref(Settings.MARKED_AUTHOR_IDS_KEY, ids);
        saveHandleMap(handleMap);
    }

    public static void clearAllMarked() {
        Utils.setSetPref(Settings.MARKED_AUTHOR_IDS_KEY, new HashSet<>());
        Utils.setStringPref(Settings.MARKED_AUTHOR_HANDLES_KEY, "");
    }

    private static void syncHandleCacheIfMarked(long userId, String username) {
        if (userId <= 0L || username == null || username.trim().isEmpty()) {
            return;
        }
        String idStr = String.valueOf(userId);
        if (!getMarkedIds().contains(idStr)) {
            return;
        }
        String clean = normalizeHandleForDisplay(username);
        if (clean.isEmpty()) {
            return;
        }
        Map<String, String> map = loadHandleMap();
        if (!clean.equals(map.get(idStr))) {
            map.put(idStr, clean);
            saveHandleMap(map);
        }
    }

    public static boolean shouldMark(long userId, int friendship) {
        if (!Pref.enableAuthorMarker() || userId <= 0L || isSelfUser(userId)) {
            return false;
        }
        if ((friendship & 0x1) != 0) {
            return true;
        }
        return isManuallyMarked(userId);
    }

    public static String appendMarkIdempotently(String rawUsername) {
        if (rawUsername == null || rawUsername.isEmpty()) {
            return rawUsername;
        }
        int nl = rawUsername.indexOf('\n');
        String firstLine = nl >= 0 ? rawUsername.substring(0, nl) : rawUsername;
        String rest = nl >= 0 ? rawUsername.substring(nl) : "";
        if (firstLine.isEmpty()) {
            return rawUsername;
        }
        if (!firstLine.endsWith(MARK_SUFFIX) && !firstLine.endsWith(MARK_SYMBOL)) {
            firstLine = firstLine + MARK_SUFFIX;
        }
        return firstLine + rest;
    }

    public static String formatTweetUsername(String username, Object tweetObject) {
        if (username == null || username.isEmpty() || tweetObject == null) {
            return username;
        }
        try {
            if (!Pref.enableAuthorMarker()) {
                return username;
            }
            Tweet tweet = new Tweet(tweetObject);
            long userId = extractAuthorId(tweet);
            if (userId <= 0L) {
                return username;
            }
            int friendship = tweet.getTweetAuthorFriendship();
            syncHandleCacheIfMarked(userId, tweet.getTweetUsername());
            if (shouldMark(userId, friendship)) {
                return appendMarkIdempotently(username);
            }
        } catch (Throwable t) {
            PikoUtils.logger(t);
        }
        return username;
    }

    public static String formatProfileUsername(String username, Object twitterUserObject) {
        if (username == null || username.isEmpty() || twitterUserObject == null) {
            return username;
        }
        try {
            TwitterUser user = new TwitterUser(twitterUserObject);
            long userId = user.getId();
            String rawHandle = user.getUsername();
            UserCreationLookup.cacheUser(userId, rawHandle, user.getCreatedAt());

            if (!Pref.enableAuthorMarker() || userId <= 0L) {
                return username;
            }
            int friendship = user.getFriendship();
            syncHandleCacheIfMarked(userId, rawHandle);
            if (shouldMark(userId, friendship)) {
                return appendMarkIdempotently(username);
            }
        } catch (Throwable t) {
            PikoUtils.logger(t);
        }
        return username;
    }

    private static long extractAuthorId(Tweet tweet) {
        try {
            long origId = tweet.getTweetOriginalAuthorId();
            if (origId > 0L) {
                return origId;
            }
            Long fallback = tweet.getTweetUserId();
            return fallback != null ? fallback : -1L;
        } catch (Throwable ignored) {
            return -1L;
        }
    }

    public static boolean isSelfTweetAuthor(Object tweetObject) {
        if (tweetObject == null) {
            return false;
        }
        try {
            long userId = extractAuthorId(new Tweet(tweetObject));
            return isSelfUser(userId);
        } catch (Throwable ignored) {
            return false;
        }
    }

    public static boolean isTweetAuthorManuallyMarked(Object tweetObject) {
        if (tweetObject == null) {
            return false;
        }
        try {
            long userId = extractAuthorId(new Tweet(tweetObject));
            return isManuallyMarked(userId);
        } catch (Throwable ignored) {
            return false;
        }
    }

    public static void toggleFromTweet(Context context, Object tweetObject) {
        if (tweetObject == null) {
            return;
        }
        try {
            Tweet tweet = new Tweet(tweetObject);
            long userId = extractAuthorId(tweet);
            if (userId <= 0L || isSelfUser(userId)) {
                return;
            }
            String cleanHandle = normalizeHandleForDisplay(tweet.getTweetUsername());
            boolean next = !isManuallyMarked(userId);
            setManuallyMarked(userId, cleanHandle, next);

            String displayTarget = cleanHandle.isEmpty() ? String.valueOf(userId) : cleanHandle;
            showToast(context, StringRef.str(
                    next ? "piko_author_marker_added" : "piko_author_marker_removed",
                    displayTarget
            ));
        } catch (Throwable t) {
            PikoUtils.logger(t);
        }
    }

    public static void onCreateProfileMenu(Menu menu, Object twitterUserObj) {
        if (menu == null || !SettingsStatus.authorMarker || !Pref.enableAuthorMarker()) {
            return;
        }
        try {
            menu.removeItem(MENU_ITEM_AUTHOR_MARKER);
            MenuItem item = menu.add(
                    Menu.NONE,
                    MENU_ITEM_AUTHOR_MARKER,
                    0,
                    StringRef.str("piko_author_marker_mark")
            );
            item.setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER);
            updateProfileMenuItem(item, twitterUserObj);
        } catch (Throwable t) {
            PikoUtils.logger(t);
        }
    }

    public static void updateProfileMenuItem(MenuItem item, Object twitterUserObj) {
        if (item == null) {
            return;
        }
        try {
            if (!Pref.enableAuthorMarker() || twitterUserObj == null) {
                item.setVisible(false);
                return;
            }
            TwitterUser user = new TwitterUser(twitterUserObj);
            long userId = user.getId();
            if (userId <= 0L || isSelfUser(userId)) {
                item.setVisible(false);
                return;
            }
            boolean marked = isManuallyMarked(userId);
            item.setVisible(true);
            item.setTitle(StringRef.str(marked ? "piko_author_marker_unmark" : "piko_author_marker_mark"));
        } catch (Throwable t) {
            PikoUtils.logger(t);
        }
    }

    public static boolean isProfileMenuMarkerItem(MenuItem item) {
        return item != null && item.getItemId() == MENU_ITEM_AUTHOR_MARKER;
    }

    public static boolean onProfileMenuItemClicked(Object twitterUserObj) {
        if (twitterUserObj == null || !Pref.enableAuthorMarker()) {
            return false;
        }
        try {
            TwitterUser user = new TwitterUser(twitterUserObj);
            long userId = user.getId();
            if (userId <= 0L || isSelfUser(userId)) {
                return false;
            }
            String cleanHandle = normalizeHandleForDisplay(user.getUsername());
            boolean next = !isManuallyMarked(userId);
            setManuallyMarked(userId, cleanHandle, next);

            String displayTarget = cleanHandle.isEmpty() ? String.valueOf(userId) : cleanHandle;
            PikoUtils.toast(StringRef.str(
                    next ? "piko_author_marker_added" : "piko_author_marker_removed",
                    displayTarget
            ));
            return true;
        } catch (Throwable t) {
            PikoUtils.logger(t);
            return false;
        }
    }

    public static void showManageMarkedAccountsDialog(Context context, Runnable onChanged) {
        if (context == null) {
            return;
        }
        try {
            Pair<Dialog, LinearLayout> pair = CustomDialog.create(
                    context,
                    StringRef.str("piko_pref_author_marker_manage"),
                    null,
                    null,
                    StringRef.str("piko_ok"),
                    () -> {},
                    null,
                    StringRef.str("piko_author_marker_clear_all"),
                    () -> {
                        clearAllMarked();
                        PikoUtils.toast(StringRef.str("piko_author_marker_cleared"));
                        if (onChanged != null) {
                            onChanged.run();
                        }
                    },
                    true
            );
            Dialog dialog = pair.first;
            LinearLayout mainLayout = pair.second;

            int bg = resolveColorSafe(context, "coreColorAppBackground", 0xFFFFFFFF);
            int primary = resolveColorSafe(context, "textColorPrimary", 0xFF0F1419);
            int secondary = resolveColorSafe(context, "textColorSecondary", 0xFF536471);

            Window window = dialog.getWindow();
            if (window != null) {
                window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
                window.setDimAmount(0.7f);
                ShapeDrawable background = new ShapeDrawable(new RoundRectShape(Dim.roundedCorners(24), null, null));
                background.getPaint().setColor(bg);
                mainLayout.setBackground(background);
            }

            if (mainLayout.getChildAt(0) instanceof TextView titleTv) {
                titleTv.setTextColor(primary);
            }

            double lum = (0.299 * Color.red(bg) + 0.587 * Color.green(bg) + 0.114 * Color.blue(bg)) / 255.0;
            boolean dark = lum < 0.5;
            int dividerColor = dark ? Color.argb(36, 255, 255, 255) : Color.argb(24, 0, 0, 0);
            int btnSurface = dark ? Color.argb(40, 255, 255, 255) : Color.argb(18, 0, 0, 0);

            ScrollView scrollView = new ScrollView(context);
            scrollView.setVerticalScrollBarEnabled(false);
            int maxHeight = (int) (context.getResources().getDisplayMetrics().heightPixels * 0.5f);

            LinearLayout listContainer = new LinearLayout(context);
            listContainer.setOrientation(LinearLayout.VERTICAL);

            Runnable[] rebuildRef = new Runnable[1];
            rebuildRef[0] = () -> {
                listContainer.removeAllViews();
                List<String> ids = new ArrayList<>(getMarkedIds());
                Map<String, String> handleMap = loadHandleMap();
                ids.sort((a, b) -> {
                    String ha = handleMap.getOrDefault(a, a).toLowerCase(Locale.US);
                    String hb = handleMap.getOrDefault(b, b).toLowerCase(Locale.US);
                    return ha.compareTo(hb);
                });

                if (ids.isEmpty()) {
                    TextView empty = new TextView(context);
                    empty.setText(StringRef.str("piko_author_marker_empty"));
                    empty.setTextColor(secondary);
                    empty.setGravity(Gravity.CENTER);
                    empty.setPadding(0, dp(context, 20), 0, dp(context, 20));
                    listContainer.addView(empty);
                    return;
                }

                for (int i = 0; i < ids.size(); i++) {
                    String idStr = ids.get(i);
                    String handle = handleMap.get(idStr);

                    LinearLayout row = new LinearLayout(context);
                    row.setOrientation(LinearLayout.HORIZONTAL);
                    row.setGravity(Gravity.CENTER_VERTICAL);
                    row.setPadding(0, dp(context, 10), 0, dp(context, 10));

                    LinearLayout infoCol = new LinearLayout(context);
                    infoCol.setOrientation(LinearLayout.VERTICAL);
                    LinearLayout.LayoutParams infoLp = new LinearLayout.LayoutParams(
                            0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
                    infoLp.setMarginEnd(dp(context, 8));
                    infoCol.setLayoutParams(infoLp);

                    TextView title = new TextView(context);
                    title.setText(handle != null && !handle.isEmpty() ? "@" + handle : "ID: " + idStr);
                    title.setTextColor(primary);
                    title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
                    title.setTypeface(Typeface.DEFAULT_BOLD);
                    title.setSingleLine(true);
                    title.setEllipsize(TextUtils.TruncateAt.END);
                    infoCol.addView(title);

                    if (handle != null && !handle.isEmpty()) {
                        TextView sub = new TextView(context);
                        sub.setText(idStr);
                        sub.setTextColor(secondary);
                        sub.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
                        sub.setSingleLine(true);
                        infoCol.addView(sub);
                    }
                    row.addView(infoCol);

                    Button removeBtn = new Button(context);
                    removeBtn.setText(StringRef.str("piko_author_marker_remove_item"));
                    removeBtn.setAllCaps(false);
                    removeBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
                    removeBtn.setTextColor(primary);
                    GradientDrawable rmBg = new GradientDrawable();
                    rmBg.setColor(btnSurface);
                    rmBg.setCornerRadius(dp(context, 14));
                    removeBtn.setBackground(rmBg);
                    removeBtn.setPadding(dp(context, 12), dp(context, 4), dp(context, 12), dp(context, 4));
                    removeBtn.setMinimumHeight(0);
                    removeBtn.setMinHeight(dp(context, 30));
                    removeBtn.setOnClickListener(v -> {
                        try {
                            setManuallyMarked(Long.parseLong(idStr), handle, false);
                        } catch (NumberFormatException ignored) {
                            Set<String> copy = getMarkedIds();
                            copy.remove(idStr);
                            Utils.setSetPref(Settings.MARKED_AUTHOR_IDS_KEY, copy);
                        }
                        if (onChanged != null) {
                            onChanged.run();
                        }
                        rebuildRef[0].run();
                    });
                    row.addView(removeBtn);

                    listContainer.addView(row);
                    if (i < ids.size() - 1) {
                        View divider = new View(context);
                        divider.setLayoutParams(new LinearLayout.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT, 1));
                        divider.setBackgroundColor(dividerColor);
                        listContainer.addView(divider);
                    }
                }
            };

            rebuildRef[0].run();
            scrollView.addView(listContainer);
            LinearLayout.LayoutParams scrollLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            scrollView.setLayoutParams(scrollLp);
            scrollView.post(() -> {
                if (scrollView.getHeight() > maxHeight) {
                    ViewGroup.LayoutParams lp = scrollView.getLayoutParams();
                    lp.height = maxHeight;
                    scrollView.setLayoutParams(lp);
                }
            });

            int buttonRowIndex = Math.max(0, mainLayout.getChildCount() - 1);
            mainLayout.addView(scrollView, buttonRowIndex);

            dialog.show();
        } catch (Throwable t) {
            PikoUtils.logger(t);
        }
    }

    private static int resolveColorSafe(Context context, String attr, int fallback) {
        try {
            return Utils.resolveColor(context, attr);
        } catch (Throwable ignored) {
            return fallback;
        }
    }

    private static int dp(Context context, int dp) {
        return (int) TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, dp, context.getResources().getDisplayMetrics());
    }

    private static void showToast(Context context, String message) {
        if (message == null || message.isEmpty()) {
            return;
        }
        Context ctx = context != null ? context : app.morphe.extension.shared.Utils.getContext();
        if (ctx != null) {
            android.widget.Toast.makeText(ctx, message, android.widget.Toast.LENGTH_SHORT).show();
        }
    }
}
