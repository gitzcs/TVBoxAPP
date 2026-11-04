package com.github.tvbox.osc.ui.dialog;

import android.content.Context;
import android.text.TextUtils;
import android.view.View;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import com.github.tvbox.osc.R;
import com.github.tvbox.osc.finder.IndexSiteStore;
import com.github.tvbox.osc.finder.SourceIndex;

import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** 搜索索引站新增 / 编辑对话框。 */
public class IndexSiteEditDialog extends BaseDialog {

    public interface OnSaveListener {
        void onSave(SourceIndex.IndexSite site);
    }

    private final EditText inputName;
    private final EditText inputUrl;
    private final EditText inputQueries;
    private final TextView btnToggle;
    private final SourceIndex.IndexSite site;
    private boolean enabled;

    public IndexSiteEditDialog(Context context, SourceIndex.IndexSite editSite,
                               OnSaveListener listener) {
        super(context);
        setContentView(R.layout.dialog_index_site_edit);
        setCanceledOnTouchOutside(true);

        TextView title = findViewById(R.id.tvDialogTitle);
        inputName = findViewById(R.id.inputIndexSiteName);
        inputUrl = findViewById(R.id.inputIndexSiteUrl);
        inputQueries = findViewById(R.id.inputIndexSiteQueries);
        btnToggle = findViewById(R.id.btnIndexSiteToggle);

        if (editSite == null) {
            site = IndexSiteStore.newSite();
            title.setText("新增索引站");
        } else {
            site = editSite.copy();
            title.setText("编辑索引站");
            inputName.setText(site.getName());
            inputUrl.setText(site.getBaseUrl());
        }
        inputQueries.setText(joinQueries(site.getQueries()));
        enabled = site.isEnabled();
        updateToggle();

        btnToggle.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                enabled = !enabled;
                updateToggle();
            }
        });
        findViewById(R.id.btnIndexSiteCancel).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                dismiss();
            }
        });
        findViewById(R.id.btnIndexSiteSave).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                save(listener);
            }
        });
    }

    private void save(OnSaveListener listener) {
        String baseUrl = normalizeBaseUrl(inputUrl.getText().toString());
        if (baseUrl == null) {
            Toast.makeText(getContext(), "请输入有效的 HTTPS 站点地址", Toast.LENGTH_SHORT).show();
            inputUrl.requestFocus();
            return;
        }
        List<String> queries = parseQueries(inputQueries.getText().toString());
        if (queries.isEmpty()) {
            Toast.makeText(getContext(), "至少填写一个搜索关键词", Toast.LENGTH_SHORT).show();
            inputQueries.requestFocus();
            return;
        }
        if (queries.size() > 12) {
            Toast.makeText(getContext(), "搜索关键词最多 12 个", Toast.LENGTH_SHORT).show();
            return;
        }

        String name = inputName.getText().toString().trim();
        if (TextUtils.isEmpty(name)) {
            try {
                name = new URI(baseUrl).getHost();
            } catch (Exception ignored) {
                name = baseUrl;
            }
        }
        site.setName(name);
        site.setBaseUrl(baseUrl);
        site.setQueries(queries);
        site.setEnabled(enabled);
        if (listener != null) {
            listener.onSave(site);
        }
        dismiss();
    }

    private void updateToggle() {
        btnToggle.setText(enabled ? "已启用" : "已停用");
        btnToggle.setTextColor(getContext().getResources()
                .getColor(enabled ? R.color.color_FFFFFF : R.color.color_FFB800));
    }

    static String normalizeBaseUrl(String raw) {
        if (raw == null) {
            return null;
        }
        String value = raw.trim();
        if (value.isEmpty()) {
            return null;
        }
        if (!value.contains("://")) {
            value = "https://" + value;
        }
        try {
            URI uri = new URI(value);
            if (!"https".equalsIgnoreCase(uri.getScheme())
                    || TextUtils.isEmpty(uri.getHost())) {
                return null;
            }
            String normalized = uri.toString();
            while (normalized.endsWith("/")) {
                normalized = normalized.substring(0, normalized.length() - 1);
            }
            return normalized;
        } catch (Exception e) {
            return null;
        }
    }

    static List<String> parseQueries(String raw) {
        List<String> out = new ArrayList<>();
        if (raw == null) {
            return out;
        }
        Set<String> seen = new LinkedHashSet<>();
        for (String line : raw.split("\\r?\\n")) {
            String query = line.trim();
            if (!query.isEmpty() && seen.add(query)) {
                out.add(query);
            }
        }
        return out;
    }

    private static String joinQueries(List<String> queries) {
        StringBuilder sb = new StringBuilder();
        for (String query : queries) {
            if (sb.length() > 0) {
                sb.append('\n');
            }
            sb.append(query);
        }
        return sb.toString();
    }
}
