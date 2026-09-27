package com.chickpet;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * 入口页：只干两件事 —— 授予悬浮窗权限、把服务拉起来。
 * 打开 App 就自动启动，不用用户手点。
 */
public class MainActivity extends Activity {

    private TextView status;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        int p = dp(22);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(p, p * 2, p, p);
        root.setGravity(Gravity.CENTER_HORIZONTAL);

        TextView title = new TextView(this);
        title.setText("🐥 姆姆");
        title.setTextSize(24f);
        title.setGravity(Gravity.CENTER);
        root.addView(title);

        TextView tip = new TextView(this);
        tip.setText("姆姆是一只浮在桌面上的黄色像素小鸡\n可拖动 · 点一下换动作 · 会自己溜达、蹦话、跳舞");
        tip.setTextSize(13f);
        tip.setGravity(Gravity.CENTER);
        tip.setPadding(0, dp(14), 0, dp(22));
        root.addView(tip);

        status = new TextView(this);
        status.setTextSize(14f);
        status.setGravity(Gravity.CENTER);
        status.setPadding(0, 0, 0, dp(18));
        root.addView(status);

        Button startBtn = new Button(this);
        startBtn.setText("启动桌宠");
        startBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startPet();
            }
        });
        root.addView(startBtn);

        Button permBtn = new Button(this);
        permBtn.setText("授予「悬浮窗」权限");
        permBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                requestOverlay();
            }
        });
        root.addView(permBtn);

        Button feedBtn = new Button(this);
        feedBtn.setText("喂姆姆 🍚");
        feedBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                try {
                    Intent i = new Intent(MainActivity.this, ChickService.class);
                    i.setAction(ChickService.ACTION_FEED);
                    if (Build.VERSION.SDK_INT >= 26) {
                        startForegroundService(i);
                    } else {
                        startService(i);
                    }
                    status.setText("已经撒了一粒米 🍚 看桌面～");
                } catch (Exception e) {
                    status.setText("投喂失败：" + e.getMessage());
                }
            }
        });
        root.addView(feedBtn);

        Button stopBtn = new Button(this);
        stopBtn.setText("收起桌宠");
        stopBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                stopService(new Intent(MainActivity.this, ChickService.class));
                status.setText("已收起。再点「启动桌宠」可以叫回来～");
            }
        });
        root.addView(stopBtn);

        setContentView(root);

        autoStartIfPossible();
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }

    private boolean hasOverlay() {
        return Build.VERSION.SDK_INT < 23 || Settings.canDrawOverlays(this);
    }

    private void requestOverlay() {
        if (hasOverlay()) {
            status.setText("已经有悬浮窗权限了 ✅");
            return;
        }
        try {
            startActivity(new Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName())));
        } catch (Exception e) {
            startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION));
        }
    }

    private void startPet() {
        if (!hasOverlay()) {
            status.setText("先去授予「悬浮窗」权限，再回来点启动");
            requestOverlay();
            return;
        }
        try {
            Intent i = new Intent(this, ChickService.class);
            if (Build.VERSION.SDK_INT >= 26) {
                startForegroundService(i);
            } else {
                startService(i);
            }
            status.setText("已启动 ✅ 看桌面，它应该来了～");
        } catch (Exception e) {
            status.setText("启动失败：" + e.getMessage());
        }
    }

    /** 权限已给且服务没跑 → 直接拉起（照指南，别让用户手点） */
    private void autoStartIfPossible() {
        if (!hasOverlay()) {
            status.setText("还没有悬浮窗权限 —— 点下面的按钮授予");
            requestOverlay();
            return;
        }
        if (!ChickService.running) {
            startPet();
        } else {
            status.setText("桌宠正在运行 ✅");
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (status != null) {
            if (!hasOverlay()) {
                status.setText("还没有悬浮窗权限 —— 点下面的按钮授予");
            } else if (ChickService.running) {
                status.setText("桌宠正在运行 ✅");
            } else {
                status.setText("权限已就绪，点「启动桌宠」");
            }
        }
    }
}