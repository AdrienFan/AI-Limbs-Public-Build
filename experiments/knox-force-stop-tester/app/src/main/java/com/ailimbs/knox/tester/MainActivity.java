package com.ailimbs.knox.tester;

import android.app.Activity;
import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.text.InputType;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

public class MainActivity extends Activity {
    private static final String TARGET = "com.ai.assistance.operit.ailimbs.v0647";
    private EditText licenseInput;
    private TextView logView;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        buildUi();
        append("Target: " + TARGET);
        appendAdminState();
        showLastLicenseResult();
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        root.setPadding(pad, pad, pad, pad);

        licenseInput = new EditText(this);
        licenseInput.setHint("KPE Development Key (not stored)");
        licenseInput.setSingleLine(false);
        licenseInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);
        root.addView(licenseInput);

        addButton(root, "1. Activate Device Admin", v -> requestAdmin());
        addButton(root, "2. Activate KPE License", v -> activateLicense());
        addButton(root, "3. Query Force Stop Blocklist", v -> queryBlocklist());
        addButton(root, "4. Add AI Limbs V0.6.4.7", v -> setBlocklist(true));
        addButton(root, "5. Remove AI Limbs V0.6.4.7", v -> setBlocklist(false));
        addButton(root, "Refresh status", v -> { appendAdminState(); showLastLicenseResult(); });

        logView = new TextView(this);
        logView.setTextIsSelectable(true);
        ScrollView scroll = new ScrollView(this);
        scroll.addView(logView);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1f));
        setContentView(root);
    }

    private void addButton(LinearLayout root, String title, View.OnClickListener listener) {
        Button button = new Button(this);
        button.setText(title);
        button.setOnClickListener(listener);
        root.addView(button);
    }

    private void requestAdmin() {
        ComponentName admin = new ComponentName(this, AdminReceiver.class);
        DevicePolicyManager dpm = (DevicePolicyManager) getSystemService(DEVICE_POLICY_SERVICE);
        if (dpm.isAdminActive(admin)) {
            append("Device Admin: already active");
            return;
        }
        Intent intent = new Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN);
        intent.putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, admin);
        intent.putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                "Required only for the Knox force-stop blocklist experiment.");
        startActivity(intent);
    }

    private void appendAdminState() {
        DevicePolicyManager dpm = (DevicePolicyManager) getSystemService(DEVICE_POLICY_SERVICE);
        ComponentName admin = new ComponentName(this, AdminReceiver.class);
        append("Device Admin active: " + dpm.isAdminActive(admin));
    }

    private void activateLicense() {
        String key = licenseInput.getText().toString().trim();
        if (key.isEmpty()) {
            append("KPE key is empty.");
            return;
        }
        try {
            Class<?> cls = Class.forName("com.samsung.android.knox.license.KnoxEnterpriseLicenseManager");
            Object manager = cls.getMethod("getInstance", Context.class).invoke(null, this);
            try {
                cls.getMethod("activateLicense", String.class, String.class)
                        .invoke(manager, key, getPackageName());
            } catch (NoSuchMethodException oldApi) {
                cls.getMethod("activateLicense", String.class).invoke(manager, key);
            }
            licenseInput.setText("");
            append("KPE activation requested. Wait for Samsung license result/EULA.");
        } catch (Throwable t) {
            appendError("activateLicense", t);
        }
    }

    private Object getApplicationPolicy() throws Exception {
        Class<?> edmClass = Class.forName("com.samsung.android.knox.EnterpriseDeviceManager");
        Object edm = edmClass.getMethod("getInstance", Context.class).invoke(null, this);
        return edmClass.getMethod("getApplicationPolicy").invoke(edm);
    }

    @SuppressWarnings("unchecked")
    private List<String> readBlocklist() throws Exception {
        Object policy = getApplicationPolicy();
        Method method = policy.getClass().getMethod("getPackagesFromForceStopBlackList");
        Object result = method.invoke(policy);
        return result == null ? new ArrayList<>() : (List<String>) result;
    }

    private void queryBlocklist() {
        try {
            List<String> list = readBlocklist();
            append("Force Stop Blocklist contains target: " + list.contains(TARGET));
            append("Blocklist size: " + list.size());
        } catch (Throwable t) {
            appendError("queryBlocklist", t);
        }
    }

    private void setBlocklist(boolean add) {
        try {
            Object policy = getApplicationPolicy();
            ArrayList<String> targets = new ArrayList<>();
            targets.add(TARGET);
            String name = add ? "addPackagesToForceStopBlackList" : "removePackagesFromForceStopBlackList";
            Method method = policy.getClass().getMethod(name, List.class);
            Object result = method.invoke(policy, targets);
            append(name + " returned: " + String.valueOf(result));
            List<String> after = readBlocklist();
            append("Verified contains target: " + after.contains(TARGET));
        } catch (Throwable t) {
            appendError(add ? "addBlocklist" : "removeBlocklist", t);
        }
    }

    private void showLastLicenseResult() {
        String result = getSharedPreferences("state", MODE_PRIVATE)
                .getString("last_license_result", "No license result received yet.");
        append(result);
    }

    private void append(String text) {
        if (logView != null) logView.append(text + "\n\n");
    }

    private void appendError(String where, Throwable error) {
        Throwable t = error;
        if (t instanceof InvocationTargetException && ((InvocationTargetException) t).getCause() != null) {
            t = ((InvocationTargetException) t).getCause();
        }
        append(where + " ERROR: " + t.getClass().getName() + ": " + String.valueOf(t.getMessage()));
    }
}
