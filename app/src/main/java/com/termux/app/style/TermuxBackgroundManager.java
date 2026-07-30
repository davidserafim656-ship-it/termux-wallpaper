package com.termux.app.style;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Point;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.viewpager.widget.ViewPager;

import com.termux.R;
import com.termux.app.TermuxActivity;
import com.termux.shared.data.DataUtils;
import com.termux.shared.errors.Error;
import com.termux.shared.file.FileUtils;
import com.termux.shared.image.ImageUtils;
import com.termux.shared.logger.Logger;
import com.termux.shared.termux.TermuxConstants;
import com.termux.shared.termux.extrakeys.ExtraKeysView;
import com.termux.shared.termux.settings.preferences.TermuxAppSharedPreferences;
import com.termux.shared.theme.ThemeUtils;
import com.termux.shared.view.ViewUtils;
import com.termux.terminal.TerminalSession;
import com.termux.terminal.TextStyle;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class TermuxBackgroundManager {

    /** Open Android image picker for a new terminal background. */
    private void pickImageFromGallery() {
        Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
        intent.setType(ImageUtils.ANY_IMAGE_TYPE);
        intent.addCategory(Intent.CATEGORY_OPENABLE);

        mActivity.startActivityForResult(
            Intent.createChooser(intent, mActivity.getString(R.string.action_set_background_image)),
            REQUEST_PICK_BACKGROUND_IMAGE
        );
    }

    /** Handle the image selected by Android's picker. */
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode != REQUEST_PICK_BACKGROUND_IMAGE ||
            resultCode != Activity.RESULT_OK ||
            data == null) {
            return;
        }

        Uri uri = data.getData();
        if (uri == null) return;

        try {
            executor.execute(() -> {
                Bitmap bitmap = ImageUtils.getBitmap(mActivity, uri);

                if (bitmap == null) {
                    Logger.logErrorAndShowToast(
                        mActivity,
                        LOG_TAG,
                        mActivity.getString(
                            R.string.error_background_image_loading_from_gallery_failed
                        )
                    );
                    return;
                }

                ImageUtils.compressAndSaveBitmap(
                    bitmap,
                    TermuxConstants.TERMUX_BACKGROUND_IMAGE_PATH
                );

                boolean success = generateImageFiles(mActivity, bitmap);

                if (success) {
                    notifyBackgroundUpdated(true);
                    Logger.logInfo(LOG_TAG, "Background image loaded successfully.");
                } else {
                    Logger.logErrorAndShowToast(
                        mActivity,
                        LOG_TAG,
                        mActivity.getString(
                            R.string.error_background_image_loading_from_gallery_failed
                        )
                    );
                }
            });
        } catch (Exception e) {
            Logger.logStackTraceWithMessage(LOG_TAG, "Failed to load image", e);
            Logger.showToast(
                mActivity,
                mActivity.getString(
                    R.string.error_background_image_loading_from_gallery_failed
                ),
                true
            );
        }
    }

    /**
     * If the background images exist then ask user whether to restore them or not.
     * If denied pick from gallery.
     */
    private void restoreBackgroundImages() {
        AlertDialog.Builder b = new AlertDialog.Builder(mActivity);

        b.setMessage(R.string.title_restore_background_image);
        b.setPositiveButton(R.string.action_yes, (dialog, id) -> {
            notifyBackgroundUpdated(true);
        });

        b.setNegativeButton(R.string.action_no, ((dialog, id) -> {
            pickImageFromGallery();
        }));

        b.show();
    }

    /**
     * Generate background image files using original image. {@link Context}
     * passed to this method must be of an {@link Activity} to determine the size
     * of display.
     *
     * @param context The context require for the operations.
     * @param bitmap Image bitmap to save as background.
     * @return Returns whether the images generated successfully.
     */
    public static boolean generateImageFiles(@NonNull Context context, Bitmap bitmap) {

        if (bitmap == null || !(context instanceof Activity)) {
            return false;
        }

        Point size = ViewUtils.getDisplaySize(context, true);
        boolean isLandscape = ViewUtils.getDisplayOrientation(context) == Configuration.ORIENTATION_LANDSCAPE;
        Error error;

        if (isLandscape) {
            error = ImageUtils.saveForDisplayResolution(bitmap, size, TermuxConstants.TERMUX_BACKGROUND_IMAGE_LANDSCAPE_PATH, TermuxConstants.TERMUX_BACKGROUND_IMAGE_PORTRAIT_PATH);

        } else {
            error = ImageUtils.saveForDisplayResolution(bitmap, DataUtils.swap(size), TermuxConstants.TERMUX_BACKGROUND_IMAGE_LANDSCAPE_PATH, TermuxConstants.TERMUX_BACKGROUND_IMAGE_PORTRAIT_PATH);
        }

        return error == null;
    }


    /**
     * Updates background to image or solid color. If forced then load again even if
     * the background is already set. Forced update is require when the display orientation
     * is changed.
     *
     * @param forced Force background update task.
     */
    public void updateBackground(boolean forced) {
        if (!mActivity.isVisible()) return;

        if (mActivity.getPreferences().isBackgroundImageEnabled()) {

            Drawable drawable = mActivity.getWindow().getDecorView().getBackground();

            // If it's not forced update and background is already drawn,
            // then avoid reloading of image.
            if (!forced && ImageUtils.isBitmapDrawable(drawable)) {
                return;
            }

            updateBackgroundImage();
        } else {
            updateBackgroundColor();
        }

        updateToolbarBackground();
    }

    /**
     * Set background to color.
     */
    public void updateBackgroundColor() {
        if (!mActivity.isVisible()) return;

        TerminalSession session = mActivity.getCurrentSession();

        if (session != null && session.getEmulator() != null) {
            mActivity.getWindow().getDecorView().setBackgroundColor(session.getEmulator().mColors.mCurrentColors[TextStyle.COLOR_INDEX_BACKGROUND]);
        }
    }

    /**
     * Set background to image corresponding to display orientation.
     */
    public void updateBackgroundImage() {
        boolean isLandscape = ViewUtils.getDisplayOrientation(mActivity) == Configuration.ORIENTATION_LANDSCAPE;
        String imagePath = isLandscape ? TermuxConstants.TERMUX_BACKGROUND_IMAGE_LANDSCAPE_PATH : TermuxConstants.TERMUX_BACKGROUND_IMAGE_PORTRAIT_PATH;

        try {
            // Performing on main Thread may cause ANR and lag.
            executor.execute(() -> {
                if (isImageFilesExist(mActivity, true)) {
                    Drawable drawable = ImageUtils.getDrawable(imagePath);
                    ImageUtils.addOverlay(drawable, mActivity.getProperties().getBackgroundOverlayColor());

                    handler.post(() -> mActivity.getWindow().getDecorView().setBackground(drawable));
                } else {
                    Logger.logErrorAndShowToast(mActivity, LOG_TAG, mActivity.getString(R.string.error_background_image_loading_failed));

                    // Image files are unable to load so set background to solid color and notify update.
                    handler.post(this::updateBackgroundColor);
                    notifyBackgroundUpdated(false);
                }
            });

        } catch (Exception e) {
            Logger.logStackTraceWithMessage(LOG_TAG, "Failed to load image", e);
            Logger.showToast(mActivity, mActivity.getString(R.string.error_background_image_loading_failed), true);

            // Since loading of image is failed, Set background to solid color.
            updateBackgroundColor();
            notifyBackgroundUpdated(false);
        }
    }

    /**
     * Set backgroudn of the ExtraKey toolbar and buttons.
     * Must be called when background preference is changed.
     */
    public void updateToolbarBackground() {
        ViewPager viewPager = mActivity.getTerminalToolbarViewPager();
        ExtraKeysView extraKeysView = mActivity.getExtraKeysView();

        if (viewPager == null || extraKeysView == null) {
            return;
        }

        if (mPreferences.isBackgroundImageEnabled()) {
            // Set overlay background to ToolbarViewPager and make button transparent.
            mActivity.getTerminalToolbarViewPager().setBackgroundColor(mActivity.getProperties().getBackgroundOverlayColor());
            extraKeysView.setButtonBackgroundColor(Color.TRANSPARENT);

        } else {
            // Use default background color of ToolbarViewPager and button.
            viewPager.setBackgroundColor(Color.BLACK);
            extraKeysView.setButtonBackgroundColor(ThemeUtils.getSystemAttrColor(mActivity, ExtraKeysView.ATTR_BUTTON_BACKGROUND_COLOR, ExtraKeysView.DEFAULT_BUTTON_BACKGROUND_COLOR));
        }
    }



    /**
     * Notify that the background is changed. New background can be image or solid color.
     *
     * @param isImage The {@code boolean} indicates that new background is image or not.
     */
    public void notifyBackgroundUpdated(boolean isImage) {
        mPreferences.setBackgroundImageEnabled(isImage);
        TermuxActivity.updateTermuxActivityStyling(mActivity, false);
    }

}
