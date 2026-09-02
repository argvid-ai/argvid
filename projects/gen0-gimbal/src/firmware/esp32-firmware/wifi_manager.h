/**
 * wifi_manager.h —— WiFi 配网（NVS 存储 + 自动重连 + 状态推送）
 */
#pragma once
#include <Arduino.h>
#include <WiFi.h>
#include <Preferences.h>

// WiFi 状态推送回调：json 形如 {"event":"wifi_status","status":"connected","ip":"...","rssi":-52}
typedef void (*WifiStatusCallback)(const String& json);

class WifiManager {
public:
    void begin();                                  // 从 NVS 读凭证尝试自动连接
    void connect(const String& ssid, const String& pass);  // 保存到 NVS + 连接
    void clearCredentials();                       // 删除保存的凭证
    void loop();                                   // 主循环轮询：状态变化推送、断线重连
    void setStatusCallback(WifiStatusCallback cb) { _cb = cb; }

    bool isConnected() const { return _connected; }
    String getStatusJson();                        // 当前状态 JSON

private:
    Preferences _prefs;
    WifiStatusCallback _cb = nullptr;
    bool     _connected = false;
    bool     _connecting = false;
    uint32_t _connectStart = 0;
    String   _ssid, _pass;
    uint32_t _lastReconnectAttempt = 0;
    uint32_t _lastPush = 0;

    void _pushStatus(bool force = false);
    String _buildStatusJson(const char* status);
};
