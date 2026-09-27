package com.chickpet;

import android.content.Context;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.webkit.WebView;

/**
 * 关键类：不吃触摸的 WebView。
 *
 * 普通 WebView 会把手指事件全部吞掉，外层悬浮窗收不到 → 拖不动。
 * 这里先让网页自己处理（super），再明确告诉外层「我没消费」→ 事件继续冒泡给悬浮窗。
 */
public class TouchThroughWebView extends WebView {

    public TouchThroughWebView(Context context) {
        super(context);
    }

    public TouchThroughWebView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        // 让网页照常处理（动画、内部状态等）
        try {
            super.onTouchEvent(event);
        } catch (Exception ignored) {
        }
        // ★ 返回 false 是全部关键：不吃事件，让悬浮窗接得到
        return false;
    }
}