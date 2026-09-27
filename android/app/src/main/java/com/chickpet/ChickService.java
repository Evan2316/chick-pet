package com.chickpet;

import android.animation.ValueAnimator;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
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
 * 前台服务：悬浮窗 + 手势（单击/双击/长按/连击/甩出）+ 自动游走 + 冒话 + 随机动作 + 喂食。
 * 画面与台词全在 assets/pet.html。
 *
 * 手势原理（都是算出来的）：
 *   位移 > 10px            → 拖动（同时取消长按计时器）
 *   按下 600ms 没动没抬     → 长按
 *   抬起时没拖没长按        → 一次点击（延迟 300ms 再执行，等第二下）
 *   两次点击间隔 < 320ms    → 双击
 *   2 秒内 3/5/8 次         → 连击
 *   抬起时仍在拖且速度 >2.6px/ms → 甩出去，1 秒后自己爬回来
 */
public class ChickService extends Service {

    public static volatile boolean running = false;
    public static final String ACTION_FEED = "com.chickpet.FEED";
    private static final String CHANNEL_ID = "chickpet";

    private WindowManager wm;
    private FrameLayout root;
    private TouchThroughWebView web;
    private WindowManager.LayoutParams params;

    private int sizePx;
    private int screenW, screenH;

    private long lastTouchAt = 0L;
    private final Handler gestureHandler = new Handler(Looper.getMainLooper());
    private final Random random = new Random();

    // ---------------- 手势状态 ----------------
    private Runnable pendingTap;      // 待定的单击
    private Runnable holdRunnable;    // 长按计时器
    private boolean holdFired = false;
    private long lastTapAt = 0L;
    private int tapCount = 0;
    private Runnable tapReset;
    private long lastMoveAt = 0L;
    private float lastRawX, lastRawY;
    private float lastVx, lastVy;
    private boolean isDragging = false;
    private int initialX, initialY;
    private float initialTouchX, initialTouchY;

    private ValueAnimator moveAnim;

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

        gestureHandler.postDelayed(wanderLoop, 9000);
        gestureHandler.postDelayed(sayLoop, 20000);
        gestureHandler.postDelayed(actionLoop, 30000);
        gestureHandler.postDelayed(hungryLoop, 60000);   // 1 分钟后开始盯饿不饿
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_FEED.equals(intent.getAction())) {
            callJs("chickFeed");
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        running = false;
        gestureHandler.removeCallbacksAndMessages(null);
        cancelMove();
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
            NotificationManager nm =
                    (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (Build.VERSION.SDK_INT >= 26 && nm != null) {
                NotificationChannel c = new NotificationChannel(
                        CHANNEL_ID, "桌宠运行中", NotificationManager.IMPORTANCE_MIN);
                c.setShowBadge(false);
                nm.createNotificationChannel(c);
            }

            String petName = readConfig().optString("name", "姆姆");
            if (petName == null || petName.trim().isEmpty()) petName = "姆姆";

            // 通知栏里直接点一下就能投喂
            int piFlag = PendingIntent.FLAG_UPDATE_CURRENT;
            if (Build.VERSION.SDK_INT >= 23) piFlag |= PendingIntent.FLAG_IMMUTABLE;
            Intent feedIntent = new Intent(this, ChickService.class);
            feedIntent.setAction(ACTION_FEED);
            PendingIntent feedPi = PendingIntent.getService(this, 1, feedIntent, piFlag);

            Notification.Builder b = (Build.VERSION.SDK_INT >= 26)
                    ? new Notification.Builder(this, CHANNEL_ID)
                    : new Notification.Builder(this);
            b.setContentTitle(petName)
                    .setContentText(petName + " 正在你桌面上溜达～")
                    .setSmallIcon(android.R.drawable.ic_menu_compass)
                    .setOngoing(true);
            try {
                b.addAction(new Notification.Action.Builder(
                        android.R.drawable.ic_menu_add, "喂 " + petName + " 🍚", feedPi).build());
            } catch (Exception ignored) {
            }
            startForeground(1, b.build());
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

        setupTouchListener();

        try {
            wm.addView(root, params);
        } catch (Exception e) {
            stopSelf();
        }
    }

    // ---------------------------------------------------------------- 手势

    private void setupTouchListener() {
        root.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent event) {
                switch (event.getActionMasked()) {

                    case MotionEvent.ACTION_DOWN: {
                        long now = System.currentTimeMillis();
                        lastTouchAt = now;
                        initialX = params.x;
                        initialY = params.y;
                        initialTouchX = event.getRawX();
                        initialTouchY = event.getRawY();
                        lastRawX = event.getRawX();
                        lastRawY = event.getRawY();
                        lastMoveAt = now;
                        lastVx = 0f;
                        lastVy = 0f;
                        isDragging = false;
                        holdFired = false;

                        cancelMove();
                        removeHold();
                        Runnable hr = new Runnable() {
                            @Override
                            public void run() {
                                if (!isDragging) {          // ★ 拖动过就不算长按
                                    holdFired = true;
                                    removePendingTap();
                                    tapCount = 0;
                                    callJs("chickHold");
                                }
                            }
                        };
                        holdRunnable = hr;
                        gestureHandler.postDelayed(hr, 600);
                        return true;
                    }

                    case MotionEvent.ACTION_MOVE: {
                        long now = System.currentTimeMillis();
                        lastTouchAt = now;
                        long dt = now - lastMoveAt;
                        if (dt > 0) {
                            lastVx = (event.getRawX() - lastRawX) / dt;
                            lastVy = (event.getRawY() - lastRawY) / dt;
                        }
                        lastRawX = event.getRawX();
                        lastRawY = event.getRawY();
                        lastMoveAt = now;

                        float deltaX = event.getRawX() - initialTouchX;
                        float deltaY = event.getRawY() - initialTouchY;
                        if (Math.abs(deltaX) > 10 || Math.abs(deltaY) > 10) {
                            isDragging = true;
                            removeHold();               // ★ 一移动就取消长按计时
                            params.x = initialX + (int) deltaX;
                            params.y = initialY + (int) deltaY;
                            safeUpdate();
                        }
                        return true;
                    }

                    case MotionEvent.ACTION_UP: {
                        removeHold();
                        if (!isDragging && !holdFired) {
                            handleTap();
                        } else if (isDragging) {
                            double speed = Math.hypot(lastVx, lastVy);
                            if (speed > 2.6) flingAway(lastVx, lastVy);
                        }
                        lastTouchAt = System.currentTimeMillis();
                        return true;
                    }

                    case MotionEvent.ACTION_CANCEL: {
                        removeHold();
                        lastTouchAt = System.currentTimeMillis();
                        return true;
                    }
                }
                return false;
            }
        });
    }

    private void removeHold() {
        if (holdRunnable != null) {
            gestureHandler.removeCallbacks(holdRunnable);
            holdRunnable = null;
        }
    }

    private void removePendingTap() {
        if (pendingTap != null) {
            gestureHandler.removeCallbacks(pendingTap);
            pendingTap = null;
        }
    }

    private void handleTap() {
        long now = System.currentTimeMillis();
        long gap = now - lastTapAt;
        if (gap > 2000) tapCount = 0;
        tapCount++;
        lastTapAt = now;

        if (tapReset != null) gestureHandler.removeCallbacks(tapReset);
        Runnable rr = new Runnable() {
            @Override
            public void run() {
                tapCount = 0;
            }
        };
        tapReset = rr;
        gestureHandler.postDelayed(rr, 2000);

        if (tapCount >= 8) {
            tapCount = 0;
            callJs("chickCombo", "8");
        } else if (tapCount >= 5) {
            callJs("chickCombo", "5");
        } else if (tapCount >= 3) {
            callJs("chickCombo", "3");
        } else if (tapCount == 2 && gap <= 320) {
            removePendingTap();
            callJs("chickPoke");            // 双击
        } else {
            removePendingTap();
            // ★ 单击延迟 300ms，等一等看有没有第二下
            Runnable pt = new Runnable() {
                @Override
                public void run() {
                    if (tapCount <= 1) callJs("chickNext");
                }
            };
            pendingTap = pt;
            gestureHandler.postDelayed(pt, 300);
        }
    }

    private void flingAway(float vx, float vy) {
        try {
            float dens = getResources().getDisplayMetrics().density;
            int pad = (int) (30 * dens);
            int winW = sizePx;
            int winH = (int) (sizePx * 1.45f);
            int fromX = params.x;
            int fromY = params.y;

            // 我们的窗口是 Gravity.TOP|START，x 从左算
            int outX = Math.abs(vx) > 0.4f
                    ? (vx > 0 ? screenW + pad : -winW - pad)
                    : fromX;
            int outY = Math.abs(vy) > 0.4f
                    ? (vy > 0 ? screenH + pad : -winH - pad)
                    : fromY;

            callJs("chickFling");
            animateTo(outX, outY, 260);       // 飞出去

            // ★ 一秒后自己爬回来（不然就再也点不到了）
            gestureHandler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    float d = getResources().getDisplayMetrics().density;
                    int spanX = Math.max(1,
                            screenW - sizePx - (int) (40 * d));
                    int spanY = Math.max(1,
                            screenH - (int) (sizePx * 1.45f) - (int) (240 * d));
                    animateTo((int) (20 * d) + random.nextInt(spanX),
                            (int) (120 * d) + random.nextInt(spanY), 700L);
                }
            }, 1000L);
        } catch (Exception ignored) {
        }
    }

    // ---------------------------------------------------------------- 移动动画

    private void cancelMove() {
        if (moveAnim != null) {
            try {
                moveAnim.cancel();
            } catch (Exception ignored) {
            }
            moveAnim = null;
        }
    }

    private void animateTo(final int toX, final int toY, long durationMs) {
        final int fromX = params.x;
        final int fromY = params.y;
        cancelMove();
        ValueAnimator a = ValueAnimator.ofFloat(0f, 1f);
        a.setDuration(durationMs);
        a.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(ValueAnimator anim) {
                float f = (Float) anim.getAnimatedValue();
                params.x = (int) (fromX + (toX - fromX) * f);
                params.y = (int) (fromY + (toY - fromY) * f);
                safeUpdate();
            }
        });
        a.start();
        moveAnim = a;
    }

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
            gestureHandler.postDelayed(this, 9000 + random.nextInt(16000));
        }
    };

    private void wander() {
        if (root == null) return;
        if (System.currentTimeMillis() - lastTouchAt < 9000) return;  // 刚被碰过就不动
        int maxX = Math.max(1, screenW - sizePx);
        int maxY = Math.max(1, screenH - (int) (sizePx * 1.45f) - 60);
        animateTo(random.nextInt(maxX), 80 + random.nextInt(maxY),
                900 + random.nextInt(1100));
    }

    // ---------------------------------------------------------------- 冒话 / 随机动作

    private final Runnable sayLoop = new Runnable() {
        @Override
        public void run() {
            if (System.currentTimeMillis() - lastTouchAt > 8000) {
                callJs("chickSayRandom");
            }
            gestureHandler.postDelayed(this, 25000 + random.nextInt(45000));
        }
    };

    private final Runnable actionLoop = new Runnable() {
        @Override
        public void run() {
            if (root != null && System.currentTimeMillis() - lastTouchAt > 12000) {
                callJs("chickAct");
            }
            gestureHandler.postDelayed(this, 20000 + random.nextInt(40000));
        }
    };

    // ---------------------------------------------------------------- 饿了自己跑来求喂

    private long lastBegAt = 0L;

    private final Runnable hungryLoop = new Runnable() {
        @Override
        public void run() {
            checkHungry();
            gestureHandler.postDelayed(this, 40000 + random.nextInt(50000));  // 40~90 秒查一次
        }
    };

    /** 问网页当前饱食度（0 饿 ~ 5 饱），<=1 就跑去找你要饭 */
    private void checkHungry() {
        final WebView w = web;
        if (w == null || root == null) return;
        w.post(new Runnable() {
            @Override
            public void run() {
                try {
                    w.evaluateJavascript(
                            "window.chickFullness ? window.chickFullness() : -1",
                            new android.webkit.ValueCallback<String>() {
                                @Override
                                public void onReceiveValue(String value) {
                                    try {
                                        int f = (int) Double.parseDouble(
                                                value.trim().replace("\"", ""));
                                        if (f >= 0 && f <= 1) begForFood();
                                    } catch (Exception ignored) {
                                    }
                                }
                            });
                } catch (Exception ignored) {
                }
            }
        });
    }

    /** 跑到屏幕中间，可怜巴巴地求喂；25 秒没等到饭就自己回去溜达 */
    private void begForFood() {
        if (System.currentTimeMillis() - lastTouchAt < 15000) return;      // 刚被摸过，别打扰
        if (System.currentTimeMillis() - lastBegAt < 4 * 60 * 1000L) return; // 4 分钟最多求一次
        lastBegAt = System.currentTimeMillis();

        callJs("chickHungry");
        animateTo(Math.max(0, (screenW - sizePx) / 2),
                (int) (screenH * 0.42f), 1200);

        gestureHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (System.currentTimeMillis() - lastTouchAt > 25000
                        && System.currentTimeMillis() - lastBegAt > 25000) {
                    callJs("chickRest");     // 没人理，哼
                    wander();
                }
            }
        }, 25000);
    }

    // ---------------------------------------------------------------- 调网页

    /** 叫 HTML 里的函数。必须 post 到主线程，并且包一层 && 防止接口还没注册 */
    private void callJs(String fn, String jsArg) {
        final WebView w = web;
        if (w == null) return;
        final String js = (jsArg == null)
                ? "window." + fn + " && window." + fn + "()"
                : "window." + fn + " && window." + fn + "(" + jsArg + ")";
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

    private void callJs(String fn) {
        callJs(fn, null);
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