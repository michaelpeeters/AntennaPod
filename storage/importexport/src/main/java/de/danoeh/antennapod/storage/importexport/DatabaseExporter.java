package de.danoeh.antennapod.storage.importexport;

import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteException;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.text.format.Formatter;
import android.util.Log;
import de.danoeh.antennapod.storage.database.PodDBAdapter;
import de.danoeh.antennapod.storage.preferences.SleepTimerPreferences;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;
import org.json.JSONArray;
import org.json.JSONException;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class DatabaseExporter {
    private static final String TAG = "DatabaseExporter";
    private static final String TEMP_DB_NAME = PodDBAdapter.DATABASE_NAME + "_tmp";

    private static final String TABLE_PREFERENCES = "Preferences";
    private static final String PREFS_TAG_DEFAULT = "default";
    private static final String[] PREFERENCES_TAGS = {PREFS_TAG_DEFAULT, SleepTimerPreferences.PREF_NAME};

    private static SharedPreferences preferencesForTag(String tag, Context context) {
        if (PREFS_TAG_DEFAULT.equals(tag)) {
            return context.getSharedPreferences(context.getPackageName() + "_preferences", Context.MODE_PRIVATE);
        }
        return context.getSharedPreferences(tag, Context.MODE_PRIVATE);
    }

    public static void exportToDocument(Uri uri, Context context) throws IOException {
        ParcelFileDescriptor pfd = context.getContentResolver().openFileDescriptor(uri, "wt");
        int bytesCopied = -1;
        int resultingFileSize = 0;
        try (FileOutputStream fileOutputStream = new FileOutputStream(pfd.getFileDescriptor())) {
            bytesCopied = exportToStream(fileOutputStream, context);
        } catch (IOException e) {
            Log.e(TAG, Log.getStackTraceString(e));
            throw e;
        } finally {
            resultingFileSize = (int) pfd.getStatSize();
            IOUtils.closeQuietly(pfd);
        }
        if (resultingFileSize != bytesCopied) {
            throw new IOException(String.format(
                    "Unable to write entire database. Expected to write %s, but wrote %s.",
                    Formatter.formatShortFileSize(context, bytesCopied),
                    Formatter.formatShortFileSize(context, resultingFileSize)));
        }
    }

    public static int exportToStream(FileOutputStream outFileStream, Context context) throws IOException {
        File currentDB = context.getDatabasePath(PodDBAdapter.DATABASE_NAME);
        if (!currentDB.exists()) {
            throw new IOException("Cannot access current database");
        }
        File tempDB = context.getDatabasePath(TEMP_DB_NAME);
        try {
            PodDBAdapter adapter = PodDBAdapter.getInstance();
            adapter.open();
            adapter.walCheckpoint();
            adapter.close();
            FileUtils.copyFile(currentDB, tempDB);
            try (SQLiteDatabase tempDbHandle = SQLiteDatabase.openDatabase(
                    tempDB.getAbsolutePath(), null, SQLiteDatabase.OPEN_READWRITE)) {
                if (tempDbHandle.getVersion() != PodDBAdapter.VERSION) {
                    throw new IOException("Database version mismatch. Expected: " + PodDBAdapter.VERSION
                            + ", found: " + tempDbHandle.getVersion());
                }
                exportPreferences(tempDbHandle, context);
            }
            try (InputStream src = new FileInputStream(tempDB)) {
                return IOUtils.copy(src, outFileStream);
            }
        } catch (IOException | SQLiteException e) {
            Log.e(TAG, Log.getStackTraceString(e));
            throw e;
        } finally {
            boolean deleted = tempDB.delete();
            Log.d(TAG, "Deleted temp database file: " + deleted);
        }
    }

    public static void importBackup(Uri inputUri, Context context) throws IOException {
        InputStream inputStream = null;
        try {
            File tempDB = context.getDatabasePath(TEMP_DB_NAME);
            inputStream = context.getContentResolver().openInputStream(inputUri);
            FileUtils.copyInputStreamToFile(inputStream, tempDB);

            SQLiteDatabase db = SQLiteDatabase.openDatabase(tempDB.getAbsolutePath(),
                    null, SQLiteDatabase.OPEN_READONLY);
            if (db.getVersion() > PodDBAdapter.VERSION) {
                throw new IOException(context.getString(R.string.import_no_downgrade));
            }
            final List<ContentValues> importedPreferences = readPreferences(db);
            db.close();

            File currentDB = context.getDatabasePath(PodDBAdapter.DATABASE_NAME);
            if (!currentDB.delete()) {
                throw new IOException("Unable to delete old database");
            }
            for (String suffix : new String[]{"-wal", "-shm", "-journal"}) {
                File sidecarFile = new File(currentDB.getAbsolutePath() + suffix);
                boolean success = sidecarFile.delete();
                Log.d(TAG, "Deleting sidecar file: " + sidecarFile.getAbsolutePath() + ", success: " + success);
            }
            FileUtils.moveFile(tempDB, currentDB);
            importPreferences(importedPreferences, context);
        } catch (IOException | SQLiteException e) {
            Log.e(TAG, Log.getStackTraceString(e));
            throw e;
        } finally {
            IOUtils.closeQuietly(inputStream);
        }
    }

    private static void exportPreferences(SQLiteDatabase db, Context context) {
        db.execSQL("DROP TABLE IF EXISTS " + TABLE_PREFERENCES);
        db.execSQL("CREATE TABLE " + TABLE_PREFERENCES
                + " (file TEXT NOT NULL, key TEXT NOT NULL, type TEXT NOT NULL, value TEXT, PRIMARY KEY(file, key))");
        for (String tag : PREFERENCES_TAGS) {
            for (Map.Entry<String, ?> entry : preferencesForTag(tag, context).getAll().entrySet()) {
                Object value = entry.getValue();
                ContentValues values = new ContentValues();
                values.put("file", tag);
                values.put("key", entry.getKey());
                if (value instanceof Set) {
                    values.put("type", "StringSet");
                    values.put("value", new JSONArray((Set<?>) value).toString());
                } else {
                    values.put("type", value.getClass().getSimpleName());
                    values.put("value", String.valueOf(value));
                }
                db.insert(TABLE_PREFERENCES, null, values);
            }
        }
    }

    private static List<ContentValues> readPreferences(SQLiteDatabase db) {
        List<ContentValues> rows = new ArrayList<>();
        try (Cursor cursor = db.query(TABLE_PREFERENCES, new String[]{"file", "key", "type", "value"},
                null, null, null, null, null)) {
            while (cursor.moveToNext()) {
                ContentValues values = new ContentValues();
                values.put("file", cursor.getString(0));
                values.put("key", cursor.getString(1));
                values.put("type", cursor.getString(2));
                values.put("value", cursor.getString(3));
                rows.add(values);
            }
        } catch (SQLiteException e) {
            Log.d(TAG, "No preferences table found in imported backup: " + e.getMessage());
        }
        return rows;
    }

    private static void importPreferences(List<ContentValues> rows, Context context) {
        Map<String, SharedPreferences.Editor> editors = new HashMap<>();
        for (ContentValues row : rows) {
            String tag = row.getAsString("file");
            SharedPreferences.Editor editor = editors.computeIfAbsent(tag,
                    t -> preferencesForTag(t, context).edit());
            String key = row.getAsString("key");
            String value = row.getAsString("value");
            switch (row.getAsString("type")) {
                case "Boolean" -> editor.putBoolean(key, Boolean.parseBoolean(value));
                case "Long" -> editor.putLong(key, Long.parseLong(value));
                case "Integer" -> editor.putInt(key, Integer.parseInt(value));
                case "Float" -> editor.putFloat(key, Float.parseFloat(value));
                case "StringSet" -> editor.putStringSet(key, jsonArrayToStringSet(value));
                default -> editor.putString(key, value);
            }
        }
        for (SharedPreferences.Editor editor : editors.values()) {
            editor.apply();
        }
    }

    private static Set<String> jsonArrayToStringSet(String json) {
        Set<String> result = new HashSet<>();
        try {
            JSONArray array = new JSONArray(json);
            for (int i = 0; i < array.length(); i++) {
                result.add(array.optString(i));
            }
        } catch (JSONException e) {
            Log.e(TAG, Log.getStackTraceString(e));
        }
        return result;
    }
}
