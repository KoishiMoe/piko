/*
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 *
 * See the included NOTICE file for GPLv3 §7(b) terms that apply to this code.
 */

package app.morphe.extension.twitter.patches.nativeFeatures.search;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import com.twitter.util.user.UserIdentifier;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;

import app.morphe.extension.crimera.PikoUtils;
import app.morphe.extension.shared.Utils;

/**
 * Resolves cached account creation timestamps from in-memory observations and the app's local
 * SQLite database. Returns -1 when unavailable.
 */
public final class UserCreationLookup {

    private static final int MAX_CACHE_ENTRIES = 512;
    // Lower bound around Twitter launch year (2006) in epoch milliseconds.
    private static final long MIN_VALID_EPOCH_MS = 1_136_073_600_000L;

    private static final Map<Long, Long> ID_CACHE = Collections.synchronizedMap(
            new LinkedHashMap<Long, Long>(128, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<Long, Long> eldest) {
                    return size() > MAX_CACHE_ENTRIES;
                }
            }
    );

    private static final Map<String, Long> HANDLE_CACHE = Collections.synchronizedMap(
            new LinkedHashMap<String, Long>(128, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Long> eldest) {
                    return size() > MAX_CACHE_ENTRIES;
                }
            }
    );

    private UserCreationLookup() {
    }

    public static long normalizeEpochMs(long rawTimestamp) {
        if (rawTimestamp <= 0L) {
            return -1L;
        }
        long ts = rawTimestamp;
        if (ts < 10_000_000_000L) {
            ts *= 1000L;
        }
        long maxValid = System.currentTimeMillis() + 86_400_000L;
        if (ts < MIN_VALID_EPOCH_MS || ts > maxValid) {
            return -1L;
        }
        return ts;
    }

    public static String normalizeHandle(String username) {
        if (username == null) {
            return "";
        }
        String trimmed = username.trim();
        if (trimmed.startsWith("@")) {
            trimmed = trimmed.substring(1).trim();
        }
        return trimmed.toLowerCase(Locale.US);
    }

    public static void cacheUser(long userId, String username, long createdAt) {
        long ts = normalizeEpochMs(createdAt);
        if (ts <= 0L) {
            return;
        }
        if (userId > 0L) {
            ID_CACHE.put(userId, ts);
        }
        String cleanHandle = normalizeHandle(username);
        if (!cleanHandle.isEmpty()) {
            HANDLE_CACHE.put(cleanHandle, ts);
        }
    }

    public static long lookupCreatedAt(long userId, String username) {
        if (userId > 0L) {
            Long cached = ID_CACHE.get(userId);
            if (cached != null && cached > 0L) {
                return cached;
            }
        }
        String cleanHandle = normalizeHandle(username);
        if (!cleanHandle.isEmpty()) {
            Long cached = HANDLE_CACHE.get(cleanHandle);
            if (cached != null && cached > 0L) {
                return cached;
            }
        }

        long fromDb = queryLocalUsersDb(userId, cleanHandle);
        if (fromDb > 0L) {
            cacheUser(userId, cleanHandle, fromDb);
            return fromDb;
        }
        return -1L;
    }

    public static int formatYearUtc(long createdAtMs) {
        long ts = normalizeEpochMs(createdAtMs);
        if (ts <= 0L) {
            return -1;
        }
        Calendar cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"), Locale.US);
        cal.setTimeInMillis(ts);
        return cal.get(Calendar.YEAR);
    }

    /**
     * Returns a pair [sinceDate, untilDate] spanning one year from the account's creation date
     * in UTC (e.g. ["2019-06-14", "2020-06-14"]), or null if createdAtMs is invalid.
     */
    public static String[] buildFirstYearRange(long createdAtMs) {
        long ts = normalizeEpochMs(createdAtMs);
        if (ts <= 0L) {
            return null;
        }
        TimeZone utc = TimeZone.getTimeZone("UTC");
        SimpleDateFormat fmt = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
        fmt.setTimeZone(utc);

        Calendar cal = Calendar.getInstance(utc, Locale.US);
        cal.setTimeInMillis(ts);
        String sinceDate = fmt.format(new Date(cal.getTimeInMillis()));

        cal.add(Calendar.YEAR, 1);
        String untilDate = fmt.format(new Date(cal.getTimeInMillis()));
        return new String[]{sinceDate, untilDate};
    }

    private static long queryLocalUsersDb(long userId, String cleanHandle) {
        if (userId <= 0L && (cleanHandle == null || cleanHandle.isEmpty())) {
            return -1L;
        }
        try {
            File dbFile = resolveMainDbFile();
            if (dbFile == null || !dbFile.isFile()) {
                return -1L;
            }
            try (SQLiteDatabase db = SQLiteDatabase.openDatabase(
                    dbFile.getAbsolutePath(),
                    null,
                    SQLiteDatabase.OPEN_READONLY | SQLiteDatabase.NO_LOCALIZED_COLLATORS
            )) {
                if (userId > 0L) {
                    long res = querySingleLong(
                            db,
                            "SELECT profile_created FROM users WHERE user_id = ? LIMIT 1",
                            new String[]{String.valueOf(userId)}
                    );
                    if (res > 0L) {
                        return res;
                    }
                }
                if (cleanHandle != null && !cleanHandle.isEmpty()) {
                    long res = querySingleLong(
                            db,
                            "SELECT profile_created FROM users WHERE LOWER(username) = ? LIMIT 1",
                            new String[]{cleanHandle}
                    );
                    if (res > 0L) {
                        return res;
                    }
                }
            }
        } catch (Throwable t) {
            PikoUtils.logger(t);
        }
        return -1L;
    }

    private static long querySingleLong(SQLiteDatabase db, String sql, String[] args) {
        try (Cursor cursor = db.rawQuery(sql, args)) {
            if (cursor != null && cursor.moveToFirst() && !cursor.isNull(0)) {
                return normalizeEpochMs(cursor.getLong(0));
            }
        }
        return -1L;
    }

    private static File resolveMainDbFile() {
        Context context = Utils.getContext();
        if (context == null) {
            return null;
        }
        UserIdentifier current = UserIdentifier.getCurrent();
        if (current == null) {
            return null;
        }
        String uid = current.getStringId();
        if (uid == null || uid.isEmpty() || "0".equals(uid) || "-1".equals(uid)) {
            return null;
        }
        File primary = context.getDatabasePath(uid + "-66.db");
        if (primary != null && primary.isFile()) {
            return primary;
        }
        File parent = primary != null ? primary.getParentFile() : null;
        if (parent != null && parent.isDirectory()) {
            String prefix = uid + "-66";
            File[] files = parent.listFiles();
            if (files != null) {
                for (File candidate : files) {
                    String name = candidate.getName();
                    if (candidate.isFile() && name.startsWith(prefix) && name.endsWith(".db")) {
                        return candidate;
                    }
                }
            }
        }
        return null;
    }
}
