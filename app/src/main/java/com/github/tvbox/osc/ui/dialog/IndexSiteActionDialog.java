package com.github.tvbox.osc.ui.dialog;

import android.content.Context;
import android.view.View;
import android.widget.TextView;

import androidx.annotation.NonNull;

import com.github.tvbox.osc.R;
import com.github.tvbox.osc.finder.SourceIndex;

/** 搜索索引站列表的遥控器操作菜单。 */
public class IndexSiteActionDialog extends BaseDialog {

    public interface OnActionListener {
        void onEdit();

        void onToggleEnabled();

        void onDelete();
    }

    private final View firstAction;

    public IndexSiteActionDialog(@NonNull Context context, SourceIndex.IndexSite site,
                                 final OnActionListener listener) {
        super(context);
        setContentView(R.layout.dialog_config_action);
        setCanceledOnTouchOutside(true);

        TextView title = findViewById(R.id.tvActionTitle);
        title.setText("索引站操作");
        findViewById(R.id.btnActionSetHome).setVisibility(View.GONE);

        TextView toggleEnabled = findViewById(R.id.btnActionToggleEnabled);
        toggleEnabled.setText(site.isEnabled() ? "停用站点" : "启用站点");

        firstAction = findViewById(R.id.btnActionEdit);
        bindAction(firstAction, new Runnable() {
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
