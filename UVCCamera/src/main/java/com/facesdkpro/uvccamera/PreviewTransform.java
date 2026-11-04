package com.facesdkpro.uvccamera;

import android.graphics.Matrix;

/** GPU-side TextureView transform of an unrotated Surface preview. It does not change callback pixels. */
public final class PreviewTransform {
    private PreviewTransform() {}
    public static Matrix fitCenter(int viewWidth, int viewHeight, int sourceWidth, int sourceHeight, int rotationDegrees) {
        return create(viewWidth, viewHeight, sourceWidth, sourceHeight, rotationDegrees, false);
    }
    public static Matrix centerCrop(int viewWidth, int viewHeight, int sourceWidth, int sourceHeight, int rotationDegrees) {
        return create(viewWidth, viewHeight, sourceWidth, sourceHeight, rotationDegrees, true);
    }
    private static Matrix create(int vw, int vh, int sw, int sh, int rotation, boolean crop) {
        Matrix matrix = new Matrix(); matrix.setValues(values(vw, vh, sw, sh, rotation, crop)); return matrix;
    }
    // Kept pure for numeric verification independently of the Android renderer.
    static float[] values(int vw, int vh, int sw, int sh, int rotation, boolean crop) {
        FrameTransforms.validateRotation(rotation);
        if (vw <= 0 || vh <= 0 || sw <= 0 || sh <= 0) throw new IllegalArgumentException("Positive view/image dimensions required");
        int cos = rotation == 0 ? 1 : rotation == 180 ? -1 : 0;
        int sin = rotation == 90 ? 1 : rotation == 270 ? -1 : 0;
        int ow = FrameTransforms.outputWidth(sw, sh, rotation), oh = FrameTransforms.outputHeight(sw, sh, rotation);
        float sx = (float)vw / ow, sy = (float)vh / oh, scale = crop ? Math.max(sx, sy) : Math.min(sx, sy);
        return new float[]{scale * cos * sw / vw, -scale * sin * sh / vh,
                vw / 2f - scale * cos * sw / 2f + scale * sin * sh / 2f,
                scale * sin * sw / vw, scale * cos * sh / vh,
                vh / 2f - scale * sin * sw / 2f - scale * cos * sh / 2f, 0, 0, 1};
    }
}
