/**
 * config.h —— 引脚与常量定义
 * 智能手机云台 ESP32-S3 固件
 */
#pragma once

// ==================== 串口（连接 F32C 电机总线） ====================
#define MOTOR_SERIAL     Serial2
#define MOTOR_TX_PIN     17      // ESP32 TX2 -> 电机 RX（3.3V TTL）
#define MOTOR_RX_PIN     18      // ESP32 RX2 <- 电机 TX（3.3V TTL）
#define MOTOR_BAUD       115200

// ==================== BLE ====================
#define BLE_DEVICE_NAME  "F32C-Gimbal"
#define BLE_SERVICE_UUID "0000ff00-0000-1000-8000-00805f9b34fb"
#define BLE_CHAR_WIFI    "0000ff01-0000-1000-8000-00805f9b34fb"   // Write  APP->ESP32 WiFi 配置
#define BLE_CHAR_STATUS  "0000ff02-0000-1000-8000-00805f9b34fb"   // Read+Notify 系统状态
#define BLE_CHAR_CMD     "0000ff03-0000-1000-8000-00805f9b34fb"   // Write  APP->ESP32 电机命令
#define BLE_CHAR_RESP    "0000ff04-0000-1000-8000-00805f9b34fb"   // Notify ESP32->APP 响应/日志
#define BLE_MTU_SIZE     247     // 连接后协商 MTU，容纳 200B JSON

// ==================== F32C 协议常量（与 Python f32c_protocol.py 对齐） ====================
#define FRAME_HEADER     0x7A
#define FRAME_TAIL       0x7B
#define DEFAULT_ADDR     0x02
#define SCAN_START_ADDR  1       // 总线扫描起始地址
#define SCAN_END_ADDR    16      // 总线扫描结束地址
#define RESP_TIMEOUT_MS  500     // 串口响应超时
#define SCAN_TIMEOUT_MS  120     // 扫描时单地址超时
#define FRAME_GAP_MS     3       // 协议要求帧间至少 1ms，取 3ms

// ==================== 默认云台配置 ====================
#define DEFAULT_PAN_ID   2       // 水平轴电机 ID
#define DEFAULT_TILT_ID  3       // 垂直轴电机 ID
