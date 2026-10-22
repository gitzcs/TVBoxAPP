package com.github.tvbox.osc.ui.dialog;

import android.content.Context;
import android.view.View;
import android.widget.TextView;

import androidx.annotation.NonNull;

import com.github.tvbox.osc.R;
import com.github.tvbox.osc.bean.ConfigBean;

/** 配置列表的遥控器操作菜单。 */
public class ConfigActionDialog extends BaseDialog {

    public interface OnActionListener {
        void onSetHome();

        void onEdit();

        void onToggleEnabled();

        void onDelete();
    }

    private final View firstAction;

    public ConfigActionDialog(@NonNull Context context, ConfigBean item,
                              final OnActionListener listener) {
        super(context);
        setContentView(R.layout.dialog_config_action);
        setCanceledOnTouchOutside(true);

        TextView toggleEnabled = findViewById(R.id.btnActionToggleEnabled);
        toggleEnabled.setText(item.enabled ? "停用配置" : "启用配置");

        firstAction = findViewById(R.id.btnActionSetHome);
        bindAction(firstAction, new Runnable() {
            @Override
            public void run() {
                listener.onSetHome();
            }
        });
        bindAction(findViewById(R.id.btnActionEdit), new Runnable() {
            @Override
            public void run() {
                listener.onEdit();
            }
        });
        bindAction(toggleEnabled, new Runnable() {
            @Override
            public void run() {
                listener.onToggleEnabled();
            }
        });
        bindAction(findViewById(R.id.btnActionDelete), new Runnable() {
            @Override
            public void run() {
                listener.onDelete();
            }
        });
        bindAction(findViewById(R.id.btnActionCancel), new Runnable() {
            @Override
            public void run() {
            }
        });
    }

    private void bindAction(View view, final Runnable action) {
        view.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                dismiss();
                action.run();
            }
        });
    }

    @Override
    protected void onStart() {
        super.onStart();
        firstAction.requestFocus();
    }
}
