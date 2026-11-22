package com.remoteapp.windesktop;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.text.InputType;
import android.text.method.PasswordTransformationMethod;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.List;

public class LoginActivity extends Activity {

    private static final String PREF_NAME = "wlremote_profiles_v1";
    private static final String KEY_PROFILES = "profiles";

    private EditText ipEdit, portEdit, userEdit, passEdit;
    private Button loginBtn, btnWinOs, btnLinuxOs;
    private TextView statusText, subtitleText, userLabel, ipDropArrow;
    private LinearLayout ipDropdownPanel, ipDropdownListContainer;
    private boolean dropdownExpanded = false;

    private String selectedOs = "windows";
    private final List<HostProfile> savedProfiles = new ArrayList<HostProfile>();

    private static class HostProfile {
        String os;
        String ip;
        int port;
        String user;
        String pass;
    }

    private int dp(int value) {
        float density = getResources().getDisplayMetrics().density;
        return (int) (value * density + 0.5f);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        try {
            loadProfiles();
            setContentView(buildLoginUI());
            if (!savedProfiles.isEmpty()) {
                applyProfile(savedProfiles.get(0));
            } else {
                updateOsSelection("windows", true);
            }
        } catch (Throwable t) {
            showCrashFallback(t);
        }
    }

    private void showCrashFallback(Throwable t) {
        StringWriter sw = new StringWriter();
        t.printStackTrace(new PrintWriter(sw));
        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(Color.parseColor("#0F172A"));
        TextView tv = new TextView(this);
        tv.setTextColor(Color.parseColor("#FCA5A5"));
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        tv.setPadding(24, 48, 24, 24);
        tv.setText("WLRemote 启动异常：\n\n" + sw.toString());
        sv.addView(tv);
        setContentView(sv);
    }

    private View buildLoginUI() {
        ScrollView scroll = new ScrollView(this);
        scroll.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
        ));
        scroll.setBackgroundColor(Color.parseColor("#0F172A"));
        scroll.setFillViewport(true);

        LinearLayout outer = new LinearLayout(this);
        outer.setOrientation(LinearLayout.VERTICAL);
        outer.setGravity(Gravity.CENTER);
        outer.setPadding(dp(20), dp(24), dp(20), dp(24));
        scroll.addView(outer, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackgroundColor(Color.parseColor("#1E293B"));
        card.setPadding(dp(22), dp(22), dp(22), dp(22));
        outer.addView(card, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));

        TextView title = new TextView(this);
        title.setText("WLRemote");
        title.setTextColor(Color.parseColor("#F8FAFC"));
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 23);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setGravity(Gravity.CENTER);
        card.addView(title);

        subtitleText = new TextView(this);
        subtitleText.setText("支持 Windows 原生远程桌面 (RDP) 与 Linux (SSH)");
        subtitleText.setTextColor(Color.parseColor("#38BDF8"));
        subtitleText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        subtitleText.setGravity(Gravity.CENTER);
        subtitleText.setPadding(0, dp(4), 0, dp(14));
        card.addView(subtitleText);

        // OS Selector Row (Windows vs Linux)
        card.addView(makeLabel("远程主机系统 / 协议"));
        LinearLayout osRow = new LinearLayout(this);
        osRow.setOrientation(LinearLayout.HORIZONTAL);
        osRow.setPadding(0, dp(4), 0, dp(10));
        card.addView(osRow);

        btnWinOs = new Button(this);
        btnWinOs.setText("Windows (RDP)");
        btnWinOs.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        btnWinOs.setAllCaps(false);
        LinearLayout.LayoutParams osLp1 = new LinearLayout.LayoutParams(0, dp(42), 1f);
        osLp1.rightMargin = dp(6);
        osRow.addView(btnWinOs, osLp1);

        btnLinuxOs = new Button(this);
        btnLinuxOs.setText("Linux (SSH)");
        btnLinuxOs.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        btnLinuxOs.setAllCaps(false);
        LinearLayout.LayoutParams osLp2 = new LinearLayout.LayoutParams(0, dp(42), 1f);
        osLp2.leftMargin = dp(6);
        osRow.addView(btnLinuxOs, osLp2);

        btnWinOs.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                updateOsSelection("windows", true);
            }
        });
        btnLinuxOs.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                updateOsSelection("linux", true);
            }
        });

        // Unified Single IP Combo-Box / Dropdown (no extra external buttons)
        card.addView(makeLabel("远程计算机 IP"));
        LinearLayout ipComboBox = new LinearLayout(this);
        ipComboBox.setOrientation(LinearLayout.HORIZONTAL);
        ipComboBox.setGravity(Gravity.CENTER_VERTICAL);
        ipComboBox.setBackgroundColor(Color.parseColor("#0F172A"));
        card.addView(ipComboBox, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));

        ipEdit = new EditText(this);
        ipEdit.setSingleLine(true);
        ipEdit.setHint("请输入或点击右侧下拉选择 IP");
        ipEdit.setHintTextColor(Color.parseColor("#64748B"));
        ipEdit.setTextColor(Color.parseColor("#F8FAFC"));
        ipEdit.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        ipEdit.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        ipEdit.setPadding(dp(12), dp(10), dp(6), dp(10));
        ipEdit.setBackgroundColor(Color.TRANSPARENT);
        LinearLayout.LayoutParams ipEditLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        ipComboBox.addView(ipEdit, ipEditLp);

        // Integrated chevron arrow inside the right edge of the IP dropdown box
        ipDropArrow = new TextView(this);
        ipDropArrow.setText("▾");
        ipDropArrow.setTextColor(Color.parseColor("#38BDF8"));
        ipDropArrow.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        ipDropArrow.setGravity(Gravity.CENTER);
        ipDropArrow.setPadding(dp(14), dp(8), dp(14), dp(8));
        ipComboBox.addView(ipDropArrow, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.MATCH_PARENT
        ));

        // Expandable Dropdown List directly below the IP box (contains history items + inline delete '✕')
        ipDropdownPanel = new LinearLayout(this);
        ipDropdownPanel.setOrientation(LinearLayout.VERTICAL);
        ipDropdownPanel.setBackgroundColor(Color.parseColor("#0B1120"));
        ipDropdownPanel.setPadding(dp(2), dp(2), dp(2), dp(2));
        ipDropdownPanel.setVisibility(View.GONE);
        LinearLayout.LayoutParams dropPanelLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        dropPanelLp.topMargin = dp(1);
        card.addView(ipDropdownPanel, dropPanelLp);

        ipDropdownListContainer = new LinearLayout(this);
        ipDropdownListContainer.setOrientation(LinearLayout.VERTICAL);
        ipDropdownPanel.addView(ipDropdownListContainer, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));

        ipDropArrow.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                toggleIpDropdown();
            }
        });

        card.addView(makeLabel("端口 (Windows 默认 3389 / Linux SSH 默认 22)"));
        portEdit = new EditText(this);
        styleInput(portEdit, "3389", InputType.TYPE_CLASS_NUMBER, false);
        portEdit.setText("3389");
        card.addView(portEdit);

        userLabel = makeLabel("Windows 用户名 (支持本地账户 / 域/用户名 / 微软账户)");
        card.addView(userLabel);
        userEdit = new EditText(this);
        styleInput(userEdit, "Administrator", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PERSON_NAME, false);
        card.addView(userEdit);

        card.addView(makeLabel("登录密码"));
        passEdit = new EditText(this);
        styleInput(passEdit, "请输入密码", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD, true);
        card.addView(passEdit);

        loginBtn = new Button(this);
        loginBtn.setText("登录远程主机");
        loginBtn.setTextColor(Color.WHITE);
        loginBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        loginBtn.setTypeface(Typeface.DEFAULT_BOLD);
        loginBtn.setAllCaps(false);
        loginBtn.setBackgroundColor(Color.parseColor("#2563EB"));
        LinearLayout.LayoutParams btnLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(48)
        );
        btnLp.topMargin = dp(18);
        card.addView(loginBtn, btnLp);

        statusText = new TextView(this);
        statusText.setTextColor(Color.parseColor("#94A3B8"));
        statusText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        statusText.setGravity(Gravity.CENTER);
        statusText.setPadding(0, dp(12), 0, 0);
        statusText.setText("提示：登录成功后将自动保存至 IP 下拉框并切换横屏");
        card.addView(statusText);

        loginBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                attemptLogin();
            }
        });

        return scroll;
    }

    private void toggleIpDropdown() {
        if (dropdownExpanded) {
            closeIpDropdown();
        } else {
            openIpDropdown();
        }
    }

    private void openIpDropdown() {
        rebuildIpDropdownRows();
        dropdownExpanded = true;
        ipDropArrow.setText("▴");
        ipDropdownPanel.setVisibility(View.VISIBLE);
    }

    private void closeIpDropdown() {
        dropdownExpanded = false;
        ipDropArrow.setText("▾");
        ipDropdownPanel.setVisibility(View.GONE);
    }

    private void rebuildIpDropdownRows() {
        ipDropdownListContainer.removeAllViews();
        if (savedProfiles.isEmpty()) {
            TextView emptyTv = new TextView(this);
            emptyTv.setText("暂无历史 IP 记录（登录成功后自动保存）");
            emptyTv.setTextColor(Color.parseColor("#64748B"));
            emptyTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
            emptyTv.setPadding(dp(12), dp(12), dp(12), dp(12));
            ipDropdownListContainer.addView(emptyTv);
            return;
        }

        for (int i = 0; i < savedProfiles.size(); i++) {
            final int idx = i;
            final HostProfile p = savedProfiles.get(i);

            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setBackgroundColor(Color.parseColor(i % 2 == 0 ? "#0F172A" : "#162032"));
            row.setPadding(dp(12), dp(4), dp(6), dp(4));

            String osTag = "linux".equals(p.os) ? "Linux/SSH" : "Win/RDP";
            TextView itemTv = new TextView(this);
            itemTv.setText(p.ip + "   [" + osTag + " :" + p.port + " · " + p.user + "]");
            itemTv.setTextColor(Color.parseColor("#E2E8F0"));
            itemTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
            itemTv.setPadding(0, dp(8), dp(8), dp(8));
            itemTv.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    applyProfile(p);
                    closeIpDropdown();
                }
            });
            row.addView(itemTv, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            TextView delBtn = new TextView(this);
            delBtn.setText("✕");
            delBtn.setTextColor(Color.parseColor("#F87171"));
            delBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
            delBtn.setGravity(Gravity.CENTER);
            delBtn.setPadding(dp(12), dp(8), dp(12), dp(8));
            delBtn.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    savedProfiles.remove(idx);
                    saveProfilesToPrefs();
                    if (ipEdit.getText().toString().trim().equals(p.ip)) {
                        if (!savedProfiles.isEmpty()) {
                            applyProfile(savedProfiles.get(0));
                        } else {
                            ipEdit.setText("");
                            passEdit.setText("");
                        }
                    }
                    rebuildIpDropdownRows();
                }
            });
            row.addView(delBtn, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
            ));

            ipDropdownListContainer.addView(row, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
            ));
        }
    }

    private void updateOsSelection(String os, boolean autoAdjustPort) {
        selectedOs = "linux".equalsIgnoreCase(os) ? "linux" : "windows";
        boolean isLinux = "linux".equals(selectedOs);

        btnWinOs.setBackgroundColor(Color.parseColor(isLinux ? "#0F172A" : "#0284C7"));
        btnWinOs.setTextColor(isLinux ? Color.parseColor("#94A3B8") : Color.WHITE);

        btnLinuxOs.setBackgroundColor(Color.parseColor(isLinux ? "#0284C7" : "#0F172A"));
        btnLinuxOs.setTextColor(isLinux ? Color.WHITE : Color.parseColor("#94A3B8"));

        if (autoAdjustPort) {
            String curPort = portEdit.getText().toString().trim();
            if (isLinux && (curPort.isEmpty() || "3389".equals(curPort))) {
                portEdit.setText("22");
            } else if (!isLinux && (curPort.isEmpty() || "22".equals(curPort))) {
                portEdit.setText("3389");
            }
        }

        if (isLinux) {
            userLabel.setText("Linux SSH 用户名 (例如 root / ubuntu)");
            userEdit.setHint("root");
            subtitleText.setText("当前协议：Linux SSH (端口 22，支持终端命令栏与 SSH-VNC 桌面)");
        } else {
            userLabel.setText("Windows 用户名 (支持本地账户 / 域/用户名 / 微软账户)");
            userEdit.setHint("Administrator");
            subtitleText.setText("当前协议：Windows 原生远程桌面 (RDP 端口 3389)");
        }
    }

    private TextView makeLabel(String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextColor(Color.parseColor("#CBD5E1"));
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        tv.setPadding(0, dp(10), 0, dp(4));
        return tv;
    }

    private void styleInput(EditText et, String hint, int inputType, boolean isPassword) {
        // IMPORTANT: setSingleLine(true) MUST be called BEFORE setInputType/setTransformationMethod
        // otherwise Android resets PasswordTransformationMethod to plaintext!
        et.setSingleLine(true);
        et.setHint(hint);
        et.setHintTextColor(Color.parseColor("#64748B"));
        et.setTextColor(Color.parseColor("#F8FAFC"));
        et.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        et.setInputType(inputType);
        if (isPassword) {
            et.setTransformationMethod(PasswordTransformationMethod.getInstance());
        }
        et.setPadding(dp(12), dp(10), dp(12), dp(10));
        et.setBackgroundColor(Color.parseColor("#0F172A"));
    }

    private void applyProfile(HostProfile p) {
        if (p == null) return;
        updateOsSelection(p.os, false);
        ipEdit.setText(p.ip);
        portEdit.setText(String.valueOf(p.port));
        userEdit.setText(p.user);
        passEdit.setTransformationMethod(PasswordTransformationMethod.getInstance());
        passEdit.setText(p.pass);
    }

    private void saveOrUpdateProfile(String os, String ip, int port, String user, String pass) {
        for (int i = savedProfiles.size() - 1; i >= 0; i--) {
            HostProfile p = savedProfiles.get(i);
            if (ip.equals(p.ip) && os.equals(p.os)) {
                savedProfiles.remove(i);
            }
        }
        HostProfile np = new HostProfile();
        np.os = os;
        np.ip = ip;
        np.port = port;
        np.user = user;
        np.pass = pass;
        savedProfiles.add(0, np);
        while (savedProfiles.size() > 20) {
            savedProfiles.remove(savedProfiles.size() - 1);
        }
        saveProfilesToPrefs();
    }

    private void loadProfiles() {
        savedProfiles.clear();
        try {
            SharedPreferences sp = getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
            String raw = sp.getString(KEY_PROFILES, "");
            if (raw == null || raw.isEmpty()) return;
            String[] lines = raw.split("\n");
            for (String line : lines) {
                if (line.trim().isEmpty()) continue;
                String[] parts = line.split("\t", -1);
                if (parts.length >= 5) {
                    HostProfile p = new HostProfile();
                    p.os = decodeField(parts[0]);
                    p.ip = decodeField(parts[1]);
                    p.port = Integer.parseInt(decodeField(parts[2]));
                    p.user = decodeField(parts[3]);
                    p.pass = decodeField(parts[4]);
                    savedProfiles.add(p);
                }
            }
        } catch (Exception ignored) {}
    }

    private void saveProfilesToPrefs() {
        try {
            StringBuilder sb = new StringBuilder();
            for (HostProfile p : savedProfiles) {
                sb.append(encodeField(p.os)).append('\t')
                  .append(encodeField(p.ip)).append('\t')
                  .append(encodeField(String.valueOf(p.port))).append('\t')
                  .append(encodeField(p.user)).append('\t')
                  .append(encodeField(p.pass)).append('\n');
            }
            SharedPreferences sp = getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
            sp.edit().putString(KEY_PROFILES, sb.toString()).apply();
        } catch (Exception ignored) {}
    }

    private String encodeField(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\t", "\\t").replace("\n", "\\n");
    }

    private String decodeField(String s) {
        if (s == null) return "";
        return s.replace("\\n", "\n").replace("\\t", "\t").replace("\\\\", "\\");
    }

    private void attemptLogin() {
        closeIpDropdown();
        final String ip = ipEdit.getText().toString().trim();
        final String portStr = portEdit.getText().toString().trim();
        final String user = userEdit.getText().toString().trim();
        final String pass = passEdit.getText().toString();
        final String os = selectedOs;

        if (ip.isEmpty()) {
            Toast.makeText(this, "请输入远程计算机 IP", Toast.LENGTH_LONG).show();
            return;
        }
        if (user.isEmpty()) {
            Toast.makeText(this, "请输入登录用户名", Toast.LENGTH_LONG).show();
            return;
        }

        int defaultPort = "linux".equals(os) ? 22 : 3389;
        int parsedPort = defaultPort;
        try {
            if (!portStr.isEmpty()) {
                parsedPort = Integer.parseInt(portStr);
            }
        } catch (NumberFormatException ignored) {}
        final int port = parsedPort;

        saveOrUpdateProfile(os, ip, port, user, pass);

        if ("linux".equals(os)) {
            Intent intent = new Intent(LoginActivity.this, LinuxSshActivity.class);
            intent.putExtra("ip", ip);
            intent.putExtra("port", port);
            intent.putExtra("username", user);
            intent.putExtra("password", pass);
            startActivity(intent);
            return;
        }

        loginBtn.setEnabled(false);
        loginBtn.setText("正在握手 Windows 远程桌面...");
        statusText.setText("正在探测 " + ip + ":" + port + " 原生 RDP 服务...");

        new Thread(new Runnable() {
            @Override
            public void run() {
                String errMsg = null;
                try {
                    RdpHandshakeChecker.verifyRdpEndpoint(ip, port);
                } catch (Exception ex) {
                    errMsg = "连接失败：" + ex.getMessage();
                }
                final String error = errMsg;
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        loginBtn.setEnabled(true);
                        loginBtn.setText("登录远程主机");
                        if (error == null) {
                            statusText.setText("RDP 握手成功，正在进入横屏桌面...");
                            Intent intent = new Intent(LoginActivity.this, RemoteDesktopActivity.class);
                            intent.putExtra("ip", ip);
                            intent.putExtra("port", port);
                            intent.putExtra("username", user);
                            intent.putExtra("password", pass);
                            startActivity(intent);
                        } else {
                            statusText.setText(error);
                            Toast.makeText(LoginActivity.this, error, Toast.LENGTH_LONG).show();
                        }
                    }
                });
            }
        }).start();
    }
}
