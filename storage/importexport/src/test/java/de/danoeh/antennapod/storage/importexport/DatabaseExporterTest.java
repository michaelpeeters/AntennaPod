package de.danoeh.antennapod.storage.importexport;

import android.content.Context;
import android.content.SharedPreferences;
import android.database.sqlite.SQLiteDatabase;
import android.net.Uri;
import androidx.preference.PreferenceManager;
import de.danoeh.antennapod.storage.database.PodDBAdapter;
import de.danoeh.antennapod.storage.preferences.SleepTimerPreferences;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

import java.io.File;
import java.io.FileOutputStream;
import java.util.HashSet;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

@RunWith(RobolectricTestRunner.class)
public class DatabaseExporterTest {
    private Context context;
    private File backupFile;

    @Before
    public void setUp() throws Exception {
        context = RuntimeEnvironment.getApplication();
        PodDBAdapter.init(context);
        PodDBAdapter.deleteDatabase();
        PodDBAdapter adapter = PodDBAdapter.getInstance();
        adapter.open();
        adapter.close();
        backupFile = File.createTempFile("antennapod-backup-test", ".db");
    }

    @After
    public void tearDown() {
        PodDBAdapter.tearDownTests();
        backupFile.delete();
    }

    private void exportToBackupFile() throws Exception {
        try (FileOutputStream out = new FileOutputStream(backupFile)) {
            DatabaseExporter.exportToStream(out, context);
        }
    }

    @Test
    public void testExportWritesPreferencesTable() throws Exception {
        PreferenceManager.getDefaultSharedPreferences(context).edit()
                .putBoolean("some_bool_pref", true)
                .putString("some_string_pref", "hello")
                .apply();

        exportToBackupFile();

        try (SQLiteDatabase db = SQLiteDatabase.openDatabase(
                backupFile.getAbsolutePath(), null, SQLiteDatabase.OPEN_READONLY)) {
            try (android.database.Cursor cursor = db.query("Preferences",
                    new String[]{"file", "key", "type", "value"},
                    "key = ?", new String[]{"some_bool_pref"}, null, null, null)) {
                assertTrue(cursor.moveToFirst());
                assertEquals("default", cursor.getString(0));
                assertEquals("Boolean", cursor.getString(2));
                assertEquals("true", cursor.getString(3));
            }
        }
    }

    @Test
    public void testImportRestoresPreferences() throws Exception {
        Set<String> tags = new HashSet<>();
        tags.add("news");
        tags.add("tech");
        PreferenceManager.getDefaultSharedPreferences(context).edit()
                .putBoolean("some_bool_pref", true)
                .putLong("some_long_pref", 42L)
                .putStringSet("some_set_pref", tags)
                .apply();
        SleepTimerPreferences.init(context);
        SleepTimerPreferences.setVibrate(true);

        exportToBackupFile();

        PreferenceManager.getDefaultSharedPreferences(context).edit().clear().apply();
        context.getSharedPreferences(SleepTimerPreferences.PREF_NAME, Context.MODE_PRIVATE)
                .edit().clear().apply();

        DatabaseExporter.importBackup(Uri.fromFile(backupFile), context);

        SharedPreferences restored = PreferenceManager.getDefaultSharedPreferences(context);
        assertTrue(restored.getBoolean("some_bool_pref", false));
        assertEquals(42L, restored.getLong("some_long_pref", 0));
        assertEquals(tags, restored.getStringSet("some_set_pref", null));
        SleepTimerPreferences.init(context);
        assertTrue(SleepTimerPreferences.vibrate());
    }

    @Test
    public void testImportBackupWithoutPreferencesTableDoesNotThrow() throws Exception {
        PreferenceManager.getDefaultSharedPreferences(context).edit()
                .putBoolean("kept_pref", true)
                .apply();

        exportToBackupFile();
        try (SQLiteDatabase db = SQLiteDatabase.openDatabase(
                backupFile.getAbsolutePath(), null, SQLiteDatabase.OPEN_READWRITE)) {
            db.execSQL("DROP TABLE Preferences");
        }

        DatabaseExporter.importBackup(Uri.fromFile(backupFile), context);

        assertFalse(PreferenceManager.getDefaultSharedPreferences(context).contains("nonexistent"));
    }
}
