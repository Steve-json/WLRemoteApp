package com.remoteapp.windesktop;

import android.app.Activity;
import android.content.Context;
import android.content.pm.ActivityInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.util.TypedValue;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.net.Socket;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;

/**
 * Linux SSH 远程控制界面（v11 手机软键盘输入友好版）：
 * 1. 底部常驻「可视化命令输入栏 (EditText)」：完美兼容手机中英文输入法、退格修改、长按粘贴、光标移动与软键盘回车直接发送
 * 2. 配备「Tab 补全」「回车执行」及常用 Linux 符号/指令快捷栏（▲上条、▼下条、Ctrl+C、/、-、|、~、ls -la、cd ..、clear、sudo 等）
 * 3. 支持一键通过 SSH 隧道切换至 Linux 图形桌面 (VNC 5900/5901)
 */
public class LinuxSshActivity extends Activity {

    private static final Charset UTF8 = Charset.forName("UTF-8");

    private PureSsh2Client sshClient;
    private PureSsh2Client.SshChannel sshChannel;
    private volatile boolean closing = false;
    private volatile boolean vncActive = false;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private TextView terminalTextView;
    private ScrollView terminalScrollView;
    private EditText cmdInputEdit;
    private LinearLayout bottomInputDock;
    private LinuxVncView vncView;
    private Button btnSwitchVnc;

    private final StringBuilder terminalBuffer = new StringBuilder();
    private float terminalTextSizeSp = 13f;
    private final List<String> cmdHistory = new ArrayList<String>();
    private int historyIndex = -1;

    private int dp(int value) {
        float density = getResources().getDisplayMetrics().density;
        return (int) (value * density + 0.5f);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        setContentView(buildLinuxUI());

        final String ip = getIntent().getStringExtra("ip");
        final int port = getIntent().getIntExtra("port", 22);
        final String username = getIntent().getStringExtra("username");
        final String password = getIntent().getStringExtra("password");

        appendTerminal("[WLRemote] 正在通过 SSH-2 连接 " + username + "@" + ip + ":" + port + " ...\n");
        connectSsh(ip, port, username, password);
    }

    private View buildLinuxUI() {
        LinearLayout mainVertical = new LinearLayout(this);
        mainVertical.setOrientation(LinearLayout.VERTICAL);
        mainVertical.setBackgroundColor(Color.parseColor("#0B1120"));
        mainVertical.setKeepScreenOn(true);

        // 1. Top Toolbar Bar (Fixed height 40dp)
        HorizontalScrollView topBarScroll = new HorizontalScrollView(this);
        topBarScroll.setBackgroundColor(Color.parseColor("#1E293B"));
        topBarScroll.setPadding(dp(6), 0, dp(6), 0);
        topBarScroll.setHorizontalScrollBarEnabled(false);
        mainVertical.addView(topBarScroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(40)
        ));

        LinearLayout topRow = new LinearLayout(this);
        topRow.setOrientation(LinearLayout.HORIZONTAL);
        topRow.setGravity(Gravity.CENTER_VERTICAL);
        topBarScroll.addView(topRow);

        Button btnCtrlC = makeBarButton("Ctrl+C(中断)", "#FBBF24");
        Button btnCtrlD = makeBarButton("Ctrl+D", "#F8FAFC");
        Button btnCtrlZ = makeBarButton("Ctrl+Z", "#F8FAFC");
        Button btnEsc = makeBarButton("Esc", "#F8FAFC");
        Button btnClear = makeBarButton("清屏", "#38BDF8");
        Button btnZoomIn = makeBarButton("字+", "#F8FAFC");
        Button btnZoomOut = makeBarButton("字-", "#F8FAFC");
        btnSwitchVnc = makeBarButton("图形桌面(SSH隧道)", "#4ADE80");
        Button btnDisconnect = makeBarButton("断开", "#F87171");

        topRow.addView(btnCtrlC);
        topRow.addView(btnCtrlD);
        topRow.addView(btnCtrlZ);
        topRow.addView(btnEsc);
        topRow.addView(btnClear);
        topRow.addView(btnZoomIn);
        topRow.addView(btnZoomOut);
        topRow.addView(btnSwitchVnc);
        topRow.addView(btnDisconnect);

        // 2. Center Content Area (Terminal ScrollView + VNC View)
        FrameLayout centerFrame = new FrameLayout(this);
        mainVertical.addView(centerFrame, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
        ));

        terminalScrollView = new ScrollView(this);
        terminalScrollView.setFillViewport(true);
        terminalScrollView.setPadding(dp(10), dp(6), dp(10), dp(6));
        centerFrame.addView(terminalScrollView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
        ));

        terminalTextView = new TextView(this);
        terminalTextView.setTypeface(Typeface.MONOSPACE);
        terminalTextView.setTextColor(Color.parseColor("#E2E8F0"));
        terminalTextView.setTextSize(TypedValue.COMPLEX_UNIT_SP, terminalTextSizeSp);
        terminalTextView.setTextIsSelectable(true);
        terminalScrollView.addView(terminalTextView, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));

        vncView = new LinuxVncView(this);
        vncView.setVisibility(View.GONE);
        centerFrame.addView(vncView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
        ));

        // 3. Bottom Mobile-Friendly Command Input Dock
        bottomInputDock = new LinearLayout(this);
        bottomInputDock.setOrientation(LinearLayout.VERTICAL);
        bottomInputDock.setBackgroundColor(Color.parseColor("#1E293B"));
        mainVertical.addView(bottomInputDock, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));

        // 3A. Quick Key & Common Linux Command Strip
        HorizontalScrollView quickScroll = new HorizontalScrollView(this);
        quickScroll.setBackgroundColor(Color.parseColor("#0F172A"));
        quickScroll.setPadding(dp(4), dp(2), dp(4), dp(2));
        quickScroll.setHorizontalScrollBarEnabled(false);
        bottomInputDock.addView(quickScroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(36)
        ));

        LinearLayout quickRow = new LinearLayout(this);
        quickRow.setOrientation(LinearLayout.HORIZONTAL);
        quickRow.setGravity(Gravity.CENTER_VERTICAL);
        quickScroll.addView(quickRow);

        addQuickAction(quickRow, "▲上条", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (!cmdHistory.isEmpty()) {
                    if (historyIndex < 0) historyIndex = cmdHistory.size() - 1;
                    else if (historyIndex > 0) historyIndex--;
                    String cmd = cmdHistory.get(historyIndex);
                    cmdInputEdit.setText(cmd);
                    cmdInputEdit.setSelection(cmd.length());
                } else {
                    sendRawBytes("\u001b[A".getBytes(UTF8));
                }
            }
        }, "#38BDF8");

        addQuickAction(quickRow, "▼下条", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (!cmdHistory.isEmpty() && historyIndex >= 0) {
                    if (historyIndex < cmdHistory.size() - 1) {
                        historyIndex++;
                        String cmd = cmdHistory.get(historyIndex);
                        cmdInputEdit.setText(cmd);
                        cmdInputEdit.setSelection(cmd.length());
                    } else {
                        historyIndex = -1;
                        cmdInputEdit.setText("");
                    }
                } else {
                    sendRawBytes("\u001b[B".getBytes(UTF8));
                }
            }
        }, "#38BDF8");

        addQuickInsert(quickRow, "/");
        addQuickInsert(quickRow, "-");
        addQuickInsert(quickRow, "|");
        addQuickInsert(quickRow, "~");
        addQuickInsert(quickRow, "_");
        addQuickInsert(quickRow, "*");
        addQuickCmd(quickRow, "ls -la");
        addQuickCmd(quickRow, "cd ..");
        addQuickCmd(quickRow, "pwd");
        addQuickCmd(quickRow, "df -h");
        addQuickCmd(quickRow, "ps aux");
        addQuickInsert(quickRow, "sudo ");
        addQuickInsert(quickRow, "cat ");
        addQuickInsert(quickRow, "tail -f ");
        addQuickInsert(quickRow, "grep ");
        addQuickCmd(quickRow, ":wq!");

        // 3B. Visible EditText Input Row + Tab Complete + Send/Enter Button
        LinearLayout editorRow = new LinearLayout(this);
        editorRow.setOrientation(LinearLayout.HORIZONTAL);
        editorRow.setGravity(Gravity.CENTER_VERTICAL);
        editorRow.setPadding(dp(8), dp(6), dp(8), dp(6));
        bottomInputDock.addView(editorRow, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));

        cmdInputEdit = new EditText(this);
        cmdInputEdit.setSingleLine(true);
        cmdInputEdit.setHint("在此输入 Linux 命令或文本 (支持粘贴/修改)，点右侧回车执行...");
        cmdInputEdit.setHintTextColor(Color.parseColor("#64748B"));
        cmdInputEdit.setTextColor(Color.parseColor("#F8FAFC"));
        cmdInputEdit.setTypeface(Typeface.MONOSPACE);
        cmdInputEdit.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        cmdInputEdit.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        cmdInputEdit.setImeOptions(EditorInfo.IME_ACTION_SEND | EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        cmdInputEdit.setPadding(dp(10), dp(8), dp(10), dp(8));
        cmdInputEdit.setBackgroundColor(Color.parseColor("#0F172A"));
        editorRow.addView(cmdInputEdit, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        Button btnTabComplete = new Button(this);
        btnTabComplete.setText("Tab补全");
        btnTabComplete.setTextColor(Color.parseColor("#F8FAFC"));
        btnTabComplete.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        btnTabComplete.setAllCaps(false);
        btnTabComplete.setBackgroundColor(Color.parseColor("#334155"));
        LinearLayout.LayoutParams tabLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(38));
        tabLp.leftMargin = dp(6);
        editorRow.addView(btnTabComplete, tabLp);

        Button btnSendEnter = new Button(this);
        btnSendEnter.setText("回车执行");
        btnSendEnter.setTextColor(Color.WHITE);
        btnSendEnter.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        btnSendEnter.setTypeface(Typeface.DEFAULT_BOLD);
        btnSendEnter.setAllCaps(false);
        btnSendEnter.setBackgroundColor(Color.parseColor("#0284C7"));
        LinearLayout.LayoutParams sendLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(38));
        sendLp.leftMargin = dp(6);
        editorRow.addView(btnSendEnter, sendLp);

        btnSendEnter.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                submitCommandFromEditor();
            }
        });

        btnTabComplete.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                String current = cmdInputEdit.getText().toString();
                if (!current.isEmpty()) {
                    sendRawBytes((current + "\t").getBytes(UTF8));
                    cmdInputEdit.setText("");
                } else {
                    sendRawBytes(new byte[]{'\t'});
                }
            }
        });

        cmdInputEdit.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            @Override
            public boolean onEditorAction(TextView v, int actionId, KeyEvent event) {
                if (actionId == EditorInfo.IME_ACTION_SEND
                        || actionId == EditorInfo.IME_ACTION_DONE
                        || (event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER && event.getAction() == KeyEvent.ACTION_DOWN)) {
                    submitCommandFromEditor();
                    return true;
                }
                return false;
            }
        });

        btnCtrlC.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                sendRawBytes(new byte[]{0x03});
            }
        });
        btnCtrlD.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                sendRawBytes(new byte[]{0x04});
            }
        });
        btnCtrlZ.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                sendRawBytes(new byte[]{0x1a});
            }
        });
        btnEsc.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                sendRawBytes(new byte[]{0x1b});
            }
        });
        btnClear.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                terminalBuffer.setLength(0);
                terminalTextView.setText("");
                sendRawBytes("clear\r".getBytes(UTF8));
            }
        });
        btnZoomIn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                terminalTextSizeSp = Math.min(24f, terminalTextSizeSp + 2f);
                terminalTextView.setTextSize(TypedValue.COMPLEX_UNIT_SP, terminalTextSizeSp);
            }
        });
        btnZoomOut.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                terminalTextSizeSp = Math.max(9f, terminalTextSizeSp - 2f);
                terminalTextView.setTextSize(TypedValue.COMPLEX_UNIT_SP, terminalTextSizeSp);
            }
        });
        btnSwitchVnc.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                toggleVncOverSsh();
            }
        });
        btnDisconnect.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                closeSession();
            }
        });

        return mainVertical;
    }

    private void submitCommandFromEditor() {
        String cmd = cmdInputEdit.getText().toString();
        if (!cmd.trim().isEmpty()) {
            cmdHistory.add(cmd);
            while (cmdHistory.size() > 50) {
                cmdHistory.remove(0);
            }
        }
        historyIndex = -1;
        sendRawBytes((cmd + "\r").getBytes(UTF8));
        cmdInputEdit.setText("");
        cmdInputEdit.requestFocus();
    }

    private void addQuickAction(LinearLayout row, String label, View.OnClickListener listener, String colorHex) {
        Button b = makeBarButton(label, colorHex);
        b.setOnClickListener(listener);
        row.addView(b);
    }

    private void addQuickInsert(LinearLayout row, final String text) {
        Button b = makeBarButton(text, "#CBD5E1");
        b.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                String cur = cmdInputEdit.getText().toString();
                String next = cur + text;
                cmdInputEdit.setText(next);
                cmdInputEdit.setSelection(next.length());
                cmdInputEdit.requestFocus();
            }
        });
        row.addView(b);
    }

    private void addQuickCmd(LinearLayout row, final String cmd) {
        Button b = makeBarButton(cmd, "#93C5FD");
        b.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                sendRawBytes((cmd + "\r").getBytes(UTF8));
            }
        });
        row.addView(b);
    }

    private Button makeBarButton(String text, String colorHex) {
        Button b = new Button(this);
        b.setText(text);
        b.setTextColor(Color.parseColor(colorHex));
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        b.setAllCaps(false);
        b.setBackgroundColor(Color.TRANSPARENT);
        b.setPadding(dp(10), dp(4), dp(10), dp(4));
        b.setMinWidth(0);
        b.setMinimumWidth(0);
        return b;
    }

    private void connectSsh(final String ip, final int port, final String user, final String pass) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    sshClient = new PureSsh2Client();
                    sshClient.connectAndLogin(ip, port, user, pass, 12000);
                    sshChannel = sshClient.openPtyShell(120, 36);

                    mainHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            Toast.makeText(LinuxSshActivity.this, "Linux SSH 已连接，可在底部命令栏直接输入或点快捷指令", Toast.LENGTH_LONG).show();
                            cmdInputEdit.requestFocus();
                        }
                    });

                    byte[] buf = new byte[4096];
                    int len;
                    while (!closing && (len = sshChannel.readData(buf, 0, buf.length)) != -1) {
                        final String chunk = stripAnsiSequences(new String(buf, 0, len, UTF8));
                        if (!chunk.isEmpty()) {
                            mainHandler.post(new Runnable() {
                                @Override
                                public void run() {
                                    appendTerminal(chunk);
                                }
                            });
                        }
                    }
                } catch (final Exception e) {
                    if (!closing) {
                        mainHandler.post(new Runnable() {
                            @Override
                            public void run() {
                                appendTerminal("\n[SSH 连接失败] " + e.getMessage() + "\n");
                                Toast.makeText(LinuxSshActivity.this, "SSH 连接失败: " + e.getMessage(), Toast.LENGTH_LONG).show();
                            }
                        });
                    }
                }
            }
        }).start();
    }

    private void appendTerminal(String text) {
        terminalBuffer.append(text);
        if (terminalBuffer.length() > 30000) {
            terminalBuffer.delete(0, terminalBuffer.length() - 25000);
        }
        terminalTextView.setText(terminalBuffer.toString());
        terminalScrollView.post(new Runnable() {
            @Override
            public void run() {
                terminalScrollView.fullScroll(View.FOCUS_DOWN);
            }
        });
    }

    private String stripAnsiSequences(String s) {
        String cleaned = s.replaceAll("\u001b\\[[0-9;?]*[a-zA-Z]", "");
        cleaned = cleaned.replaceAll("\u001b\\][^\u0007\u001b]*(\u0007|\u001b\\\\)", "");
        cleaned = cleaned.replace("\r\n", "\n").replace("\r", "");
        return cleaned;
    }

    private void sendRawBytes(final byte[] data) {
        if (sshChannel == null || data == null) return;
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    sshChannel.writeData(data, 0, data.length);
                } catch (Exception ignored) {}
            }
        }).start();
    }

    private void toggleVncOverSsh() {
        if (vncActive) {
            vncActive = false;
            vncView.disconnect();
            vncView.setVisibility(View.GONE);
            terminalScrollView.setVisibility(View.VISIBLE);
            bottomInputDock.setVisibility(View.VISIBLE);
            btnSwitchVnc.setText("图形桌面(SSH隧道)");
            return;
        }
        if (sshClient == null || !sshClient.isConnected()) {
            Toast.makeText(this, "SSH 尚未连接成功", Toast.LENGTH_LONG).show();
            return;
        }
        Toast.makeText(this, "正在通过 SSH 隧道探测 Linux 本机图形桌面 (5900/5901)...", Toast.LENGTH_LONG).show();
        new Thread(new Runnable() {
            @Override
            public void run() {
                Exception lastErr = null;
                for (int remotePort : new int[]{5900, 5901}) {
                    try {
                        int localPort = sshClient.startLocalPortForwarding("127.0.0.1", remotePort);
                        vncView.connect("127.0.0.1", localPort);
                        vncActive = true;
                        mainHandler.post(new Runnable() {
                            @Override
                            public void run() {
                                terminalScrollView.setVisibility(View.GONE);
                                bottomInputDock.setVisibility(View.GONE);
                                vncView.setVisibility(View.VISIBLE);
                                btnSwitchVnc.setText("返回SSH终端");
                                Toast.makeText(LinuxSshActivity.this, "已通过 SSH 隧道进入 Linux 图形桌面", Toast.LENGTH_LONG).show();
                            }
                        });
                        return;
                    } catch (Exception e) {
                        lastErr = e;
                    }
                }
                final String msg = lastErr != null ? lastErr.getMessage() : "未检测到 VNC 服务";
                mainHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        Toast.makeText(LinuxSshActivity.this,
                                "未检测到 Linux 本机 5900/5901 图形桌面 (" + msg + ")，当前可直接使用 SSH 终端控制",
                                Toast.LENGTH_LONG).show();
                    }
                });
            }
        }).start();
    }

    private void closeSession() {
        closing = true;
        new Thread(new Runnable() {
            @Override
            public void run() {
                try { vncView.disconnect(); } catch (Exception ignored) {}
                try { if (sshChannel != null) sshChannel.close(); } catch (Exception ignored) {}
                try { if (sshClient != null) sshClient.disconnect(); } catch (Exception ignored) {}
            }
        }).start();
        finish();
    }

    @Override
    protected void onDestroy() {
        closing = true;
        try { vncView.disconnect(); } catch (Exception ignored) {}
        try { if (sshChannel != null) sshChannel.close(); } catch (Exception ignored) {}
        try { if (sshClient != null) sshClient.disconnect(); } catch (Exception ignored) {}
        super.onDestroy();
    }

    private static class LinuxVncView extends View {
        private Socket socket;
        private DataInputStream in;
        private DataOutputStream out;
        private volatile boolean running = false;
        private Bitmap frame;
        private int fbW = 1280, fbH = 720;
        private final Object lock = new Object();
        private final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG);
        private final Matrix matrix = new Matrix();
        private float scale = 1f, minScale = 1f, tx = 0f, ty = 0f;
        private float lastX, lastY;
        private ScaleGestureDetector scaleDet;
        private GestureDetector gestDet;

        public LinuxVncView(Context ctx) {
            super(ctx);
            scaleDet = new ScaleGestureDetector(ctx, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
                @Override
                public boolean onScale(ScaleGestureDetector d) {
                    float prev = scale;
                    scale = Math.max(minScale, Math.min(5f, scale * d.getScaleFactor()));
                    tx = d.getFocusX() - (d.getFocusX() - tx) * (scale / prev);
                    ty = d.getFocusY() - (d.getFocusY() - ty) * (scale / prev);
                    invalidate();
                    return true;
                }
            });
            gestDet = new GestureDetector(ctx, new GestureDetector.SimpleOnGestureListener() {
                @Override
                public boolean onSingleTapConfirmed(MotionEvent e) {
                    int rx = (int) Math.max(0, Math.min(fbW - 1, (e.getX() - tx) / scale));
                    int ry = (int) Math.max(0, Math.min(fbH - 1, (e.getY() - ty) / scale));
                    sendPointer(rx, ry, 1);
                    sendPointer(rx, ry, 0);
                    return true;
                }
            });
        }

        public void connect(String host, int port) throws Exception {
            socket = new Socket(host, port);
            socket.setSoTimeout(15000);
            in = new DataInputStream(socket.getInputStream());
            out = new DataOutputStream(socket.getOutputStream());

            byte[] ver = new byte[12];
            in.readFully(ver);
            out.write("RFB 003.008\n".getBytes("US-ASCII"));
            out.flush();

            int nTypes = in.readUnsignedByte();
            if (nTypes == 0) throw new Exception("VNC 拒绝连接");
            byte[] types = new byte[nTypes];
            in.readFully(types);
            boolean hasNone = false;
            for (byte t : types) if (t == 1) hasNone = true;
            if (!hasNone) throw new Exception("Linux 本机 VNC 需要密码或未开放免密本地回环");
            out.writeByte(1);
            out.flush();

            int secRes = in.readInt();
            if (secRes != 0) throw new Exception("VNC 认证失败");

            out.writeByte(1);
            out.flush();

            fbW = in.readUnsignedShort();
            fbH = in.readUnsignedShort();
            byte[] pf = new byte[16];
            in.readFully(pf);
            int nameLen = in.readInt();
            if (nameLen > 0 && nameLen < 4096) in.readFully(new byte[nameLen]);

            out.writeByte(0);
            out.write(new byte[3]);
            out.write(new byte[]{32, 24, 0, 1, 0, (byte)255, 0, (byte)255, 0, (byte)255, 16, 8, 0, 0, 0, 0});
            out.writeByte(2);
            out.writeByte(0);
            out.writeShort(1);
            out.writeInt(0);
            out.flush();

            synchronized (lock) {
                frame = Bitmap.createBitmap(fbW, fbH, Bitmap.Config.ARGB_8888);
            }
            running = true;
            post(new Runnable() {
                @Override
                public void run() {
                    if (getWidth() > 0 && getHeight() > 0) {
                        minScale = Math.min((float) getWidth() / fbW, (float) getHeight() / fbH);
                        scale = minScale;
                        tx = (getWidth() - fbW * scale) / 2f;
                        ty = (getHeight() - fbH * scale) / 2f;
                    }
                }
            });

            new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        socket.setSoTimeout(0);
                        requestUpdate(false);
                        while (running) {
                            int msgType = in.readUnsignedByte();
                            if (msgType == 0) {
                                in.readUnsignedByte();
                                int rects = in.readUnsignedShort();
                                for (int i = 0; i < rects; i++) {
                                    int x = in.readUnsignedShort();
                                    int y = in.readUnsignedShort();
                                    int w = in.readUnsignedShort();
                                    int h = in.readUnsignedShort();
                                    int enc = in.readInt();
                                    if (enc == 0) {
                                        byte[] raw = new byte[w * h * 4];
                                        in.readFully(raw);
                                        int[] pixels = new int[w * h];
                                        for (int p = 0; p < pixels.length; p++) {
                                            int b = raw[p * 4] & 0xFF;
                                            int g = raw[p * 4 + 1] & 0xFF;
                                            int r = raw[p * 4 + 2] & 0xFF;
                                            pixels[p] = 0xFF000000 | (r << 16) | (g << 8) | b;
                                        }
                                        synchronized (lock) {
                                            if (frame != null) {
                                                frame.setPixels(pixels, 0, w, x, y, w, h);
                                            }
                                        }
                                    }
                                }
                                postInvalidate();
                                requestUpdate(true);
                            }
                        }
                    } catch (Exception ignored) {
                        running = false;
                    }
                }
            }).start();
        }

        private void requestUpdate(boolean inc) throws Exception {
            out.writeByte(3);
            out.writeByte(inc ? 1 : 0);
            out.writeShort(0);
            out.writeShort(0);
            out.writeShort(fbW);
            out.writeShort(fbH);
            out.flush();
        }

        private void sendPointer(final int x, final int y, final int mask) {
            if (!running || out == null) return;
            new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        out.writeByte(5);
                        out.writeByte(mask);
                        out.writeShort(x);
                        out.writeShort(y);
                        out.flush();
                    } catch (Exception ignored) {}
                }
            }).start();
        }

        public void disconnect() {
            running = false;
            try { if (socket != null) socket.close(); } catch (Exception ignored) {}
        }

        @Override
        public boolean onTouchEvent(MotionEvent e) {
            scaleDet.onTouchEvent(e);
            gestDet.onTouchEvent(e);
            if (e.getPointerCount() == 1 && !scaleDet.isInProgress()) {
                if (e.getActionMasked() == MotionEvent.ACTION_DOWN) {
                    lastX = e.getX();
                    lastY = e.getY();
                } else if (e.getActionMasked() == MotionEvent.ACTION_MOVE && scale > minScale * 1.04f) {
                    tx += e.getX() - lastX;
                    ty += e.getY() - lastY;
                    lastX = e.getX();
                    lastY = e.getY();
                    invalidate();
                }
            }
            return true;
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            canvas.drawColor(Color.BLACK);
            synchronized (lock) {
                if (frame != null && !frame.isRecycled()) {
                    matrix.reset();
                    matrix.postScale(scale, scale);
                    matrix.postTranslate(tx, ty);
                    canvas.drawBitmap(frame, matrix, paint);
                }
            }
        }
    }
}
