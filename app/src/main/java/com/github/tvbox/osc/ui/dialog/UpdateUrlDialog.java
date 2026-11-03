package com.github.tvbox.osc.ui.dialog;

import android.content.Context;
import android.text.TextUtils;
import android.view.View;
import android.widget.EditText;
import android.widget.Toast;

import androidx.annotation.NonNull;

import com.github.tvbox.osc.R;

/**
 * 更新源地址输入框：保存一份 update.json 的 URL 到 Hawk
 * （实际写入由 {@link com.github.tvbox.osc.update.UpdateManager} 的回调完成）。
 */
public class UpdateUrlDialog extends BaseDialog {

    public interface OnSaveListener {
        void onSave(String url);
    }

    private final OnSaveListener listener;

    public UpdateUrlDialog(@NonNull Context context, String currentUrl, OnSaveListener listener) {
        super(context);
        this.listener = listener;
        setContentView(R.layout.dialog_update_url);
        setCanceledOnTouchOutside(false);
        final EditText input = findViewById(R.id.updateUrlInput);
        if (!TextUtils.isEmpty(currentUrl)) {
            input.setText(currentUrl);
        }
        findViewById(R.id.updateOkBtn).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                String url = input.getText() == null ? "" : input.getText().toString().trim();
                if (!TextUtils.isEmpty(url) && !url.startsWith("http")) {
                    Toast.makeText(getContext(), "地址需以 http(s):// 开头", Toast.LENGTH_SHORT).show();
                    return;
                }
                dismiss();
                if (UpdateUrlDialog.this.listener != null) {
                    UpdateUrlDialog.this.listener.onSave(url);
                }
            }
        });
        findViewById(R.id.updateCancelBtn).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                dismiss();
            }
        });
    }
}
