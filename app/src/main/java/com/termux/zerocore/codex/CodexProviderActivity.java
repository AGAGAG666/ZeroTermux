package com.termux.zerocore.codex;

import android.app.AlertDialog;
import android.os.Bundle;
import android.text.InputType;
import android.text.TextUtils;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.ArrayAdapter;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.SwitchCompat;

import com.termux.R;
import com.termux.app.terminal.TerminalTitleNotificationSettings;

import java.util.ArrayList;
import java.util.List;

/** Android-native CC Switch surface scoped to Codex and OpenCode. */
public class CodexProviderActivity extends AppCompatActivity {
    private final List<CodexProviderProfile> providers = new ArrayList<>();
    private String agent = CodexProviderProfile.AGENT_CODEX;
    private ProviderAdapter adapter;
    private TextView agentTitle;
    private TextView proxyStatus;
    private SwitchCompat routeSwitch;

    @Override protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_codex_provider);
        CodexProviderStore.ensureProxyRunning(this);
        findViewById(R.id.codex_provider_back).setOnClickListener(v -> finish());
        findViewById(R.id.codex_provider_add).setOnClickListener(v -> editProvider(null));
        agentTitle = findViewById(R.id.ccs_agent_title);
        proxyStatus = findViewById(R.id.ccs_proxy_status);
        SwitchCompat backgroundTitleSwitch = findViewById(R.id.ccs_background_title_switch);
        backgroundTitleSwitch.setChecked(TerminalTitleNotificationSettings.isEnabled(this));
        backgroundTitleSwitch.setOnCheckedChangeListener((button, checked) ->
            TerminalTitleNotificationSettings.setEnabled(this, checked));
        routeSwitch = findViewById(R.id.ccs_route_switch);
        routeSwitch.setChecked(CodexProviderStore.isRouteEnabled(this));
        routeSwitch.setOnCheckedChangeListener((button, checked) -> toggleRoute(checked));
        findViewById(R.id.ccs_codex_tab).setOnClickListener(v -> selectAgent(CodexProviderProfile.AGENT_CODEX));
        findViewById(R.id.ccs_opencode_tab).setOnClickListener(v -> selectAgent(CodexProviderProfile.AGENT_OPENCODE));
        findViewById(R.id.ccs_mcp).setOnClickListener(v -> CcsMcpManager.show(this, agent));
        findViewById(R.id.ccs_skills).setOnClickListener(v -> CcsSkillManager.show(this));
        findViewById(R.id.ccs_usage).setOnClickListener(v -> CcsUsageStore.show(this));
        findViewById(R.id.ccs_presets).setOnClickListener(v -> showPresets());
        findViewById(R.id.ccs_import_export).setOnClickListener(v -> showImportExport());
        findViewById(R.id.ccs_settings).setOnClickListener(v -> showSettings());

        ListView list = findViewById(R.id.codex_provider_list);
        adapter = new ProviderAdapter();
        list.setAdapter(adapter);
        list.setOnItemClickListener((parent, view, position, id) -> activate(providers.get(position)));
        list.setOnItemLongClickListener((parent, view, position, id) -> {
            showActions(providers.get(position));
            return true;
        });
        selectAgent(CodexProviderProfile.AGENT_CODEX);
    }

    @Override protected void onResume() {
        super.onResume();
        refreshStatus();
    }

    private void selectAgent(String value) {
        agent = value;
        agentTitle.setText(CodexProviderProfile.AGENT_CODEX.equals(agent) ? "Codex 供应商" : "OpenCode 供应商");
        reload();
    }

    private void reload() {
        providers.clear();
        providers.addAll(CodexProviderStore.load(this, agent));
        adapter.notifyDataSetChanged();
        refreshStatus();
    }

    private void refreshStatus() {
        boolean enabled = CodexProviderStore.isRouteEnabled(this);
        routeSwitch.setChecked(enabled);
        proxyStatus.setText(enabled
            ? "路由已开启 · 127.0.0.1:" + CodexProviderStore.proxyPort()
            : "路由已关闭 · Chat/Anthropic/Responses 不兼容时仍允许直连");
    }

    private void toggleRoute(boolean checked) {
        try {
            CodexProviderStore.setRouteEnabled(this, checked);
            refreshStatus();
        } catch (Exception error) {
            routeSwitch.setOnCheckedChangeListener(null);
            routeSwitch.setChecked(!checked);
            routeSwitch.setOnCheckedChangeListener((button, value) -> toggleRoute(value));
            showError("路由切换失败", error);
        }
    }

    private void activate(CodexProviderProfile profile) {
        try {
            CodexProviderStore.apply(this, agent, profile);
            adapter.notifyDataSetChanged();
            String message = CodexProviderStore.isRouteEnabled(this)
                ? profile.name + " 已切换，后续请求立即使用新渠道"
                : profile.name + " 已切换，重新启动 Agent 后生效";
            Toast.makeText(this, message, Toast.LENGTH_LONG).show();
        } catch (Exception error) {
            showError(getString(R.string.codex_provider_apply_failed), error);
        }
    }

    private void showActions(CodexProviderProfile profile) {
        String[] labels = {"测试连接", "流式测试", "端点测速", getString(R.string.codex_provider_edit), "复制", getString(R.string.codex_provider_delete)};
        new AlertDialog.Builder(this).setTitle(profile.name).setItems(labels, (dialog, which) -> {
            if (which == 0) testConnection(profile);
            else if (which == 1) testStream(profile);
            else if (which == 2) speedTest(profile);
            else if (which == 3) editProvider(profile);
            else if (which == 4) duplicate(profile);
            else confirmDelete(profile);
        }).show();
    }

    private void testStream(CodexProviderProfile profile) {
        Toast.makeText(this, "正在进行流式测试", Toast.LENGTH_SHORT).show();
        CcsConnectionTester.test(profile, true, result -> runOnUiThread(() -> new AlertDialog.Builder(this)
            .setTitle(result.success ? "流式连接成功" : "流式连接失败").setMessage(result.message)
            .setPositiveButton(android.R.string.ok, null).show()));
    }

    private void speedTest(CodexProviderProfile profile) {
        Toast.makeText(this, "正在测试全部端点", Toast.LENGTH_SHORT).show();
        CcsConnectionTester.speedTest(profile, result -> runOnUiThread(() -> new AlertDialog.Builder(this)
            .setTitle("端点测速").setMessage(result.message).setPositiveButton(android.R.string.ok, null).show()));
    }

    private void testConnection(CodexProviderProfile profile) {
        Toast.makeText(this, "正在测试 " + profile.name, Toast.LENGTH_SHORT).show();
        CcsConnectionTester.test(profile, result -> runOnUiThread(() -> new AlertDialog.Builder(this)
            .setTitle(result.success ? "连接成功" : "连接失败")
            .setMessage(result.message).setPositiveButton(android.R.string.ok, null).show()));
    }

    private void duplicate(CodexProviderProfile profile) {
        CodexProviderProfile copy = CcsJson.copy(profile);
        copy.id = java.util.UUID.randomUUID().toString();
        copy.name = profile.name + " 副本";
        providers.add(copy);
        CodexProviderStore.save(this, agent, providers);
        adapter.notifyDataSetChanged();
    }

    private void confirmDelete(CodexProviderProfile profile) {
        new AlertDialog.Builder(this).setTitle(R.string.codex_provider_delete)
            .setMessage(getString(R.string.codex_provider_delete_confirm, profile.name))
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                CodexProviderStore.delete(this, agent, profile, providers);
                adapter.notifyDataSetChanged();
            }).show();
    }

    private void editProvider(CodexProviderProfile existing) {
        boolean adding = existing == null || !providers.contains(existing);
        CodexProviderProfile draft = existing == null ? new CodexProviderProfile() : CcsJson.copy(existing);
        draft.normalize(agent);
        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(18);
        form.setPadding(pad, dp(4), pad, dp(8));

        EditText name = field("供应商名称", draft.name, false);
        EditText baseUrl = field("Base URL", draft.baseUrl, false);
        EditText apiKey = field("API Key", draft.apiKey, true);
        EditText model = field("默认模型", draft.model, false);
        Button fetchModels = new Button(this);
        fetchModels.setText("获取上游模型");
        Spinner format = spinner(new String[]{"Responses", "Chat Completions", "Anthropic Messages"}, formatIndex(draft.apiFormat));
        CheckBox fullUrl = check("Base URL 是完整端点", draft.fullUrl);
        form.addView(label("基础配置")); form.addView(name); form.addView(baseUrl); form.addView(apiKey);
        form.addView(model); form.addView(fetchModels); form.addView(format); form.addView(fullUrl);

        LinearLayout anthropicOptions = new LinearLayout(this);
        anthropicOptions.setOrientation(LinearLayout.VERTICAL);
        Spinner authField = spinner(new String[]{"ANTHROPIC_AUTH_TOKEN (Authorization Bearer)", "ANTHROPIC_API_KEY (x-api-key)"},
            "ANTHROPIC_API_KEY".equals(draft.anthropicAuthField) ? 1 : 0);
        CheckBox impersonate = check("模拟 Claude Code 客户端", draft.impersonateClaudeCode);
        EditText maxTokens = field("Anthropic 最大输出 tokens（留空=8192）",
            draft.maxOutputTokens > 0 ? String.valueOf(draft.maxOutputTokens) : "", false);
        maxTokens.setInputType(InputType.TYPE_CLASS_NUMBER);
        anthropicOptions.addView(label("Anthropic")); anthropicOptions.addView(authField);
        anthropicOptions.addView(impersonate); anthropicOptions.addView(maxTokens);
        form.addView(anthropicOptions);

        LinearLayout chatOptions = new LinearLayout(this);
        chatOptions.setOrientation(LinearLayout.VERTICAL);
        Spinner cache = spinner(new String[]{"提示词缓存：自动", "提示词缓存：开启", "提示词缓存：关闭"},
            "enabled".equals(draft.promptCacheRouting) ? 1 : "disabled".equals(draft.promptCacheRouting) ? 2 : 0);
        CheckBox thinking = check("支持思考模式", draft.supportsThinking);
        CheckBox effort = check("支持思考等级", draft.supportsEffort);
        Spinner thinkingParam = spinner(new String[]{"thinking", "enable_thinking", "reasoning_split"},
            "enable_thinking".equals(draft.thinkingParam) ? 1 : "reasoning_split".equals(draft.thinkingParam) ? 2 : 0);
        Spinner effortParam = spinner(new String[]{"reasoning_effort", "reasoning.effort"},
            "reasoning.effort".equals(draft.effortParam) ? 1 : 0);
        effort.setOnCheckedChangeListener((button, checked) -> { if (checked) thinking.setChecked(true); });
        chatOptions.addView(label("Chat 思考与缓存")); chatOptions.addView(cache); chatOptions.addView(thinking);
        chatOptions.addView(thinkingParam); chatOptions.addView(effort); chatOptions.addView(effortParam);
        form.addView(chatOptions);
        format.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(android.widget.AdapterView<?> parent, View view, int position, long id) {
                String selected = formatValue(position);
                anthropicOptions.setVisibility(CodexProviderProfile.FORMAT_ANTHROPIC.equals(selected) ? View.VISIBLE : View.GONE);
                chatOptions.setVisibility(CodexProviderProfile.FORMAT_CHAT.equals(selected) ? View.VISIBLE : View.GONE);
            }
            @Override public void onNothingSelected(android.widget.AdapterView<?> parent) {
                anthropicOptions.setVisibility(View.GONE);
                chatOptions.setVisibility(View.GONE);
            }
        });

        EditText mappings = multiline("每行：菜单显示名|实际请求模型|上下文窗口", mappingsText(draft));
        EditText userAgent = field("自定义 User-Agent", draft.customUserAgent, false);
        EditText headers = multiline("请求头覆盖 JSON", draft.headersJson);
        EditText body = multiline("请求体覆盖 JSON", draft.bodyJson);
        CheckBox failover = check("加入故障转移队列", draft.failoverEnabled);
        CheckBox autoEndpoint = check("自动选择可用端点", draft.endpointAutoSelect);
        EditText alternateEndpoints = multiline("备用端点，每行一个", TextUtils.join("\n", draft.alternateEndpoints));
        EditText priority = field("故障转移优先级（数字越小越优先）", String.valueOf(draft.failoverPriority), false);
        priority.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_SIGNED);
        form.addView(label("模型映射")); form.addView(mappings);
        form.addView(label("代理高级选项")); form.addView(userAgent); form.addView(headers); form.addView(body);
        form.addView(autoEndpoint); form.addView(alternateEndpoints); form.addView(failover); form.addView(priority);
        fetchModels.setOnClickListener(v -> {
            if (baseUrl.getText().toString().trim().isEmpty()) { baseUrl.setError("必填"); return; }
            if (apiKey.getText().toString().trim().isEmpty()) { apiKey.setError("必填"); return; }
            CodexProviderProfile pending = CcsJson.copy(draft);
            pending.baseUrl = baseUrl.getText().toString().trim();
            pending.apiKey = apiKey.getText().toString().trim();
            pending.fullUrl = fullUrl.isChecked();
            pending.customUserAgent = userAgent.getText().toString().trim();
            fetchModels.setEnabled(false);
            fetchModels.setText("获取中...");
            CcsModelFetcher.fetch(pending, result -> runOnUiThread(() -> {
                fetchModels.setEnabled(true);
                fetchModels.setText("获取上游模型");
                if (!result.success()) { showError("获取模型失败", new IllegalStateException(result.error)); return; }
                List<CodexProviderProfile.ModelMapping> merged = parseMappings(mappings.getText().toString());
                java.util.HashSet<String> existingModels = new java.util.HashSet<>();
                for (CodexProviderProfile.ModelMapping item : merged) if (item != null && item.model != null) existingModels.add(item.model);
                for (CcsModelFetcher.Model item : result.models) {
                    if (TextUtils.isEmpty(item.id) || !existingModels.add(item.id)) continue;
                    merged.add(new CodexProviderProfile.ModelMapping(item.id, item.id,
                        CodexProviderProfile.DEFAULT_CONTEXT_WINDOW));
                }
                if (TextUtils.isEmpty(model.getText().toString().trim()) && !result.models.isEmpty())
                    model.setText(result.models.get(0).id);
                mappings.setText(mappingsText(merged));
                Toast.makeText(this, "已获取 " + result.models.size() + " 个模型", Toast.LENGTH_SHORT).show();
            }));
        });

        ScrollView scroll = new ScrollView(this); scroll.addView(form);
        AlertDialog dialog = new AlertDialog.Builder(this)
            .setTitle(adding ? R.string.codex_provider_add : R.string.codex_provider_edit)
            .setView(scroll).setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.codex_provider_save, null).create();
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            if (name.getText().toString().trim().isEmpty()) { name.setError("必填"); return; }
            if (baseUrl.getText().toString().trim().isEmpty()) { baseUrl.setError("必填"); return; }
            try {
                CcsJson.requireObject(headers.getText().toString());
                CcsJson.requireObject(body.getText().toString());
                draft.name = name.getText().toString().trim();
                draft.baseUrl = baseUrl.getText().toString().trim();
                draft.apiKey = apiKey.getText().toString().trim();
                draft.model = model.getText().toString().trim();
                draft.apiFormat = formatValue(format.getSelectedItemPosition());
                draft.fullUrl = fullUrl.isChecked();
                draft.anthropicAuthField = authField.getSelectedItemPosition() == 1 ? "ANTHROPIC_API_KEY" : "ANTHROPIC_AUTH_TOKEN";
                draft.impersonateClaudeCode = impersonate.isChecked();
                draft.maxOutputTokens = parseInt(maxTokens.getText().toString());
                draft.promptCacheRouting = cache.getSelectedItemPosition() == 1 ? "enabled" : cache.getSelectedItemPosition() == 2 ? "disabled" : "auto";
                draft.supportsThinking = thinking.isChecked() || effort.isChecked();
                draft.supportsEffort = effort.isChecked();
                draft.thinkingParam = thinkingParam.getSelectedItemPosition() == 1 ? "enable_thinking" : thinkingParam.getSelectedItemPosition() == 2 ? "reasoning_split" : "thinking";
                draft.effortParam = effortParam.getSelectedItemPosition() == 1 ? "reasoning.effort" : "reasoning_effort";
                draft.modelCatalog = parseMappings(mappings.getText().toString());
                draft.customUserAgent = userAgent.getText().toString().trim();
                draft.headersJson = headers.getText().toString().trim();
                draft.bodyJson = body.getText().toString().trim();
                draft.failoverEnabled = failover.isChecked();
                draft.endpointAutoSelect = autoEndpoint.isChecked();
                draft.alternateEndpoints = nonEmptyLines(alternateEndpoints.getText().toString());
                draft.failoverPriority = parseInt(priority.getText().toString());
                if (adding) providers.add(draft);
                else providers.set(providers.indexOf(existing), draft);
                CodexProviderStore.save(this, agent, providers);
                if (draft.id.equals(CodexProviderStore.activeId(this, agent))) {
                    CodexProviderStore.apply(this, agent, draft);
                }
                dialog.dismiss();
                adapter.notifyDataSetChanged();
            } catch (Exception error) { showError("配置无效", error); }
        }));
        dialog.show();
    }

    private void showPresets() {
        String[] labels = {"Responses 模板", "Chat 模板", "Anthropic 模板", "从在线 JSON 导入"};
        new AlertDialog.Builder(this).setTitle("供应商预设").setItems(labels, (dialog, which) -> {
            if (which == 3) { CcsPresetManager.importOnline(this, this::reload); return; }
            CodexProviderProfile preset = new CodexProviderProfile(); preset.normalize(agent);
            preset.name = labels[which];
            preset.apiFormat = which == 0 ? CodexProviderProfile.FORMAT_RESPONSES
                : which == 1 ? CodexProviderProfile.FORMAT_CHAT : CodexProviderProfile.FORMAT_ANTHROPIC;
            editProvider(preset);
        }).show();
    }

    private void showImportExport() {
        EditText json = multiline("CC Switch JSON", CodexProviderStore.exportJson(this));
        json.setMinLines(12);
        new AlertDialog.Builder(this).setTitle("导入 / 导出")
            .setView(json).setNegativeButton(android.R.string.cancel, null)
            .setNeutralButton("复制", (d, w) -> CcsJson.copyToClipboard(this, json.getText().toString()))
            .setPositiveButton("导入", (d, w) -> {
                try { CodexProviderStore.importJson(this, json.getText().toString()); reload(); }
                catch (Exception error) { showError("导入失败", error); }
            }).show();
    }

    private void showSettings() {
        new AlertDialog.Builder(this).setTitle("CC Switch 设置")
            .setMessage("代理端口：" + CodexProviderStore.proxyPort() +
                "\nCodex model_provider：custom\n供应商密钥：兼容 CCS 的明文存储" +
                "\n路由开启：切换渠道后续请求立即生效" +
                "\n路由关闭：重新启动 Codex/OpenCode 后生效，无需重启 Termux")
            .setPositiveButton(android.R.string.ok, null).show();
    }

    private EditText field(String hint, String value, boolean secret) {
        EditText field = new EditText(this); field.setHint(hint); field.setText(value); field.setSingleLine(true);
        if (secret) field.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        field.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return field;
    }
    private EditText multiline(String hint, String value) {
        EditText field = new EditText(this); field.setHint(hint); field.setText(value); field.setMinLines(3);
        field.setGravity(android.view.Gravity.TOP); field.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        return field;
    }
    private Spinner spinner(String[] values, int selected) {
        Spinner spinner = new Spinner(this);
        spinner.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, values));
        spinner.setSelection(selected); return spinner;
    }
    private CheckBox check(String text, boolean checked) { CheckBox box = new CheckBox(this); box.setText(text); box.setChecked(checked); return box; }
    private TextView label(String text) { TextView view = new TextView(this); view.setText(text); view.setTextSize(15); view.setPadding(0, dp(14), 0, dp(4)); return view; }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private int formatIndex(String value) { return CodexProviderProfile.FORMAT_CHAT.equals(value) ? 1 : CodexProviderProfile.FORMAT_ANTHROPIC.equals(value) ? 2 : 0; }
    private String formatValue(int index) { return index == 1 ? CodexProviderProfile.FORMAT_CHAT : index == 2 ? CodexProviderProfile.FORMAT_ANTHROPIC : CodexProviderProfile.FORMAT_RESPONSES; }
    private int parseInt(String value) { try { return Integer.parseInt(value.trim()); } catch (Exception ignored) { return 0; } }
    private List<String> nonEmptyLines(String value) {
        List<String> result = new ArrayList<>();
        for (String line : value.split("\\r?\\n")) if (!line.trim().isEmpty()) result.add(line.trim());
        return result;
    }
    private String mappingsText(CodexProviderProfile profile) {
        return mappingsText(profile.modelCatalog);
    }
    private String mappingsText(List<CodexProviderProfile.ModelMapping> mappings) {
        StringBuilder result = new StringBuilder();
        for (CodexProviderProfile.ModelMapping item : mappings) {
            if (item == null) continue;
            if (result.length() > 0) result.append('\n');
            result.append(item.displayName == null ? "" : item.displayName).append('|')
                .append(item.model == null ? "" : item.model).append('|').append(item.contextWindow);
        }
        return result.toString();
    }
    private List<CodexProviderProfile.ModelMapping> parseMappings(String value) {
        List<CodexProviderProfile.ModelMapping> result = new ArrayList<>();
        for (String line : value.split("\\r?\\n")) {
            if (line.trim().isEmpty()) continue;
            String[] parts = line.split("\\|", -1);
            String model = parts.length > 1 ? parts[1].trim() : parts[0].trim();
            if (model.isEmpty()) continue;
            long context = CodexProviderProfile.DEFAULT_CONTEXT_WINDOW;
            if (parts.length > 2) try { context = Long.parseLong(parts[2].trim()); } catch (Exception ignored) {}
            result.add(new CodexProviderProfile.ModelMapping(parts[0].trim(), model, context));
        }
        return result;
    }
    private void showError(String title, Throwable error) {
        new AlertDialog.Builder(this).setTitle(title).setMessage(error.getMessage() == null ? error.toString() : error.getMessage())
            .setPositiveButton(android.R.string.ok, null).show();
    }

    private class ProviderAdapter extends BaseAdapter {
        public int getCount() { return providers.size(); }
        public CodexProviderProfile getItem(int position) { return providers.get(position); }
        public long getItemId(int position) { return position; }
        @Override public View getView(int position, View convertView, ViewGroup parent) {
            if (convertView == null) convertView = getLayoutInflater().inflate(R.layout.item_codex_provider, parent, false);
            CodexProviderProfile profile = getItem(position);
            TextView name = convertView.findViewById(R.id.codex_provider_item_name);
            TextView endpoint = convertView.findViewById(R.id.codex_provider_item_endpoint);
            TextView active = convertView.findViewById(R.id.codex_provider_item_active);
            name.setText(profile.name);
            endpoint.setText(formatLabel(profile.apiFormat) + " · " + profile.baseUrl +
                (TextUtils.isEmpty(profile.model) ? "" : "\n" + profile.model));
            active.setVisibility(profile.id.equals(CodexProviderStore.activeId(CodexProviderActivity.this, agent)) ? View.VISIBLE : View.GONE);
            return convertView;
        }
        private String formatLabel(String format) {
            if (CodexProviderProfile.FORMAT_CHAT.equals(format)) return "Chat";
            if (CodexProviderProfile.FORMAT_ANTHROPIC.equals(format)) return "Anthropic";
            return "Responses";
        }
    }
}
