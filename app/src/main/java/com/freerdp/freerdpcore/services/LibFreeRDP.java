package com.freerdp.freerdpcore.services;

import android.content.Context;
import android.graphics.Bitmap;
import android.util.Log;

import java.util.ArrayList;

/**
 * FreeRDP Android 原生协议栈 JNI 桥接层 (完整对接 Windows 自带远程桌面 MS-RDPBCGR / 3389 端口)：
 * 支持 NLA (CredSSP/NTLMv2)、TLS、标准 RDP 加密、自动信任自签名证书、软件 GDI 位图渲染以及全功能键鼠输入。
 */
public class LibFreeRDP {

    private static final String TAG = "LibFreeRDP";

    // RDP 鼠标事件标志位 (对应 <freerdp/input.h> 中的 PTR_FLAGS_*)
    public static final int PTR_FLAGS_WHEEL = 0x0200;
    public static final int PTR_FLAGS_WHEEL_NEGATIVE = 0x0100;
    public static final int PTR_FLAGS_MOVE = 0x0800;
    public static final int PTR_FLAGS_BUTTON1 = 0x1000; // 左键
    public static final int PTR_FLAGS_BUTTON2 = 0x2000; // 右键
    public static final int PTR_FLAGS_BUTTON3 = 0x4000; // 中键
    public static final int PTR_FLAGS_DOWN = 0x8000;

    // 扩展虚拟键标志位 (对应 <winpr/input.h> 中的 KBDEXT)
    public static final int KBDEXT = 0x0100;

    public interface SessionEventListener {
        void onSettingsChanged(int width, int height, int bpp);
        void onGraphicsUpdate(int x, int y, int width, int height);
        void onGraphicsResize(int width, int height, int bpp);
        void onConnectionSuccess();
        void onConnectionFailure(String errorMsg);
        void onDisconnected();
    }

    private static volatile SessionEventListener activeListener;
    private static volatile String savedUsername = "";
    private static volatile String savedDomain = "";
    private static volatile String savedPassword = "";
    private static boolean nativeLoaded = false;
    private static String loadError = "";
    private static int majorVersion = 3;

    static {
        try {
            // 先尝试按依赖顺序预加载 winpr / freerdp / freerdp-client，再加载 JNI 入口库 freerdp-android
            String[] suffixes = new String[]{"3", "2", ""};
            for (int i = 0; i < suffixes.length; i++) {
                String s = suffixes[i];
                try { System.loadLibrary("winpr" + s); } catch (Throwable ignored) {}
                try { System.loadLibrary("freerdp" + s); } catch (Throwable ignored) {}
                try { System.loadLibrary("freerdp-client" + s); } catch (Throwable ignored) {}
            }
            System.loadLibrary("freerdp-android");
            try {
                String ver = freerdp_get_jni_version();
                if (ver != null && ver.length() > 0) {
                    String[] parts = ver.split("[\\.-]");
                    if (parts.length > 0) {
                        try { System.loadLibrary("freerdp-client" + parts[0]); } catch (Throwable ignored) {}
                        try { System.loadLibrary("freerdp" + parts[0]); } catch (Throwable ignored) {}
                        try { System.loadLibrary("winpr" + parts[0]); } catch (Throwable ignored) {}
                        majorVersion = Integer.parseInt(parts[0]);
                    }
                }
            } catch (Throwable ignored) {
            }
            nativeLoaded = true;
            Log.i(TAG, "FreeRDP native library loaded successfully (major=" + majorVersion + ")");
        } catch (Throwable t) {
            nativeLoaded = false;
            loadError = t.getMessage() != null ? t.getMessage() : t.toString();
            Log.e(TAG, "Failed to load FreeRDP native library: " + loadError, t);
        }
    }

    public static boolean isNativeLoaded() {
        return nativeLoaded;
    }

    public static String getLoadError() {
        return loadError;
    }

    public static void setSessionEventListener(SessionEventListener listener) {
        activeListener = listener;
    }

    // ==================== JNI Native 方法声明 (与 android_freerdp.c 保持严格一致) ====================
    private static native boolean freerdp_has_h264();
    private static native boolean freerdp_has_camera_redirection();
    private static native String freerdp_get_jni_version();
    private static native String freerdp_get_version();
    private static native String freerdp_get_build_revision();
    private static native String freerdp_get_build_config();
    private static native long freerdp_new(Context context);
    private static native void freerdp_free(long inst);
    private static native boolean freerdp_parse_arguments(long inst, String[] args);
    private static native boolean freerdp_connect(long inst);
    private static native boolean freerdp_disconnect(long inst);
    private static native boolean freerdp_update_graphics(long inst, Bitmap bitmap, int x, int y, int width, int height);
    private static native boolean freerdp_send_cursor_event(long inst, int x, int y, int flags);
    private static native boolean freerdp_send_key_event(long inst, int keycode, boolean down);
    private static native boolean freerdp_send_unicodekey_event(long inst, int keycode, boolean down);
    private static native boolean freerdp_is_unicode_input_supported(long inst);
    private static native boolean freerdp_send_clipboard_data(long inst, String data);
    private static native boolean freerdp_send_clipboard_image_data(long inst, byte[] data, String mimeType);
    private static native boolean freerdp_send_monitor_layout(long inst, int width, int height);
    private static native String freerdp_get_last_error_string(long inst);

    // ==================== 对外封装的 Java 控制接口 ====================
    public static long newInstance(Context context) {
        return freerdp_new(context);
    }

    public static void freeInstance(long inst) {
        if (inst != 0) {
            try {
                freerdp_free(inst);
            } catch (Throwable ignored) {
            }
        }
    }

    /**
     * 配置并连接 Windows 原生远程桌面 (默认端口 3389)
     */
    public static boolean configureAndConnect(Context context, long inst, String host, int port,
                                              String rawUsername, String password, int width, int height) {
        String domain = "";
        String user = rawUsername != null ? rawUsername.trim() : "";
        if (user.contains("\\")) {
            String[] parts = user.split("\\", 2);
            domain = parts[0];
            user = parts[1];
        }
        savedUsername = user;
        savedDomain = domain;
        savedPassword = password != null ? password : "";

        // 确保分辨率为偶数（Windows RDP 编解码器要求宽度为偶数）
        int w = (width <= 0 ? 1920 : (width & ~1));
        int h = (height <= 0 ? 1080 : (height & ~1));

        // 优先尝试 FreeRDP 3.x 标准参数集
        String[] args3 = buildArgs(host, port, user, domain, savedPassword, w, h, true);
        boolean parsed = false;
        try {
            parsed = freerdp_parse_arguments(inst, args3);
        } catch (Throwable ignored) {
        }

        // 若当前 .so 为 FreeRDP 2.x，自动回退至 2.x 参数格式
        if (!parsed) {
            String[] args2 = buildArgs(host, port, user, domain, savedPassword, w, h, false);
            try {
                parsed = freerdp_parse_arguments(inst, args2);
            } catch (Throwable ignored) {
            }
        }

        // 极简兜底参数集
        if (!parsed) {
            ArrayList<String> minArgs = new ArrayList<String>();
            minArgs.add("LibFreeRDP");
            minArgs.add("/gdi:sw");
            minArgs.add("/v:" + host);
            minArgs.add("/port:" + port);
            if (!user.isEmpty()) minArgs.add("/u:" + user);
            if (!domain.isEmpty()) minArgs.add("/d:" + domain);
            if (!savedPassword.isEmpty()) minArgs.add("/p:" + savedPassword);
            minArgs.add("/size:" + w + "x" + h);
            minArgs.add("/bpp:16");
            parsed = freerdp_parse_arguments(inst, minArgs.toArray(new String[0]));
        }

        if (!parsed) {
            if (activeListener != null) {
                activeListener.onConnectionFailure("RDP 参数解析失败");
            }
            return false;
        }

        return freerdp_connect(inst);
    }

    private static String[] buildArgs(String host, int port, String user, String domain,
                                      String password, int width, int height, boolean v3Syntax) {
        ArrayList<String> args = new ArrayList<String>();
        args.add("LibFreeRDP");
        args.add("/gdi:sw");
        args.add("/v:" + host);
        args.add("/port:" + port);
        if (!user.isEmpty()) {
            args.add("/u:" + user);
        }
        if (!domain.isEmpty()) {
            args.add("/d:" + domain);
        }
        if (!password.isEmpty()) {
            args.add("/p:" + password);
        }
        args.add("/size:" + width + "x" + height);
        args.add("/bpp:32");
        if (v3Syntax) {
            args.add("/cert:ignore");
            args.add("/tls:seclevel:0");
            args.add("/kbd:unicode:on");
        } else {
            args.add("/cert-ignore");
            args.add("/tls-seclevel:0");
        }
        args.add("+window-drag");
        args.add("+fonts");
        args.add("-themes");
        args.add("-wallpaper");
        args.add("-async-channels");
        args.add("-async-update");
        args.add("/clipboard");
        args.add("/audio-mode:2");
        return args.toArray(new String[0]);
    }

    public static boolean disconnect(long inst) {
        if (inst == 0) return true;
        try {
            return freerdp_disconnect(inst);
        } catch (Throwable ignored) {
            return false;
        }
    }

    public static boolean updateGraphics(long inst, Bitmap bitmap, int x, int y, int width, int height) {
        if (inst == 0 || bitmap == null || bitmap.isRecycled()) return false;
        return freerdp_update_graphics(inst, bitmap, x, y, width, height);
    }

    public static boolean sendCursorEvent(long inst, int x, int y, int flags) {
        if (inst == 0) return false;
        return freerdp_send_cursor_event(inst, x, y, flags);
    }

    public static boolean sendKeyEvent(long inst, int keycode, boolean down) {
        if (inst == 0) return false;
        return freerdp_send_key_event(inst, keycode, down);
    }

    public static boolean sendUnicodeKeyEvent(long inst, int keycode, boolean down) {
        if (inst == 0) return false;
        return freerdp_send_unicodekey_event(inst, keycode, down);
    }

    public static boolean sendClipboardData(long inst, String data) {
        if (inst == 0 || data == null) return false;
        return freerdp_send_clipboard_data(inst, data);
    }

    public static String getLastErrorString(long inst) {
        if (inst == 0) return "未知错误";
        try {
            String err = freerdp_get_last_error_string(inst);
            return (err != null && !err.isEmpty()) ? err : "连接失败，请检查 Windows 用户名密码及远程桌面权限";
        } catch (Throwable t) {
            return "连接失败";
        }
    }

    // ==================== JNI C -> Java 静态回调方法 (供 libfreerdp-android.so 反射调用) ====================
    private static void OnPreConnect(long inst) {
        Log.i(TAG, "OnPreConnect: inst=" + inst);
    }

    private static void OnConnectionSuccess(long inst) {
        Log.i(TAG, "OnConnectionSuccess: inst=" + inst);
        if (activeListener != null) {
            activeListener.onConnectionSuccess();
        }
    }

    private static void OnConnectionFailure(long inst) {
        String err = getLastErrorString(inst);
        Log.e(TAG, "OnConnectionFailure: inst=" + inst + ", err=" + err);
        if (activeListener != null) {
            activeListener.onConnectionFailure(err);
        }
    }

    private static void OnDisconnecting(long inst) {
        Log.i(TAG, "OnDisconnecting: inst=" + inst);
    }

    private static void OnDisconnected(long inst) {
        Log.i(TAG, "OnDisconnected: inst=" + inst);
        if (activeListener != null) {
            activeListener.onDisconnected();
        }
    }

    private static void OnSettingsChanged(long inst, int width, int height, int bpp) {
        Log.i(TAG, "OnSettingsChanged: " + width + "x" + height + "@" + bpp);
        if (activeListener != null) {
            activeListener.onSettingsChanged(width, height, bpp);
        }
    }

    private static boolean OnAuthenticate(long inst, StringBuilder username, StringBuilder domain, StringBuilder password) {
        username.setLength(0);
        username.append(savedUsername);
        domain.setLength(0);
        domain.append(savedDomain);
        password.setLength(0);
        password.append(savedPassword);
        return true;
    }

    private static boolean OnGatewayAuthenticate(long inst, StringBuilder username, StringBuilder domain, StringBuilder password) {
        return OnAuthenticate(inst, username, domain, password);
    }

    // FreeRDP 3.x 证书校验回调：返回 1 自动信任 Windows 自签名 RDP 证书
    private static int OnVerifyCertificateEx(long inst, String host, long port, String commonName,
                                             String subject, String issuer, String fingerprint, long flags) {
        return 1;
    }

    private static int OnVerifyChangedCertificateEx(long inst, String host, long port, String commonName,
                                                    String subject, String issuer, String fingerprint,
                                                    String oldSubject, String oldIssuer, String oldFingerprint, long flags) {
        return 1;
    }

    // FreeRDP 2.x 证书校验回调：返回 true 自动信任
    private static boolean OnVerifyCertificate(long inst, String subject, String issuer, String fingerprint) {
        return true;
    }

    private static boolean OnVerifyChangedCertificate(long inst, String subject, String issuer, String fingerprint,
                                                      String oldSubject, String oldIssuer, String oldFingerprint) {
        return true;
    }

    private static int OnVerifyX509Certificate(long inst, byte[] cert, long length, String hostname, long port, long flags) {
        return 1;
    }

    private static boolean OnExperimentalFeature(long inst, int feature) {
        return true;
    }

    private static void OnGraphicsUpdate(long inst, int x, int y, int width, int height) {
        if (activeListener != null) {
            activeListener.onGraphicsUpdate(x, y, width, height);
        }
    }

    private static void OnGraphicsResize(long inst, int width, int height, int bpp) {
        if (activeListener != null) {
            activeListener.onGraphicsResize(width, height, bpp);
        }
    }

    private static void OnRemoteClipboardChanged(long inst, String data) {
    }

    private static void OnRemoteClipboardImageChanged(long inst, byte[] data) {
    }

    private static void OnPointerSet(long inst, int[] pixels, int width, int height, int hotX, int hotY) {
    }

    private static void OnPointerSetNull(long inst) {
    }

    private static void OnPointerSetDefault(long inst) {
    }

    private static void OnRailWindowUpdate(long inst, long windowId, int width, int height, int[] pixels) {
    }

    private static void OnRailWindowMove(long inst, long windowId, int x, int y, int w, int h) {
    }

    private static void OnRailWindowHide(long inst, long windowId) {
    }

    private static void OnRailWindowDestroy(long inst, long windowId) {
    }

    private static void OnRailSessionEnd(long inst) {
    }

    private static void OnRailMonitoredDesktop(long inst, long[] windowIds, long activeWindowId) {
    }
}
