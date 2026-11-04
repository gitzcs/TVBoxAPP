package com.github.tvbox.osc.ui.activity;

import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import com.chad.library.adapter.base.BaseQuickAdapter;
import com.chad.library.adapter.base.BaseViewHolder;
import com.github.tvbox.osc.R;
import com.github.tvbox.osc.base.BaseActivity;
import com.github.tvbox.osc.finder.AutoSourceManager;
import com.github.tvbox.osc.finder.IndexSiteStore;
import com.github.tvbox.osc.finder.SourceIndex;
import com.github.tvbox.osc.ui.dialog.IndexSiteActionDialog;
import com.github.tvbox.osc.ui.dialog.IndexSiteEditDialog;
import com.github.tvbox.osc.ui.dialog.TipDialog;
import com.owen.tvrecyclerview.widget.TvRecyclerView;
import com.owen.tvrecyclerview.widget.V7LinearLayoutManager;

import java.util.ArrayList;
import java.util.List;

/** 搜索索引站管理：新增 / 编辑 / 删除 / 启停 / 恢复默认。 */
public class IndexSiteManageActivity extends BaseActivity {

    private static final int MAX_INDEX_SITES = 8;

    private final Handler refreshHandler = new Handler(Looper.getMainLooper());
    private final Runnable refreshRunnable = new Runnable() {
        @Override
        public void run() {
            startRefresh(false);
        }
    };

    private TvRecyclerView siteList;
    private IndexSiteAdapter adapter;
    private IndexSiteStore store;
    private List<SourceIndex.IndexSite> sites;
    private boolean refreshPending;

    @Override
    protected int getLayoutResID() {
        return R.layout.activity_index_site_manage;
    }

    @Override
    protected void init() {
        store = new IndexSiteStore(this);
        sites = store.load(SourceIndex.fromAssets(this).indexSites());

        siteList = findViewById(R.id.indexSiteList);
        siteList.setLayoutManager(new V7LinearLayoutManager(this, 1, false));
        adapter = new IndexSiteAdapter(sites);
        siteList.setAdapter(adapter);
        siteList.setOnItemListener(new TvRecyclerView.OnItemListener() {
            @Override
            public void onItemPreSelected(TvRecyclerView parent, View itemView, int position) {
            }

            @Override
            public void onItemSelected(TvRecyclerView parent, View itemView, int position) {
            }

            @Override
            public void onItemClick(TvRecyclerView parent, View itemView, int position) {
                SourceIndex.IndexSite site = adapter.getItem(position);
                if (site != null) {
                    showActions(site);
                }
            }
        });
        postInitialFocus();

        findViewById(R.id.btnAddIndexSite).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showEditDialog(null);
            }
        });
        findViewById(R.id.btnResetIndexSites).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                confirmReset();
            }
        });
        findViewById(R.id.btnRefreshIndexSites).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startRefresh(true);
            }
        });
    }

    private void postInitialFocus() {
        siteList.post(new Runnable() {
            @Override
            public void run() {
                if (adapter.getItemCount() > 0) {
                    if (siteList.getSelectedPosition() < 0) {
                        siteList.setSelectedPosition(0);
                    }
                    siteList.requestDefaultFocus();
                } else {
                    findViewById(R.id.btnAddIndexSite).requestFocus();
                }
            }
        });
    }

    private void showEditDialog(final SourceIndex.IndexSite editSite) {
        if (editSite == null && sites.size() >= MAX_INDEX_SITES) {
            Toast.makeText(this, "最多配置 " + MAX_INDEX_SITES + " 个索引站",
                    Toast.LENGTH_SHORT).show();
            return;
        }
        new IndexSiteEditDialog(this, editSite, new IndexSiteEditDialog.OnSaveListener() {
            @Override
            public void onSave(SourceIndex.IndexSite site) {
                if (editSite == null) {
                    sites.add(site);
                } else {
                    int index = indexOf(editSite);
                    if (index >= 0) {
                        sites.set(index, site);
                    } else {
                        sites.add(site);
                    }
                }
                persistAndRefresh("索引站已保存");
            }
        }).show();
    }

    private void showActions(final SourceIndex.IndexSite site) {
        new IndexSiteActionDialog(this, site, new IndexSiteActionDialog.OnActionListener() {
            @Override
            public void onEdit() {
                showEditDialog(site);
            }

            @Override
            public void onToggleEnabled() {
                site.setEnabled(!site.isEnabled());
                persistAndRefresh(site.isEnabled() ? "索引站已启用" : "索引站已停用");
            }

            @Override
            public void onDelete() {
                confirmDelete(site);
            }
        }).show();
    }

    private void confirmDelete(final SourceIndex.IndexSite site) {
        new TipDialog(this, "确定删除索引站: " + site.getName() + " ?",
                "删除", "取消", new TipDialog.OnListener() {
            @Override
            public void left() {
                sites.remove(site);
                persistAndRefresh("索引站已删除");
            }

            @Override
            public void right() {
            }

            @Override
            public void cancel() {
            }
        }).show();
    }

    private void confirmReset() {
        new TipDialog(this, "恢复默认会丢弃所有自定义索引站，是否继续？",
                "恢复", "取消", new TipDialog.OnListener() {
            @Override
            public void left() {
                store.reset();
                sites.clear();
                sites.addAll(SourceIndex.fromAssets(IndexSiteManageActivity.this).indexSites());
                adapter.setNewData(sites);
                queueRefresh();
                Toast.makeText(IndexSiteManageActivity.this,
                        "已恢复默认索引站", Toast.LENGTH_SHORT).show();
            }

            @Override
            public void right() {
            }

            @Override
            public void cancel() {
            }
        }).show();
    }

    private void persistAndRefresh(String message) {
        store.save(sites);
        adapter.setNewData(sites);
        queueRefresh();
        Toast.makeText(this, message + "，自动找源已重新排期", Toast.LENGTH_SHORT).show();
    }

    private int indexOf(SourceIndex.IndexSite target) {
        for (int i = 0; i < sites.size(); i++) {
            if (sites.get(i) == target) {
                return i;
            }
        }
        return -1;
    }

    private void queueRefresh() {
        refreshPending = true;
        refreshHandler.removeCallbacks(refreshRunnable);
        refreshHandler.postDelayed(refreshRunnable, 1000L);
    }

    private void startRefresh(boolean toast) {
        refreshPending = false;
        refreshHandler.removeCallbacks(refreshRunnable);
        AutoSourceManager manager = AutoSourceManager.get(this);
        manager.refreshAsync(manager.cache().includeGithub());
        if (toast) {
            Toast.makeText(this, "已按当前索引站配置开始找源", Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    protected void onStop() {
        if (refreshPending) {
            startRefresh(false);
        }
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        refreshHandler.removeCallbacks(refreshRunnable);
        super.onDestroy();
    }

    private class IndexSiteAdapter extends BaseQuickAdapter<SourceIndex.IndexSite, BaseViewHolder> {

        IndexSiteAdapter(List<SourceIndex.IndexSite> data) {
            super(R.layout.item_index_site, data == null ? new ArrayList<SourceIndex.IndexSite>() : data);
        }

        @Override
        protected void convert(BaseViewHolder helper, final SourceIndex.IndexSite item) {
            TextView enabled = helper.getView(R.id.tvIndexSiteEnabled);
            enabled.setText(item.isEnabled() ? "✓" : "✗");
            enabled.setTextColor(getResources().getColor(
                    item.isEnabled() ? R.color.color_FFFFFF : R.color.color_FFB800));

            helper.setText(R.id.tvIndexSiteName, item.getName());
            helper.setText(R.id.tvIndexSiteUrl, item.getBaseUrl());
            helper.setText(R.id.tvIndexSiteInfo,
                    item.getQueries().size() + " 个关键词 · " + item.getSort() + " 排序");

            helper.getView(R.id.btnIndexSiteEdit).setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    showEditDialog(item);
                }
            });
            helper.getView(R.id.btnIndexSiteDelete).setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    confirmDelete(item);
                }
            });
        }
    }
}
