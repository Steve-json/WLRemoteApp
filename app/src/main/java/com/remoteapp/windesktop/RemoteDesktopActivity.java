package com.remoteapp.windesktop;

import android.app.Activity;
import android.content.Context;
import android.content.pm.ActivityInfo;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.Toast;

import com.freerdp.freerdpcore.services.LibFreeRDP;

public class RemoteDesktopActivity extends Activity implements LibFreeRDP.SessionEventListener {

    private RemoteDesktopView desktopView;
    private volatile long rdpInstance = 0;
    private Bitmap desktopBitmap;
    private final Object bitmapLock = new Object();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private LinearLayout actionButtonsGroup;
    private Button btnToggleToolbar, btnMouseMode;
    private volatile boolean closing = false;

    private int dp(int value) {
        float density = getResources().getDisplayMetrics().density;
        return (int) (value * density + 0.5f);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        hideSystemUI();

        setContentView(buildDesktopUI());

        final String ip = getIntent().getStringExtra("ip");
        final int port = getIntent().getIntExtra("port", 3389);
        final String username = getIntent().getStringExtra("username");
        final String password = getIntent().getStringExtra("password");

        LibFreeRDP.setSessionEventListener(this);

        new Thread(new Runnable() {
            @Override
            public void run() {
                rdpInstance = LibFreeRDP.newInstance(RemoteDesktopActivity.this);
                desktopView.setRdpInstance(rdpInstance);
                LibFreeRDP.configureAndConnect(
                        RemoteDesktopActivity.this,
                        rdpInstance,
                        ip,
                        port,
                        username,
                        password,
                        1920,
                        1080
                );
            }
        }).start();
    }

    private View buildDesktopUI() {
        FrameLayout root = new FrameLayout(this);
        root.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
        ));
        root.setBackgroundColor(Color.BLACK);
        root.setKeepScreenOn(true);

        desktopView = new RemoteDesktopView(this, bitmapLock);
        desktopView.setFocusable(true);
        desktopView.setFocusableInTouchMode(true);
        root.addView(desktopView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
        ));

        HorizontalScrollView toolbarScroll = new HorizontalScrollView(this);
        toolbarScroll.setBackgroundColor(Color.parseColor("#CC1E293B"));
        toolbarScroll.setPadding(dp(6), 0, dp(6), 0);
        toolbarScroll.setHorizontalScrollBarEnabled(false);
        FrameLayout.LayoutParams toolbarLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                dp(42),
                Gravity.TOP | Gravity.CENTER_HORIZONTAL
        );
        toolbarLp.topMargin = dp(6);
        root.addView(toolbarScroll, toolbarLp);

        LinearLayout barRow = new LinearLayout(this);
        barRow.setOrientation(LinearLayout.HORIZONTAL);
        barRow.setGravity(Gravity.CENTER_VERTICAL);
        toolbarScroll.addView(barRow, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.MATCH_PARENT
        ));

        btnToggleToolbar = makeBarButton("收起", "#38BDF8");
        barRow.addView(btnToggleToolbar);

        actionButtonsGroup = new LinearLayout(this);
        actionButtonsGroup.setOrientation(LinearLayout.HORIZONTAL);
        actionButtonsGroup.setGravity(Gravity.CENTER_VERTICAL);
        barRow.addView(actionButtonsGroup, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.MATCH_PARENT
        ));

        btnMouseMode = makeBarButton("模式:左键", "#4ADE80");
        final Button btnTouchMode = makeBarButton("触控:智能", "#38BDF8");
        Button btnScrollUp = makeBarButton("▲上滑", "#F8FAFC");
        Button btnScrollDown = makeBarButton("▼下滑", "#F8FAFC");
        Button btnRightClick = makeBarButton("右键", "#F8FAFC");
        Button btnKeyboard = makeBarButton("键盘", "#F8FAFC");
        Button btnWinKey = makeBarButton("Win", "#F8FAFC");
        Button btnAltTab = makeBarButton("Alt+Tab", "#F8FAFC");
        Button btnCtrlAltDel = makeBarButton("Ctrl+Alt+Del", "#FBBF24");
        Button btnTaskMgr = makeBarButton("任务管理器", "#F8FAFC");
        Button btnCopy = makeBarButton("Ctrl+C", "#F8FAFC");
        Button btnPaste = makeBarButton("Ctrl+V", "#F8FAFC");
        Button btnEsc = makeBarButton("Esc", "#F8FAFC");
        Button btnResetZoom = makeBarButton("1:1复位", "#F8FAFC");
        Button btnDisconnect = makeBarButton("断开", "#F87171");

        actionButtonsGroup.addView(btnMouseMode);
        actionButtonsGroup.addView(btnTouchMode);
        actionButtonsGroup.addView(btnScrollUp);
        actionButtonsGroup.addView(btnScrollDown);
        actionButtonsGroup.addView(btnRightClick);

        btnTouchMode.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                int next = (desktopView.getTouchMode() + 1) % 3;
                desktopView.setTouchMode(next);
                if (next == RemoteDesktopView.TOUCH_SMART) {
                    btnTouchMode.setText("触控:智能");
                    Toast.makeText(RemoteDesktopActivity.this, "智能模式：放大后单指拖动画布，双指滑动滚轮", Toast.LENGTH_LONG).show();
                } else if (next == RemoteDesktopView.TOUCH_SCROLL) {
                    btnTouchMode.setText("触控:滚轮");
                    Toast.makeText(RemoteDesktopActivity.this, "滚轮模式：单指上下滑动即可滚动列表", Toast.LENGTH_LONG).show();
                } else {
                    btnTouchMode.setText("触控:拖拽");
                    Toast.makeText(RemoteDesktopActivity.this, "拖拽模式：放大后单指仍可拖动窗口/框选", Toast.LENGTH_LONG).show();
                }
            }
        });
        btnScrollUp.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                desktopView.sendWheelStep(true, 3);
            }
        });
        btnScrollDown.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                desktopView.sendWheelStep(false, 3);
            }
        });
        actionButtonsGroup.addView(btnKeyboard);
        actionButtonsGroup.addView(btnWinKey);
        actionButtonsGroup.addView(btnAltTab);
        actionButtonsGroup.addView(btnCtrlAltDel);
        actionButtonsGroup.addView(btnTaskMgr);
        actionButtonsGroup.addView(btnCopy);
        actionButtonsGroup.addView(btnPaste);
        actionButtonsGroup.addView(btnEsc);
        actionButtonsGroup.addView(btnResetZoom);
        actionButtonsGroup.addView(btnDisconnect);

        btnToggleToolbar.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (actionButtonsGroup.getVisibility() == View.VISIBLE) {
                    actionButtonsGroup.setVisibility(View.GONE);
                    btnToggleToolbar.setText("展开工具栏");
                } else {
                    actionButtonsGroup.setVisibility(View.VISIBLE);
                    btnToggleToolbar.setText("收起");
                }
            }
        });

        btnMouseMode.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                boolean nextMode = !desktopView.isRightClickMode();
                desktopView.setRightClickMode(nextMode);
                btnMouseMode.setText(nextMode ? "模式:右键" : "模式:左键");
            }
        });

        btnRightClick.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                desktopView.performRightClickAtCursor();
            }
        });

        btnKeyboard.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                desktopView.requestFocus();
                InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
                if (imm != null) {
                    imm.toggleSoftInput(InputMethodManager.SHOW_FORCED, 0);
                }
            }
        });

        btnWinKey.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                sendKeyCombo(new int[]{0x5B | LibFreeRDP.KBDEXT});
            }
        });
        btnAltTab.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                sendKeyCombo(new int[]{0xA4, 0x09}); // VK_LMENU + VK_TAB
            }
        });
        btnCtrlAltDel.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                sendKeyCombo(new int[]{0xA2, 0xA4, 0x2E | LibFreeRDP.KBDEXT}); // Ctrl + Alt + Del
            }
        });
        btnTaskMgr.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                sendKeyCombo(new int[]{0xA2, 0xA0, 0x1B}); // Ctrl + Shift + Esc
            }
        });
        btnCopy.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                sendKeyCombo(new int[]{0xA2, 0x43}); // Ctrl + C
            }
        });
        btnPaste.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                sendKeyCombo(new int[]{0xA2, 0x56}); // Ctrl + V
            }
        });
        btnEsc.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                sendKeyCombo(new int[]{0x1B}); // Esc
            }
        });
        btnResetZoom.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                desktopView.resetZoomToFit();
            }
        });
        btnDisconnect.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                closeSession();
            }
        });

        return root;
    }

    private void sendKeyCombo(final int[] vkCodes) {
        final long inst = rdpInstance;
        if (inst == 0) return;
        new Thread(new Runnable() {
            @Override
            public void run() {
                for (int i = 0; i < vkCodes.length; i++) {
                    LibFreeRDP.sendKeyEvent(inst, vkCodes[i], true);
                }
                try { Thread.sleep(30); } catch (InterruptedException ignored) {}
                for (int i = vkCodes.length - 1; i >= 0; i--) {
                    LibFreeRDP.sendKeyEvent(inst, vkCodes[i], false);
                }
            }
        }).start();
    }

    private Button makeBarButton(String label, String hexColor) {
        Button b = new Button(this);
        b.setText(label);
        b.setTextColor(Color.parseColor(hexColor));
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        b.setAllCaps(false);
        b.setBackgroundColor(Color.TRANSPARENT);
        b.setPadding(dp(8), dp(2), dp(8), dp(2));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                dp(36)
        );
        b.setLayoutParams(lp);
        return b;
    }

    private void hideSystemUI() {
        View decorView = getWindow().getDecorView();
        decorView.setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_FULLSCREEN
        );
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            hideSystemUI();
        }
    }

    @Override
    public void onSettingsChanged(final int width, final int height, final int bpp) {
        synchronized (bitmapLock) {
            desktopBitmap = Bitmap.createBitmap(
                    width,
                    height,
                    bpp > 16 ? Bitmap.Config.ARGB_8888 : Bitmap.Config.RGB_565
            );
        }
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                desktopView.updateRemoteResolution(width, height);
                desktopView.updateFrame(desktopBitmap);
            }
        });
    }

    @Override
    public void onGraphicsUpdate(int x, int y, int width, int height) {
        synchronized (bitmapLock) {
            if (desktopBitmap != null && rdpInstance != 0) {
                LibFreeRDP.updateGraphics(rdpInstance, desktopBitmap, x, y, width, height);
            }
        }
        desktopView.updateFrame(desktopBitmap);
    }

    @Override
    public void onGraphicsResize(int width, int height, int bpp) {
        onSettingsChanged(width, height, bpp);
    }

    @Override
    public void onConnectionSuccess() {
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                Toast.makeText(RemoteDesktopActivity.this, "已成功登录 Windows 远程桌面", Toast.LENGTH_LONG).show();
            }
        });
    }

    @Override
    public void onConnectionFailure(final String errorMsg) {
        if (closing) return;
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                Toast.makeText(RemoteDesktopActivity.this, "RDP 登录失败: " + errorMsg, Toast.LENGTH_LONG).show();
                finish();
            }
        });
    }

    @Override
    public void onDisconnected() {
        if (closing) return;
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                Toast.makeText(RemoteDesktopActivity.this, "远程桌面连接已断开", Toast.LENGTH_LONG).show();
                finish();
            }
        });
    }

    private void closeSession() {
        if (closing) return;
        closing = true;
        final long inst = rdpInstance;
        rdpInstance = 0;
        desktopView.setRdpInstance(0);
        new Thread(new Runnable() {
            @Override
            public void run() {
                if (inst != 0) {
                    LibFreeRDP.disconnect(inst);
                }
            }
        }).start();
        finish();
    }

    @Override
    protected void onDestroy() {
        closing = true;
        LibFreeRDP.setSessionEventListener(null);
        final long inst = rdpInstance;
        rdpInstance = 0;
        if (inst != 0) {
            new Thread(new Runnable() {
                @Override
                public void run() {
                    LibFreeRDP.disconnect(inst);
                }
            }).start();
        }
        super.onDestroy();
    }
}
