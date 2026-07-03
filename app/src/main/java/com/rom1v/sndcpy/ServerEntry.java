package com.rom1v.sndcpy;

/**
 * Represents a saved server connection entry in the history list.
 * The {@code id} is a UUID assigned once on first save and never changes,
 * allowing updates to nickname/ip/port without creating duplicate entries.
 */
public class ServerEntry {

    private String id;        // UUID — stable, never reassigned
    private String nickname;  // User-visible label (optional, defaults to a random 5-digit number)
    private String ip;
    private int port;
    private long lastConnectedMs; // epoch ms — used for ordering newest-first

    public ServerEntry(String id, String nickname, String ip, int port, long lastConnectedMs) {
        this.id = id;
        this.nickname = nickname;
        this.ip = ip;
        this.port = port;
        this.lastConnectedMs = lastConnectedMs;
    }

    public String getId() { return id; }

    public String getNickname() { return nickname; }
    public void setNickname(String nickname) { this.nickname = nickname; }

    public String getIp() { return ip; }
    public void setIp(String ip) { this.ip = ip; }

    public int getPort() { return port; }
    public void setPort(int port) { this.port = port; }

    public long getLastConnectedMs() { return lastConnectedMs; }
    public void setLastConnectedMs(long lastConnectedMs) { this.lastConnectedMs = lastConnectedMs; }
}
