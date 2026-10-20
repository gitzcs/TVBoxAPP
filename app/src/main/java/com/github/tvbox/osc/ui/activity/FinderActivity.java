package com.github.tvbox.osc.ui.activity;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import com.chad.library.adapter.base.BaseQuickAdapter;
import com.chad.library.adapter.base.BaseViewHolder;
import com.github.tvbox.osc.R;
import com.github.tvbox.osc.base.BaseActivity;
import com.github.tvbox.osc.bean.ConfigBean;
import com.github.tvbox.osc.event.RefreshEvent;
import com.github.tvbox.osc.finder.AutoSourceManager;
import com.github.tvbox.osc.finder.Finding;
import com.github.tvbox.osc.ui.dialog.TipDialog;
import com.github.tvbox.osc.util.ConfigManager;
import com.github.tvbox.osc.util.LOG;
import com.owen.tvrecyclerview.widget.TvRecyclerView;
import com.owen.tvrecyclerview.widget.V7LinearLayoutManager;

import org.greenrobot.eventbus.EventBus;

import java.util.ArrayList;
import java.util.List;

/**
 * 视频源自动获取。
 *
 * <p>流程：内置索引 + GitHub 在线发现 → 逐个验证 → 验证通过的存本地缓存。
 * 页面本身不改变用户的当前配置，只有点「使用」才会写入配置列表。
 */
public class FinderActivity extends BaseActivity implements AutoSourceManager.Listener {

    private TvRecyclerView finderList;
    private TextView tvStatus;
    private TextView tvPolicy;
    private FinderAdapter adapter;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private String lastSig = "";

    @Override
    protected int getLayoutResID() {
        return R.layout.activity_finder;
    }

    @Override
    protected void init() {
        finderList = findViewById(R.id.finderList);
        tvStatus = findViewById(R.id.tvStatus);
        tvPolicy = findViewById(R.id.tvPolicy);
        finderList.setLayoutManager(new V7LinearLayoutManager(this, 1, false));
        adapter = new FinderAdapter();
        finderList.setAdapter(adapter);

        tvPolicy.setText(AutoSourceManager.describePolicy());

        findViewById(R.id.btnRefresh).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                AutoSourceManager.get(FinderActivity.this).refreshAsync(true);
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        AutoSourceManager manager = AutoSourceManager.get(this);
        manager.addListener(this);
        // 先展示本地缓存（零网络），再由后台任务刷新
        refreshList();
        manager.start();
    }

    @Override
    protected void onPause() {
        super.onPause();
        AutoSourceManager.get(this).removeListener(this);
    }

    @Override
    public void onState(AutoSourceManager.State state) {
        tvStatus.setText(state.describe());
        refreshList();
    }

    /** 刷新列表：内容有变化才重建，避免频繁刷新导致黑闪。 */
    private void refreshList() {
        List<Finding> all = AutoSourceManager.get(this).allCached();
        StringBuilder sb = new StringBuilder();
        for (Finding f : all) {
            sb.append(f.getUrl()).append(':').append(f.isUsable()).append(';');
        }
        String sig = sb.toString();
        if (sig.equals(lastSig)) {
            return;
        }
        lastSig = sig;
        adapter.setNewData(all);
    }

    // ---------------- 导入 ----------------

    /**
     * 「使用」这个源：写入配置列表。
     *
     * <p>含爬虫站（type 3）的配置会先弹窗明示风险——导入它等于让 App
     * 下载并运行远程代码。
     */
    private void useFinding(final Finding finding) {
        ConfigBean bean = new ConfigBean(finding.getName(), finding.getUrl());
        ConfigManager.addConfig(bean);
        Toast.makeText(this, "已添加配置: " + bean.displayName(), Toast.LENGTH_SHORT).show();
        // 顺带设为当前生效配置并刷新首页
        ConfigManager.setHomeConfigId(bean.id);
        EventBus.getDefault().post(new RefreshEvent(RefreshEvent.TYPE_HOME_SOURCE_CHANGE));
    }

    private void confirmUse(final Finding finding) {
        // 安全闸: 配置里含爬虫站(type 3)时, 导入意味着 App 会真的下载并运行那些 jar
        // (远程代码执行)。必须明示风险, 让用户自己决定。见 docs/实现方案.md §4.5。
        String tip = "把「" + finding.getName() + "」设为当前配置？";
        int spiders = finding.getSpiderSiteCount();
        if (spiders > 0) {
            tip = tip + "\n\n⚠ 该配置包含 " + spiders + " 个爬虫站，"
                    + "使用后会从远程下载并执行其代码。\n仅在信任该配置来源时继续。";
        }
        TipDialog dialog = new TipDialog(this,
                tip,
                "确定", "取消", new TipDialog.OnListener() {
                    @Override
                    public void left() {
                        useFinding(finding);
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

    // ---------------- 适配器 ----------------

    private class FinderAdapter extends BaseQuickAdapter<Finding, BaseViewHolder> {

        FinderAdapter() {
            super(R.layout.item_finder, new ArrayList<Finding>());
        }

        @Override
        protected void convert(BaseViewHolder helper, final Finding item) {
            helper.setText(R.id.tvFinderName, item.getName());
            helper.setText(R.id.tvFinderUrl, item.getUrl());

            TextView tvState = helper.getView(R.id.tvFinderState);
            TextView btnUse = helper.getView(R.id.btnFinderUse);

            if (item.isUsable()) {
                StringBuilder sb = new StringBuilder();
                sb.append(item.getSiteCount()).append(" 个采集站");
                if (item.getVerifiedSites() > 0) {
                    sb.append("，验证通过 ").append(item.getVerifiedSites()).append(" 个");
                }
                if (item.getCategoryCount() > 0) {
                    sb.append("，分类 ").append(item.getCategoryCount()).append(" 个");
                }
                sb.append(item.isPlayable() ? "，可播放" : "，未取到播放地址");
                if (item.getSpiderSiteCount() > 0) {
                    sb.append("，含 ").append(item.getSpiderSiteCount()).append(" 个爬虫站");
                }
                helper.setText(R.id.tvFinderInfo, sb.toString());
                tvState.setText("可用");
                tvState.setTextColor(getResources().getColor(R.color.color_00FF0A));
                btnUse.setVisibility(View.VISIBLE);
                btnUse.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        confirmUse(item);
                    }
                });
            } else {
                helper.setText(R.id.tvFinderInfo, item.getMessage() == null ? "" : item.getMessage());
                tvState.setText("不可用");
                tvState.setTextColor(getResources().getColor(R.color.color_FFB800));
                // 不可用的不给「使用」按钮
                btnUse.setVisibility(View.GONE);
                tvState.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        // 点一下重新验证这个地址
                        AutoSourceManager.get(FinderActivity.this)
                                .verifyAndSaveAsync(item.getUrl(), item.getName(),
                                        new AutoSourceManager.VerifyCallback() {
                                            @Override
                                            public void onDone(Finding finding, boolean saved) {
                                                Toast.makeText(FinderActivity.this,
                                                        finding.isUsable() ? "验证通过" : "仍然不可用",
                                                        Toast.LENGTH_SHORT).show();
                                                refreshList();
                                            }
                                        });
                    }
                });
            }
        }
    }
}
