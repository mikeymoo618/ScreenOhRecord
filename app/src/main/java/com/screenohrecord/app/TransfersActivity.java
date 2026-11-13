package com.screenohrecord.app;

import android.app.Activity;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.View;
import android.view.WindowInsets;
import android.widget.ArrayAdapter;
import android.widget.AdapterView;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;

/** User-configured Wi-Fi transfer destinations. Credentials stay on the device. */
public final class TransfersActivity extends Activity {
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final AtomicBoolean cancelled = new AtomicBoolean();
    private LinearLayout root;
    private Spinner protocol;
    private Switch enabled;
    private EditText host, port, share, folder, domain, username, password, delay;
    private TextView status, shareLabel, domainLabel;
    private Button test, save;
    private boolean busy;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        SharedPreferences p = TransferSettings.preferences(this);
        ScrollView scroll = new ScrollView(this);
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int padding = (int) (24 * getResources().getDisplayMetrics().density);
        root.setPadding(padding, padding, padding, padding);
        scroll.addView(root);
        scroll.setOnApplyWindowInsetsListener((view, insets) -> {
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars());
                view.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            } else view.setPadding(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(),
                    insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom());
            return insets;
        });
        setContentView(scroll);
        scroll.requestApplyInsets();
        button("Done", this::finish);
        label("Automatic video moves", 26);
        label("Completed ScreenOhRecord videos in your current recording folder can move to your server over Wi-Fi. "
                + "Files are read back and verified before the local copy is permanently deleted.", 15);
        enabled = new Switch(this);
        enabled.setText("Enable automatic moves");
        enabled.setId(R.id.transfer_enabled);
        enabled.setChecked(p.getBoolean("enabled", false));
        root.addView(enabled);
        enabled.setOnCheckedChangeListener((view, checked) -> {
            if (!checked) {
                p.edit().putBoolean("enabled", false).apply();
                TransferScheduler.schedule(this);
                status.setText("Automatic moves disabled. Save settings to enable again.");
            }
        });
        label("Protocol", 17);
        protocol = new Spinner(this);
        protocol.setId(R.id.transfer_protocol);
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item,
                new String[]{"SMB (SMB 2/3 server)", "FTP (unencrypted)"});
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        protocol.setAdapter(adapter);
        protocol.setSelection("ftp".equals(p.getString("protocol", "smb")) ? 1 : 0);
        root.addView(protocol);
        host = field("Server hostname or IPv4 address", "192.168.1.1", p.getString("host", ""), R.id.transfer_host);
        port = field("Port", "SMB 445 / FTP 21", Integer.toString(p.getInt("port", 445)), R.id.transfer_port);
        port.setInputType(InputType.TYPE_CLASS_NUMBER);
        shareLabel = label("SMB share name", 17);
        share = input("video files", p.getString("share", ""), R.id.transfer_share);
        folder = field("Destination folder (must already exist)", "videos", p.getString("folder", ""), R.id.transfer_folder);
        domainLabel = label("SMB domain (optional)", 17);
        domain = input("", p.getString("domain", ""), R.id.transfer_domain);
        username = field("Username (blank for SMB guest)", "FTP username or anonymous", p.getString("username", ""), R.id.transfer_username);
        String savedPassword = "";
        try { savedPassword = TransferSettings.password(this); }
        catch (IOException error) { android.widget.Toast.makeText(this, "Re-enter the transfer password", android.widget.Toast.LENGTH_LONG).show(); }
        password = field("Password", "", savedPassword, R.id.transfer_password);
        password.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        password.setSaveEnabled(false);
        delay = field("Minutes after recording completes", "60", Integer.toString(p.getInt("delay", 60)), R.id.transfer_delay);
        delay.setInputType(InputType.TYPE_CLASS_NUMBER);
        label("Save applies connection changes. Checks run about every 15 minutes and Android may defer them. "
                + "Transfers pause during recording. Plain FTP sends credentials and videos unencrypted. "
                + "SMB uses SMB2 and needs a server accepting SMB2 without mandatory encryption.", 14);
        status = label(p.getString("status", "Automatic moves are off until enabled and saved."), 15);
        status.setId(R.id.transfer_status);
        test = button("Test connection", this::testConnection);
        test.setId(R.id.transfer_test);
        save = button("Save settings", this::saveSettings);
        save.setId(R.id.transfer_save);
        protocol.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            private int previous = protocol.getSelectedItemPosition();
            @Override public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (position != previous) { port.setText(position == 0 ? "445" : "21"); previous = position; }
                boolean smb = position == 0;
                share.setVisibility(smb ? View.VISIBLE : View.GONE);
                shareLabel.setVisibility(smb ? View.VISIBLE : View.GONE);
                domain.setVisibility(smb ? View.VISIBLE : View.GONE);
                domainLabel.setVisibility(smb ? View.VISIBLE : View.GONE);
            }
            @Override public void onNothingSelected(AdapterView<?> parent) { }
        });
    }

    private TransferSettings.Config form() throws IOException {
        try {
            return new TransferSettings.Config(protocol.getSelectedItemPosition() == 0 ? "smb" : "ftp",
                    host.getText().toString(), Integer.parseInt(port.getText().toString()), share.getText().toString(),
                    folder.getText().toString(), domain.getText().toString(), username.getText().toString(),
                    password.getText().toString(), Integer.parseInt(delay.getText().toString()), enabled.isChecked(), "");
        } catch (NumberFormatException error) { throw new IOException("Enter a valid port and delay in minutes", error); }
    }

    private void saveSettings() {
        if (busy) return;
        try {
            TransferSettings.Config config = form();
            TransferSettings.save(this, config);
            enabled.setChecked(TransferSettings.preferences(this).getBoolean("enabled", false));
            status.setText(enabled.isChecked() ? "Saved. Automatic moves enabled over Wi-Fi." : "Saved. Automatic moves disabled.");
        } catch (IOException error) { status.setText(error.getMessage()); }
    }

    private void testConnection() {
        if (busy) return;
        final TransferSettings.Config config;
        try { config = form(); }
        catch (IOException error) { status.setText(error.getMessage()); return; }
        if (!TransferEngine.begin()) { status.setText("A transfer or connection test is already running."); return; }
        busy = true;
        test.setEnabled(false);
        save.setEnabled(false);
        status.setText("Testing login, file upload, read-back, rename and cleanup over Wi-Fi…");
        new Thread(() -> {
            String result;
            try {
                TransferEngine.test(this, config, cancelled::get);
                result = "Connection test passed. Upload, checksum, rename and cleanup verified.";
            } catch (Exception error) {
                result = "Connection test failed. Check Wi-Fi, credentials, folder and server permissions.";
                android.util.Log.w("ScreenOhRecord", "Transfer connection test failed", error);
            } finally { TransferEngine.end(); }
            String message = result;
            handler.post(() -> {
                if (cancelled.get()) return;
                busy = false;
                test.setEnabled(true);
                save.setEnabled(true);
                status.setText(message);
            });
        }, "ScreenOhRecord-transfer-test").start();
    }

    private TextView label(String text, int size) {
        TextView view = new TextView(this);
        view.setText(text); view.setTextSize(size); view.setPadding(0, 16, 0, 8);
        root.addView(view);
        return view;
    }

    private EditText field(String title, String hint, String value, int id) {
        label(title, 17);
        return input(hint, value, id);
    }

    private EditText input(String hint, String value, int id) {
        EditText view = new EditText(this);
        view.setId(id); view.setHint(hint); view.setSingleLine(true); view.setText(value);
        view.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        view.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);
        root.addView(view);
        return view;
    }

    private Button button(String title, Runnable action) {
        Button button = new Button(this);
        button.setText(title); button.setOnClickListener(view -> action.run()); root.addView(button);
        return button;
    }

    @Override public void onDestroy() { cancelled.set(true); handler.removeCallbacksAndMessages(null); super.onDestroy(); }
}
