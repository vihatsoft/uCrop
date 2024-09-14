package com.yalantis.ucrop.view;

import android.annotation.TargetApi;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Matrix;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Build;
import android.renderscript.Allocation;
import android.renderscript.Element;
import android.renderscript.RenderScript;
import android.renderscript.ScriptIntrinsicConvolve3x3;
import android.util.AttributeSet;
import android.util.Log;

import androidx.annotation.IntRange;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.widget.AppCompatImageView;

import com.yalantis.ucrop.callback.BitmapLoadCallback;
import com.yalantis.ucrop.model.ExifInfo;
import com.yalantis.ucrop.util.BitmapLoadUtils;
import com.yalantis.ucrop.util.ColorFilterGenerator;
import com.yalantis.ucrop.util.FastBitmapDrawable;
import com.yalantis.ucrop.util.RectUtils;

/**
 * Created by Oleksii Shliama (https://github.com/shliama).
 * <p/>
 * This class provides base logic to setup the image, transform it with matrix (move, scale, rotate),
 * and methods to get current matrix state.
 */
public class TransformImageView extends AppCompatImageView {

    private static final String TAG = "TransformImageView";

    private static final int RECT_CORNER_POINTS_COORDS = 8;
    private static final int RECT_CENTER_POINT_COORDS = 2;
    private static final int MATRIX_VALUES_COUNT = 9;

    protected final float[] mCurrentImageCorners = new float[RECT_CORNER_POINTS_COORDS];
    protected final float[] mCurrentImageCenter = new float[RECT_CENTER_POINT_COORDS];

    private final float[] mMatrixValues = new float[MATRIX_VALUES_COUNT];

    protected Matrix mCurrentImageMatrix = new Matrix();
    protected int mThisWidth, mThisHeight;

    protected TransformImageListener mTransformImageListener;

    private float[] mInitialImageCorners;
    private float[] mInitialImageCenter;

    protected boolean mBitmapDecoded = false;
    protected boolean mBitmapLaidOut = false;

    private int mMaxBitmapSize = 0;

    private float mBrightness = 0;
    private float mContrast = 0;
    private float mSaturation = 0;

    private Allocation mInAllocation;
    private Allocation mOutAllocation;
    private ScriptIntrinsicConvolve3x3 mSharpnessScript;
    private SharpnessScriptTask mSharpnessScriptTask;

    private float mSharpness = 0;

    private String mImageInputPath, mImageOutputPath;
    private ExifInfo mExifInfo;

    /**
     * Interface for rotation and scale change notifying.
     */
    public interface TransformImageListener {

        void onLoadComplete();

        void onLoadFailure(@NonNull Exception e);

        void onRotate(float currentAngle);

        void onScale(float currentScale);

        void onBrightness(float currentBrightness);

        void onContrast(float currentContrast);

        void onSaturation(float currentSaturation);

        void onSharpness(float currentSharpness);
    }

    public TransformImageView(Context context) {
        this(context, null);
    }

    public TransformImageView(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public TransformImageView(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
        init();
    }

    public void setTransformImageListener(TransformImageListener transformImageListener) {
        mTransformImageListener = transformImageListener;
    }

    @Override
    public void setScaleType(ScaleType scaleType) {
        if (scaleType == ScaleType.MATRIX) {
            super.setScaleType(scaleType);
        } else {
            Log.w(TAG, "Invalid ScaleType. Only ScaleType.MATRIX can be used");
        }
    }

    /**
     * Setter for {@link #mMaxBitmapSize} value.
     * Be sure to call it before {@link #setImageURI(Uri)} or other image setters.
     *
     * @param maxBitmapSize - max size for both width and height of bitmap that will be used in the view.
     */
    public void setMaxBitmapSize(int maxBitmapSize) {
        mMaxBitmapSize = maxBitmapSize;
    }

    public int getMaxBitmapSize() {
        if (mMaxBitmapSize <= 0) {
            mMaxBitmapSize = BitmapLoadUtils.calculateMaxBitmapSize(getContext());
        }
        return mMaxBitmapSize;
    }

    @Override
    public void setImageBitmap(final Bitmap bitmap) {
        setImageDrawable(new FastBitmapDrawable(bitmap));
    }

    public String getImageInputPath() {
        return mImageInputPath;
    }

    public String getImageOutputPath() {
        return mImageOutputPath;
    }

    public ExifInfo getExifInfo() {
        return mExifInfo;
    }

    /**
     * This method takes an Uri as a parameter, then calls method to decode it into Bitmap with specified size.
     *
     * @param imageUri - image Uri
     * @throws Exception - can throw exception if having problems with decoding Uri or OOM.
     */
    public void setImageUri(@NonNull Uri imageUri, @Nullable Uri outputUri) throws Exception {
        int maxBitmapSize = getMaxBitmapSize();

        BitmapLoadUtils.decodeBitmapInBackground(getContext(), imageUri, outputUri, maxBitmapSize, maxBitmapSize,
                new BitmapLoadCallback() {

                    @Override
                    public void onBitmapLoaded(@NonNull Bitmap bitmap, @NonNull ExifInfo exifInfo, @NonNull String imageInputPath, @Nullable String imageOutputPath) {
                        mImageInputPath = imageInputPath;
                        mImageOutputPath = imageOutputPath;
                        mExifInfo = exifInfo;

                        mBitmapDecoded = true;
                        createScript(bitmap);
                        setImageBitmap(bitmap);
                    }

                    @Override
                    public void onFailure(@NonNull Exception bitmapWorkerException) {
                        Log.e(TAG, "onFailure: setImageUri", bitmapWorkerException);
                        if (mTransformImageListener != null) {
                            mTransformImageListener.onLoadFailure(bitmapWorkerException);
                        }
                    }
                });
    }

    /**
     * @return - current image scale value.
     * [1.0f - for original image, 2.0f - for 200% scaled image, etc.]
     */
    public float getCurrentScale() {
        return getMatrixScale(mCurrentImageMatrix);
    }

    /**
     * This method calculates scale value for given Matrix object.
     */
    public float getMatrixScale(@NonNull Matrix matrix) {
        return (float) Math.sqrt(Math.pow(getMatrixValue(matrix, Matrix.MSCALE_X), 2)
                + Math.pow(getMatrixValue(matrix, Matrix.MSKEW_Y), 2));
    }

    /**
     * @return - current image rotation angle.
     */
    public float getCurrentAngle() {
        return getMatrixAngle(mCurrentImageMatrix);
    }

    /**
     * This method calculates rotation angle for given Matrix object.
     */
    public float getMatrixAngle(@NonNull Matrix matrix) {
        return (float) -(Math.atan2(getMatrixValue(matrix, Matrix.MSKEW_X),
                getMatrixValue(matrix, Matrix.MSCALE_X)) * (180 / Math.PI));
    }

    /**
     * @return - current image brightness.
     */
    public float getCurrentBrightness() {
        return mBrightness;
    }

    /**
     * @return - current image contrast.
     */
    public float getCurrentContrast() {
        return mContrast;
    }

    /**
     * @return - current image saturation.
     */
    public float getCurrentSaturation() {
        return mSaturation;
    }

    /**
     * @return - current image sharpness.
     */
    public float getCurrentSharpness() {
        return mSharpness;
    }

    @Override
    public void setImageMatrix(Matrix matrix) {
        super.setImageMatrix(matrix);
        mCurrentImageMatrix.set(matrix);
        updateCurrentImagePoints();
    }

    @Nullable
    public Bitmap getViewBitmap() {
        if (getDrawable() == null || !(getDrawable() instanceof FastBitmapDrawable)) {
            return null;
        } else {
            return ((FastBitmapDrawable) getDrawable()).getBitmap();
        }
    }

    /**
     * This method translates current image.
     *
     * @param deltaX - horizontal shift
     * @param deltaY - vertical shift
     */
    public void postTranslate(float deltaX, float deltaY) {
        if (deltaX != 0 || deltaY != 0) {
            mCurrentImageMatrix.postTranslate(deltaX, deltaY);
            setImageMatrix(mCurrentImageMatrix);
        }
    }

    /**
     * This method scales current image.
     *
     * @param deltaScale - scale value
     * @param px         - scale center X
     * @param py         - scale center Y
     */
    public void postScale(float deltaScale, float px, float py) {
        if (deltaScale != 0) {
            mCurrentImageMatrix.postScale(deltaScale, deltaScale, px, py);
            setImageMatrix(mCurrentImageMatrix);
            if (mTransformImageListener != null) {
                mTransformImageListener.onScale(getMatrixScale(mCurrentImageMatrix));
            }
        }
    }

    /**
     * This method rotates current image.
     *
     * @param deltaAngle - rotation angle
     * @param px         - rotation center X
     * @param py         - rotation center Y
     */
    public void postRotate(float deltaAngle, float px, float py) {
        if (deltaAngle != 0) {
            mCurrentImageMatrix.postRotate(deltaAngle, px, py);
            setImageMatrix(mCurrentImageMatrix);
            if (mTransformImageListener != null) {
                mTransformImageListener.onRotate(getMatrixAngle(mCurrentImageMatrix));
            }
        }
    }

    /**
     * This method changes image brightness.
     *
     * @param brightness - brightness
     */
    public void postBrightness(float brightness) {
        mBrightness += brightness;

        setColorFilters();
        mTransformImageListener.onBrightness(mBrightness);
    }

    /**
     * This method changes image contrast.
     *
     * @param contrast - contrast
     */
    public void postContrast(float contrast) {
        mContrast += contrast;

        setColorFilters();
        mTransformImageListener.onContrast(mContrast);
    }

    /**
     * This method changes image saturation.
     *
     * @param saturation - saturation
     */
    public void postSaturation(float saturation) {
        mSaturation += saturation;

        setColorFilters();
        mTransformImageListener.onSaturation(mSaturation);
    }

    private void setColorFilters() {
        ColorMatrix cm = new ColorMatrix();
        mBrightness = ColorFilterGenerator.adjustBrightness(cm, mBrightness);
        mContrast = ColorFilterGenerator.adjustContrast(cm, mContrast);
        mSaturation = ColorFilterGenerator.adjustSaturation(cm, mSaturation);
        setColorFilter(new ColorMatrixColorFilter(cm));
    }

    /**
     * This method changes image sharpness.
     *
     * @param sharpness - sharpness
     */
    public void postSharpness(float sharpness) {
        mSharpness += sharpness;
        mSharpness = Math.min(5, Math.max(0, mSharpness));

        if (mSharpnessScriptTask != null) {
            mSharpnessScriptTask.cancel(false);
        }
        mSharpnessScriptTask = new SharpnessScriptTask();
        mSharpnessScriptTask.execute(mSharpness);

        mTransformImageListener.onSharpness(mSharpness * 10);
    }

    /*
     * In the AsyncTask, it invokes RenderScript intrinsics to do a filtering.
     * After the filtering is done, an operation blocks at Allocation.copyTo() in AsyncTask thread.
     * Once all operation is finished at onPostExecute() in UI thread, it can invalidate and update
     * ImageView UI.
     */
    @TargetApi(Build.VERSION_CODES.JELLY_BEAN_MR1)
    private class SharpnessScriptTask extends AsyncTask<Float, Void, Boolean> {
        Boolean issued = false;

        protected Boolean doInBackground(Float... values) {
            if (!isCancelled()) {
                issued = true;

                float value = values[0];
                float[] coefficients = {
                        0, -value, 0,
                        -value, 1 + (4 * value), -value,
                        0, -value, 0};

                mSharpnessScript.setCoefficients(coefficients);
                mSharpnessScript.forEach(mOutAllocation);
                return true;
            }
            return false;
        }

        @Override
        protected void onPostExecute(Boolean result) {
            if (result) {
                updateView();
            }
        }

        @Override
        protected void onCancelled(Boolean result) {
            if (issued) {
                updateView();
            }
        }

        private void updateView() {
            Bitmap sourceBitmap = getViewBitmap();
            Bitmap alteredBitmap = sourceBitmap.copy(sourceBitmap.getConfig(), false);

            mOutAllocation.copyTo(alteredBitmap);
            setImageBitmap(alteredBitmap);
        }
    }

    protected void init() {
        setScaleType(ScaleType.MATRIX);
    }

    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        super.onLayout(changed, left, top, right, bottom);
        if (changed || (mBitmapDecoded && !mBitmapLaidOut)) {

            left = getPaddingLeft();
            top = getPaddingTop();
            right = getWidth() - getPaddingRight();
            bottom = getHeight() - getPaddingBottom();
            mThisWidth = right - left;
            mThisHeight = bottom - top;

            onImageLaidOut();
        }
    }

    /**
     * When image is laid out {@link #mInitialImageCenter} and {@link #mInitialImageCenter}
     * must be set.
     */
    protected void onImageLaidOut() {
        final Drawable drawable = getDrawable();
        if (drawable == null) {
            return;
        }

        float w = drawable.getIntrinsicWidth();
        float h = drawable.getIntrinsicHeight();

        Log.d(TAG, String.format("Image size: [%d:%d]", (int) w, (int) h));

        RectF initialImageRect = new RectF(0, 0, w, h);
        mInitialImageCorners = RectUtils.getCornersFromRect(initialImageRect);
        mInitialImageCenter = RectUtils.getCenterFromRect(initialImageRect);

        mBitmapLaidOut = true;

        if (mTransformImageListener != null) {
            mTransformImageListener.onLoadComplete();
        }
    }

    /**
     * This method returns Matrix value for given index.
     *
     * @param matrix     - valid Matrix object
     * @param valueIndex - index of needed value. See {@link Matrix#MSCALE_X} and others.
     * @return - matrix value for index
     */
    protected float getMatrixValue(@NonNull Matrix matrix, @IntRange(from = 0, to = MATRIX_VALUES_COUNT) int valueIndex) {
        matrix.getValues(mMatrixValues);
        return mMatrixValues[valueIndex];
    }

    /**
     * This method logs given matrix X, Y, scale, and angle values.
     * Can be used for debug.
     */
    @SuppressWarnings("unused")
    protected void printMatrix(@NonNull String logPrefix, @NonNull Matrix matrix) {
        float x = getMatrixValue(matrix, Matrix.MTRANS_X);
        float y = getMatrixValue(matrix, Matrix.MTRANS_Y);
        float rScale = getMatrixScale(matrix);
        float rAngle = getMatrixAngle(matrix);
        Log.d(TAG, logPrefix + ": matrix: { x: " + x + ", y: " + y + ", scale: " + rScale + ", angle: " + rAngle + " }");
    }

    /**
     * This method updates current image corners and center points that are stored in
     * {@link #mCurrentImageCorners} and {@link #mCurrentImageCenter} arrays.
     * Those are used for several calculations.
     */
    private void updateCurrentImagePoints() {
        mCurrentImageMatrix.mapPoints(mCurrentImageCorners, mInitialImageCorners);
        mCurrentImageMatrix.mapPoints(mCurrentImageCenter, mInitialImageCenter);
    }

    /**
     * Initialize RenderScript.
     * <p>
     * <p>Creates RenderScript kernel that performs sharpness manipulation.</p>
     */
    private void createScript(Bitmap bitmap) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.JELLY_BEAN_MR1) {
            return;
        }

        // Initialize RS
        RenderScript rs = RenderScript.create(getContext());

        // Allocate buffers
        mInAllocation = Allocation.createFromBitmap(rs, bitmap.copy(bitmap.getConfig(), false));
        mOutAllocation = Allocation.createFromBitmap(rs, bitmap);

        // Load script
        mSharpnessScript = ScriptIntrinsicConvolve3x3.create(rs, Element.U8_4(rs));
        mSharpnessScript.setInput(mInAllocation);

        rs.destroy();
    }
}
