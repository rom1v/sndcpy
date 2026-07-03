package com.rom1v.sndcpy;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * RecyclerView adapter for the server history list.
 * Supports live filtering via {@link #filter(String)}.
 */
public class ServerAdapter extends RecyclerView.Adapter<ServerAdapter.ViewHolder> {

    public interface OnSelectListener {
        void onSelect(ServerEntry entry);
    }

    private final List<ServerEntry> allEntries   = new ArrayList<>();
    private final List<ServerEntry> shownEntries = new ArrayList<>();
    private final OnSelectListener  listener;

    private static final SimpleDateFormat DATE_FMT =
            new SimpleDateFormat("dd MMM yyyy  HH:mm", Locale.getDefault());

    public ServerAdapter(OnSelectListener listener) {
        this.listener = listener;
    }

    public void setData(List<ServerEntry> entries) {
        allEntries.clear();
        allEntries.addAll(entries);
        shownEntries.clear();
        shownEntries.addAll(entries);
        notifyDataSetChanged();
    }

    /** Filter displayed list by nickname or IP (case-insensitive). Empty query shows all. */
    public void filter(String query) {
        shownEntries.clear();
        if (query == null || query.trim().isEmpty()) {
            shownEntries.addAll(allEntries);
        } else {
            String q = query.trim().toLowerCase(Locale.getDefault());
            for (ServerEntry e : allEntries) {
                if (e.getNickname().toLowerCase(Locale.getDefault()).contains(q)
                        || e.getIp().toLowerCase(Locale.getDefault()).contains(q)) {
                    shownEntries.add(e);
                }
            }
        }
        notifyDataSetChanged();
    }

    public int getShownCount() { return shownEntries.size(); }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_server, parent, false);
        return new ViewHolder(v);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        ServerEntry entry = shownEntries.get(position);
        holder.tvNickname.setText(entry.getNickname());
        holder.tvIpPort.setText(entry.getIp() + ":" + entry.getPort());
        holder.tvLastConnected.setText(
                "Last connected: " + DATE_FMT.format(new Date(entry.getLastConnectedMs())));
        holder.btnSelect.setOnClickListener(v -> listener.onSelect(entry));
    }

    @Override
    public int getItemCount() { return shownEntries.size(); }

    static class ViewHolder extends RecyclerView.ViewHolder {
        final TextView tvNickname;
        final TextView tvIpPort;
        final TextView tvLastConnected;
        final Button   btnSelect;

        ViewHolder(View itemView) {
            super(itemView);
            tvNickname      = itemView.findViewById(R.id.tvNickname);
            tvIpPort        = itemView.findViewById(R.id.tvIpPort);
            tvLastConnected = itemView.findViewById(R.id.tvLastConnected);
            btnSelect       = itemView.findViewById(R.id.btnSelect);
        }
    }
}
