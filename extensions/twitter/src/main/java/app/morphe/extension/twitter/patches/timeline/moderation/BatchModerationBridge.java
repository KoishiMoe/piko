/*
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 *
 * See the included NOTICE file for GPLv3 §7(b) terms that apply to this code.
 */

package app.morphe.extension.twitter.patches.timeline.moderation;

import android.app.Activity;
import android.content.Context;
import android.view.Menu;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.widget.Toolbar;

import com.twitter.util.user.UserIdentifier;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.Map;

import app.morphe.extension.crimera.PikoUtils;

/**
 * Runtime reflection bridge configured at patch-time by {@code BatchModerationPatch}.
 * <p>
 * Keeps obfuscated Twitter class/field/method names out of extension Java code while caching all
 * resolved reflective handles cleanly on first use.
 */
public final class BatchModerationBridge {

    static final int FLAG_BLOCKED = 4;
    static final int FLAG_MUTED = 8192;

    private static final Map<String, String> CONFIG = new HashMap<>();
    private static volatile boolean configLoaded = false;
    private static volatile boolean handlesResolved = false;
    private static volatile boolean resolutionFailed = false;

    // Resolved classes
    private static Class<?> clsNavigationBar;
    private static Class<?> clsTimelineTweet;
    private static Class<?> clsFocalFinder;
    private static Class<?> clsAdContent;
    private static Class<?> clsBlockReq;
    private static Class<?> clsMuteReq;
    private static Class<?> clsHttpController;
    private static Class<?> clsListenerInterface;

    // Resolved constructors
    private static Constructor<?> ctorBlockReq;
    private static Constructor<?> ctorMuteReq;

    // Resolved fields
    private static Field fCtrlActivity;
    private static Field fCtrlUserCache;
    private static Field fCtrlViewHost;
    private static Field fHostRecyclerHelper;
    private static Field fRecyclerHelperView;
    private static Field fCtrlAdapter;
    private static Field fCtrlFocalHelper;
    private static Field fFocalHelperItem;
    private static Field fBindEventHolder;
    private static Field fBindEventItem;
    private static Field fTimelineTweetCore;
    private static Field fTweetGetId; // unused if method
    private static Field fTweetCoreData;
    private static Field fTweetDataAuthor;
    private static Field fTweetAuthorFriendship;
    private static Field fTweetAdContent;
    private static Field fHttpResultSuccess;

    // Resolved methods
    private static Method mCtrlItems;
    private static Method mNavGetBar;
    private static Method mBarGetToolbar;
    private static Method mHolderGetView;
    private static Method mCollectionSize;
    private static Method mCollectionGet;
    private static Method mFocalFinderIsFocal;
    private static Method mTweetGetId;
    private static Method mTweetAuthorId;
    private static Method mTweetAuthorHandle;
    private static Method mTweetAuthorName;
    private static Method mHttpCtrlGet;
    private static Method mHttpCtrlEnqueue;
    private static Method mRequestSetListener;
    private static Method mRequestGetResult;
    private static Method mUserCacheAddFlags;
    private static Method mUserCacheHasFlag;

    private static String listenerCompleteMethodName;

    public interface RequestCallback {
        void onComplete(boolean success);
    }

    public static final class ReplyItemData {
        public final long tweetId;
        public final long authorId;
        @NonNull
        public final String handle;
        @NonNull
        public final String displayName;
        @Nullable
        public final Object adContent;
        public final int friendshipFlags;

        ReplyItemData(
                long tweetId,
                long authorId,
                @NonNull String handle,
                @NonNull String displayName,
                @Nullable Object adContent,
                int friendshipFlags
        ) {
            this.tweetId = tweetId;
            this.authorId = authorId;
            this.handle = handle;
            this.displayName = displayName;
            this.adContent = adContent;
            this.friendshipFlags = friendshipFlags;
        }
    }

    private BatchModerationBridge() {
    }

    /**
     * Populates resolved class, field, and method names emitted by the Morphe bytecode patch.
     */
    @NonNull
    private static String getPatchConfig() {
        return "";
    }

    public static synchronized void initConfig(@NonNull String packedConfig) {
        CONFIG.clear();
        String[] lines = packedConfig.split("\n");
        for (String line : lines) {
            int eq = line.indexOf('=');
            if (eq > 0 && eq < line.length() - 1) {
                CONFIG.put(line.substring(0, eq).trim(), line.substring(eq + 1).trim());
            }
        }
        configLoaded = !CONFIG.isEmpty();
        handlesResolved = false;
        resolutionFailed = false;
    }

    static boolean isReady() {
        if (!configLoaded) {
            String packed = getPatchConfig();
            if (packed != null && !packed.isEmpty()) {
                initConfig(packed);
            }
        }
        return configLoaded && ensureResolved();
    }

    private static boolean ensureResolved() {
        if (handlesResolved) return true;
        if (resolutionFailed || !configLoaded) return false;
        synchronized (BatchModerationBridge.class) {
            if (handlesResolved) return true;
            if (resolutionFailed || !configLoaded) return false;
            try {
                Class<?> clsCtrl = loadClass("ctrlClass");
                Class<?> clsViewHost = loadClass("viewHostClass");
                Class<?> clsRecyclerHelper = loadClass("recyclerHelperClass");
                Class<?> clsFocalHelper = loadClass("focalHelperClass");
                Class<?> clsNav = loadClass("navClass");
                clsNavigationBar = loadClass("navBarClass");
                Class<?> clsBindEvent = loadClass("bindEventClass");
                Class<?> clsViewHolder = loadClass("viewHolderClass");
                Class<?> clsCollection = loadClass("collectionClass");
                clsFocalFinder = loadClass("focalFinderClass");
                clsTimelineTweet = loadClass("timelineTweetClass");
                Class<?> clsTweet = loadClass("tweetClass");
                Class<?> clsTweetData = loadClass("tweetCoreDataClass");
                Class<?> clsTweetAuthor = loadClass("tweetDataAuthorClass");
                clsAdContent = loadClass("adContentClass");
                clsBlockReq = loadClass("blockReqClass");
                clsMuteReq = loadClass("muteReqClass");
                clsHttpController = loadClass("httpControllerClass");
                Class<?> clsRequestBase = loadClass("requestBaseClass");
                Class<?> clsAsyncHttpReq = loadClass("asyncHttpReqClass");
                Class<?> clsAsyncOp = loadClass("asyncOpClass");
                Class<?> clsHttpResult = loadClass("httpResultClass");
                clsListenerInterface = loadClass("listenerInterface");
                Class<?> clsUserCache = loadClass("userCacheClass");

                fCtrlActivity = findField(clsCtrl, req("ctrlActivityField"));
                fCtrlUserCache = findField(clsCtrl, req("ctrlUserCacheField"));
                fCtrlViewHost = findField(clsCtrl, req("ctrlViewHostField"));
                fHostRecyclerHelper = findField(clsViewHost, req("hostRecyclerHelperField"));
                fRecyclerHelperView = findField(clsRecyclerHelper, req("recyclerHelperViewField"));
                fCtrlAdapter = findField(clsCtrl, req("ctrlAdapterField"));
                fCtrlFocalHelper = findField(clsCtrl, req("ctrlFocalHelperField"));
                fFocalHelperItem = findField(clsFocalHelper, req("focalHelperItemField"));

                fBindEventHolder = findField(clsBindEvent, req("bindEventHolderField"));
                fBindEventItem = findField(clsBindEvent, req("bindEventItemField"));
                fTimelineTweetCore = findField(clsTimelineTweet, req("timelineTweetCoreField"));
                fTweetCoreData = findField(clsTweet, req("tweetCoreDataField"));
                fTweetDataAuthor = findField(clsTweetData, req("tweetDataAuthorField"));
                fTweetAuthorFriendship = findField(clsTweetAuthor, req("tweetAuthorFriendshipField"));
                fTweetAdContent = findField(clsTweet, req("tweetAdContentField"));
                fHttpResultSuccess = findField(clsHttpResult, req("httpResultSuccessField"));

                mCtrlItems = findMethod(clsCtrl, req("ctrlItemsMethod"));
                mNavGetBar = findMethod(clsNav, req("navGetBarMethod"));
                mBarGetToolbar = findMethod(clsNavigationBar, req("barGetToolbarMethod"));
                mHolderGetView = findMethod(clsViewHolder, req("holderGetViewMethod"));
                mCollectionSize = findMethod(clsCollection, req("collectionSizeMethod"));
                mCollectionGet = findMethod(clsCollection, req("collectionGetMethod"), int.class);
                mFocalFinderIsFocal = findMethod(clsFocalFinder, req("focalFinderMethod"), clsCollection, int.class);

                mTweetGetId = findMethod(clsTweet, req("tweetGetIdMethod"));
                mTweetAuthorId = findMethod(clsTweet, req("tweetAuthorIdMethod"));
                mTweetAuthorHandle = findMethod(clsTweet, req("tweetAuthorHandleMethod"));
                mTweetAuthorName = findMethod(clsTweet, req("tweetAuthorNameMethod"));

                mHttpCtrlGet = findMethod(clsHttpController, req("httpControllerGetMethod"));
                mHttpCtrlEnqueue = findMethod(clsHttpController, req("httpControllerEnqueueMethod"), clsAsyncHttpReq);
                mRequestSetListener = findMethod(clsRequestBase, req("requestSetListenerMethod"), clsListenerInterface);
                mRequestGetResult = findMethod(clsAsyncHttpReq, req("requestGetResultMethod"));

                mUserCacheAddFlags = findMethod(clsUserCache, req("userCacheAddFlagsMethod"), int.class, long.class);
                mUserCacheHasFlag = findMethod(clsUserCache, req("userCacheHasFlagMethod"), int.class, long.class);

                listenerCompleteMethodName = req("listenerCompleteMethod");

                ctorBlockReq = clsBlockReq.getDeclaredConstructor(
                        Context.class,
                        UserIdentifier.class,
                        long.class,
                        clsAdContent,
                        int.class
                );
                ctorBlockReq.setAccessible(true);

                ctorMuteReq = clsMuteReq.getDeclaredConstructor(
                        Activity.class,
                        UserIdentifier.class,
                        long.class,
                        clsAdContent
                );
                ctorMuteReq.setAccessible(true);

                // Ensure unused warning does not happen if any optional field is left null
                fTweetGetId = null;

                handlesResolved = true;
                return true;
            } catch (Throwable t) {
                resolutionFailed = true;
                PikoUtils.logger(t);
                return false;
            }
        }
    }

    @NonNull
    private static String req(@NonNull String key) {
        String val = CONFIG.get(key);
        if (val == null || val.isEmpty()) {
            throw new IllegalStateException("Missing bridge config key: " + key);
        }
        return val;
    }

    @NonNull
    private static Class<?> loadClass(@NonNull String configKey) throws ClassNotFoundException {
        return Class.forName(req(configKey));
    }

    @NonNull
    private static Field findField(@NonNull Class<?> clazz, @NonNull String name) throws NoSuchFieldException {
        Class<?> cur = clazz;
        while (cur != null) {
            try {
                Field f = cur.getDeclaredField(name);
                f.setAccessible(true);
                return f;
            } catch (NoSuchFieldException ignored) {
                cur = cur.getSuperclass();
            }
        }
        throw new NoSuchFieldException("Field " + name + " not found on " + clazz.getName());
    }

    @NonNull
    private static Method findMethod(@NonNull Class<?> clazz, @NonNull String name, Class<?>... params)
            throws NoSuchMethodException {
        Class<?> cur = clazz;
        while (cur != null) {
            try {
                Method m = cur.getDeclaredMethod(name, params);
                m.setAccessible(true);
                return m;
            } catch (NoSuchMethodException ignored) {
                cur = cur.getSuperclass();
            }
        }
        // Also search interfaces implemented by hierarchy
        for (Class<?> iface : clazz.getInterfaces()) {
            try {
                Method m = iface.getMethod(name, params);
                m.setAccessible(true);
                return m;
            } catch (NoSuchMethodException ignored) {
            }
        }
        throw new NoSuchMethodException("Method " + name + " not found on " + clazz.getName());
    }

    @Nullable
    static Activity getControllerActivity(@Nullable Object controller) {
        if (controller == null || !ensureResolved()) return null;
        try {
            Object act = fCtrlActivity.get(controller);
            return act instanceof Activity ? (Activity) act : null;
        } catch (Throwable t) {
            PikoUtils.logger(t);
            return null;
        }
    }

    @Nullable
    static Object getUserCache(@Nullable Object controller) {
        if (controller == null || !ensureResolved()) return null;
        try {
            return fCtrlUserCache.get(controller);
        } catch (Throwable ignored) {
            return null;
        }
    }

    @Nullable
    static View getRecyclerView(@Nullable Object controller) {
        if (controller == null || !ensureResolved()) return null;
        try {
            Object host = fCtrlViewHost.get(controller);
            if (host == null) return null;
            Object helper = fHostRecyclerHelper.get(host);
            if (helper == null) return null;
            Object rv = fRecyclerHelperView.get(helper);
            return rv instanceof View ? (View) rv : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    @Nullable
    static Object getAdapter(@Nullable Object controller) {
        if (controller == null || !ensureResolved()) return null;
        try {
            return fCtrlAdapter.get(controller);
        } catch (Throwable ignored) {
            return null;
        }
    }

    static void notifyAdapterChanged(@Nullable Object adapter) {
        if (adapter == null) return;
        try {
            Method m = adapter.getClass().getMethod("notifyDataSetChanged");
            m.invoke(adapter);
        } catch (Throwable ignored) {
        }
    }

    @Nullable
    static Toolbar getToolbar(@Nullable Object navComponent) {
        if (navComponent == null || !ensureResolved()) return null;
        try {
            Object bar = mNavGetBar.invoke(navComponent);
            if (bar == null) return null;
            Object view = mBarGetToolbar.invoke(bar);
            return view instanceof Toolbar ? (Toolbar) view : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    @Nullable
    static Object getControllerItems(@Nullable Object controller) {
        if (controller == null || !ensureResolved()) return null;
        try {
            return mCtrlItems.invoke(controller);
        } catch (Throwable ignored) {
            return null;
        }
    }

    static long getFocalTweetIdFromController(@Nullable Object controller) {
        if (controller == null || !ensureResolved()) return -1L;
        try {
            Object focalHelper = fCtrlFocalHelper.get(controller);
            if (focalHelper == null) return -1L;
            Object focalTimelineItem = fFocalHelperItem.get(focalHelper);
            if (focalTimelineItem == null) return -1L;
            ReplyItemData data = extractReplyItem(focalTimelineItem);
            return data != null ? data.tweetId : -1L;
        } catch (Throwable ignored) {
            return -1L;
        }
    }

    static int getCollectionSize(@Nullable Object collection) {
        if (collection == null || !ensureResolved()) return 0;
        try {
            Object res = mCollectionSize.invoke(collection);
            return res instanceof Integer ? (Integer) res : 0;
        } catch (Throwable ignored) {
            return 0;
        }
    }

    @Nullable
    static Object getCollectionItem(@Nullable Object collection, int index) {
        if (collection == null || !ensureResolved()) return null;
        try {
            return mCollectionGet.invoke(collection, index);
        } catch (Throwable ignored) {
            return null;
        }
    }

    static boolean isFocalItemAt(@Nullable Object collection, int index) {
        if (collection == null || !ensureResolved()) return false;
        try {
            Object res = mFocalFinderIsFocal.invoke(null, collection, index);
            return res instanceof Boolean && (Boolean) res;
        } catch (Throwable ignored) {
            return false;
        }
    }

    @Nullable
    static View getBoundView(@Nullable Object bindEvent) {
        if (bindEvent == null || !ensureResolved()) return null;
        try {
            Object holder = fBindEventHolder.get(bindEvent);
            if (holder == null) return null;
            Object view = mHolderGetView.invoke(holder);
            return view instanceof View ? (View) view : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    @Nullable
    static Object getBoundItem(@Nullable Object bindEvent) {
        if (bindEvent == null || !ensureResolved()) return null;
        try {
            return fBindEventItem.get(bindEvent);
        } catch (Throwable ignored) {
            return null;
        }
    }

    @Nullable
    static ReplyItemData extractReplyItem(@Nullable Object timelineItem) {
        if (timelineItem == null || !ensureResolved()) return null;
        if (!clsTimelineTweet.isInstance(timelineItem)) return null;
        try {
            Object tweetCore = fTimelineTweetCore.get(timelineItem);
            if (tweetCore == null) return null;

            long tweetId = (Long) mTweetGetId.invoke(tweetCore);
            long authorId = (Long) mTweetAuthorId.invoke(tweetCore);
            String handle = (String) mTweetAuthorHandle.invoke(tweetCore);
            String displayName = (String) mTweetAuthorName.invoke(tweetCore);
            Object adContent = fTweetAdContent.get(tweetCore);

            int friendshipFlags = 0;
            try {
                Object tweetData = fTweetCoreData.get(tweetCore);
                if (tweetData != null) {
                    Object authorObj = fTweetDataAuthor.get(tweetData);
                    if (authorObj != null) {
                        Object fVal = fTweetAuthorFriendship.get(authorObj);
                        if (fVal instanceof Integer) {
                            friendshipFlags = (Integer) fVal;
                        }
                    }
                }
            } catch (Throwable ignored) {
            }

            if (handle == null) handle = "";
            if (displayName == null) displayName = "";

            return new ReplyItemData(tweetId, authorId, handle, displayName, adContent, friendshipFlags);
        } catch (Throwable t) {
            PikoUtils.logger(t);
            return null;
        }
    }

    static boolean hasUserFlag(@Nullable Object userCache, int flag, long userId) {
        if (userCache == null || !ensureResolved()) return false;
        try {
            Object res = mUserCacheHasFlag.invoke(userCache, flag, userId);
            return res instanceof Boolean && (Boolean) res;
        } catch (Throwable ignored) {
            return false;
        }
    }

    static void addUserFlag(@Nullable Object userCache, int flag, long userId) {
        if (userCache == null || !ensureResolved()) return;
        try {
            mUserCacheAddFlags.invoke(userCache, flag, userId);
        } catch (Throwable ignored) {
        }
    }

    static long getCurrentUserId() {
        try {
            UserIdentifier current = UserIdentifier.getCurrent();
            if (current == null) return -1L;
            String idStr = current.getStringId();
            if (idStr != null && !idStr.isEmpty()) {
                return Long.parseLong(idStr);
            }
        } catch (Throwable ignored) {
        }
        return -1L;
    }

    static void dispatchBlockOrMute(
            @NonNull Activity activity,
            long targetUserId,
            @Nullable Object adContent,
            boolean isBlock,
            @NonNull RequestCallback callback
    ) {
        if (!ensureResolved()) {
            callback.onComplete(false);
            return;
        }
        try {
            UserIdentifier currentUser = UserIdentifier.getCurrent();
            if (currentUser == null) {
                callback.onComplete(false);
                return;
            }
            Object safeAdContent = (adContent != null && clsAdContent.isInstance(adContent)) ? adContent : null;
            Object request;
            if (isBlock) {
                request = ctorBlockReq.newInstance(
                        activity.getApplicationContext(),
                        currentUser,
                        targetUserId,
                        safeAdContent,
                        1
                );
            } else {
                request = ctorMuteReq.newInstance(
                        activity,
                        currentUser,
                        targetUserId,
                        safeAdContent
                );
            }

            Object listenerProxy = Proxy.newProxyInstance(
                    clsListenerInterface.getClassLoader(),
                    new Class<?>[]{clsListenerInterface},
                    (proxy, method, args) -> {
                        String mName = method.getName();
                        if ("equals".equals(mName) && args != null && args.length == 1) {
                            return proxy == args[0];
                        }
                        if ("hashCode".equals(mName)) {
                            return System.identityHashCode(proxy);
                        }
                        if ("toString".equals(mName)) {
                            return "BatchModerationListener";
                        }
                        if (listenerCompleteMethodName.equals(mName) && args != null && args.length >= 1) {
                            boolean ok = false;
                            try {
                                Object op = args[0];
                                if (op != null) {
                                    Object httpResult = mRequestGetResult.invoke(op);
                                    if (httpResult != null) {
                                        Object boolVal = fHttpResultSuccess.get(httpResult);
                                        ok = boolVal instanceof Boolean && (Boolean) boolVal;
                                    }
                                }
                            } catch (Throwable ignored) {
                            }
                            callback.onComplete(ok);
                        }
                        return null;
                    }
            );

            mRequestSetListener.invoke(request, listenerProxy);
            Object httpController = mHttpCtrlGet.invoke(null);
            mHttpCtrlEnqueue.invoke(httpController, request);
        } catch (Throwable t) {
            PikoUtils.logger(t);
            callback.onComplete(false);
        }
    }
}
