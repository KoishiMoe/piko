/*
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 *
 * See the included NOTICE file for GPLv3 §7(b) terms that apply to this code.
 */

package app.morphe.extension.twitter.patches.gallery;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.appcompat.widget.Toolbar;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

import app.morphe.extension.crimera.PikoUtils;
import app.morphe.extension.shared.ResourceType;
import app.morphe.extension.shared.ResourceUtils;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.twitter.Pref;
import app.morphe.extension.twitter.entity.ExtMediaEntities;
import app.morphe.extension.twitter.entity.Media;
import app.morphe.extension.twitter.entity.Tweet;
import app.morphe.extension.twitter.settings.ActivityHook;

/**
 * UI helpers for legacy media gallery and timeline multi-image enhancements.
 */
@SuppressWarnings("unused")
public final class GalleryHelper {
    private static final String COUNTER_TAG = "piko_gallery_counter";
    private static final String MULTI_IMAGE_BADGE_TAG = "piko_multi_image_badge";
    private static final int STITCH_MENU_ITEM_ID = 0x7f0b7001;

    private GalleryHelper() {
    }

    /**
     * Updates or hides a centered pill counter ("1 / N") in the gallery {@link Toolbar}.
     *
     * @param toolbar    the gallery chrome toolbar (nullable)
     * @param position   0-based current pager index
     * @param totalCount total item count in the gallery adapter
     */
    public static void updatePageCounter(@Nullable Toolbar toolbar, int position, int totalCount) {
        if (toolbar == null) {
            return;
        }
        try {
            View existing = toolbar.findViewWithTag(COUNTER_TAG);
            TextView badge = existing instanceof TextView ? (TextView) existing : null;

            if (!Pref.galleryPageCounter() || totalCount <= 1 || position < 0 || position >= totalCount) {
                if (badge != null) {
                    badge.setVisibility(View.GONE);
                }
                return;
            }

            if (badge == null) {
                badge = createPillBadge(toolbar.getContext(), COUNTER_TAG, 13f, 10f, 4f, 0x99000000, 14f, 0);
                Toolbar.LayoutParams params = new Toolbar.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        Gravity.CENTER
                );
                toolbar.addView(badge, params);
            }

            badge.setText((position + 1) + " / " + totalCount);
            badge.setVisibility(View.VISIBLE);
        } catch (Throwable t) {
            PikoUtils.logger(t);
        }
    }

    /**
     * Displays a "+N" indicator on the 4th grid cell of {@code TweetMediaView}
     * when a post contains more than 4 media items.
     */
    public static void updateMultiImageBadge(@Nullable ViewGroup mediaView, @Nullable List<?> mediaItems) {
        if (mediaView == null) {
            return;
        }
        try {
            View existing = mediaView.findViewWithTag(MULTI_IMAGE_BADGE_TAG);
            int total = mediaItems == null ? 0 : mediaItems.size();

            if (!Pref.showMultiImageBadge() || total <= 4 || mediaView.getChildCount() < 4) {
                if (existing != null) {
                    existing.setVisibility(View.GONE);
                }
                return;
            }

            View fourthChild = mediaView.getChildAt(3);
            if (!(fourthChild instanceof ViewGroup fourthTile) || fourthTile.getVisibility() != View.VISIBLE) {
                if (existing != null) {
                    existing.setVisibility(View.GONE);
                }
                return;
            }

            View tileExisting = fourthTile.findViewWithTag(MULTI_IMAGE_BADGE_TAG);
            TextView badge = tileExisting instanceof TextView ? (TextView) tileExisting : null;
            if (existing != null && existing != badge) {
                existing.setVisibility(View.GONE);
            }

            if (badge == null) {
                Context context = fourthTile.getContext();
                badge = createPillBadge(context, MULTI_IMAGE_BADGE_TAG, 12f, 8f, 3f, 0xCC000000, 12f, 0x44FFFFFF);
                badge.setClickable(false);
                badge.setFocusable(false);

                FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        Gravity.BOTTOM | Gravity.END
                );
                int margin = dpToPx(context, 6f);
                lp.setMargins(margin, margin, margin, margin);
                fourthTile.addView(badge, lp);
            }

            badge.setText("+" + (total - 4));
            badge.setVisibility(View.VISIBLE);
            badge.bringToFront();
        } catch (Throwable t) {
            PikoUtils.logger(t);
        }
    }

    /**
     * Adds the "Stitch images" action to the gallery options menu.
     */
    public static void onCreateGalleryMenu(@Nullable Menu menu) {
        if (menu == null) {
            return;
        }
        try {
            if (menu.findItem(STITCH_MENU_ITEM_ID) != null) {
                return;
            }
            String title = safeString("piko_title_stitch_viewer", "Stitch images");
            MenuItem item = menu.add(Menu.NONE, STITCH_MENU_ITEM_ID, Menu.NONE, title);
            item.setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS);

            Drawable icon = loadWhiteIcon("ic_vector_image_collection", "ic_vector_grid_stroke");
            if (icon != null) {
                item.setIcon(icon);
            }
        } catch (Throwable t) {
            PikoUtils.logger(t);
        }
    }

    /**
     * Updates visibility of the "Stitch images" gallery toolbar item based on photo count.
     */
    public static void onPrepareGalleryMenu(
            @Nullable Object navBar,
            @Nullable List<?> galleryItems,
            @Nullable Object tweetObj) {
        if (navBar == null) {
            return;
        }
        try {
            MenuItem item = findMenuItem(navBar, STITCH_MENU_ITEM_ID);
            if (item == null) {
                return;
            }
            boolean visible = Pref.enableStitchViewer()
                    && extractPhotoUrls(tweetObj, galleryItems).size() >= 2;
            item.setVisible(visible);
        } catch (Throwable t) {
            PikoUtils.logger(t);
        }
    }

    /**
     * Handles clicks on the injected gallery toolbar "Stitch images" action.
     *
     * @return {@code true} if the click was consumed
     */
    public static boolean onGalleryMenuItemSelected(
            @Nullable MenuItem item,
            @Nullable List<?> galleryItems,
            @Nullable Object tweetObj) {
        if (item == null || item.getItemId() != STITCH_MENU_ITEM_ID) {
            return false;
        }
        try {
            ArrayList<String> urls = extractPhotoUrls(tweetObj, galleryItems);
            if (urls.size() >= 2) {
                ActivityHook.startStitchViewer(urls);
            } else {
                Utils.showToastShort(safeString("piko_stitch_no_images", "Fewer than 2 stitchable images"));
            }
        } catch (Throwable t) {
            PikoUtils.logger(t);
        }
        return true;
    }

    /**
     * Returns {@code true} when {@code tweetObj} contains at least 2 photos.
     */
    public static boolean hasStitchableImages(@Nullable Object tweetObj) {
        return extractPhotoUrlsFromTweet(tweetObj).size() >= 2;
    }

    /**
     * Launches {@link StitchViewerFragment} for a post from the Share menu.
     */
    public static void openStitchViewerFromTweet(@Nullable Object tweetObj) {
        try {
            ArrayList<String> urls = extractPhotoUrlsFromTweet(tweetObj);
            if (urls.size() >= 2) {
                ActivityHook.startStitchViewer(urls);
            } else {
                Utils.showToastShort(safeString("piko_stitch_no_images", "Fewer than 2 stitchable images"));
            }
        } catch (Throwable t) {
            PikoUtils.logger(t);
        }
    }

    public static ArrayList<String> extractPhotoUrlsFromTweet(@Nullable Object tweetObj) {
        LinkedHashSet<String> urls = new LinkedHashSet<>();
        if (tweetObj == null) {
            return new ArrayList<>();
        }
        try {
            ArrayList<ArrayList<Media>> mediaGroups = new Tweet(tweetObj).getMediaList();
            for (ArrayList<Media> group : mediaGroups) {
                for (Media media : group) {
                    if (media != null && media.type == 0 && media.url != null && !media.url.isEmpty()) {
                        urls.add(media.url);
                    }
                }
            }
        } catch (Throwable t) {
            PikoUtils.logger(t);
        }
        return new ArrayList<>(urls);
    }

    public static ArrayList<String> extractPhotoUrls(@Nullable Object tweetObj, @Nullable List<?> galleryItems) {
        ArrayList<String> fromTweet = extractPhotoUrlsFromTweet(tweetObj);
        if (fromTweet.size() >= 2 || galleryItems == null || galleryItems.isEmpty()) {
            return fromTweet;
        }

        LinkedHashSet<String> urls = new LinkedHashSet<>(fromTweet);
        for (Object galleryItem : galleryItems) {
            if (galleryItem == null) {
                continue;
            }
            collectGalleryItemPhoto(galleryItem, urls);
        }
        return new ArrayList<>(urls);
    }

    private static void collectGalleryItemPhoto(Object galleryItem, LinkedHashSet<String> out) {
        try {
            String fallbackRequestUrl = null;
            for (Field field : galleryItem.getClass().getFields()) {
                Object value = field.get(galleryItem);
                if (value == null) {
                    continue;
                }
                String className = value.getClass().getName();
                if (className.startsWith("com.twitter.model.core.entity.")) {
                    ArrayList<Media> mediaList = new ExtMediaEntities(value).getMediaList();
                    for (Media m : mediaList) {
                        if (m != null && m.type == 0 && m.url != null && !m.url.isEmpty()) {
                            out.add(m.url);
                            return;
                        }
                    }
                    // Entity was present and did not represent a photo (e.g. video/GIF).
                    return;
                }
                if (fallbackRequestUrl == null && className.startsWith("com.twitter.media.request.")) {
                    try {
                        Field urlField = value.getClass().getField("a");
                        Object inner = urlField.get(value);
                        if (inner instanceof String s && s.startsWith("http") && !isVideoUrl(s)) {
                            fallbackRequestUrl = s;
                        }
                    } catch (NoSuchFieldException ignored) {
                    }
                }
            }
            if (fallbackRequestUrl != null) {
                out.add(fallbackRequestUrl);
            }
        } catch (Throwable t) {
            PikoUtils.logger(t);
        }
    }

    private static boolean isVideoUrl(String url) {
        return url.contains(".mp4") || url.contains(".m3u8") || url.contains("/ext_tw_video/");
    }

    @Nullable
    private static MenuItem findMenuItem(Object navBar, int itemId) {
        try {
            Method m = navBar.getClass().getMethod("findItem", int.class);
            Object res = m.invoke(navBar, itemId);
            return res instanceof MenuItem ? (MenuItem) res : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    @Nullable
    private static Drawable loadWhiteIcon(String primary, String fallback) {
        Context context = Utils.getContext();
        int id = ResourceUtils.getIdentifier(ResourceType.DRAWABLE, primary);
        if (id == 0) {
            id = ResourceUtils.getIdentifier(ResourceType.DRAWABLE, fallback);
        }
        if (id == 0) {
            return null;
        }
        Drawable d = context.getDrawable(id);
        if (d == null) {
            return null;
        }
        Drawable mutated = d.mutate();
        mutated.setTint(Color.WHITE);
        return mutated;
    }

    private static TextView createPillBadge(
            Context context,
            String tag,
            float textSizeSp,
            float padHDp,
            float padVDp,
            int fillColor,
            float cornerDp,
            int strokeColor) {
        TextView badge = new TextView(context);
        badge.setTag(tag);
        badge.setTextColor(Color.WHITE);
        badge.setTextSize(TypedValue.COMPLEX_UNIT_SP, textSizeSp);
        badge.setTypeface(Typeface.DEFAULT_BOLD);
        badge.setIncludeFontPadding(false);

        int padH = dpToPx(context, padHDp);
        int padV = dpToPx(context, padVDp);
        badge.setPadding(padH, padV, padH, padV);

        GradientDrawable pill = new GradientDrawable();
        pill.setColor(fillColor);
        pill.setCornerRadius(dpToPx(context, cornerDp));
        if (strokeColor != 0) {
            pill.setStroke(dpToPx(context, 1f), strokeColor);
        }
        badge.setBackground(pill);
        return badge;
    }

    private static String safeString(String name, String fallback) {
        try {
            String value = ResourceUtils.getString(name);
            return (value != null && !value.isEmpty()) ? value : fallback;
        } catch (Throwable ignored) {
            return fallback;
        }
    }

    private static int dpToPx(Context context, float dp) {
        return Math.round(TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP,
                dp,
                context.getResources().getDisplayMetrics()));
    }
}
