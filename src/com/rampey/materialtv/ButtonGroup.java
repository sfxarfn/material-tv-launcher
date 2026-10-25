package com.rampey.materialtv;

import android.animation.ValueAnimator;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/**
 * A tinted strip of tabs. The selected page has an accent-colored pill;
 * the focused tab uses the inverse surface colors. Fills fade as focus and selection change.
 */
final class ButtonGroup extends LinearLayout {

    interface Listener {
        void onPick(int index);
    }

    private static final int HEIGHT = 48;
    private static final int ICON = 20;

    private final List<TextView> buttons = new ArrayList<>();
    private final List<Integer> fills = new ArrayList<>();
    private final List<ValueAnimator> fillAnimators = new ArrayList<>();
    private ValueAnimator stripAnimator;
    private final boolean[] selectable;
    private int selected = -1;
    private Palette palette;
    private Listener focusListener;
    private boolean evenGaps;
    private int[] openGap;           // margin per gap while a neighbour of it is focused
    private float[] spread;          // 0 = icons at rest gap, 1 = open around the focused button, per gap
    private ValueAnimator spreadAnimator;

    ButtonGroup(Context context, Typeface face, Palette palette, String[] labels, int[] icons, boolean[] selectable, Listener listener) {
        super(context);
        this.palette = palette;
        this.selectable = selectable;
        setGravity(Gravity.CENTER_VERTICAL);
        setClipChildren(false);
        setClipToPadding(false);
        strip.setCornerRadius(dp(30));
        setBackground(strip);
        setPadding(dp(5), dp(5), dp(5), dp(5));
        styleStrip();
        for (int i = 0; i < labels.length; i++) {
            final int index = i;
            TextView b = new TextView(context);
            b.setText(labels[i]);
            b.setTypeface(face);
            b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
            b.setGravity(Gravity.CENTER);
            b.setFontFeatureSettings("tnum");   // a ticking label keeps its width
            b.setFocusable(true);
            b.setClickable(true);
            Drawable icon = context.getDrawable(icons[i]);
            if (icon != null) {
                icon.setBounds(0, 0, dp(ICON), dp(ICON));
                b.setCompoundDrawablesRelative(icon, null, null, null);
            }
            fitLabel(b);
            b.setOnClickListener(v -> listener.onPick(index));
            b.setOnFocusChangeListener((v, focused) -> {
                float grown = focused ? scaleFor((TextView) v) : 1f;
                v.animate().scaleX(grown).scaleY(grown)
                        .setInterpolator(Motion.FAST_SPATIAL).setDuration(Motion.FAST_SPATIAL_MS).start();
                restyle(index, true);
                if (evenGaps) spreadAround();
                if (focused && focusListener != null) focusListener.onPick(index);
            });
            LayoutParams lp = new LayoutParams(LayoutParams.WRAP_CONTENT, dp(HEIGHT));   // one height for every button, so icon-only ones can be true circles
            if (i > 0) lp.setMarginStart(dp(4));
            buttons.add(b);
            fills.add(Color.TRANSPARENT);
            fillAnimators.add(null);
            addView(b, lp);
            restyle(index, false);
        }
    }

    /** Called when a tab gets focus, so the page can follow focus without a click. */
    void setFocusListener(Listener l) {
        focusListener = l;
    }

    void select(int index) {
        int previous = selected;
        selected = index;
        if (previous >= 0) restyle(previous, true);
        if (index >= 0) restyle(index, true);
    }

    void setPalette(Palette p) {
        palette = p;
        if (stripAnimator != null) stripAnimator.cancel();
        int from = strip.getColor().getDefaultColor();
        int to = stripColor();
        stripAnimator = ValueAnimator.ofArgb(from, to).setDuration(Motion.DEFAULT_EFFECTS_MS);
        stripAnimator.setInterpolator(Motion.DEFAULT_EFFECTS);
        stripAnimator.addUpdateListener(a -> strip.setColor((int) a.getAnimatedValue()));
        stripAnimator.start();
        for (int i = 0; i < buttons.size(); i++) restyle(i, true, true);
    }

    private final GradientDrawable strip = new GradientDrawable();

    private void styleStrip() {
        strip.setColor(stripColor());
    }

    private int stripColor() {
        return (palette.container & 0x00FFFFFF) | 0xE6000000;
    }

    private void restyle(int index, boolean animate) {
        restyle(index, animate, false);
    }

    private void restyle(int index, boolean animate, boolean retint) {
        ValueAnimator previous = fillAnimators.get(index);
        if (previous != null) previous.cancel();
        fillAnimators.set(index, null);
        TextView b = buttons.get(index);
        boolean focused = b.hasFocus();
        boolean on = index == selected && selectable[index];
        // The selected tab is quiet (a tonal fill) until focus is on it; only the focused button gets the bright fill.
        int fill = focused ? palette.inverseSurface : on ? palette.containerHigh : Color.TRANSPARENT;
        int ink = focused ? palette.inverseOnSurface : on ? palette.onSurface : palette.onSurfaceVariant;
        b.setTextColor(ink);
        for (Drawable d : b.getCompoundDrawablesRelative()) if (d != null) d.setTintList(ColorStateList.valueOf(ink));
        GradientDrawable bg = b.getBackground() instanceof GradientDrawable ? (GradientDrawable) b.getBackground() : null;
        if (bg == null) {
            bg = new GradientDrawable();
            bg.setCornerRadius(dp(HEIGHT / 2f));
            bg.setColor(fill);
            b.setBackground(bg);
            fills.set(index, fill);
            return;
        }
        int from = fills.get(index);
        if (!animate || from == fill || !isAttachedToWindow()) {
            bg.setColor(fill);
            fills.set(index, fill);
            return;
        }
        final GradientDrawable g = bg;
        ValueAnimator a = ValueAnimator.ofArgb(from, fill)
                .setDuration(retint ? Motion.DEFAULT_EFFECTS_MS : Motion.FAST_EFFECTS_MS);
        a.setInterpolator(retint ? Motion.DEFAULT_EFFECTS : Motion.FAST_EFFECTS);
        a.addUpdateListener(an -> {
            int color = (int) an.getAnimatedValue();
            fills.set(index, color);
            g.setColor(color);
        });
        fillAnimators.set(index, a);
        a.start();
    }

    void stopAnimations() {
        if (spreadAnimator != null) {
            spreadAnimator.end();
            spreadAnimator = null;
        }
        if (stripAnimator != null) {
            stripAnimator.end();
            stripAnimator = null;
        }
        for (int i = 0; i < buttons.size(); i++) {
            ValueAnimator a = fillAnimators.get(i);
            if (a != null) a.end();
            fillAnimators.set(i, null);
            TextView b = buttons.get(i);
            b.animate().cancel();
            b.setScaleX(b.hasFocus() ? scaleFor(b) : 1f);
            b.setScaleY(b.hasFocus() ? scaleFor(b) : 1f);
        }
    }

    @Override protected void onDetachedFromWindow() {
        stopAnimations();
        super.onDetachedFromWindow();
    }

    private int dp(float v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics()));
    }

    View button(int index) {
        return buttons.get(index);
    }

    /**
     * Every icon-to-icon gap equals the gap from the strip's edge to the first icon. Buttons may overlap in layout:
     * only the focused one has a fill, and the overlap sits in its neighbour's empty padding, never on its icon.
     */
    void evenGaps() {
        evenGaps = true;
        spaceIcons();
    }

    private void spaceIcons() {
        if (spread == null) {
            spread = new float[buttons.size()];
            openGap = new int[buttons.size()];
            java.util.Arrays.fill(openGap, dp(4));
        }
        int inset = dp((HEIGHT - ICON) / 2f);
        int target = dp(5) + inset;
        for (int i = 1; i < buttons.size(); i++) {
            TextView prev = buttons.get(i - 1), cur = buttons.get(i);
            int rest = prev.getText().length() == 0 ? target - inset - cur.getPaddingStart() : dp(4);
            LayoutParams lp = (LayoutParams) cur.getLayoutParams();
            lp.setMarginStart(Math.round(rest + (openGap[i] - rest) * spread[i]));
            cur.setLayoutParams(lp);   // a margin change after the first layout is only picked up when the params are set again
        }
        requestLayout();
    }

    /** Slide the neighbours of the focused button apart, and close the gaps it left behind. */
    private void spreadAround() {
        if (spread == null) spread = new float[buttons.size()];
        final float[] from = spread.clone(), to = new float[spread.length];
        int inset = dp((HEIGHT - ICON) / 2f);
        int target = dp(5) + inset;
        for (int i = 1; i < to.length; i++) {
            TextView prev = buttons.get(i - 1), cur = buttons.get(i);
            if (prev.hasFocus() || cur.hasFocus()) {
                to[i] = 1f;
                // The focused circle ends up `target` away from its neighbour's icon, on each side.
                if (prev.hasFocus() && prev.getText().length() == 0) openGap[i] = target - cur.getPaddingStart();
                else if (cur.hasFocus() && prev.getText().length() == 0) openGap[i] = target - inset;
                else openGap[i] = dp(4);
            }
        }
        if (spreadAnimator != null) spreadAnimator.cancel();
        spreadAnimator = ValueAnimator.ofFloat(0f, 1f).setDuration(Motion.FAST_SPATIAL_MS);
        spreadAnimator.setInterpolator(Motion.FAST_SPATIAL);
        spreadAnimator.addUpdateListener(a -> {
            float t = (float) a.getAnimatedValue();
            for (int i = 1; i < to.length; i++) spread[i] = from[i] + (to[i] - from[i]) * t;
            spaceIcons();
        });
        spreadAnimator.start();
    }

    /** Icon-only buttons stay their size on focus, so the circle keeps the strip's own top and bottom space. */
    private static float scaleFor(TextView b) {
        return b.getText().length() == 0 ? 1f : 1.05f;
    }

    /** An empty label makes an icon-only round button. */
    void setLabel(int index, String label) {
        TextView b = buttons.get(index);
        if (label.contentEquals(b.getText())) return;
        b.setText(label);
        fitLabel(b);
    }

    private void fitLabel(TextView b) {
        boolean iconOnly = b.getText().length() == 0;
        b.setMinWidth(iconOnly ? dp(HEIGHT) : 0);   // icon-only: width = height, so the fill is a circle
        b.setPadding(iconOnly ? dp((HEIGHT - ICON) / 2f) : dp(20), 0, iconOnly ? 0 : dp(22), 0);   // a TextView draws its icon at the edge, so centre it with padding
        b.setCompoundDrawablePadding(iconOnly ? 0 : dp(8));
        if (evenGaps) spaceIcons();
    }
}
