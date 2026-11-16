/*
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 *
 * See the included NOTICE file for GPLv3 §7(b) terms that apply to this code.
 */

package app.morphe.extension.twitter.patches.timeline.history;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.database.sqlite.SQLiteStatement;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import app.morphe.extension.crimera.PikoUtils;

/**
 * Local WAL-backed SQLite ring storage for seen posts, partitioned by accountId.
 */
final class TimelineHistoryDatabase extends SQLiteOpenHelper {

    static final int FILTER_ALL = 0;
    static final int FILTER_MEDIA = 1;
    static final int FILTER_QUOTES = 2;

    private static final String DB_NAME = "piko_timeline_history.db";
    private static final int DB_VERSION = 1;
    private static final String TABLE = "timeline_history";

    private static final String SQL_UPDATE =
            "UPDATE " + TABLE + " SET "
                    + "author_handle = CASE WHEN ? <> '' THEN ? ELSE author_handle END, "
                    + "author_name = CASE WHEN ? <> '' THEN ? ELSE author_name END, "
                    + "tweet_text = CASE WHEN ? <> '' THEN ? ELSE tweet_text END, "
                    + "media_count = CASE WHEN ? > media_count THEN ? ELSE media_count END, "
                    + "source_type = ?, "
                    + "is_retweet = ?, "
                    + "last_seen_at = CASE WHEN ? > last_seen_at THEN ? ELSE last_seen_at END "
                    + "WHERE account_id = ? AND tweet_id = ?";

    private static final String SQL_INSERT =
            "INSERT OR IGNORE INTO " + TABLE
                    + "(account_id, tweet_id, author_handle, author_name, tweet_text, media_count, source_type, is_retweet, first_seen_at, last_seen_at) "
                    + "VALUES(?,?,?,?,?,?,?,?,?,?)";

    private static final String SQL_PRUNE_ACCOUNT =
            "DELETE FROM " + TABLE + " WHERE account_id = ? AND id NOT IN ("
                    + "SELECT id FROM " + TABLE + " WHERE account_id = ? "
                    + "ORDER BY last_seen_at DESC, id DESC LIMIT ?)";

    private static volatile TimelineHistoryDatabase instance;
    private final Object dbLock = new Object();

    private TimelineHistoryDatabase(@NonNull Context context) {
        super(context.getApplicationContext(), DB_NAME, null, DB_VERSION);
    }

    @NonNull
    static TimelineHistoryDatabase getInstance(@NonNull Context context) {
        TimelineHistoryDatabase existing = instance;
        if (existing != null) {
            return existing;
        }
        synchronized (TimelineHistoryDatabase.class) {
            if (instance == null) {
                instance = new TimelineHistoryDatabase(context);
            }
            return instance;
        }
    }

    @Override
    public void onConfigure(SQLiteDatabase db) {
        super.onConfigure(db);
        try {
            db.enableWriteAheadLogging();
        } catch (Throwable ignored) {
        }
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE IF NOT EXISTS " + TABLE + " ("
                + "id INTEGER PRIMARY KEY AUTOINCREMENT, "
                + "account_id TEXT NOT NULL, "
                + "tweet_id INTEGER NOT NULL, "
                + "author_handle TEXT NOT NULL, "
                + "author_name TEXT NOT NULL, "
                + "tweet_text TEXT NOT NULL, "
                + "media_count INTEGER NOT NULL DEFAULT 0, "
                + "source_type INTEGER NOT NULL DEFAULT 0, "
                + "is_retweet INTEGER NOT NULL DEFAULT 0, "
                + "first_seen_at INTEGER NOT NULL, "
                + "last_seen_at INTEGER NOT NULL, "
                + "UNIQUE(account_id, tweet_id)"
                + ")");
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_history_acct_time "
                + "ON " + TABLE + "(account_id, last_seen_at DESC, id DESC)");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        // Initial schema version 1.
    }

    void upsertBatch(@NonNull List<TimelineHistoryEntry> entries, int maxPerAccount) {
        if (entries.isEmpty()) {
            return;
        }
        int cap = Math.max(1, maxPerAccount);
        synchronized (dbLock) {
            SQLiteDatabase db = null;
            SQLiteStatement updateStmt = null;
            SQLiteStatement insertStmt = null;
            SQLiteStatement pruneStmt = null;
            try {
                db = getWritableDatabase();
                db.beginTransactionNonExclusive();

                updateStmt = db.compileStatement(SQL_UPDATE);
                insertStmt = db.compileStatement(SQL_INSERT);
                Set<String> touchedAccounts = new HashSet<>();

                for (TimelineHistoryEntry entry : entries) {
                    touchedAccounts.add(entry.accountId);

                    updateStmt.clearBindings();
                    updateStmt.bindString(1, entry.authorHandle);
                    updateStmt.bindString(2, entry.authorHandle);
                    updateStmt.bindString(3, entry.authorName);
                    updateStmt.bindString(4, entry.authorName);
                    updateStmt.bindString(5, entry.text);
                    updateStmt.bindString(6, entry.text);
                    updateStmt.bindLong(7, entry.mediaCount);
                    updateStmt.bindLong(8, entry.mediaCount);
                    updateStmt.bindLong(9, entry.sourceType);
                    updateStmt.bindLong(10, entry.isRetweet ? 1L : 0L);
                    updateStmt.bindLong(11, entry.lastSeenAt);
                    updateStmt.bindLong(12, entry.lastSeenAt);
                    updateStmt.bindString(13, entry.accountId);
                    updateStmt.bindLong(14, entry.tweetId);

                    if (updateStmt.executeUpdateDelete() == 0) {
                        insertStmt.clearBindings();
                        insertStmt.bindString(1, entry.accountId);
                        insertStmt.bindLong(2, entry.tweetId);
                        insertStmt.bindString(3, entry.authorHandle);
                        insertStmt.bindString(4, entry.authorName);
                        insertStmt.bindString(5, entry.text);
                        insertStmt.bindLong(6, entry.mediaCount);
                        insertStmt.bindLong(7, entry.sourceType);
                        insertStmt.bindLong(8, entry.isRetweet ? 1L : 0L);
                        insertStmt.bindLong(9, entry.firstSeenAt);
                        insertStmt.bindLong(10, entry.lastSeenAt);
                        insertStmt.executeInsert();
                    }
                }

                pruneStmt = db.compileStatement(SQL_PRUNE_ACCOUNT);
                for (String acct : touchedAccounts) {
                    pruneStmt.clearBindings();
                    pruneStmt.bindString(1, acct);
                    pruneStmt.bindString(2, acct);
                    pruneStmt.bindLong(3, cap);
                    pruneStmt.executeUpdateDelete();
                }

                db.setTransactionSuccessful();
            } catch (Throwable t) {
                PikoUtils.logger(t);
            } finally {
                closeQuietly(pruneStmt);
                closeQuietly(insertStmt);
                closeQuietly(updateStmt);
                if (db != null && db.inTransaction()) {
                    try {
                        db.endTransaction();
                    } catch (Throwable ignored) {
                    }
                }
            }
        }
    }

    @NonNull
    List<TimelineHistoryEntry> query(
            @NonNull String accountId,
            @Nullable String rawQuery,
            int filterMode,
            int limit
    ) {
        List<TimelineHistoryEntry> results = new ArrayList<>();
        String trimmed = rawQuery == null ? "" : rawQuery.trim();
        int safeLimit = Math.max(1, limit);

        StringBuilder where = new StringBuilder("account_id = ?");
        List<String> args = new ArrayList<>();
        args.add(accountId);

        if (filterMode == FILTER_MEDIA) {
            where.append(" AND media_count > 0");
        } else if (filterMode == FILTER_QUOTES) {
            where.append(" AND source_type = ?");
            args.add(String.valueOf(TimelineHistoryEntry.SOURCE_QUOTE));
        }

        if (!trimmed.isEmpty()) {
            String escaped = escapeLike(trimmed);
            String pattern = "%" + escaped + "%";
            where.append(" AND (author_handle LIKE ? ESCAPE '\\' OR author_name LIKE ? ESCAPE '\\' OR tweet_text LIKE ? ESCAPE '\\')");
            args.add(pattern);
            args.add(pattern);
            args.add(pattern);
        }

        synchronized (dbLock) {
            try {
                SQLiteDatabase db = getReadableDatabase();
                try (Cursor c = db.query(
                        TABLE,
                        new String[]{
                                "tweet_id", "account_id", "author_handle", "author_name",
                                "tweet_text", "media_count", "source_type", "is_retweet",
                                "first_seen_at", "last_seen_at"
                        },
                        where.toString(),
                        args.toArray(new String[0]),
                        null,
                        null,
                        "last_seen_at DESC, id DESC",
                        String.valueOf(safeLimit)
                )) {
                    while (c.moveToNext()) {
                        results.add(new TimelineHistoryEntry(
                                c.getLong(0),
                                nonNull(c.getString(1)),
                                nonNull(c.getString(2)),
                                nonNull(c.getString(3)),
                                nonNull(c.getString(4)),
                                c.getInt(5),
                                c.getInt(6),
                                c.getInt(7) != 0,
                                c.getLong(8),
                                c.getLong(9)
                        ));
                    }
                }
            } catch (Throwable t) {
                PikoUtils.logger(t);
            }
        }
        return results;
    }

    int getCount(@NonNull String accountId) {
        synchronized (dbLock) {
            try {
                SQLiteDatabase db = getReadableDatabase();
                try (Cursor c = db.rawQuery(
                        "SELECT COUNT(*) FROM " + TABLE + " WHERE account_id = ?",
                        new String[]{accountId}
                )) {
                    if (c.moveToFirst()) {
                        return c.getInt(0);
                    }
                }
            } catch (Throwable t) {
                PikoUtils.logger(t);
            }
        }
        return 0;
    }

    void pruneAccount(@NonNull String accountId, int maxPerAccount) {
        int cap = Math.max(1, maxPerAccount);
        synchronized (dbLock) {
            try {
                SQLiteDatabase db = getWritableDatabase();
                db.execSQL(SQL_PRUNE_ACCOUNT, new Object[]{accountId, accountId, cap});
            } catch (Throwable t) {
                PikoUtils.logger(t);
            }
        }
    }

    void deleteEntry(@NonNull String accountId, long tweetId) {
        synchronized (dbLock) {
            try {
                SQLiteDatabase db = getWritableDatabase();
                db.delete(TABLE, "account_id = ? AND tweet_id = ?",
                        new String[]{accountId, String.valueOf(tweetId)});
            } catch (Throwable t) {
                PikoUtils.logger(t);
            }
        }
    }

    void clearAccount(@NonNull String accountId) {
        synchronized (dbLock) {
            try {
                SQLiteDatabase db = getWritableDatabase();
                db.delete(TABLE, "account_id = ?", new String[]{accountId});
            } catch (Throwable t) {
                PikoUtils.logger(t);
            }
        }
    }

    private static String escapeLike(@NonNull String input) {
        return input
                .replace("\\", "\\\\")
                .replace("%", "\\%")
                .replace("_", "\\_");
    }

    @NonNull
    private static String nonNull(@Nullable String s) {
        return s == null ? "" : s;
    }

    private static void closeQuietly(@Nullable SQLiteStatement stmt) {
        if (stmt != null) {
            try {
                stmt.close();
            } catch (Throwable ignored) {
            }
        }
    }
}
