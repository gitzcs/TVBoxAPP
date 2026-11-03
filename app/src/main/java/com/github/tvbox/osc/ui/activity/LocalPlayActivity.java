package com.github.tvbox.osc.ui.activity;

import android.os.Bundle;
import android.text.TextUtils;
import android.view.KeyEvent;
import android.view.View;
import android.widget.TextView;

import com.github.tvbox.osc.R;
import com.github.tvbox.osc.base.BaseActivity;
import com.github.tvbox.osc.util.LOG;
import com.github.tvbox.osc.util.PlayerHelper;

import xyz.doikki.videoplayer.player.VideoView;

/**
 * 本地缓存文件播放。
 *
 * <p>A 原本没有独立的播放 Activity（点播走 DetailActivity 内的 PlayFragment，
 * 直播走 LivePlayActivity），这里为「播放已下载的本地文件」单独开一个轻量页，
 * 复用 A 的 {@code PlayerHelper} 播放器配置与 {@code VideoView}。
 */
public class LocalPlayActivity extends BaseActivity {

    private VideoView mVideoView;
    private TextView tvTitle;
    private TextView tvError;
    private String playUrl;

    @Override
    protected int getLayoutResID() {
        return R.layout.activity_local_play;
    }

    @Override
    protected void init() {
        mVideoView = findViewById(R.id.mVideoView);
        tvTitle = findViewById(R.id.tvTitle);
        tvError = findViewById(R.id.tvError);

        String title = getIntent() != null ? getIntent().getStringExtra("title") : null;
        playUrl = getIntent() != null ? getIntent().getStringExtra("url") : null;
        if (!TextUtils.isEmpty(title)) tvTitle.setText(title);

        if (TextUtils.isEmpty(playUrl)) {
            showError("播放地址为空");
            return;
        }
        // 复用全局播放器配置(EXO/IJK、渲染方式、缩放)
        PlayerHelper.updateCfg(mVideoView);
        mVideoView.setOnStateChangeListener(new VideoView.SimpleOnStateChangeListener() {
            @Override
            public void onPlayStateChanged(int playState) {
                if (playState == VideoView.STATE_ERROR) {
                    showError("播放失败，文件可能已损坏");
                }
            }
        });
        mVideoView.setUrl(playUrl);
        mVideoView.start();
    }

    private void showError(String msg) {
        tvError.setText(msg);
        tvError.setVisibility(View.VISIBLE);
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            if (mVideoView != null && mVideoView.isPlaying()) {
                mVideoView.pause();
                return true;
            }
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (mVideoView != null) mVideoView.pause();
    }

    @Override
    protected void onDestroy() {
        if (mVideoView != null) {
            mVideoView.release();
            mVideoView = null;
        }
        super.onDestroy();
    }
}