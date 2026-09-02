/**
 * f32c_protocol.h —— F32C 无刷电机串口协议层（C++ 移植版）
 * 移植自 Python f32c_protocol.py，帧格式与功能码完全一致：
 *   [0x7A][addr][func][data...][BCC][0x7B]，BCC = 从帧头到最后一字节数据逐字节异或
 */
#pragma once
#include <Arduino.h>

// 反馈帧解析结果
struct MotorResponse {
    bool    valid = false;      // 是否为合法有效帧
    bool    bcc_ok = false;     // BCC 校验是否通过
    uint8_t type_code = 0;      // 反馈类型码
    int32_t value = 0;          // 反馈数值（大端 int32）
    String  parsed_text;        // 可读文本
};

// 扫描到的电机信息
struct MotorInfo {
    uint8_t addr;
    float   volt;    // V
};

// TX/RX 日志回调：dir="TX"/"RX"，hex 为十六进制字符串
typedef void (*SerialLogCallback)(const char* dir, const String& hex);

class F32CMotor {
public:
    // 功能码（与手册 4.3 节对应）
    static const uint8_t FC_ENABLE          = 0x06;
    static const uint8_t FC_DISABLE         = 0x05;
    static const uint8_t FC_SET_MODE        = 0x00;
    static const uint8_t FC_SET_SPEED       = 0x01;
    static const uint8_t FC_SET_MULTI_ANGLE  = 0x02;
    static const uint8_t FC_SET_SINGLE_ANGLE = 0x03;
    static const uint8_t FC_SET_ACCEL        = 0x07;
    static const uint8_t FC_SAVE_PARAMS      = 0x08;
    static const uint8_t FC_CLEAR_ANGLE      = 0x09;
    static const uint8_t FC_SET_SINGLE_ZERO  = 0x0A;
    static const uint8_t FC_FACTORY_RESET    = 0x0B;
    static const uint8_t FC_SET_ADDR         = 0x0D;
    static const uint8_t FC_QUERY            = 0x0E;
    static const uint8_t FC_SPEED_KP         = 0x0F;
    static const uint8_t FC_SPEED_KI         = 0x10;
    static const uint8_t FC_POS_KP           = 0x11;
    static const uint8_t FC_POS_KI           = 0x12;

    // 反馈类型码（FC_QUERY 的数据位）
    static const uint8_t RT_SPEED        = 0x00;  // 当前转速
    static const uint8_t RT_TOTAL_ANGLE  = 0x01;  // 转过总角度
    static const uint8_t RT_MECH_ANGLE  = 0x02;  // 单圈机械角度
    static const uint8_t RT_ACCEL       = 0x03;  // 加速度
    static const uint8_t RT_VOLTAGE     = 0x04;  // 母线电压

    void begin(HardwareSerial* serial, uint8_t addr = 0x02);
    void setAddr(uint8_t addr) { _addr = addr; }
    uint8_t addr() const { return _addr; }

    // TX/RX 日志回调（供 BLE 日志推送）
    void setLogCallback(SerialLogCallback cb) { _logCb = cb; }

    // ---- 帧构建/发送/接收 ----
    void sendFrame(uint8_t func, const uint8_t* data = nullptr, size_t len = 0);
    MotorResponse readResponse(uint32_t timeout_ms = 500);

    // ---- 高层命令（与 Python 接口一一对应） ----
    MotorResponse enable();
    MotorResponse disable();
    MotorResponse setMode(uint8_t mode);
    MotorResponse setSpeed(int16_t rpm);
    MotorResponse setMultiAngle(float degree);
    MotorResponse setSingleAngle(float degree);   // 0~359.9
    MotorResponse setAccel(uint16_t accel_rps2);
    MotorResponse query(uint8_t type_code);
    MotorResponse saveParams();
    MotorResponse clearTotalAngle();
    MotorResponse setSingleZero();
    MotorResponse factoryReset();
    MotorResponse setDeviceAddress(uint8_t new_addr);
    MotorResponse setSpeedKp(uint16_t v);
    MotorResponse setSpeedKi(uint16_t v);
    MotorResponse setPosKp(uint16_t v);
    MotorResponse setPosKi(uint16_t v);

    // ---- 总线扫描：返回在线电机列表（阻塞约 2 秒，需在主循环调用） ----
    size_t scanBus(uint8_t start, uint8_t end, MotorInfo* out, size_t out_max,
                   uint32_t per_motor_timeout_ms = 120);

    // ---- 一键联通测试（电压/机械角/转速） ----
    bool connectivityTest(String& report);

private:
    HardwareSerial* _serial = nullptr;
    uint8_t   _addr = 0x02;
    SerialLogCallback _logCb = nullptr;

    MotorResponse _doCmd(uint8_t func, const uint8_t* data = nullptr, size_t len = 0,
                         bool expect_response = true);
    uint8_t _calcBcc(const uint8_t* data, size_t len);
    String  _toHex(const uint8_t* data, size_t len);
    MotorResponse _parseResponse(const uint8_t* raw, size_t len);
    String  _formatFeedback(uint8_t type_code, int32_t value);
};
