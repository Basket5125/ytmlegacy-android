package com.ytmlegacy.android.ui;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;

/**
 * A square, center-cropped image with rounded corners. Drawn with a BitmapShader instead of a
 * clip path, so it stays hardware-accelerated on every Android version (clipPath was not
 * before 4.3).
 */
public class RoundedImageView extends SquareImageView {

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Matrix matrix = new Matrix();
    private final RectF rect = new RectF();
    private final float radius;
    private Bitmap shaderBitmap;
    private BitmapShader shader;

    public RoundedImageView(Context context) {
        super(context);
        radius = 14 * context.getResources().getDisplayMetrics().density;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        Drawable d = getDrawable();
        Bitmap b = d instanceof BitmapDrawable ? ((BitmapDrawable) d).getBitmap() : null;
        if (b == null || b.isRecycled() || getWidth() == 0) {
            super.onDraw(canvas);
            return;
        }
        if (b != shaderBitmap) {
            shaderBitmap = b;
            shader = new BitmapShader(b, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
        }
        // Center-crop the bitmap into the view.
        float scale = Math.max(getWidth() / (float) b.getWidth(), getHeight() / (float) b.getHeight());
        matrix.setScale(scale, scale);
        matrix.postTranslate((getWidth() - b.getWidth() * scale) / 2f, (getHeight() - b.getHeight() * scale) / 2f);
        shader.setLocalMatrix(matrix);
        paint.setShader(shader);
        rect.set(0, 0, getWidth(), getHeight());
        canvas.drawRoundRect(rect, radius, radius, paint);
    }
}
