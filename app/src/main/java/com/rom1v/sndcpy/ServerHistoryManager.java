package com.rom1v.sndcpy;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Manages persistence of the server connection history.
 *
 * Storage: a single JSON array in SharedPreferences (no extra DB dependency).
 * Each entry is a JSON object:
 *   { "id": "...", "nickname": "...", "ip": "...", "port": ..., "lastConnectedMs": ... }
 *
 * Ordering: newest-first (sorted by lastConnectedMs descending).
 */
public class ServerHistoryManager {

    private static final String PREFS_NAME  = "sndcpy_history";
    private static final String KEY_SERVERS = "servers";

    private static final String F_ID            = "id";
    private static final String F_NICKNAME      = "nickname";
    private static final String F_IP            = "ip";
    private static final String F_PORT          = "port";
    private static final String F_LAST_CONNECTED = "lastConnectedMs";

    private final SharedPreferences prefs;

    public ServerHistoryManager(Context context) {
        prefs = context.getApplicationContext()
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    /**
     * Insert a new entry or update an existing one (matched by {@code entry.getId()}).
     * Always refreshes {@code lastConnectedMs} to now.
     */
    public void saveOrUpdate(ServerEntry entry) {
        entry.setLastConnectedMs(System.currentTimeMillis());
        List<ServerEntry> list = loadAll();

        boolean found = false;
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).getId().equals(entry.getId())) {
                list.set(i, entry);
                found = true;
                break;
            }
        }
        if (!found) {
            list.add(entry);
        }

        persist(list);
    }

    /**
     * Returns all entries sorted newest-first.
     */
    public List<ServerEntry> getAll() {
        List<ServerEntry> list = loadAll();
        Collections.sort(list,
                (a, b) -> Long.compare(b.getLastConnectedMs(), a.getLastConnectedMs()));
        return list;
    }

    /** Returns the entry with the given id, or null if not found. */
    public ServerEntry findById(String id) {
        for (ServerEntry e : loadAll()) {
            if (e.getId().equals(id)) return e;
        }
        return null;
    }

    // -------------------------------------------------------------------------

    private List<ServerEntry> loadAll() {
        List<ServerEntry> list = new ArrayList<>();
        String json = prefs.getString(KEY_SERVERS, "[]");
        try {
            JSONArray arr = new JSONArray(json);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject obj = arr.getJSONObject(i);
                list.add(new ServerEntry(
                        obj.getString(F_ID),
                        obj.optString(F_NICKNAME, ""),
                        obj.getString(F_IP),
                        obj.getInt(F_PORT),
                        obj.getLong(F_LAST_CONNECTED)
                ));
            }
        } catch (JSONException e) {
            // Corrupted prefs — start fresh
        }
        return list;
    }

    private void persist(List<ServerEntry> list) {
        JSONArray arr = new JSONArray();
        for (ServerEntry e : list) {
            try {
                JSONObject obj = new JSONObject();
                obj.put(F_ID,             e.getId());
                obj.put(F_NICKNAME,       e.getNickname());
                obj.put(F_IP,             e.getIp());
                obj.put(F_PORT,           e.getPort());
                obj.put(F_LAST_CONNECTED, e.getLastConnectedMs());
                arr.put(obj);
            } catch (JSONException ignored) {}
        }
        prefs.edit().putString(KEY_SERVERS, arr.toString()).apply();
    }
}
