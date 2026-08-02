package com.termux.zerocore.codex;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.TextView;

import com.termux.R;

import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

public class CodexSessionAdapter extends BaseAdapter {
    private final LayoutInflater inflater;
    private final DateFormat dateFormat;
    private final List<CodexSessionInfo> sessions = new ArrayList<>();

    public CodexSessionAdapter(Context context) {
        inflater = LayoutInflater.from(context);
        dateFormat = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT);
    }

    public void replace(List<CodexSessionInfo> values) {
        sessions.clear();
        if (values != null) sessions.addAll(values);
        notifyDataSetChanged();
    }

    public CodexSessionInfo getItem(int position) { return sessions.get(position); }
    public long getItemId(int position) { return position; }
    public int getCount() { return sessions.size(); }

    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
        Holder holder;
        if (convertView == null) {
            convertView = inflater.inflate(R.layout.item_codex_session, parent, false);
            holder = new Holder();
            holder.title = convertView.findViewById(R.id.codex_session_title);
            holder.meta = convertView.findViewById(R.id.codex_session_meta);
            convertView.setTag(holder);
        } else {
            holder = (Holder) convertView.getTag();
        }
        CodexSessionInfo item = getItem(position);
        holder.title.setText("Termux-codex · " + item.getTitle());
        String cwd = item.getCwd() == null ? "" : item.getCwd();
        holder.meta.setText(item.getId() + "\n" + (item.isRunning() ? "运行中" : "未运行")
            + (cwd.isEmpty() ? "" : " · " + cwd) + " · " + dateFormat.format(new Date(item.getUpdatedAt())));
        return convertView;
    }

    private static class Holder {
        TextView title;
        TextView meta;
    }
}
