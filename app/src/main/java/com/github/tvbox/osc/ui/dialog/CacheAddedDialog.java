package com.github.tvbox.osc.ui.dialog;

import android.content.Context;
import android.view.View;
import android.widget.TextView;

import androidx.annotation.NonNull;

import com.github.tvbox.osc.R;

/**
 * 缓存添加成功弹窗：跳转缓存页 / 知道了。
 */
public class CacheAddedDialog extends BaseDialog {

    public interface OnListener {
        void onJump();
    }

    public CacheAddedDialog(@NonNull Context context, String tip, OnListener listener) {
        super(context, R.style.CustomDialogStyleDim);
        setContentView(R.layout.dialog_cache_added);
        setCanceledOnTouchOutside(false);
        if (tip != null && !tip.isEmpty()) {
            ((TextView) findViewById(R.id.tipInfo)).setText(tip);
        }
        // 跳转缓存页
        findViewById(R.id.btnJumpCache).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                dismiss();
                if (listener != null) listener.onJump();
            }
        });
        // 知道了: 关闭
        findViewById(R.id.btnKnow).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                dismiss();
            }
        });
    }
}