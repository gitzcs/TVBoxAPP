package com.github.tvbox.osc.player;

import android.content.Context;
import android.util.Pair;

import com.github.tvbox.osc.util.LOG;
import com.google.android.exoplayer2.Format;
import com.google.android.exoplayer2.SimpleExoPlayer;
import com.google.android.exoplayer2.source.TrackGroup;
import com.google.android.exoplayer2.source.TrackGroupArray;
import com.google.android.exoplayer2.trackselection.DefaultTrackSelector;
import com.google.android.exoplayer2.trackselection.MappingTrackSelector;

import xyz.doikki.videoplayer.exo.ExoMediaPlayer;

import com.google.android.exoplayer2.C;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class ExoPlayer extends ExoMediaPlayer {

    public ExoPlayer(Context context) {
        super(context);
    }
    // 3. 获取所有轨道信息
    public TrackInfo getTrackInfo() {
        TrackInfo data = new TrackInfo();
        MappingTrackSelector.MappedTrackInfo mappedInfo = getMappingTrackSelector().getCurrentMappedTrackInfo();
        if (mappedInfo == null) return data;
        DefaultTrackSelector.Parameters params = ((DefaultTrackSelector) getMappingTrackSelector()).getParameters();
        for (int rendererIndex = 0; rendererIndex < mappedInfo.getRendererCount(); rendererIndex++) {
            int type = mappedInfo.getRendererType(rendererIndex);
            TrackGroupArray groups = mappedInfo.getTrackGroups(rendererIndex);
            DefaultTrackSelector.SelectionOverride override = params.getSelectionOverride(rendererIndex, groups);
            boolean hasSelected = false;
            for (int groupIndex = 0; groupIndex < groups.length; groupIndex++) {
                TrackGroup group = groups.get(groupIndex);
                for (int trackIndex = 0; trackIndex < group.length; trackIndex++) {
                    Format fmt = group.getFormat(trackIndex);
                    TrackInfoBean bean = new TrackInfoBean();
                    bean.language   = getLanguage(fmt);
                    bean.name       = getName(fmt);
                    bean.groupIndex = groupIndex;
                    bean.index      = trackIndex;
                    boolean selected = false;
                    if (override != null) {
                        if(override.groupIndex == groupIndex){
                            for (int t : override.tracks) {
                                if (t == trackIndex) {
                                    selected = true;
                                    hasSelected = true;
                                    break;
                                }
                            }
                        }
                    }else if (type == C.TRACK_TYPE_AUDIO && !hasSelected) {
                        selected = true;
                        hasSelected = true;
                    }
                    bean.selected = selected;
                    if (type == C.TRACK_TYPE_AUDIO) {
                        data.addAudio(bean);
                    } else if (type == C.TRACK_TYPE_TEXT) {
                        data.addSubtitle(bean);
                    }
                }
            }
        }
        return data;
    }

    protected MappingTrackSelector getMappingTrackSelector() {
        if (mTrackSelector instanceof MappingTrackSelector) {
            return (MappingTrackSelector) mTrackSelector;
        }
        throw new IllegalStateException("trackSelector 必须是 MappingTrackSelector 类型");
    }

    /**
     * 设置当前播放的音轨
     * @param groupIndex 音轨组的索引
     * @param trackIndex 音轨在组内的索引
     */
    public void setTrack(int groupIndex, int trackIndex) {
        try {
            DefaultTrackSelector trackSelector = (DefaultTrackSelector) getMappingTrackSelector();
            MappingTrackSelector.MappedTrackInfo mappedInfo = trackSelector.getCurrentMappedTrackInfo();
            if (mappedInfo == null) {
                LOG.i("echo-setTrack: MappedTrackInfo is null");
                return;
            }
            int audioRendererIndex = findAudioRendererIndex(mappedInfo);
            if (audioRendererIndex == C.INDEX_UNSET) {
                LOG.i("echo-setTrack: No audio renderer found");
                return;
            }
            TrackGroupArray audioGroups = mappedInfo.getTrackGroups(audioRendererIndex);
            if (!isTrackIndexValid(audioGroups, groupIndex, trackIndex)) {
                LOG.i("echo-setTrack: Invalid track index - group:" + groupIndex + ", track:" + trackIndex);
                return;
            }
            DefaultTrackSelector.SelectionOverride newOverride = new DefaultTrackSelector.SelectionOverride(groupIndex, trackIndex);
            DefaultTrackSelector.ParametersBuilder builder = trackSelector.buildUponParameters();
            builder.clearSelectionOverrides(audioRendererIndex);
            builder.setSelectionOverride(audioRendererIndex, audioGroups, newOverride);
            trackSelector.setParameters(builder.build());
        } catch (Exception e) {
            LOG.i("echo-setTrack error: " + e.getMessage());
        }
    }

    /**
     * 查找音频渲染器索引
     */
    private int findAudioRendererIndex(MappingTrackSelector.MappedTrackInfo mappedInfo) {
        for (int i = 0; i < mappedInfo.getRendererCount(); i++) {
            if (mappedInfo.getRendererType(i) == C.TRACK_TYPE_AUDIO) {
                return i;
            }
        }
        return C.INDEX_UNSET;
    }

    /**
     * 验证音轨索引是否有效
     */
    private boolean isTrackIndexValid(TrackGroupArray groups, int groupIndex, int trackIndex) {
        if (groupIndex < 0 || groupIndex >= groups.length) {
            return false;
        }

        TrackGroup group = groups.get(groupIndex);
        return trackIndex >= 0 && trackIndex < group.length;
    }

    private static final Map<String, String> LANG_MAP = new HashMap<>();
    static {
        LANG_MAP.put("zh", "中文");
        LANG_MAP.put("zh-cn", "中文");
        LANG_MAP.put("en", "英语");
        LANG_MAP.put("en-us", "英语");
    }

    private String getLanguage(Format fmt){
        String lang = fmt.language;
        if (lang == null || lang.isEmpty() || "und".equalsIgnoreCase(lang)) {
            return "未知";
        }
        String name = LANG_MAP.get(lang.toLowerCase());
        return name != null ? name : lang;
    }

    private String getName(Format fmt){
        String channelLabel;
        if (fmt.channelCount <= 0) {
            channelLabel = "";
        } else if (fmt.channelCount == 1) {
            channelLabel = "单声道";
        } else if (fmt.channelCount == 2) {
            channelLabel = "立体声";
        } else {
            channelLabel = fmt.channelCount + " 声道";
        }
        String codec = "";
        if (fmt.sampleMimeType != null) {
            String mime = fmt.sampleMimeType.substring(fmt.sampleMimeType.indexOf('/') + 1);
            codec = mime.toUpperCase();
        }
        return String.join(", ", channelLabel, codec);
    }
}