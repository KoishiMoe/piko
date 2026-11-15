/*
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 *
 * See the included NOTICE file for GPLv3 §7(b) terms that apply to this code.
 */

package app.morphe.extension.twitter.patches.profile;

import android.content.Context;
import android.view.View;

import app.morphe.extension.shared.ResourceType;
import app.morphe.extension.shared.ResourceUtils;
import app.morphe.extension.crimera.PikoUtils;
import static app.morphe.extension.shared.StringRef.str;

import app.morphe.extension.twitter.entity.Debug;
import app.morphe.extension.twitter.entity.TwitterUser;
import app.morphe.extension.twitter.Pref;
import app.morphe.extension.twitter.patches.nativeFeatures.search.UserCreationLookup;

import com.twitter.ui.tweet.TweetStatView;

import java.util.Locale;

public class MoreProfileInfo {

    private static void setTweetStatViewValue(Context context,TweetStatView tweetStatView,String name,int value){
        // The code will be injected in patching.
    }

    private static String headerComponentViewFieldName(){
        return "c";
    }

    private static String headerComponentContextFieldName(){
        return "a";
    }

    private static void setTweetStatView(Context context,View rootView, String resourceName, String text, int count){
        int targetId = ResourceUtils.getIdentifier(ResourceType.ID, resourceName);
        TweetStatView tweetStatView = (TweetStatView)  rootView.findViewById(targetId);

        tweetStatView.setOnTouchListener((v, event) -> true);
        tweetStatView.setOnClickListener(v -> {});

        setTweetStatViewValue(context, tweetStatView, text, count);
        if(count>0)
            tweetStatView.setVisibility(View.VISIBLE);
    }

    private static void cacheCreationTime(TwitterUser twitterUserEntity) {
        try {
            long userId = twitterUserEntity.getId();
            String username = twitterUserEntity.getUsername();
            long createdAt = twitterUserEntity.getCreatedAt();
            UserCreationLookup.cacheUser(userId, username, createdAt);
        } catch (Exception e) {
            PikoUtils.logger(e);
        }
    }

    private static String formatDecimalTrimmed(double value) {
        String s = String.format(Locale.US, "%.1f", value);
        if (s.endsWith(".0")) {
            return s.substring(0, s.length() - 2);
        }
        return s;
    }

    private static String formatMediaFrequency(int mediaCount, long createdAtMs) {
        if (mediaCount <= 0) {
            return "0";
        }
        double elapsedDays = Math.max(1.0, (System.currentTimeMillis() - createdAtMs) / 86_400_000.0);
        double avgPerDay = mediaCount / elapsedDays;
        if (avgPerDay >= 1.0) {
            String valueStr = avgPerDay >= 100.0
                    ? String.format(Locale.US, "%.0f", avgPerDay)
                    : formatDecimalTrimmed(avgPerDay);
            return str("piko_profile_media_frequency_per_day", valueStr);
        }
        double daysPerMedia = elapsedDays / mediaCount;
        String intervalStr = daysPerMedia >= 10.0
                ? String.valueOf(Math.round(daysPerMedia))
                : formatDecimalTrimmed(daysPerMedia);
        return str("piko_profile_media_frequency_every_n_days", intervalStr);
    }

    private static void bindMediaFrequencyStat(View rootView, TwitterUser twitterUserEntity) {
        try {
            int targetId = ResourceUtils.getIdentifier(ResourceType.ID, "media_frequency_stat");
            if (targetId == 0) {
                return;
            }
            View rawView = rootView.findViewById(targetId);
            if (!(rawView instanceof TweetStatView tweetStatView)) {
                return;
            }

            if (!Pref.enableMediaFrequencyStat()) {
                tweetStatView.setVisibility(View.GONE);
                return;
            }

            long createdAtMs = UserCreationLookup.normalizeEpochMs(twitterUserEntity.getCreatedAt());
            if (createdAtMs <= 0L) {
                tweetStatView.setVisibility(View.GONE);
                return;
            }

            int mediaCount = Math.max(0, twitterUserEntity.getMediaCount());
            String formattedValue = formatMediaFrequency(mediaCount, createdAtMs);

            tweetStatView.setOnTouchListener((v, event) -> true);
            tweetStatView.setOnClickListener(v -> {});
            tweetStatView.setName(str("piko_profile_media_frequency_label"));
            tweetStatView.a(formattedValue, false);
            tweetStatView.setVisibility(View.VISIBLE);
        } catch (Exception e) {
            PikoUtils.logger(e);
        }
    }

    public static void addTweetStatView(Object headerComponent, Object TwitterUserObject){
        try {
            TwitterUser twitterUserEntity = new TwitterUser(TwitterUserObject);
            cacheCreationTime(twitterUserEntity);

            Debug headerComponentEntity = new Debug(headerComponent);
            View rootView = (View) headerComponentEntity.getField(headerComponentViewFieldName());

            if (rootView != null) {
                bindMediaFrequencyStat(rootView, twitterUserEntity);
            }

            if(Pref.moreInfoOnProfile()) {
                Context context = (Context) headerComponentEntity.getField(headerComponentContextFieldName());

                int count = twitterUserEntity.getFastFollowersCount();
                String text = str("piko_fast_follower");
                setTweetStatView(context, rootView, "fast_follower_stat", text, count);

                count = twitterUserEntity.getStatusCount();
                text = str("profile_tab_title_posts");
                setTweetStatView(context, rootView, "tweet_stat", text, count);

                count = twitterUserEntity.getArticleCount();
                text = str("profile_tab_title_articles");
                setTweetStatView(context, rootView, "article_stat", text, count);

                count = twitterUserEntity.getMediaCount();
                text = str("profile_tab_title_media");
                setTweetStatView(context, rootView, "media_stat", text, count);

                count = twitterUserEntity.getLikesCount();
                text = str("profile_tab_title_likes");
                setTweetStatView(context, rootView, "likes_stat", text, count);
            }
        } catch (Exception e) {
            PikoUtils.logger(e);
        }

    }

    public static String addUserId(String userName, Object TwitterUserObject){
        try{
            TwitterUser twitterUserEntity = new TwitterUser(TwitterUserObject);
            cacheCreationTime(twitterUserEntity);

            if(Pref.moreInfoOnProfile()) {
                long userId = twitterUserEntity.getId();

                String userIdstr = str("piko_pref_account_id", String.valueOf(userId));
                return userName + "\n" + userIdstr;
            }
            return userName;
        } catch (Exception e) {
            PikoUtils.logger(e);
            return userName;
        }
    }



}