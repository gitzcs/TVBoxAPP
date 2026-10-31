package com.github.tvbox.osc.ui.dialog;

import android.content.Context;
import android.view.View;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.NonNull;

import com.github.tvbox.osc.R;

import java.util.Locale;

/**
 * 更新包下载进度框：百分比 + 已下载/总大小，可取消。
 *
 * <p>进度由 {@link com.github.tvbox.osc.update.UpdateManager} 在主线程回调
 * {@link #updateProgress(int, long, long)}；取消只关框并通知管理器中止下载。
 */
public class UpdateProgressDialog extends BaseDialog {

    public interface OnCancelListener {
        void onCancel();
    }

    private final OnCancelListener listener;
    private ProgressBar progressBar;
    private TextView tvPercent;
    private TextView tvSize;

    public UpdateProgressDialog(@NonNull Context context, OnCancelListener listener) {
        super(context);
        this.listener = listener;
        setContentView(R.layout.dialog_update_progress);
        setCanceledOnTouchOutside(false);
        progressBar = findViewById(R.id.updateProgressBar);
        tvPercent = findViewById(R.id.tvUpdatePercent);
        tvSize = findViewById(R.id.tvUpdateSize);
        findViewById(R.id.updateCancelBtn).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                dismiss();
                if (UpdateProgressDialog.this.listener != null) {
                    UpdateProgressDialog.this.listener.onCancel();
                }
            }
        });
    }

    /** 主线程更新进度；total 未知时只显示已下载大小。 */
    public void updateProgress(int percent, long done, long total) {
        if (progressBar == null) {
            return;
        }
        if (total > 0) {
            progressBar.setIndeterminate(false);
            progressBar.setProgress(percent);
        } else {
            progressBar.setIndeterminate(done <= 0);
        }
        tvPercent.setText(String.format(Locale.CHINA, "%d%%", percent));
        tvSize.setText(total > 0
                ? formatSize(done) + " / " + formatSize(total)
                : formatSize(done));
    }

    private static String formatSize(long bytes) {
        if (bytes >= 1024L * 1024L) {
            return String.format(Locale.CHINA, "%.1fMB", bytes / 1024f / 1024f);
        }
        if (bytes >= 1024L) {
            return String.format(Locale.CHINA, "%.0fKB", bytes / 1024f);
        }
        return bytes + "B";
    }
}
