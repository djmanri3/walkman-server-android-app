package com.djmanri3.Walkman;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

/**
 * Selector de color manual basado en HSB: un área 2D de saturación/brillo y un
 * slider de matiz (hue). Expone el color elegido vía un listener.
 */
public class ColorPickerView extends View {

    public interface OnColorListener {
        void onColorChanged(int color);
    }

    private static final float[] HSV = {0f, 1f, 1f};

    private final Paint mPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mIndicatorPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private Bitmap mSvBitmap;
    private Bitmap mHueBitmap;

    private float mSvLeft, mSvTop, mSvWidth, mSvHeight;
    private float mHueLeft, mHueTop, mHueWidth, mHueHeight;
    private float mSvX, mSvY;
    private float mHueX;
    private int mColor = 0xFFFF0000;
    private OnColorListener mListener;
    private boolean mTouchSv = false;
    private boolean mTouchHue = false;

    public ColorPickerView(Context context) {
        this(context, null);
    }

    public ColorPickerView(Context context, AttributeSet attrs) {
        super(context, attrs);
        mIndicatorPaint.setStyle(Paint.Style.STROKE);
        mIndicatorPaint.setStrokeWidth(dp(2));
        mIndicatorPaint.setColor(0xFFFFFFFF);
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        int pad = (int) dp(8);
        mSvWidth = w - pad * 2;
        mSvHeight = h * 0.68f;
        mSvLeft = pad;
        mSvTop = pad;
        mHueWidth = w - pad * 2;
        mHueHeight = dp(26);
        mHueLeft = pad;
        mHueTop = mSvTop + mSvHeight + dp(16);
        buildSvBitmap();
        buildHueBitmap();
        applyPosition();
    }

    private void buildSvBitmap() {
        int w = Math.max(1, (int) mSvWidth);
        int h = Math.max(1, (int) mSvHeight);
        if (mSvBitmap != null && mSvBitmap.getWidth() == w && mSvBitmap.getHeight() == h) {
            mSvBitmap.eraseColor(Color.TRANSPARENT);
        } else {
            mSvBitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        }
        Canvas c = new Canvas(mSvBitmap);
        // De blanco (s=0) al matiz puro (s=1) en horizontal.
        Paint p = new Paint();
        int hueColor = Color.HSVToColor(new float[]{HSV[0], 1f, 1f});
        p.setShader(new LinearGradient(0, 0, w, 0,
                0xFFFFFFFF, hueColor, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, w, h, p);
        // De transparente (v=1) a negro (v=0) en vertical.
        Paint black = new Paint();
        black.setShader(new LinearGradient(0, 0, 0, h,
                0x00000000, 0xFF000000, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, w, h, black);
    }

    private void buildHueBitmap() {
        int w = Math.max(1, (int) mHueWidth);
        int h = Math.max(1, (int) mHueHeight);
        if (mHueBitmap == null || mHueBitmap.getWidth() != w || mHueBitmap.getHeight() != h) {
            mHueBitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        }
        Canvas c = new Canvas(mHueBitmap);
        int[] cols = new int[7];
        float[] pos = null;
        int[] stops = {0, 60, 120, 180, 240, 300, 360};
        for (int i = 0; i < 7; i++) {
            cols[i] = Color.HSVToColor(new float[]{stops[i], 1f, 1f});
        }
        Paint p = new Paint();
        p.setShader(new LinearGradient(0, 0, w, 0, cols, pos, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, w, h, p);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (mSvBitmap == null || mHueBitmap == null) {
            return;
        }
        canvas.drawBitmap(mSvBitmap, mSvLeft, mSvTop, mPaint);
        canvas.drawBitmap(mHueBitmap, mHueLeft, mHueTop, mPaint);
        drawIndicator(canvas, mSvX, mSvY, mSvWidth, mSvHeight);
        drawIndicator(canvas, mHueX, mHueTop, mHueWidth, mHueHeight);
    }

    private void drawIndicator(Canvas canvas, float x, float y, float w, float h) {
        canvas.drawCircle(x, y, dp(7), mIndicatorPaint);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        float x = event.getX();
        float y = event.getY();
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                mTouchSv = inside(mSvLeft, mSvTop, mSvWidth, mSvHeight, x, y);
                if (!mTouchSv) {
                    mTouchHue = inside(mHueLeft, mHueTop, mHueWidth, mHueHeight, x, y);
                }
                if (mTouchSv || mTouchHue) {
                    handle(x, y);
                }
                return true;
            case MotionEvent.ACTION_MOVE:
                if (mTouchSv || mTouchHue) {
                    handle(x, y);
                }
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                mTouchSv = false;
                mTouchHue = false;
                performClick();
                return true;
        }
        return super.onTouchEvent(event);
    }

    @Override
    public boolean performClick() {
        super.performClick();
        return true;
    }

    private void handle(float x, float y) {
        if (mTouchHue) {
            mHueX = clampX(x, mHueLeft, mHueWidth);
            float hue = 360f * (mHueX - mHueLeft) / mHueWidth;
            HSV[0] = hue;
            buildSvBitmap();
            applyPosition();
        } else if (mTouchSv) {
            mSvX = clampX(x, mSvLeft, mSvWidth);
            mSvY = clampX(y, mSvTop, mSvHeight);
            float s = (mSvX - mSvLeft) / mSvWidth;
            float v = 1f - (mSvY - mSvTop) / mSvHeight;
            HSV[1] = s;
            HSV[2] = v;
        }
        mColor = Color.HSVToColor(HSV);
        invalidate();
        if (mListener != null) {
            mListener.onColorChanged(mColor);
        }
    }

    /** Ajusta la posición del indicador SV a la S/V actuales. */
    private void applyPosition() {
        float s = HSV[1];
        float v = HSV[2];
        mSvX = mSvLeft + s * mSvWidth;
        mSvY = mSvTop + (1f - v) * mSvHeight;
        mHueX = mHueLeft + (HSV[0] / 360f) * mHueWidth;
        invalidate();
    }

    private boolean inside(float l, float t, float w, float h, float x, float y) {
        return x >= l && x <= l + w && y >= t && y <= t + h;
    }

    private float clampX(float val, float min, float len) {
        return Math.max(min, Math.min(min + len, val));
    }

    private float dp(float v) {
        return v * getResources().getDisplayMetrics().density;
    }

    public int getColor() {
        return mColor;
    }

    public void setColor(int color) {
        Color.colorToHSV(color, HSV);
        mColor = color;
        if (mSvBitmap != null) {
            buildSvBitmap();
        }
        applyPosition();
    }

    public void setOnColorListener(OnColorListener l) {
        mListener = l;
    }
}
