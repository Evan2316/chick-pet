package com.chickpet;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.Random;

/**
 * 前台服务：撑住悬浮窗，处理拖动 / 点击 / 自动游走 / 定时冒话 / 随机表演动作。
 * 台词和动作全部在 assets/pet.html 里维护，这里只负责「什么时候触发」。
 */
public class ChickService extends Service {

    public static volatile boolean running = false;

    private static final String CHANNEL_ID = "chickpet";

    private WindowManager wm;
    private FrameLayout root;
    private TouchThroughWebView web;
    private WindowManager.LayoutParams params;

    private int sizePx;
    private int screenW, screenH;

    private long lastTouchAt = 0L;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Random random = new Random();

    private float downX, downY;
    private int startX, startY;
    private long downTime;

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        running = true;

        wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        DisplayMetrics dm = getResources().getDisplayMetrics();
        screenW = dm.widthPixels;
        screenH = dm.heightPixels;

        int sizeDp = readConfigInt("size", 110);
        if (sizeDp < 40) sizeDp = 40;
        if (sizeDp > 400) sizeDp = 400;

        sizePx = (int) (sizeDp * dm.density);
        if (sizePx < 1) sizePx = (int) (110 * dm.density);

        startForegroundSafe();
        buildOverlay();

        handler.postDelayed(wanderLoop, 9000);
        handler.postDelayed(sayLoop, 20000);
        handler.postDelayed(actionLoop, 30000);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        running = false;
        handler.removeCallbacksAndMessages(null);
        if (root != null && wm != null) {
            try {
                wm.removeView(root);
            } catch (Exception ignored) {
            }
        }
        if (web != null) {
            try {
                web.destroy();
            } catch (Exception ignored) {
            }
            web = null;
        }
        root = null;
        super.onDestroy();
    }

    // ---------------------------------------------------------------- 前台通知

    private void startForegroundSafe() {
        try {
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (Build.VERSION.SDK_INT >= 26 && nm != null) {
                NotificationChannel c = new NotificationChannel(
                        CHANNEL_ID, "桌宠运行中", NotificationManager.IMPORTANCE_MIN);
                c.setShowBadge(false);
                nm.createNotificationChannel(c);
            }
            String petName = readConfig().optString("name", "姆姆");
            if (petName == null || petName.trim().isEmpty()) petName = "姆姆";
            Notification.Builder b = (Build.VERSION.SDK_INT >= 26)
                    ? new Notification.Builder(this, CHANNEL_ID)
                    : new Notification.Builder(this);
            Notification n = b
                    .setContentTitle(petName)
                    .setContentText(petName + " 正在你桌面上溜达～")
                    .setSmallIcon(android.R.drawable.ic_menu_compass)
                    .setOngoing(true)
                    .build();
            startForeground(1, n);
        } catch (Exception ignored) {
        }
    }

    // ---------------------------------------------------------------- 悬浮窗

    private void buildOverlay() {
        root = new FrameLayout(this);

        web = new TouchThroughWebView(this);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(true);
        s.setSupportZoom(false);
        s.setBuiltInZoomControls(false);
        s.setDisplayZoomControls(false);
        web.setBackgroundColor(Color.TRANSPARENT);
        web.setVerticalScrollBarEnabled(false);
        web.setHorizontalScrollBarEnabled(false);
        web.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView v, String url) {
                super.onPageFinished(v, url);
                try {
                    // 告诉网页「由 Android 驱动点击」，避免同一次点击被处理两遍
                    // 顺便把名字注入进去（台词里的 {n} 会用它）
                    String nm = readConfig().optString("name", "姆姆");
                    if (nm == null || nm.trim().isEmpty()) nm = "姆姆";
                    v.evaluateJavascript(
                            "window.__androiddriven = true; window.chickSetName("
                                    + JSONObject.quote(nm) + ");", null);
                } catch (Exception ignored) {
                }
            }
        });
        web.loadUrl("file:///android_asset/pet.html");

        root.addView(web, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));

        params = new WindowManager.LayoutParams(
                sizePx,
                (int) (sizePx * 1.45f),          // 多留 45% 高度给头顶气泡
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.TOP | Gravity.START;
        params.x = Math.max(0, screenW - sizePx - 20);
        params.y = (int) (screenH * 0.35f);

        root.setOnTouchListener(touchListener);

        try {
            wm.addView(root, params);
        } catch (Exception e) {
            stopSelf();
        }
    }

    // ---------------------------------------------------------------- 拖动 / 点击

    private final View.OnTouchListener touchListener = new View.OnTouchListener() {
        @Override
        public boolean onTouch(View v, MotionEvent e) {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downX = e.getRawX();
                    downY = e.getRawY();
                    startX = params.x;
                    startY = params.y;
                    downTime = System.currentTimeMillis();
                    lastTouchAt = downTime;
                    return true;

                case MotionEvent.ACTION_MOVE:
                    params.x = startX + (int) (e.getRawX() - downX);
                    params.y = startY + (int) (e.getRawY() - downY);
                    lastTouchAt = System.currentTimeMillis();
                    safeUpdate();
                    return true;

                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    lastTouchAt = System.currentTimeMillis();
                    float dx = Math.abs(e.getRawX() - downX);
                    float dy = Math.abs(e.getRawY() - downY);
                    if (dx < 14 && dy < 14 && System.currentTimeMillis() - downTime < 450) {
                        // 点一下 → 随机换个动作 + 说对应的话（都在 HTML 里决定）
                        runJs("chickNext()");
                    }
                    return true;
            }
            return false;
        }
    };

    private void safeUpdate() {
        try {
            wm.updateViewLayout(root, params);
        } catch (Exception ignored) {
        }
    }

    // ---------------------------------------------------------------- 自动游走

    private final Runnable wanderLoop = new Runnable() {
        @Override
        public void run() {
            wander();
            handler.postDelayed(this, 9000 + random.nextInt(16000));
        }
    };

    private void wander() {
        if (root == null || System.currentTimeMillis() - lastTouchAt < 9000) return;

        final int maxX = Math.max(1, screenW - sizePx);
        final int maxY = Math.max(1, screenH - (int) (sizePx * 1.45f) - 60);
        final int tx = random.nextInt(maxX);
        final int ty = 80 + random.nextInt(maxY);
        final int fromX = params.x, fromY = params.y;
        final long t0 = System.currentTimeMillis();
        final long dur = 900 + random.nextInt(1100);

        handler.post(new Runnable() {
            @Override
            public void run() {
                if (root == null) return;
                float p = Math.min(1f, (System.currentTimeMillis() - t0) / (float) dur);
                float e = 1f - (1f - p) * (1f - p);
                params.x = (int) (fromX + (tx - fromX) * e);
                params.y = (int) (fromY + (ty - fromY) * e);
                safeUpdate();
                boolean touched = System.currentTimeMillis() - lastTouchAt < 600;
                if (p < 1f && !touched) {
                    handler.postDelayed(this, 16);
                }
            }
        });
    }

    // ---------------------------------------------------------------- 冒话

    private final Runnable sayLoop = new Runnable() {
        @Override
        public void run() {
            if (System.currentTimeMillis() - lastTouchAt > 8000) {
                runJs("chickSayRandom()");
            }
            handler.postDelayed(this, 25000 + random.nextInt(45000));
        }
    };

    // ---------------------------------------------------------------- 随机表演动作

    private final Runnable actionLoop = new Runnable() {
        @Override
        public void run() {
            if (root != null && System.currentTimeMillis() - lastTouchAt > 12000) {
                runJs("chickAct()");
            }
            handler.postDelayed(this, 20000 + random.nextInt(40000));
        }
    };

    private void runJs(final String js) {
        final WebView w = web;
        if (w == null) return;
        w.post(new Runnable() {
            @Override
            public void run() {
                try {
                    w.evaluateJavascript(js, null);
                } catch (Exception ignored) {
                }
            }
        });
    }

    // ---------------------------------------------------------------- 配置

    private int readConfigInt(String key, int def) {
        try {
            return readConfig().optInt(key, def);
        } catch (Exception e) {
            return def;
        }
    }

    private JSONObject readConfig() {
        InputStream in = null;
        try {
            in = getAssets().open("config.json");
            BufferedReader r = new BufferedReader(new InputStreamReader(in));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = r.readLine()) != null) sb.append(line);
            return new JSONObject(sb.toString());
        } catch (Exception e) {
            return new JSONObject();
        } finally {
            try {
                if (in != null) in.close();
            } catch (Exception ignored) {
            }
        }
    }
}