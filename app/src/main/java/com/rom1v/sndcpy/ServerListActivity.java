package com.rom1v.sndcpy;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.TextView;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.List;

/**
 * Displays saved server connection history.
 * The user can search by nickname or IP, then tap "Select" to return
 * the chosen entry to MainActivity.
 *
 * Result extras:
 *   EXTRA_ENTRY_ID       — String UUID
 *   EXTRA_ENTRY_NICKNAME — String
 *   EXTRA_ENTRY_IP       — String
 *   EXTRA_ENTRY_PORT     — int
 */
public class ServerListActivity extends Activity {

    public static final String EXTRA_ENTRY_ID       = "entry_id";
    public static final String EXTRA_ENTRY_NICKNAME = "entry_nickname";
    public static final String EXTRA_ENTRY_IP       = "entry_ip";
    public static final String EXTRA_ENTRY_PORT     = "entry_port";

    private ServerAdapter adapter;
    private TextView tvEmpty;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_server_list);

        // Back button
        ImageButton btnBack = findViewById(R.id.btnBack);
        btnBack.setOnClickListener(v -> finish());

        // RecyclerView
        RecyclerView recycler = findViewById(R.id.recyclerServers);
        recycler.setLayoutManager(new LinearLayoutManager(this));

        tvEmpty = findViewById(R.id.tvEmpty);

        adapter = new ServerAdapter(this::onEntrySelected);
        recycler.setAdapter(adapter);

        // Search bar
        EditText editSearch = findViewById(R.id.editSearch);
        editSearch.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int st, int c, int a) {}
            @Override public void onTextChanged(CharSequence s, int st, int b, int c) {
                adapter.filter(s.toString());
                updateEmptyState();
            }
            @Override public void afterTextChanged(Editable s) {}
        });

        loadHistory();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Reload in case the list was updated while this screen was paused
        loadHistory();
    }

    private void loadHistory() {
        ServerHistoryManager mgr = new ServerHistoryManager(this);
        List<ServerEntry> entries = mgr.getAll();
        adapter.setData(entries);
        updateEmptyState();
    }

    private void updateEmptyState() {
        tvEmpty.setVisibility(adapter.getShownCount() == 0 ? View.VISIBLE : View.GONE);
    }

    private void onEntrySelected(ServerEntry entry) {
        Intent result = new Intent();
        result.putExtra(EXTRA_ENTRY_ID,       entry.getId());
        result.putExtra(EXTRA_ENTRY_NICKNAME, entry.getNickname());
        result.putExtra(EXTRA_ENTRY_IP,       entry.getIp());
        result.putExtra(EXTRA_ENTRY_PORT,     entry.getPort());
        setResult(RESULT_OK, result);
        finish();
    }
}
