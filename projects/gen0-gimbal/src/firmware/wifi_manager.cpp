/**
 * wifi_manager.cpp —— WiFi 配网实现
 * 流程：APP 蓝牙写入 SSID+密码 → 存 NVS → WiFi.begin → 状态变化 Notify 推送
 * 重启后自动从 NVS 读取凭证重连。
 */
#include "wifi_manager.h"

#define NVS_NS   "gimbal-wifi"
#define NVS_SSID "ssid"
#define NVS_PASS "pass"
#define WIFI_CONNECT_TIMEOUT_MS 20000   // 连接超时：推送失败状态
#define WIFI_PUSH_INTERVAL_MS  5000      // 已连接时周期推送（含 RSSI）
#define WIFI_RECONNECT_MS      10000     // 断线后重试间隔

void WifiManager::begin() {
    _prefs.begin(NVS_NS, true);
    _ssid = _prefs.getString(NVS_SSID, "");
    _pass = _prefs.getString(NVS_PASS, "");
    _prefs.end();

    WiFi.mode(WIFI_STA);
    WiFi.setAutoReconnect(true);

    if (_ssid.length() > 0) {
        _connecting = true;
        _connectStart = millis();
        WiFi.begin(_ssid.c_str(), _pass.c_str());
    }
}

void WifiManager::connect(const String& ssid, const String& pass) {
    if (ssid.length() == 0) return;
    _ssid = ssid;
    _pass = pass;

    // 保存到 NVS（掉电保持）
    _prefs.begin(NVS_NS, false);
    _prefs.putString(NVS_SSID, _ssid);
    _prefs.putString(NVS_PASS, _pass);
    _prefs.end();

    WiFi.disconnect(false, true);
    WiFi.begin(_ssid.c_str(), _pass.c_str());
    _connecting = true;
    _connectStart = millis();

    // 立即推送 connecting 状态
    if (_cb) _cb(_buildStatusJson("connecting"));
}

void WifiManager::clearCredentials() {
    _prefs.begin(NVS_NS, false);
    _prefs.clear();
    _prefs.end();
    _ssid = ""; _pass = "";
    WiFi.disconnect(false, true);
}

void WifiManager::loop() {
    bool now = (WiFi.status() == WL_CONNECTED);

    // 连接中：等待结果
    if (_connecting) {
        if (now) {
            _connecting = false;
        } else if (millis() - _connectStart > WIFI_CONNECT_TIMEOUT_MS) {
            _connecting = false;   // 超时，停止等待（保留凭证，稍后重试）
            _lastReconnectAttempt = millis();
        }
    } else if (!now && _ssid.length() > 0 && millis() - _lastReconnectAttempt > WIFI_RECONNECT_MS) {
        // 断线重连（WiFi.setAutoReconnect 之外的双保险）
        _lastReconnectAttempt = millis();
        WiFi.begin(_ssid.c_str(), _pass.c_str());
    }

    // 状态变化推送 + 周期推送
    if (now != _connected) {
        _connected = now;
        _pushStatus(true);
    } else if (_connected && millis() - _lastPush > WIFI_PUSH_INTERVAL_MS) {
        _pushStatus();
    }
}

String WifiManager::getStatusJson() {
    if (_connected)    return _buildStatusJson("connected");
    if (_connecting)   return _buildStatusJson("connecting");
    return _buildStatusJson("disconnected");
}

void WifiManager::_pushStatus(bool force) {
    (void)force;
    if (!_cb) return;
    _lastPush = millis();
    _cb(getStatusJson());
}

String WifiManager::_buildStatusJson(const char* status) {
    String s = "{\"event\":\"wifi_status\",\"status\":\"";
    s += status;
    s += "\"";
    if (_connected) {
        s += ",\"ip\":\"" + WiFi.localIP().toString() + "\"";
        s += ",\"ssid\":\"" + _ssid + "\"";
        s += ",\"rssi\":" + String(WiFi.RSSI());
    } else if (_ssid.length() > 0) {
        s += ",\"ssid\":\"" + _ssid + "\"";
    }
    s += "}";
    return s;
}
