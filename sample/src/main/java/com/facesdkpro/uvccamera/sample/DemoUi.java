package com.facesdkpro.uvccamera.sample;

import android.app.Activity;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.graphics.drawable.StateListDrawable;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.TextureView;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;

import java.util.List;

/** 两个演示页面共用的原生 View 样式与组件。 */
final class DemoUi {
    final Context context;
    final int background, surface, input, border, text, muted, accent, rgb, ir, error;

    DemoUi(Context context) {
        this.context = context;
        background = context.getColor(R.color.demo_background);
        surface = context.getColor(R.color.demo_surface);
        input = context.getColor(R.color.demo_input);
        border = context.getColor(R.color.demo_border);
        text = context.getColor(R.color.demo_text);
        muted = context.getColor(R.color.demo_muted);
        accent = context.getColor(R.color.demo_accent);
        rgb = context.getColor(R.color.demo_rgb);
        ir = context.getColor(R.color.demo_ir);
        error = context.getColor(R.color.demo_error);
    }

    int dp(float value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }

    LinearLayout column() {
        LinearLayout v = new LinearLayout(context);
        v.setOrientation(LinearLayout.VERTICAL);
        return v;
    }

    LinearLayout row() {
        LinearLayout v = new LinearLayout(context);
        v.setGravity(Gravity.CENTER_VERTICAL);
        return v;
    }

    LinearLayout card() {
        LinearLayout v = column();
        v.setPadding(dp(16), dp(16), dp(16), dp(16));
        v.setBackground(shape(surface, 16, border));
        return v;
    }

    GradientDrawable shape(int color, int radius, int stroke) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp(radius));
        if (stroke != 0) d.setStroke(dp(1), stroke);
        return d;
    }

    int tint(int color, int alpha) {
        return (color & 0x00ffffff) | (alpha << 24);
    }

    TextView label(String value, int size, int color) {
        TextView v = new TextView(context);
        v.setText(value);
        v.setTextSize(size);
        v.setTextColor(color);
        v.setIncludeFontPadding(false);
        return v;
    }

    TextView strong(String value, int size, int color) {
        TextView v = label(value, size, color);
        v.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        return v;
    }

    void gap(LinearLayout parent, int size) {
        parent.addView(
                new View(context),
                new LinearLayout.LayoutParams(
                        parent.getOrientation() == LinearLayout.HORIZONTAL ? dp(size) : 1,
                        parent.getOrientation() == LinearLayout.VERTICAL ? dp(size) : 1));
    }

    Spinner spinner(String description) {
        Spinner v = new Spinner(context, Spinner.MODE_DROPDOWN);
        v.setContentDescription(description);
        v.setPadding(0, 0, 0, 0);
        v.setBackground(context.getDrawable(R.drawable.spinner_background));
        v.setPopupBackgroundDrawable(shape(surface, 12, border));
        v.setDropDownVerticalOffset(dp(4));
        v.setMinimumHeight(dp(48));
        return v;
    }

    void items(Spinner spinner, List<String> values) {
        ArrayAdapter<String> a = new ArrayAdapter<>(context, R.layout.spinner_value, values);
        a.setDropDownViewResource(R.layout.spinner_choice);
        spinner.setAdapter(a);
    }

    void deviceItems(Spinner spinner, List<String> values) {
        ArrayAdapter<String> a = new ArrayAdapter<>(context, R.layout.spinner_device_value, values);
        a.setDropDownViewResource(R.layout.spinner_device_choice);
        spinner.setAdapter(a);
    }

    void items(Spinner spinner, String[] values) {
        items(spinner, java.util.Arrays.asList(values));
    }

    LinearLayout field(String caption, View control) {
        LinearLayout v = column();
        v.addView(label(caption, 12, muted));
        gap(v, 6);
        v.addView(control, new LinearLayout.LayoutParams(-1, -2));
        return v;
    }

    LinearLayout deviceField(View control) {
        LinearLayout v = column();
        TextView caption = label("选择 USB 设备", 12, 0xffffffff);
        caption.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
        v.addView(caption);
        gap(v, 6);
        v.addView(control, new LinearLayout.LayoutParams(-1, -2));
        return v;
    }

    Drawable icon(int resource, int color, int size) {
        Drawable d = context.getDrawable(resource).mutate();
        d.setTint(color);
        d.setBounds(0, 0, dp(size), dp(size));
        return d;
    }

    private Drawable buttonBackground(boolean primary) {
        StateListDrawable states = new StateListDrawable();
        states.addState(new int[] {-android.R.attr.state_enabled}, shape(input, 12, border));
        states.addState(
                new int[] {}, shape(primary ? accent : tint(muted, 22), 12, primary ? 0 : border));
        return new RippleDrawable(
                ColorStateList.valueOf(0x24ffffff), states, shape(0xffffffff, 12, 0));
    }

    Button button(String value, int resource, boolean primary, Runnable action) {
        Button v = new Button(context);
        v.setAllCaps(false);
        v.setText(value);
        v.setTextSize(14);
        v.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        v.setMinWidth(0);
        v.setMinimumWidth(0);
        v.setMinHeight(dp(48));
        v.setMinimumHeight(dp(48));
        v.setPadding(dp(16), dp(10), dp(16), dp(10));
        ColorStateList colors =
                new ColorStateList(
                        new int[][] {{-android.R.attr.state_enabled}, {}},
                        new int[] {muted, primary ? 0xff062b23 : text});
        v.setTextColor(colors);
        v.setBackground(buttonBackground(primary));
        v.setStateListAnimator(null);
        if (resource != 0) {
            v.setCompoundDrawables(icon(resource, text, 18), null, null, null);
            v.setCompoundDrawableTintList(colors);
            v.setCompoundDrawablePadding(dp(8));
        }
        v.setOnClickListener(view -> action.run());
        return v;
    }

    ImageButton iconButton(String description, int resource, Runnable action) {
        ImageButton v = new ImageButton(context);
        v.setContentDescription(description);
        v.setImageDrawable(icon(resource, muted, 20));
        v.setPadding(dp(13), dp(13), dp(13), dp(13));
        v.setBackground(buttonBackground(false));
        v.setOnClickListener(view -> action.run());
        v.setImageTintList(
                new ColorStateList(
                        new int[][] {{-android.R.attr.state_enabled}, {}},
                        new int[] {tint(muted, 90), text}));
        return v;
    }

    LinearLayout header(String title, String subtitle, Button action) {
        LinearLayout row = row();
        row.setPadding(dp(20), dp(14), dp(20), dp(12));
        ImageView logo = new ImageView(context);
        logo.setImageDrawable(icon(R.drawable.ic_camera, accent, 24));
        logo.setPadding(dp(10), dp(10), dp(10), dp(10));
        logo.setBackground(shape(tint(accent, 22), 13, 0));
        row.addView(logo, new LinearLayout.LayoutParams(dp(44), dp(44)));
        gap(row, 12);
        LinearLayout copy = column();
        TextView name = strong(title, 22, text);
        name.setSingleLine();
        name.setEllipsize(TextUtils.TruncateAt.END);
        copy.addView(name);
        gap(copy, 4);
        TextView sub = label(subtitle, 12, muted);
        sub.setSingleLine();
        sub.setEllipsize(TextUtils.TruncateAt.END);
        copy.addView(sub);
        row.addView(copy, new LinearLayout.LayoutParams(0, -2, 1));
        gap(row, 12);
        row.addView(action, new LinearLayout.LayoutParams(-2, -2));
        return row;
    }

    TextView status(String message) {
        TextView v = label(message, 13, muted);
        v.setMinHeight(dp(44));
        v.setGravity(Gravity.CENTER_VERTICAL);
        v.setMaxLines(2);
        v.setPadding(dp(14), dp(10), dp(14), dp(10));
        v.setBackground(shape(surface, 12, border));
        setStatus(v, message, muted);
        return v;
    }

    static void setText(TextView view, String value) {
        if (!TextUtils.equals(view.getText(), value)) view.setText(value);
    }

    void setStatus(TextView view, String value, int color) {
        setText(view, value);
        if (view.getCurrentTextColor() != color || view.getCompoundDrawables()[0] == null) {
            view.setTextColor(color);
            GradientDrawable dot = shape(color, 4, 0);
            dot.setBounds(0, 0, dp(7), dp(7));
            view.setCompoundDrawables(dot, null, null, null);
            view.setCompoundDrawablePadding(dp(10));
        }
    }

    void install(Activity activity, LinearLayout screen) {
        screen.setBackgroundColor(background);
        screen.setOnApplyWindowInsetsListener(
                (v, insets) -> {
                    v.setPadding(
                            insets.getSystemWindowInsetLeft(),
                            insets.getSystemWindowInsetTop(),
                            insets.getSystemWindowInsetRight(),
                            insets.getSystemWindowInsetBottom());
                    return insets;
                });
        activity.setContentView(screen);
        screen.requestApplyInsets();
    }

    LinearLayout.LayoutParams margin(
            int width, int height, int left, int top, int right, int bottom) {
        LinearLayout.LayoutParams p =
                new LinearLayout.LayoutParams(
                        width < 0 ? width : dp(width), height < 0 ? height : dp(height));
        p.setMargins(dp(left), dp(top), dp(right), dp(bottom));
        return p;
    }

    final class Metric extends LinearLayout {
        final TextView value, detail;

        Metric(String caption, String unit, int color) {
            super(DemoUi.this.context);
            setOrientation(VERTICAL);
            setPadding(dp(12), dp(11), dp(12), dp(11));
            setBackground(shape(surface, 14, border));
            addView(label(caption, 11, muted));
            gap(this, 6);
            LinearLayout values = row();
            value = strong("—", 24, color);
            value.setFontFeatureSettings("tnum");
            value.setSingleLine();
            value.setContentDescription(caption);
            values.addView(value);
            gap(values, 5);
            values.addView(label(unit, 11, muted));
            addView(values);
            gap(this, 5);
            detail = label("等待采集", 11, muted);
            detail.setSingleLine();
            detail.setEllipsize(TextUtils.TruncateAt.END);
            addView(detail);
        }

        void set(String number, String caption) {
            setText(value, number);
            setText(detail, caption);
        }
    }

    final class PreviewCard extends LinearLayout {
        final TextureView texture;
        final TextView meta, badge, emptyTitle, emptyDetail;
        final LinearLayout empty;
        private int displayState = -1;

        PreviewCard(String title, String format, int color) {
            super(DemoUi.this.context);
            setOrientation(VERTICAL);
            setBackground(shape(surface, 16, border));
            setClipToOutline(true);
            LinearLayout head = row();
            head.setPadding(dp(16), dp(13), dp(16), dp(13));
            LinearLayout titles = column();
            titles.addView(strong(title, 15, text));
            gap(titles, 4);
            meta = label(format, 12, muted);
            titles.addView(meta);
            head.addView(titles, new LinearLayout.LayoutParams(0, -2, 1));
            badge = strong("未采集", 11, muted);
            badge.setPadding(dp(9), dp(5), dp(9), dp(5));
            badge.setBackground(shape(input, 8, 0));
            head.addView(badge);
            addView(head);
            FrameLayout frame = new FrameLayout(context);
            frame.setBackgroundColor(0xff050b14);
            texture = new TextureView(context);
            texture.setOpaque(false);
            frame.addView(texture, new FrameLayout.LayoutParams(-1, -1));
            empty = column();
            empty.setGravity(Gravity.CENTER);
            empty.setPadding(dp(20), dp(12), dp(20), dp(12));
            empty.setBackgroundColor(0xff091321);
            ImageView camera = new ImageView(context);
            camera.setImageDrawable(icon(R.drawable.ic_camera, tint(color, 180), 36));
            camera.setPadding(dp(16), dp(16), dp(16), dp(16));
            camera.setBackground(shape(tint(color, 16), 20, 0));
            empty.addView(camera, new LinearLayout.LayoutParams(dp(68), dp(68)));
            gap(empty, 14);
            emptyTitle = strong("等待摄像头画面", 16, text);
            emptyTitle.setGravity(Gravity.CENTER);
            empty.addView(emptyTitle);
            gap(empty, 8);
            emptyDetail = label("开始采集后显示实时预览", 12, muted);
            emptyDetail.setGravity(Gravity.CENTER);
            empty.addView(emptyDetail);
            empty.addOnLayoutChangeListener(
                    (v, l, t, r, b, ol, ot, or, ob) -> {
                        boolean compact = b - t < dp(170);
                        int size = dp(compact ? 48 : 68);
                        if (camera.getLayoutParams().width != size) {
                            camera.setLayoutParams(new LinearLayout.LayoutParams(size, size));
                            camera.setPadding(dp(12), dp(12), dp(12), dp(12));
                        }
                        camera.setVisibility(b - t < dp(120) ? View.GONE : View.VISIBLE);
                        emptyTitle.setTextSize(compact ? 14 : 16);
                        emptyDetail.setVisibility(compact ? View.GONE : View.VISIBLE);
                    });
            frame.addView(empty, new FrameLayout.LayoutParams(-1, -1));
            addView(frame, new LinearLayout.LayoutParams(-1, 0, 1));
        }

        void state(boolean active, String info) {
            display(active ? 2 : 0, info);
        }

        void connecting() {
            display(1, "正在打开设备…");
        }

        private void display(int state, String info) {
            setText(meta, info);
            if (displayState == state) return;
            displayState = state;
            boolean active = state == 2;
            empty.setVisibility(active ? View.GONE : View.VISIBLE);
            badge.setText(state == 1 ? "连接中" : active ? "采集中" : "未采集");
            badge.setTextColor(state == 1 ? ir : active ? accent : muted);
            badge.setBackground(shape(active ? tint(accent, 22) : input, 8, 0));
            setText(emptyTitle, state == 1 ? "正在连接摄像头" : "等待摄像头画面");
            setText(emptyDetail, state == 1 ? "请允许相机及 USB 设备访问" : "开始采集后显示实时预览");
        }
    }
}
