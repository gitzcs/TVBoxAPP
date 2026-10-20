package com.github.tvbox.osc.ui.activity;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import com.chad.library.adapter.base.BaseQuickAdapter;
import com.chad.library.adapter.base.BaseViewHolder;
import com.github.tvbox.osc.R;
import com.github.tvbox.osc.base.BaseActivity;
import com.github.tvbox.osc.bean.CacheTask;
import com.github.tvbox.osc.download.DownloadManager;
import com.github.tvbox.osc.util.ImgUtil;
import com.owen.tvrecyclerview.widget.TvRecyclerView;
import com.owen.tvrecyclerview.widget.V7LinearLayoutManager;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;

/**
 * 离线缓存管理：显示下载任务列表（状态 / 进度 / 播放 / 删除）。
 *
 * <p>分两层：先按视频聚合（分组视图），点进去看该视频的各集任务。
 */
public class CacheActivity extends BaseActivity {
    private TvRecyclerView cacheList;
    private TextView tvEmpty;
    private TextView tvCacheSize;
    private TextView btnBackGroup;
    private CacheAdapter adapter;
    private GroupAdapter groupAdapter;
    // 分组模式: 显示视频列表(缩略图+名称); 点击进入该视频的集数任务视图
    private boolean groupMode = true;
    private String currentGroup = null;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private String lastListSig = "";
    private final Runnable refreshRunnable = new Runnable() {
        @Override
        public void run() {
            if (adapter != null) {
                // 内容变化才刷新(避免频繁重建导致列表黑闪)
                String sig = buildSig();
                if (!sig.equals(lastListSig)) {
                    lastListSig = sig;
                    if (groupMode) {
                        groupAdapter.setNewData(buildGroups());
                    } else {
                        adapter.setNewData(getGroupTasks());
                    }
                    updateEmpty();
                }
            }
            handler.postDelayed(this, 1000);
        }
    };

    /**
     * 列表内容签名: 任务数+状态+进度首字节, 用于增量刷新。
     */
    private String buildSig() {
        StringBuilder sb = new StringBuilder();
        for (CacheTask t : DownloadManager.getInstance().getTasks()) {
            sb.append(t.state).append(':').append(t.progress).append(';');
        }
        return sb.toString();
    }

    /**
     * 视频分组: 名称(去集数后缀) -> 统计。
     */
    private List<GroupInfo> buildGroups() {
        LinkedHashMap<String, GroupInfo> map = new LinkedHashMap<>();
        for (CacheTask t : DownloadManager.getInstance().getTasks()) {
            String key = groupKey(t);
            GroupInfo g = map.get(key);
            if (g == null) {
                g = new GroupInfo();
                g.name = key;
                g.pic = t.pic;
                map.put(key, g);
            }
            if (g.pic == null && t.pic != null) g.pic = t.pic;
            g.total++;
            if (t.state == CacheTask.STATE_DONE) g.done++;
            else if (t.state == CacheTask.STATE_FAILED) g.failed++;
            else if (t.state == CacheTask.STATE_PAUSED) g.paused++;
            else g.downloading++;
            if (t.filePath != null) {
                try {
                    File f = new File(t.filePath);
                    if (f.exists()) g.size += f.length();
                } catch (Throwable ignored) {
                }
            }
        }
        return new ArrayList<>(map.values());
    }

    /**
     * 任务名去掉集数后缀，得到所属视频名。
     */
    private static String groupKey(CacheTask t) {
        String key = t.name == null ? "视频" : t.name;
        int idx = key.lastIndexOf("第");
        if (idx > 0) key = key.substring(0, idx).trim();
        if (key.isEmpty()) key = "视频";
        return key;
    }

    private List<CacheTask> getGroupTasks() {
        List<CacheTask> all = DownloadManager.getInstance().getTasks();
        if (currentGroup == null) return all;
        List<CacheTask> out = new ArrayList<>();
        for (CacheTask t : all) {
            if (groupKey(t).equals(currentGroup)) out.add(t);
        }
        return out;
    }

    static class GroupInfo {
        String name;
        String pic;
        int total, done, failed, downloading, paused;
        long size;
    }

    @Override
    protected int getLayoutResID() {
        return R.layout.activity_cache;
    }

    @Override
    protected void init() {
        cacheList = findViewById(R.id.cacheList);
        tvEmpty = findViewById(R.id.tvEmpty);
        tvCacheSize = findViewById(R.id.tvCacheSize);
        btnBackGroup = findViewById(R.id.btnBackGroup);
        cacheList.setLayoutManager(new V7LinearLayoutManager(this, 1, false));
        adapter = new CacheAdapter(new ArrayList<>());
        groupAdapter = new GroupAdapter(new ArrayList<>());
        cacheList.setAdapter(groupAdapter);
        // 返回分组列表
        btnBackGroup.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                currentGroup = null;
                groupMode = true;
                btnBackGroup.setVisibility(View.GONE);
                cacheList.setAdapter(groupAdapter);
                groupAdapter.setNewData(buildGroups());
                updateEmpty();
            }
        });
        updateEmpty();
        handler.post(refreshRunnable);
        // 清空全部: 删除所有任务+文件
        findViewById(R.id.btnClearAll).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                long size = DownloadManager.getInstance().getTotalSize();
                new androidx.appcompat.app.AlertDialog.Builder(CacheActivity.this)
                        .setTitle("清空全部缓存")
                        .setMessage("将删除全部缓存任务及文件(当前占用 " + formatSize(size) + ")，确定继续？")
                        .setPositiveButton("清空", (d, w) -> {
                            DownloadManager.getInstance().clearAll();
                            groupMode = true;
                            currentGroup = null;
                            btnBackGroup.setVisibility(View.GONE);
                            cacheList.setAdapter(groupAdapter);
                            adapter.setNewData(new ArrayList<>());
                            groupAdapter.setNewData(new ArrayList<>());
                            updateEmpty();
                            Toast.makeText(CacheActivity.this, "已清空全部缓存", Toast.LENGTH_SHORT).show();
                        })
                        .setNegativeButton("取消", null)
                        .show();
            }
        });
    }

    private void updateEmpty() {
        List<CacheTask> tasks = DownloadManager.getInstance().getTasks();
        boolean empty = tasks == null || tasks.isEmpty();
        tvEmpty.setVisibility(empty ? View.VISIBLE : View.GONE);
        cacheList.setVisibility(empty ? View.GONE : View.VISIBLE);
        if (tvCacheSize != null) {
            long total = DownloadManager.getInstance().getTotalSize();
            int n = groupMode ? buildGroups().size() : getGroupTasks().size();
            tvCacheSize.setText("已用空间 " + formatSize(total) + " · " + n + (groupMode ? " 个视频" : " 个任务"));
        }
    }

    class GroupAdapter extends BaseQuickAdapter<GroupInfo, BaseViewHolder> {
        GroupAdapter(List<GroupInfo> data) {
            super(R.layout.item_cache_group, data);
        }

        @Override
        protected void convert(BaseViewHolder helper, GroupInfo item) {
            if (item == null) return;
            helper.setText(R.id.tvGroupName, item.name);
            String info = item.total + " 集 · 已完成 " + item.done;
            if (item.downloading > 0) info += " · 下载中 " + item.downloading;
            if (item.paused > 0) info += " · 已暂停 " + item.paused;
            if (item.failed > 0) info += " · 失败 " + item.failed;
            info += " · " + formatSize(item.size);
            helper.setText(R.id.tvGroupInfo, info);
            TextView state = helper.getView(R.id.tvGroupState);
            if (item.downloading > 0) {
                state.setText("下载中");
                state.setTextColor(android.graphics.Color.parseColor("#80D8FF"));
            } else if (item.paused > 0) {
                state.setText("已暂停");
                state.setTextColor(android.graphics.Color.parseColor("#FFB800"));
            } else if (item.failed > 0) {
                state.setText("有失败");
                state.setTextColor(android.graphics.Color.parseColor("#FF6E6E"));
            } else {
                state.setText("已完成");
                state.setTextColor(android.graphics.Color.parseColor("#FFB800"));
            }
            ImageView ivPic = helper.getView(R.id.ivGroupPic);
            if (item.pic != null && !item.pic.isEmpty()) {
                ImgUtil.load(item.pic, ivPic, 10);
            } else {
                ivPic.setImageResource(R.drawable.img_loading_placeholder);
            }
            helper.itemView.setOnClickListener(v -> {
                currentGroup = item.name;
                groupMode = false;
                btnBackGroup.setVisibility(View.VISIBLE);
                adapter.setNewData(getGroupTasks());
                cacheList.setAdapter(adapter);
                updateEmpty();
            });
        }
    }

    class CacheAdapter extends BaseQuickAdapter<CacheTask, BaseViewHolder> {

        CacheAdapter(List<CacheTask> data) {
            super(R.layout.item_cache_task, data);
        }

        @Override
        protected void convert(BaseViewHolder helper, CacheTask item) {
            if (item == null) return;
            helper.setText(R.id.tvTaskName, item.name == null ? "视频" : item.name);
            TextView tvStatus = helper.getView(R.id.tvTaskStatus);
            ProgressBar pb = helper.getView(R.id.taskProgress);
            switch (item.state) {
                case CacheTask.STATE_DOWNLOADING:
                    tvStatus.setText("下载中 " + formatSize(item.progress) + " / " + formatSize(item.total));
                    pb.setVisibility(View.VISIBLE);
                    pb.setProgress(item.total > 0 ? (int) (item.progress * 100 / item.total) : 0);
                    break;
                case CacheTask.STATE_DONE:
                    if (item.errorMsg != null && !item.errorMsg.isEmpty()) {
                        tvStatus.setText("已完成 " + formatSize(item.progress) + "（" + item.errorMsg + "）");
                    } else {
                        tvStatus.setText("已完成 " + formatSize(item.progress));
                    }
                    pb.setVisibility(View.VISIBLE);
                    pb.setProgress(100);
                    break;
                case CacheTask.STATE_FAILED:
                    tvStatus.setText("下载失败: " + (item.errorMsg != null && !item.errorMsg.isEmpty() ? item.errorMsg : "未知原因"));
                    pb.setVisibility(View.VISIBLE);
                    pb.setProgress(0);
                    break;
                case CacheTask.STATE_PAUSED:
                    tvStatus.setText("已暂停");
                    pb.setVisibility(View.VISIBLE);
                    pb.setProgress(0);
                    break;
                default:
                    tvStatus.setText("排队中");
                    pb.setVisibility(View.VISIBLE);
                    pb.setProgress(0);
                    break;
            }
            // 暂停/继续(下载中/排队/已暂停状态显示)
            TextView btnPause = helper.getView(R.id.btnTaskPause);
            boolean showPause = item.state == CacheTask.STATE_DOWNLOADING || item.state == CacheTask.STATE_WAIT
                    || item.state == CacheTask.STATE_PAUSED;
            btnPause.setVisibility(showPause ? View.VISIBLE : View.GONE);
            btnPause.setText(item.state == CacheTask.STATE_PAUSED ? "继续" : "暂停");
            btnPause.setOnClickListener(v -> {
                if (item.state == CacheTask.STATE_PAUSED) {
                    DownloadManager.getInstance().resumeTask(CacheActivity.this, item);
                    Toast.makeText(CacheActivity.this, "已继续下载", Toast.LENGTH_SHORT).show();
                } else {
                    DownloadManager.getInstance().pauseTask(item);
                    Toast.makeText(CacheActivity.this, "已暂停下载", Toast.LENGTH_SHORT).show();
                }
                lastListSig = "";
            });
            // 重试(仅失败状态显示)
            View btnRetry = helper.getView(R.id.btnTaskRetry);
            btnRetry.setVisibility(item.state == CacheTask.STATE_FAILED ? View.VISIBLE : View.GONE);
            btnRetry.setOnClickListener(v -> {
                DownloadManager.getInstance().retryTask(CacheActivity.this, item);
                Toast.makeText(CacheActivity.this, "已重新入队", Toast.LENGTH_SHORT).show();
                lastListSig = "";
            });
            // 播放: 用内置播放器播本地文件, 失败再回退系统播放器
            helper.getView(R.id.btnTaskPlay).setOnClickListener(v -> {
                File f = new File(item.filePath);
                if (item.state == CacheTask.STATE_DONE && f.exists()) {
                    try {
                        Intent intent = new Intent(CacheActivity.this, LocalPlayActivity.class);
                        // 用原始路径(file:// + 绝对路径), 不经过 Uri.fromFile 的百分号编码:
                        // ffmpeg/IJK 的 file 协议不做 percent-decode, 编码 URL 会找不到文件
                        intent.putExtra("url", "file://" + f.getAbsolutePath());
                        intent.putExtra("title", item.name);
                        startActivity(intent);
                    } catch (Throwable th) {
                        th.printStackTrace();
                        Toast.makeText(CacheActivity.this, "内部播放失败, 尝试系统播放器", Toast.LENGTH_SHORT).show();
                        try {
                            Intent sysIntent = new Intent(Intent.ACTION_VIEW);
                            sysIntent.setDataAndType(Uri.fromFile(f), "video/mp2t");
                            sysIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                            startActivity(sysIntent);
                        } catch (Throwable th2) {
                            Toast.makeText(CacheActivity.this, "无法播放, 请检查文件", Toast.LENGTH_SHORT).show();
                        }
                    }
                } else {
                    Toast.makeText(CacheActivity.this, "下载完成后方可播放", Toast.LENGTH_SHORT).show();
                }
            });
            // 删除
            helper.getView(R.id.btnTaskDel).setOnClickListener(v -> {
                DownloadManager.getInstance().removeTask(CacheActivity.this, item.id);
                adapter.setNewData(getGroupTasks());
                updateEmpty();
            });
        }
    }

    private String formatSize(long bytes) {
        if (bytes <= 0) return "未知";
        double kb = bytes / 1024.0;
        if (kb < 1024) return String.format(Locale.getDefault(), "%.1f KB", kb);
        double mb = kb / 1024.0;
        if (mb < 1024) return String.format(Locale.getDefault(), "%.1f MB", mb);
        double gb = mb / 1024.0;
        return String.format(Locale.getDefault(), "%.2f GB", gb);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        handler.removeCallbacksAndMessages(null);
    }
}