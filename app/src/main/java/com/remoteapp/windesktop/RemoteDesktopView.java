package com.remoteapp.windesktop;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.GestureDetector;
import android.view.HapticFeedbackConstants;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.view.inputmethod.BaseInputConnection;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;

import com.freerdp.freerdpcore.services.LibFreeRDP;

public class RemoteDesktopView extends View {

    public static final int TOUCH_SMART = 0;
    public static final int TOUCH_SCROLL = 1;
    public static final int TOUCH_DRAG = 2;

    private volatile long rdpInstance = 0;
    private Bitmap currentFrame;
    private final Object bitmapLock;
    private int remoteWidth = 1920;
    private int remoteHeight = 1080;

    private final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Paint cursorPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Matrix renderMatrix = new Matrix();

    private float scaleFactor = 1.0f;
    private float minScale = 1.0f;
    private float maxScale = 5.0f;
    private float translateX = 0f;
    private float translateY = 0f;

    private float lastCursorRemoteX = 960f;
    private float lastCursorRemoteY = 540f;

    private boolean rightClickMode = false;
    private int touchMode = TOUCH_SMART;
    private boolean isMouseDragging = false;
    private boolean isPanning = false;
    private float downViewX = 0f;
    private float downViewY = 0f;
    private float lastSingleX = 0f;
    private float lastSingleY = 0f;
    private float scrollAccumY = 0f;

    private ScaleGestureDetector scaleDetector;
    private GestureDetector gestureDetector;

    public RemoteDesktopView(Context context, Object lock) {
        super(context);
        this.bitmapLock = lock != null ? lock : new Object();
        init(context);
    }

    public RemoteDesktopView(Context context, AttributeSet attrs) {
        super(context, attrs);
        this.bitmapLock = new Object();
        init(context);
    }

    private void init(Context context) {
        cursorPaint.setColor(Color.parseColor("#38BDF8"));
        cursorPaint.setStyle(Paint.Style.STROKE);
        cursorPaint.setStrokeWidth(3f);

        scaleDetector = new ScaleGestureDetector(context, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override
            public boolean onScale(ScaleGestureDetector detector) {
                float prevScale = scaleFactor;
                scaleFactor *= detector.getScaleFactor();
                scaleFactor = Math.max(minScale, Math.min(scaleFactor, maxScale));

                float focusX = detector.getFocusX();
                float focusY = detector.getFocusY();
                translateX = focusX - (focusX - translateX) * (scaleFactor / prevScale);
                translateY = focusY - (focusY - translateY) * (scaleFactor / prevScale);
                clampTranslation();
                invalidate();
                return true;
            }
        });

        gestureDetector = new GestureDetector(context, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onSingleTapConfirmed(MotionEvent e) {
                int[] pt = mapViewToRemote(e.getX(), e.getY());
                int btnFlag = rightClickMode ? LibFreeRDP.PTR_FLAGS_BUTTON2 : LibFreeRDP.PTR_FLAGS_BUTTON1;
                sendMouseClick(pt[0], pt[1], btnFlag, false);
                invalidate();
                return true;
            }

            @Override
            public boolean onDoubleTap(MotionEvent e) {
                int[] pt = mapViewToRemote(e.getX(), e.getY());
                sendMouseClick(pt[0], pt[1], LibFreeRDP.PTR_FLAGS_BUTTON1, true);
                invalidate();
                return true;
            }

            @Override
            public void onLongPress(MotionEvent e) {
                if (isPanning || isMouseDragging) return;
                int[] pt = mapViewToRemote(e.getX(), e.getY());
                sendMouseClick(pt[0], pt[1], LibFreeRDP.PTR_FLAGS_BUTTON2, false);
                performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
                invalidate();
            }

            @Override
            public boolean onScroll(MotionEvent e1, MotionEvent e2, float distanceX, float distanceY) {
                if (e2.getPointerCount() >= 2 && !scaleDetector.isInProgress()) {
                    // Two-finger swipe: send mouse wheel at midpoint (works in both normal and zoomed states)
                    float midX = (e2.getX(0) + e2.getX(1)) * 0.5f;
                    float midY = (e2.getY(0) + e2.getY(1)) * 0.5f;
                    int[] pt = mapViewToRemote(midX, midY);
                    accumulateAndSendWheel(pt[0], pt[1], -distanceY);
                    return true;
                }
                return false;
            }
        });
    }

    private void accumulateAndSendWheel(final int rx, final int ry, float deltaY) {
        final long inst = rdpInstance;
        if (inst == 0) return;
        scrollAccumY += deltaY;
        final float stepPx = 26f;
        int steps = 0;
        while (scrollAccumY >= stepPx) {
            steps++;
            scrollAccumY -= stepPx;
        }
        while (scrollAccumY <= -stepPx) {
            steps--;
            scrollAccumY += stepPx;
        }
        if (steps != 0) {
            final int count = Math.abs(steps);
            final boolean up = steps > 0;
            new Thread(new Runnable() {
                @Override
                public void run() {
                    LibFreeRDP.sendCursorEvent(inst, rx, ry, LibFreeRDP.PTR_FLAGS_MOVE);
                    int flags = up
                            ? (LibFreeRDP.PTR_FLAGS_WHEEL | 0x0078)
                            : (LibFreeRDP.PTR_FLAGS_WHEEL | LibFreeRDP.PTR_FLAGS_WHEEL_NEGATIVE | 0x0088);
                    for (int i = 0; i < count; i++) {
                        LibFreeRDP.sendCursorEvent(inst, rx, ry, flags);
                    }
                }
            }).start();
        }
    }

    public void sendWheelStep(final boolean up, final int notches) {
        final long inst = rdpInstance;
        if (inst == 0) return;
        final int rx = (int) lastCursorRemoteX;
        final int ry = (int) lastCursorRemoteY;
        new Thread(new Runnable() {
            @Override
            public void run() {
                LibFreeRDP.sendCursorEvent(inst, rx, ry, LibFreeRDP.PTR_FLAGS_MOVE);
                int flags = up
                        ? (LibFreeRDP.PTR_FLAGS_WHEEL | 0x0078)
                        : (LibFreeRDP.PTR_FLAGS_WHEEL | LibFreeRDP.PTR_FLAGS_WHEEL_NEGATIVE | 0x0088);
                for (int i = 0; i < notches; i++) {
                    LibFreeRDP.sendCursorEvent(inst, rx, ry, flags);
                }
            }
        }).start();
    }

    private void sendMouseClick(final int x, final int y, final int btnFlag, final boolean isDouble) {
        final long inst = rdpInstance;
        if (inst == 0) return;
        new Thread(new Runnable() {
            @Override
            public void run() {
                LibFreeRDP.sendCursorEvent(inst, x, y, LibFreeRDP.PTR_FLAGS_MOVE);
                LibFreeRDP.sendCursorEvent(inst, x, y, btnFlag | LibFreeRDP.PTR_FLAGS_DOWN);
                LibFreeRDP.sendCursorEvent(inst, x, y, btnFlag);
                if (isDouble) {
                    try { Thread.sleep(40); } catch (InterruptedException ignored) {}
                    LibFreeRDP.sendCursorEvent(inst, x, y, btnFlag | LibFreeRDP.PTR_FLAGS_DOWN);
                    LibFreeRDP.sendCursorEvent(inst, x, y, btnFlag);
                }
            }
        }).start();
    }

    public void setRdpInstance(long inst) {
        this.rdpInstance = inst;
    }

    public void updateRemoteResolution(int width, int height) {
        if (width > 0 && height > 0) {
            this.remoteWidth = width;
            this.remoteHeight = height;
            post(new Runnable() {
                @Override
                public void run() {
                    resetZoomToFit();
                }
            });
        }
    }

    public void updateFrame(Bitmap bitmap) {
        this.currentFrame = bitmap;
        postInvalidate();
    }

    public void setRightClickMode(boolean enabled) {
        this.rightClickMode = enabled;
    }

    public boolean isRightClickMode() {
        return rightClickMode;
    }

    public void setTouchMode(int mode) {
        this.touchMode = mode;
    }

    public int getTouchMode() {
        return touchMode;
    }

    public void performRightClickAtCursor() {
        sendMouseClick((int) lastCursorRemoteX, (int) lastCursorRemoteY, LibFreeRDP.PTR_FLAGS_BUTTON2, false);
    }

    public void resetZoomToFit() {
        int vw = getWidth();
        int vh = getHeight();
        if (vw <= 0 || vh <= 0) return;

        float sx = (float) vw / remoteWidth;
        float sy = (float) vh / remoteHeight;
        minScale = Math.min(sx, sy);
        scaleFactor = minScale;
        maxScale = Math.max(5.0f, minScale * 6.0f);

        translateX = (vw - remoteWidth * scaleFactor) / 2f;
        translateY = (vh - remoteHeight * scaleFactor) / 2f;
        invalidate();
    }

    private void clampTranslation() {
        int vw = getWidth();
        int vh = getHeight();
        float scaledW = remoteWidth * scaleFactor;
        float scaledH = remoteHeight * scaleFactor;

        if (scaledW <= vw) {
            translateX = (vw - scaledW) / 2f;
        } else {
            translateX = Math.min(0f, Math.max(vw - scaledW, translateX));
        }

        if (scaledH <= vh) {
            translateY = (vh - scaledH) / 2f;
        } else {
            translateY = Math.min(0f, Math.max(vh - scaledH, translateY));
        }
    }

    private int[] mapViewToRemote(float viewX, float viewY) {
        float rx = (viewX - translateX) / scaleFactor;
        float ry = (viewY - translateY) / scaleFactor;
        rx = Math.max(0, Math.min(remoteWidth - 1, rx));
        ry = Math.max(0, Math.min(remoteHeight - 1, ry));
        lastCursorRemoteX = rx;
        lastCursorRemoteY = ry;
        return new int[]{(int) rx, (int) ry};
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        resetZoomToFit();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        canvas.drawColor(Color.BLACK);

        synchronized (bitmapLock) {
            if (currentFrame != null && !currentFrame.isRecycled()) {
                renderMatrix.reset();
                float frameScaleX = (float) remoteWidth / currentFrame.getWidth();
                float frameScaleY = (float) remoteHeight / currentFrame.getHeight();
                renderMatrix.postScale(frameScaleX * scaleFactor, frameScaleY * scaleFactor);
                renderMatrix.postTranslate(translateX, translateY);
                canvas.drawBitmap(currentFrame, renderMatrix, paint);
            }
        }

        float cx = translateX + lastCursorRemoteX * scaleFactor;
        float cy = translateY + lastCursorRemoteY * scaleFactor;
        canvas.drawCircle(cx, cy, 10f, cursorPaint);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        scaleDetector.onTouchEvent(event);
        gestureDetector.onTouchEvent(event);

        int action = event.getActionMasked();
        if (event.getPointerCount() == 1 && !scaleDetector.isInProgress() && rdpInstance != 0) {
            boolean isZoomed = scaleFactor > minScale * 1.04f;
            switch (action) {
                case MotionEvent.ACTION_DOWN:
                    isMouseDragging = false;
                    isPanning = false;
                    scrollAccumY = 0f;
                    downViewX = event.getX();
                    downViewY = event.getY();
                    lastSingleX = event.getX();
                    lastSingleY = event.getY();
                    int[] downPt = mapViewToRemote(downViewX, downViewY);
                    LibFreeRDP.sendCursorEvent(rdpInstance, downPt[0], downPt[1], LibFreeRDP.PTR_FLAGS_MOVE);
                    break;

                case MotionEvent.ACTION_MOVE:
                    float totalDx = Math.abs(event.getX() - downViewX);
                    float totalDy = Math.abs(event.getY() - downViewY);
                    float stepDx = event.getX() - lastSingleX;
                    float stepDy = event.getY() - lastSingleY;
                    lastSingleX = event.getX();
                    lastSingleY = event.getY();

                    if (touchMode == TOUCH_SCROLL) {
                        if (totalDx > 8f || totalDy > 8f) {
                            isPanning = true;
                            int[] pt = mapViewToRemote(downViewX, downViewY);
                            accumulateAndSendWheel(pt[0], pt[1], stepDy);
                        }
                    } else if (touchMode == TOUCH_SMART && isZoomed) {
                        // Zoomed in: single-finger slide pans the zoomed desktop viewport
                        if (!isPanning && (totalDx > 10f || totalDy > 10f)) {
                            isPanning = true;
                        }
                        if (isPanning) {
                            translateX += stepDx;
                            translateY += stepDy;
                            clampTranslation();
                            invalidate();
                        }
                    } else {
                        // Normal (1x) or explicit TOUCH_DRAG: single-finger slide drags mouse left button
                        int[] pt = mapViewToRemote(event.getX(), event.getY());
                        if (!isMouseDragging && (totalDx > 12f || totalDy > 12f)) {
                            isMouseDragging = true;
                            LibFreeRDP.sendCursorEvent(rdpInstance, pt[0], pt[1], LibFreeRDP.PTR_FLAGS_BUTTON1 | LibFreeRDP.PTR_FLAGS_DOWN);
                        } else if (isMouseDragging) {
                            LibFreeRDP.sendCursorEvent(rdpInstance, pt[0], pt[1], LibFreeRDP.PTR_FLAGS_MOVE);
                        }
                        invalidate();
                    }
                    break;

                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    if (isMouseDragging) {
                        int[] pt = mapViewToRemote(event.getX(), event.getY());
                        LibFreeRDP.sendCursorEvent(rdpInstance, pt[0], pt[1], LibFreeRDP.PTR_FLAGS_BUTTON1);
                    }
                    isMouseDragging = false;
                    isPanning = false;
                    invalidate();
                    break;
            }
        } else if (event.getPointerCount() > 1 && isMouseDragging) {
            int[] pt = mapViewToRemote(lastSingleX, lastSingleY);
            LibFreeRDP.sendCursorEvent(rdpInstance, pt[0], pt[1], LibFreeRDP.PTR_FLAGS_BUTTON1);
            isMouseDragging = false;
        }
        return true;
    }

    @Override
    public boolean onCheckIsTextEditor() {
        return true;
    }

    @Override
    public InputConnection onCreateInputConnection(EditorInfo outAttrs) {
        outAttrs.imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI | EditorInfo.IME_ACTION_NONE;
        outAttrs.inputType = EditorInfo.TYPE_CLASS_TEXT | EditorInfo.TYPE_TEXT_FLAG_NO_SUGGESTIONS;
        return new BaseInputConnection(this, false) {
            @Override
            public boolean commitText(CharSequence text, int newCursorPosition) {
                if (rdpInstance != 0 && text != null) {
                    String s = text.toString();
                    for (int i = 0; i < s.length(); i++) {
                        int ch = s.charAt(i);
                        LibFreeRDP.sendUnicodeKeyEvent(rdpInstance, ch, true);
                        LibFreeRDP.sendUnicodeKeyEvent(rdpInstance, ch, false);
                    }
                }
                return true;
            }

            @Override
            public boolean deleteSurroundingText(int beforeLength, int afterLength) {
                if (rdpInstance != 0 && beforeLength > 0) {
                    LibFreeRDP.sendKeyEvent(rdpInstance, 0x08, true);
                    LibFreeRDP.sendKeyEvent(rdpInstance, 0x08, false);
                }
                return true;
            }
        };
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            return super.onKeyDown(keyCode, event);
        }
        int vk = mapAndroidKeyToWindowsVk(keyCode);
        if (vk != 0 && rdpInstance != 0) {
            LibFreeRDP.sendKeyEvent(rdpInstance, vk, true);
            return true;
        }
        int uni = event.getUnicodeChar();
        if (uni != 0 && rdpInstance != 0) {
            LibFreeRDP.sendUnicodeKeyEvent(rdpInstance, uni, true);
            LibFreeRDP.sendUnicodeKeyEvent(rdpInstance, uni, false);
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        int vk = mapAndroidKeyToWindowsVk(keyCode);
        if (vk != 0 && rdpInstance != 0) {
            LibFreeRDP.sendKeyEvent(rdpInstance, vk, false);
            return true;
        }
        return super.onKeyUp(keyCode, event);
    }

    private int mapAndroidKeyToWindowsVk(int keyCode) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_DEL: return 0x08;
            case KeyEvent.KEYCODE_TAB: return 0x09;
            case KeyEvent.KEYCODE_ENTER: return 0x0D;
            case KeyEvent.KEYCODE_ESCAPE: return 0x1B;
            case KeyEvent.KEYCODE_SPACE: return 0x20;
            case KeyEvent.KEYCODE_DPAD_LEFT: return 0x25 | LibFreeRDP.KBDEXT;
            case KeyEvent.KEYCODE_DPAD_UP: return 0x26 | LibFreeRDP.KBDEXT;
            case KeyEvent.KEYCODE_DPAD_RIGHT: return 0x27 | LibFreeRDP.KBDEXT;
            case KeyEvent.KEYCODE_DPAD_DOWN: return 0x28 | LibFreeRDP.KBDEXT;
            case KeyEvent.KEYCODE_FORWARD_DEL: return 0x2E | LibFreeRDP.KBDEXT;
            default: return 0;
        }
    }
}
