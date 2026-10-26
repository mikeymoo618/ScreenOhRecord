package com.screenohrecord.app;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.DocumentsContract;
import android.view.View;
import android.view.WindowInsets;
import android.widget.ArrayAdapter;
import android.widget.AdapterView;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

/** Local settings; only the device's external-storage document provider is accepted. */
public final class SettingsActivity extends Activity {
    private static final int FOLDER_REQUEST = 20;
    static final String LOCAL_AUTHORITY = "com.android.externalstorage.documents";
    private SharedPreferences preferences;
    private TextView estimate;
    private TextView folder;
    private LinearLayout controls;
    private Spinner codec;
    private boolean codecAvailable;
    private boolean av1Available;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        preferences = RecordingOptions.get(this);
        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int padding = (int) (24 * getResources().getDisplayMetrics().density);
        root.setPadding(padding, padding, padding, padding);
        scroll.addView(root);
        scroll.setOnApplyWindowInsetsListener((view, insets) -> {
            if (Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars());
                view.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            } else {
                view.setPadding(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(),
                        insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom());
            }
            return insets;
        });
        setContentView(scroll);
        scroll.requestApplyInsets();
        Button back = new Button(this);
        back.setText("Done");
        back.setOnClickListener(v -> finish());
        root.addView(back);
        label(root, "Recording settings", 26);
        label(root, "Changes apply to the next recording, including floating controls.", 14);
        controls = new LinearLayout(this);
        controls.setOrientation(LinearLayout.VERTICAL);
        root.addView(controls);
        option("Resolution", R.id.settings_resolution, new String[]{"720p", "1080p"},
                preferences.getInt("quality", 720) >= 1080 ? 1 : 0,
                i -> preferences.edit().putInt("quality", i == 1 ? 1080 : 720).apply());
        option("Frame rate", R.id.settings_fps, new String[]{"15 fps · smaller / less smooth", "30 fps · smoother"},
                RecordingOptions.fps(preferences.getInt("fps", 15)) == 30 ? 1 : 0,
                i -> preferences.edit().putInt("fps", i == 1 ? 30 : 15).apply());
        boolean hevc = RecordingOptions.supportsHevc(this);
        codecAvailable = hevc;
        av1Available = RecordingOptions.supportsCodec(this, "av1");
        String selectedCodec = RecordingOptions.codec(this);
        codec = option("Compression", R.id.settings_codec, codecValues(hevc, av1Available),
                "av1".equals(selectedCodec) ? 2 : "hevc".equals(selectedCodec) ? 1 : 0,
                i -> preferences.edit().putString("codec", i == 2 ? "av1" : i == 1 ? "hevc" : "h264")
                        .putBoolean("hevc", i == 1).apply());
        int[] rates = {250_000, 350_000, 500_000, 1_000_000, 2_000_000, 4_000_000, 6_000_000};
        int current = RecordingOptions.bitrate(preferences.getInt("bitrate", 1_000_000));
        int selected = 0;
        for (int i = 0; i < rates.length; i++) if (rates[i] == current) selected = i;
        option("Video bitrate", R.id.settings_bitrate,
                new String[]{"250 kbps · smallest files", "350 kbps", "500 kbps", "1 Mbps",
                        "2 Mbps", "4 Mbps", "6 Mbps · more detail"}, selected,
                i -> preferences.edit().putInt("bitrate", rates[i]).apply());
        Switch microphone = new Switch(this);
        microphone.setId(R.id.settings_microphone);
        microphone.setText("Microphone audio");
        microphone.setChecked(preferences.getBoolean("microphone", true));
        microphone.setOnCheckedChangeListener((v, checked) -> {
            preferences.edit().putBoolean("microphone", checked).apply(); refresh();
        });
        controls.addView(microphone);
        estimate = label(root, "", 17);
        label(root, "Lower bitrate reduces size but can blur text and motion. HEVC can give similar quality at a lower bitrate; at the same bitrate its file size is similar. Actual size varies. Microphone audio uses AAC at 96 kbps.", 14);
        label(controls, "Save location", 20);
        folder = label(controls, "", 15);
        Button choose = new Button(this);
        choose.setId(R.id.settings_folder);
        choose.setText("Choose local folder");
        choose.setOnClickListener(v -> chooseFolder());
        controls.addView(choose);
        Button reset = new Button(this);
        reset.setText("Use Movies / ScreenOhRecord");
        reset.setOnClickListener(v -> {
            preferences.edit().remove("folder_uri").remove("folder_label").apply(); refresh();
        });
        controls.addView(reset);
        Button updates = new Button(this); updates.setText("App updates"); updates.setId(R.id.open_updates);
        updates.setOnClickListener(v -> startActivity(new Intent(this, UpdatesActivity.class)));
        root.addView(updates);
        label(root, "Only folders on this phone or local removable storage are accepted. Existing recordings stay in their original folder. Other apps may back up shared files if you enable cloud sync.", 14);
        refresh();
    }
    private interface Selection { void save(int index); }
    private Spinner option(String title, int id, String[] values, int selected, Selection selection) {
        label(controls, title, 17);
        Spinner spinner = new Spinner(this);
        spinner.setId(id);
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, values);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinner.setAdapter(adapter);
        spinner.setSelection(selected);
        spinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view, int position, long row) {
                selection.save(position); refresh();
            }
            @Override public void onNothingSelected(AdapterView<?> parent) { }
        });
        controls.addView(spinner);
        return spinner;
    }
    private String[] codecValues(boolean hevc, boolean av1) {
        return new String[]{"H.264 · widely compatible",
                hevc ? "H.265 / HEVC" : "H.265 / HEVC · unavailable for these settings",
                av1 ? "AV1 · try on this device" : "AV1 · unavailable for these settings"};
    }
    private TextView label(LinearLayout parent, String value, int size) {
        TextView text = new TextView(this);
        text.setText(value); text.setTextSize(size); text.setPadding(0, 20, 0, 12);
        parent.addView(text); return text;
    }
    private void refresh() {
        if (codec != null) {
            boolean available = RecordingOptions.supportsHevc(this);
            boolean av1 = RecordingOptions.supportsCodec(this, "av1");
            if (available != codecAvailable || av1 != av1Available) {
                codecAvailable = available;
                av1Available = av1;
                String selected = RecordingOptions.codec(this);
                ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, codecValues(available, av1));
                adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
                codec.setAdapter(adapter);
                codec.setSelection("av1".equals(selected) ? 2 : "hevc".equals(selected) ? 1 : 0);
            }
        }
        if (estimate != null) estimate.setText(RecordingOptions.summary(this));
        if (folder != null) folder.setText(RecordingOptions.folder(this));
    }
    private void chooseFolder() {
        try {
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
                    .putExtra(Intent.EXTRA_LOCAL_ONLY, true)
                    .putExtra(DocumentsContract.EXTRA_INITIAL_URI,
                            DocumentsContract.buildDocumentUri(LOCAL_AUTHORITY, "primary:Movies"))
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                            | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
            startActivityForResult(intent, FOLDER_REQUEST);
        } catch (RuntimeException error) { toast("This phone has no folder picker. Use the default Movies folder."); }
    }
    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request != FOLDER_REQUEST || result != RESULT_OK || data == null || data.getData() == null) return;
        Uri tree = data.getData();
        if (!isLocalTree(tree)) { toast("Choose a folder on this phone or local removable storage."); return; }
        try {
            int flags = data.getFlags() & (Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            if ((flags & Intent.FLAG_GRANT_WRITE_URI_PERMISSION) == 0) throw new SecurityException();
            if ((flags & Intent.FLAG_GRANT_READ_URI_PERMISSION) == 0) throw new SecurityException();
            getContentResolver().takePersistableUriPermission(tree,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            preferences.edit().putString("folder_uri", tree.toString())
                    .putString("folder_label", DocumentsContract.getTreeDocumentId(tree)).apply();
            refresh();
        } catch (RuntimeException error) { toast("Cannot keep access to this folder. Choose another local folder."); }
    }
    static boolean isLocalTree(Uri uri) {
        return "content".equals(uri.getScheme()) && LOCAL_AUTHORITY.equals(uri.getAuthority())
                && DocumentsContract.isTreeUri(uri);
    }
    private void toast(String message) { Toast.makeText(this, message, Toast.LENGTH_LONG).show(); }
}
