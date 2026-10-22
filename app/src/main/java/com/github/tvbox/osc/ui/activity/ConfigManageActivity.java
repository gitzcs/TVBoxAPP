package com.github.tvbox.osc.ui.activity;

import android.os.Bundle;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;

import com.chad.library.adapter.base.BaseQuickAdapter;
import com.chad.library.adapter.base.BaseViewHolder;
import com.github.tvbox.osc.R;
import com.github.tvbox.osc.base.BaseActivity;
import com.github.tvbox.osc.bean.ConfigBean;
import com.github.tvbox.osc.event.RefreshEvent;
import com.github.tvbox.osc.ui.dialog.ConfigActionDialog;
import com.github.tvbox.osc.ui.dialog.ConfigEditDialog;
import com.github.tvbox.osc.ui.dialog.TipDialog;
import com.github.tvbox.osc.util.ConfigManager;
import com.owen.tvrecyclerview.widget.TvRecyclerView;
import com.owen.tvrecyclerview.widget.V7LinearLayoutManager;

import org.greenrobot.eventbus.EventBus;

import java.util.List;

/**
 * 配置地址管理：新增 / 编辑 / 删除 / 启停 / 设主页源。
 *
 * <p>配置来源：随包内置的已验证采集配置、用户手动添加，或「视频源自动获取」页导入。
 */
public class ConfigManageActivity extends BaseActivity {

    private TvRecyclerView configList;
    private ConfigAdapter adapter;

    @Override
    protected int getLayoutResID() {
        return R.layout.activity_config_manage;
    }

    @Override
    protected void init() {
        configList = findViewById(R.id.configList);
        configList.setLayoutManager(new V7LinearLayoutManager(this, 1, false));
        adapter = new ConfigAdapter(ConfigManager.getConfigs());
        configList.setAdapter(adapter);
        configList.post(new Runnable() {
            @Override
            public void run() {
                if (adapter.getItemCount() > 0) {
                    if (configList.getSelectedPosition() < 0) {
                        configList.setSelectedPosition(0);
                    }
                    configList.requestDefaultFocus();
                }
            }
        });
        adapter.setOnItemClickListener(new BaseQuickAdapter.OnItemClickListener() {
            @Override
            public void onItemClick(BaseQuickAdapter adapter, View view, int position) {
                ConfigBean item = ConfigManageActivity.this.adapter.getItem(position);
                if (item != null) {
                    showConfigActions(item);
                }
            }
        });

        findViewById(R.id.btnAddConfig).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showEditDialog(null);
            }
        });

        findViewById(R.id.btnFinder).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                jumpActivity(FinderActivity.class);
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (adapter != null) {
            adapter.setNewData(ConfigManager.getConfigs());
        }
    }

    // ---------------- 操作 ----------------

    private void showEditDialog(ConfigBean editBean) {
        ConfigEditDialog dialog = new ConfigEditDialog(this, editBean, new ConfigEditDialog.OnSaveListener() {
            @Override
            public void onSave(ConfigBean bean) {
                if (editBean == null) {
                    ConfigManager.addConfig(bean);
                    Toast.makeText(ConfigManageActivity.this,
                            "已新增配置: " + bean.displayName(), Toast.LENGTH_SHORT).show();
                } else {
                    ConfigManager.updateConfig(bean);
                    Toast.makeText(ConfigManageActivity.this,
                            "已保存配置: " + bean.displayName(), Toast.LENGTH_SHORT).show();
                }
                refreshAdapter();
            }
        });
        dialog.show();
    }

    private void showConfigActions(final ConfigBean item) {
        new ConfigActionDialog(this, item, new ConfigActionDialog.OnActionListener() {
            @Override
            public void onSetHome() {
                setHomeConfig(item);
            }

            @Override
            public void onEdit() {
                showEditDialog(item);
            }

            @Override
            public void onToggleEnabled() {
                ConfigManager.toggleEnabled(item.id, !item.enabled);
                refreshAdapter();
                Toast.makeText(ConfigManageActivity.this,
                        item.enabled ? "配置已启用" : "配置已停用", Toast.LENGTH_SHORT).show();
            }

            @Override
            public void onDelete() {
                confirmDelete(item);
            }
        }).show();
    }

    /** 把该配置设为「主页配置」：主页显示该配置的数据，点击后立即刷新。 */
    private void setHomeConfig(ConfigBean item) {
        ConfigManager.setHomeConfigId(item.id);
        refreshAdapter();
        Toast.makeText(this, "主页已设为: " + item.displayName() + "，正在刷新",
                Toast.LENGTH_SHORT).show();
        EventBus.getDefault().post(new RefreshEvent(RefreshEvent.TYPE_HOME_SOURCE_CHANGE));
    }

    private void refreshAdapter() {
        if (adapter != null) {
            adapter.setNewData(ConfigManager.getConfigs());
        }
    }

    // ---------------- 适配器 ----------------

    private class ConfigAdapter extends BaseQuickAdapter<ConfigBean, BaseViewHolder> {

        ConfigAdapter(List<ConfigBean> data) {
            super(R.layout.item_config, data);
        }

        @Override
        protected void convert(BaseViewHolder helper, ConfigBean item) {
            TextView tvEnabled = helper.getView(R.id.tvEnabled);
            tvEnabled.setText(item.enabled ? "✓" : "✗");
            tvEnabled.setTextColor(getResources().getColor(
                    item.enabled ? R.color.color_FFFFFF : R.color.color_FFB800));

            boolean isHome = item.id != null && item.id.equals(ConfigManager.getHomeConfigId());
            helper.setText(R.id.tvConfigName,
                    isHome ? "★ " + item.displayName() + " (主页)" : item.displayName());
            helper.setText(R.id.tvConfigUrl, item.apiUrl == null ? "" : item.apiUrl);

            TextView btnHome = helper.getView(R.id.btnHomeConfig);
            if (isHome) {
                btnHome.setText("✓主页");
                btnHome.setTextColor(getResources().getColor(R.color.color_FFB800));
            } else {
                btnHome.setText("主页源");
                btnHome.setTextColor(getResources().getColor(android.R.color.white));
            }

            // 启用状态开关
            tvEnabled.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    ConfigManager.toggleEnabled(item.id, !item.enabled);
                    refreshAdapter();
                }
            });
            // 设为主页源
            helper.getView(R.id.btnHomeConfig).setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    setHomeConfig(item);
                }
            });
            // 编辑
            helper.getView(R.id.btnEditConfig).setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    showEditDialog(item);
                }
            });
            // 删除
            helper.getView(R.id.btnDelConfig).setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    confirmDelete(item);
                }
            });
        }
    }

    private void confirmDelete(final ConfigBean item) {
        TipDialog dialog = new TipDialog(this,
                "确定删除配置: " + item.displayName() + " ?",
                "删除", "取消", new TipDialog.OnListener() {
                    @Override
                    public void left() {
                        ConfigManager.deleteConfig(item.id);
                        refreshAdapter();
                        Toast.makeText(ConfigManageActivity.this,
                                "已删除配置", Toast.LENGTH_SHORT).show();
                    }

                    @Override
                    public void right() {
                    }

                    @Override
                    public void cancel() {
                    }
                });
        dialog.show();
    }
}
