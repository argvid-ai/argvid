/**
 * esp32-firmware.ino —— 智能手机云台 ESP32-S3 固件主入口
 *
 * 架构：手机 APP（Flutter，BLE 中央） ←BLE GATT→ 本固件（协议网关） ←Serial2→ F32C 电机总线
 *
 * 数据流：
 *   1. APP 写 FF03/FF01 → BLE Write 回调（蓝牙栈任务）把 JSON 入队
 *   2. loop()（主任务）出队 → CmdHandler 解析执行（串口阻塞安全）
 *   3. 结果 / TX-RX 日志 / 状态 → FF04/FF02 Notify 推回 APP
 *
 * 接线：
 *   ESP32-S3 GPIO17(TX2) → 电机 RX
 *   ESP32-S3 GPIO18(RX2) → 电机 TX
 *   GND ↔ 电机 GND（必须共地）
 *   电机 V+ → 8~15V 独立电源；ESP32 可独立 USB 供电或从电机电源降压
 */

#include "config.h"
#include "f32c_protocol.h"
#include "gimbal_controller.h"
#include "wifi_manager.h"
#include "ble_service.h"
#include "cmd_handler.h"

F32CMotor         motor;
GimbalController  gimbal;
WifiManager       wifiMgr;
BleServiceManager ble;

// ---- 回调：串口 TX/RX 日志 → 日志汇聚器 ----
void onSerialLog(const char* dir, const String& hex) {
    cmdHandler.collectLog(dir, hex);
}

// ---- 回调：WiFi 状态变化 → BLE 状态特征推送 ----
void onWifiStatus(const String& json) {
    ble.notifyStatus(json);
}

uint32_t lastStatusPush = 0;

void setup() {
    Serial.begin(115200);
    delay(1500);   // ESP32-S3 USB CDC 初始化（沿用之前项目的经验值）
    Serial.println("\n[F32C-Gimbal] ESP32-S3 booting...");

    // 电机串口（Serial2 引脚映射，3.3V TTL 直连）
    MOTOR_SERIAL.begin(MOTOR_BAUD, SERIAL_8N1, MOTOR_RX_PIN, MOTOR_TX_PIN);
    motor.begin(&MOTOR_SERIAL, DEFAULT_ADDR);
    motor.setLogCallback(onSerialLog);

    // 云台（初始未配置，等扫描自动配置或 APP 下发 gimbal_config）
    gimbal.begin(&motor);

    // 命令路由
    cmdHandler.begin(&motor, &gimbal, &wifiMgr, &ble);

    // WiFi（NVS 凭证自动重连）
    wifiMgr.begin();
    wifiMgr.setStatusCallback(onWifiStatus);

    // BLE GATT 服务端（广播名 F32C-Gimbal）
    ble.begin(BLE_DEVICE_NAME);

    Serial.println("[F32C-Gimbal] ready. BLE advertising as '" BLE_DEVICE_NAME "'");
}

void loop() {
    // 1. 处理 APP 命令队列（串口阻塞执行在主任务，安全）
    cmdHandler.processQueue();

    // 2. 串口 TX/RX 日志限速推送
    cmdHandler.flushLogs();

    // 3. WiFi 状态轮询 / 断线重连
    wifiMgr.loop();

    // 4. 周期推送系统状态（含云台角度，APP 可校正 UI）
    if (ble.isConnected() && millis() - lastStatusPush > 5000) {
        lastStatusPush = millis();
        cmdHandler.pushSystemStatus();
    }

    delay(2);   // 让出 CPU 给蓝牙栈
}
