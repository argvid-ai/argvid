/**
 * cmd_handler.h —— BLE JSON 命令路由
 * 解析 APP 写入的命令 JSON → 调用 F32CMotor / GimbalController / WifiManager
 * → 通过 BLE Notify 推送结果（严格按方案第四章协议契约）
 */
#pragma once
#include <Arduino.h>
#include "f32c_protocol.h"
#include "gimbal_controller.h"
#include "wifi_manager.h"
#include "ble_service.h"

#define LOG_RING_SIZE 40      // 串口 TX/RX 日志环形缓冲行数（扫描时 16 地址 × 2 帧 = 32 行）

class CmdHandler {
public:
    void begin(F32CMotor* motor, GimbalController* gimbal,
               WifiManager* wifi, BleServiceManager* ble);

    // 主循环：处理队列中的命令
    void processQueue();

    // 日志汇聚（F32CMotor 回调写入，主循环 flush 为一条 Notify）
    void collectLog(const char* dir, const String& hex);
    void flushLogs();

    // 推送系统状态汇总（FF02）
    void pushSystemStatus();

private:
    F32CMotor*         _motor = nullptr;
    GimbalController* _gimbal = nullptr;
    WifiManager*       _wifi = nullptr;
    BleServiceManager* _ble = nullptr;

    // ---- 日志环形缓冲 ----
    String  _logLines[LOG_RING_SIZE];
    uint8_t _logHead = 0, _logCount = 0;
    uint32_t _lastLogFlush = 0;
    uint16_t _droppedLogs = 0;

    // ---- 命令处理 ----
    void _handleMotorCmd(const String& json);
    void _handleWifiCmd(const String& json);

    void _notifyResult(bool ok, const String& msg);
    void _notifyError(const String& msg);
    void _notifyQueryResult(uint8_t addr, const char* type, float value, const String& text);
    void _notifyScanResult(MotorInfo* motors, size_t count, bool ok);
    void _notifyGimbalState();

    // 查询类型名 → 反馈类型码（voltage/speed/total_angle/mech_angle/accel）
    static bool _queryTypeCode(const String& type, uint8_t& code);
};

// 全局单例入口（供 F32CMotor 日志回调）
extern CmdHandler cmdHandler;
