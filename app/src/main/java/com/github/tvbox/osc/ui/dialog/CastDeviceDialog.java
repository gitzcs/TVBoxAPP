package com.github.tvbox.osc.ui.dialog;

import android.content.Context;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.github.tvbox.osc.R;
import com.github.tvbox.osc.dlna.CastDevice;
import com.github.tvbox.osc.dlna.CastVideo;
import com.github.tvbox.osc.dlna.DLNACastManager;
import com.github.tvbox.osc.player.thirdparty.RemoteTVBox;
import com.owen.tvrecyclerview.widget.TvRecyclerView;

import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.io.IOException;
import java.net.URLEncoder;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import okhttp3.Call;
import okhttp3.Response;

public class CastDeviceDialog extends BaseDialog {
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Map<String, CastDevice> devices = new LinkedHashMap<>();
    private final CastVideo video;
    private final DeviceAdapter adapter = new DeviceAdapter();
    private OnCastListener onCastListener;
    private TextView title;
    private boolean searchFinished;

    public CastDeviceDialog(@NonNull @NotNull Context context, CastVideo video) {
        super(context);
        this.video = video;
        setContentView(R.layout.dialog_select);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        title = findViewById(R.id.title);
        TvRecyclerView list = findViewById(R.id.list);
        list.setAdapter(adapter);
        title.setText("搜索投屏设备...");
        searchDevices();
    }

    @Override
    public void dismiss() {
        DLNACastManager.get().setDeviceListener(null);
        DLNACastManager.get().release(getContext());
        super.dismiss();
    }

    public void setOnCastListener(OnCastListener listener) {
        this.onCastListener = listener;
    }

    private void searchDevices() {
        searchTvBoxDevices();
        searchDlnaDevices();
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                searchFinished = true;
                updateTitle();
            }
        }, 15000);
    }

    private void searchTvBoxDevices() {
        new Thread(new Runnable() {
            @Override
            public void run() {
                RemoteTVBox tv = new RemoteTVBox();
                RemoteTVBox.searchAvalible(tv.new Callback() {
                    @Override
                    public void found(final String viewHost, boolean end) {
                        handler.post(new Runnable() {
                            @Override
                            public void run() {
                                addDevice(CastDevice.tvbox(viewHost));
                            }
                        });
                    }

                    @Override
                    public void fail(boolean all, boolean end) {
                        if (end) {
                            handler.post(new Runnable() {
                                @Override
                                public void run() {
                                    updateTitle();
                                }
                            });
                        }
                    }
                });
            }
        }).start();
    }

    private void searchDlnaDevices() {
        DLNACastManager.get().setDeviceListener(new DLNACastManager.DeviceListener() {
            @Override
            public void onDeviceChanged() {
                for (CastDevice device : DLNACastManager.get().getDevices()) {
                    addDevice(device);
                }
            }
        });
        DLNACastManager.get().init(getContext());
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                DLNACastManager.get().search();
            }
        }, 1000);
    }

    private void addDevice(CastDevice device) {
        devices.put(device.getType() + ":" + device.getId(), device);
        adapter.setData(new ArrayList<>(devices.values()));
        updateTitle();
    }

    private void updateTitle() {
        if (title == null) return;
        if (!devices.isEmpty()) {
            title.setText("投屏到设备");
        } else {
            title.setText(searchFinished ? "未找到投屏设备" : "搜索投屏设备...");
        }
    }

    private void castToDevice(final CastDevice device) {
        if (device.getType() == CastDevice.TYPE_TVBOX) {
            castToTvBox(device);
            return;
        }
        DLNACastManager.get().cast(device, video, new DLNACastManager.CastCallback() {
            @Override
            public void onResult(boolean success, String msg) {
                handleCastResult(success, msg);
            }
        });
    }

    private void castToTvBox(CastDevice device) {
        try {
            String url = buildTvBoxUrl(video.getUrl(), video.getHeaders());
            Map<String, String> params = new HashMap<>();
            params.put("do", "push");
            params.put("url", url);
            RemoteTVBox.post("http://" + device.getId() + "/action", params, new okhttp3.Callback() {
                @Override
                public void onFailure(Call call, IOException e) {
                    handler.post(new Runnable() {
                        @Override
                        public void run() {
                            handleCastResult(false, "TVBox投屏失败");
                        }
                    });
                }

                @Override
                public void onResponse(Call call, Response response) throws IOException {
                    final boolean ok;
                    try {
                        ok = response.body() != null && "ok".equals(response.body().string());
                    } finally {
                        response.close();
                    }
                    handler.post(new Runnable() {
                        @Override
                        public void run() {
                            handleCastResult(ok, ok ? "" : "TVBox投屏失败");
                        }
                    });
                }
            });
        } catch (Exception e) {
            handleCastResult(false, "TVBox投屏失败");
        }
    }

    private String buildTvBoxUrl(String url, HashMap<String, String> headers) throws Exception {
        if (headers == null || headers.isEmpty()) return url;
        StringBuilder sb = new StringBuilder(url).append("|");
        int index = 0;
        for (String key : headers.keySet()) {
            sb.append(key).append("=").append(URLEncoder.encode(headers.get(key), "UTF-8"));
            if (index < headers.size() - 1) sb.append("&");
            index++;
        }
        return sb.toString();
    }

    private void handleCastResult(boolean success, String msg) {
        if (success) {
            Toast.makeText(getContext(), "投屏成功", Toast.LENGTH_SHORT).show();
            if (onCastListener != null) onCastListener.onCastSuccess();
            dismiss();
        } else {
            Toast.makeText(getContext(), msg == null || msg.length() == 0 ? "投屏失败" : msg, Toast.LENGTH_SHORT).show();
            if (onCastListener != null) onCastListener.onCastFailed();
        }
    }

    public interface OnCastListener {
        void onCastSuccess();

        void onCastFailed();
    }

    private class DeviceAdapter extends RecyclerView.Adapter<DeviceAdapter.DeviceHolder> {
        private final List<CastDevice> data = new ArrayList<>();

        void setData(List<CastDevice> devices) {
            data.clear();
            data.addAll(devices);
            notifyDataSetChanged();
        }

        @NonNull
        @Override
        public DeviceHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new DeviceHolder(LayoutInflater.from(parent.getContext()).inflate(R.layout.item_dialog_select, parent, false));
        }

        @Override
        public void onBindViewHolder(@NonNull DeviceHolder holder, final int position) {
            final CastDevice device = data.get(position);
            TextView name = holder.itemView.findViewById(R.id.tvName);
            name.setText(device.getDisplayName());
            holder.itemView.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    castToDevice(device);
                }
            });
        }

        @Override
        public int getItemCount() {
            return data.size();
        }

        class DeviceHolder extends RecyclerView.ViewHolder {
            DeviceHolder(@NonNull View itemView) {
                super(itemView);
            }
        }
    }
}
