/**
 * ble_service.h —— BLE GATT 服务端
 * 4 个特征：FF01 WiFi配置(Write) / FF02 系统状态(Read+Notify) /
 *           FF03 电机命令(Write) / FF04 响应日志(Notify)
 *
 * 设计要点：Write 回调运行在蓝牙协议栈任务中，禁止阻塞（串口等待/扫描都会卡死蓝牙栈），
 *          回调只把 JSON 命令入队，主循环 loop() 中出队执行，完成后 Notify 回推结果。
 */
#pragma once
#include <Arduino.h>
#include <BLEDevice.h>
#include <BLEServer.h>
#include <BLEUtils.h>
#include <BLE2902.h>
#include "config.h"

// APP 写入的一条命令（队列元素）
struct BleCmdMsg {
    bool isWifi;             // true=FF01 WiFi 配置, false=FF03 电机命令
    char json[240];          // JSON 载荷（MTU 247 下写请求最长 ~240）
};

#define CMD_QUEUE_LEN 12

class BleServiceManager {
public:
    typedef void (*ConnectCallback)(bool connected);

    void begin(const char* deviceName);
    bool isConnected() const { return _connected; }
    void setConnected(bool connected);   // 连接回调中调用

    // FF04 推送命令结果 / 日志 / 错误（主循环调用）
    void notifyResponse(const String& json);
    // FF02 推送系统状态（WiFi/云台等）（主循环调用）
    void notifyStatus(const String& json);
    void updateStatusRead(const String& json) { _statusReadValue = json; }

    // 队列：主循环出队执行
    bool popCommand(BleCmdMsg& msg);
    size_t pendingCommands() const;

    // 内部使用（供 Write 回调入队）
    void _queueCmdPublic(bool isWifi, const String& json);

    void setConnectCallback(ConnectCallback cb) { _connectCb = cb; }

private:
    BLEServer*         _server = nullptr;
    BLECharacteristic* _charWifi = nullptr;
    BLECharacteristic* _charStatus = nullptr;
    BLECharacteristic* _charCmd = nullptr;
    BLECharacteristic* _charResp = nullptr;
    bool _connected = false;
    ConnectCallback _connectCb = nullptr;
    String _statusReadValue = "{}";

    void _queueCmd(bool isWifi, const String& json);
};
