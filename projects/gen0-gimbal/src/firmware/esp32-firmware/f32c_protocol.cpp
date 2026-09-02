/**
 * f32c_protocol.cpp —— F32C 协议层实现（移植自 Python f32c_protocol.py）
 */
#include "f32c_protocol.h"
#include "config.h"

void F32CMotor::begin(HardwareSerial* serial, uint8_t addr) {
    _serial = serial;
    _addr = addr;
}

uint8_t F32CMotor::_calcBcc(const uint8_t* data, size_t len) {
    uint8_t bcc = 0;
    for (size_t i = 0; i < len; i++) bcc ^= data[i];
    return bcc;
}

String F32CMotor::_toHex(const uint8_t* data, size_t len) {
    String s;
    s.reserve(len * 3);
    for (size_t i = 0; i < len; i++) {
        if (i) s += ' ';
        if (data[i] < 0x10) s += '0';
        s += String(data[i], HEX);
    }
    s.toUpperCase();
    return s;
}

// ---------------- 发送一帧 ----------------
void F32CMotor::sendFrame(uint8_t func, const uint8_t* data, size_t len) {
    if (!_serial) return;
    uint8_t frame[4 + 16];  // 头+地址+功能码+数据+BCC+尾，数据最长 4
    if (len > 16) len = 16;

    size_t n = 0;
    frame[n++] = FRAME_HEADER;
    frame[n++] = _addr;
    frame[n++] = func;
    for (size_t i = 0; i < len; i++) frame[n++] = data[i];
    frame[n++] = _calcBcc(frame, n);   // BCC：从帧头到最后一字节数据
    frame[n++] = FRAME_TAIL;

    // 清空接收缓冲（与 Python _send_frame 行为一致）
    while (_serial->available()) _serial->read();
    _serial->write(frame, n);
    _serial->flush();
    delay(FRAME_GAP_MS);   // 协议要求帧间至少 1ms

    if (_logCb) _logCb("TX", _toHex(frame, n));
}

// ---------------- 接收并解析反馈帧 ----------------
MotorResponse F32CMotor::readResponse(uint32_t timeout_ms) {
    uint8_t buf[32];
    size_t  n = 0;
    bool    tailFound = false;
    uint32_t t0 = millis();

    while (millis() - t0 < timeout_ms) {
        int ch = _serial ? _serial->read() : -1;
        if (ch < 0) {
            if (tailFound) {
                // 收到帧尾后再读 5ms，读空缓冲（与 Python 一致）
                uint32_t t2 = millis();
                while (millis() - t2 < 5) {
                    int more = _serial->read();
                    if (more < 0) break;
                    if (n < sizeof(buf)) buf[n++] = (uint8_t)more;
                    if (n >= sizeof(buf)) break;
                }
                break;
            }
            delay(1);
            continue;
        }
        if (n < sizeof(buf)) buf[n++] = (uint8_t)ch;
        if (ch == FRAME_TAIL) tailFound = true;
    }

    if (_logCb && n > 0) _logCb("RX", _toHex(buf, n));
    return _parseResponse(buf, n);
}

MotorResponse F32CMotor::_parseResponse(const uint8_t* raw, size_t len) {
    MotorResponse r;
    if (len < 5) {
        r.parsed_text = "帧太短 (" + String((int)len) + " 字节)";
        return r;
    }
    if (raw[0] != FRAME_HEADER) {
        r.parsed_text = "帧头错误 期望 7A 实际 " + String(raw[0], HEX);
        return r;
    }
    if (raw[len - 1] != FRAME_TAIL) {
        r.parsed_text = "帧尾错误";
        return r;
    }

    // BCC = 倒数第 2 字节
    uint8_t expected = _calcBcc(raw, len - 2);
    uint8_t actual = raw[len - 2];
    r.bcc_ok = (expected == actual);
    if (!r.bcc_ok) {
        r.parsed_text = "BCC 校验失败: 期望 " + String(expected, HEX) + " 实际 " + String(actual, HEX);
        return r;
    }
    if (raw[1] != _addr) {
        r.valid = true; r.bcc_ok = true;
        r.parsed_text = "地址不匹配 期望 " + String(_addr, HEX) + " 实际 " + String(raw[1], HEX);
        return r;
    }

    r.valid = true;
    r.type_code = raw[2];
    // 大端 int32（与 Python struct.unpack(">i") 一致）
    r.value = 0;
    if (len >= 9) {
        r.value = ((int32_t)raw[3] << 24) | ((int32_t)raw[4] << 16) |
                  ((int32_t)raw[5] << 8)  |  (int32_t)raw[6];
    }
    r.parsed_text = _formatFeedback(r.type_code, r.value);
    return r;
}

String F32CMotor::_formatFeedback(uint8_t type_code, int32_t value) {
    switch (type_code) {
        case RT_SPEED:       return "当前转速: " + String(value) + " RPM";
        case RT_TOTAL_ANGLE: return "转过总角度: " + String(value / 10.0, 1) + " 度";
        case RT_MECH_ANGLE: return "单圈机械角度: " + String(value / 10.0, 1) + " 度";
        case RT_ACCEL:       return "当前加速度: " + String(value) + " 圈/s²";
        case RT_VOLTAGE:     return "母线电压: " + String(value / 100.0, 2) + " V";
        default:             return "未知反馈类型 0x" + String(type_code, HEX) + ", 原始值=" + String(value);
    }
}

MotorResponse F32CMotor::_doCmd(uint8_t func, const uint8_t* data, size_t len,
                                bool expect_response) {
    sendFrame(func, data, len);
    if (expect_response) return readResponse(RESP_TIMEOUT_MS);
    MotorResponse r;
    r.parsed_text = "（不等待响应）";
    return r;
}

// ---------------- 高层命令 ----------------
MotorResponse F32CMotor::enable()             { return _doCmd(FC_ENABLE); }
MotorResponse F32CMotor::disable()            { return _doCmd(FC_DISABLE); }

MotorResponse F32CMotor::setMode(uint8_t mode) {
    uint8_t d[2] = {0x00, mode};
    return _doCmd(FC_SET_MODE, d, 2);
}

MotorResponse F32CMotor::setSpeed(int16_t rpm) {
    uint8_t d[2] = {(uint8_t)(rpm >> 8), (uint8_t)(rpm & 0xFF)};   // 大端 int16
    return _doCmd(FC_SET_SPEED, d, 2);
}

MotorResponse F32CMotor::setMultiAngle(float degree) {
    int32_t v = (int32_t)(degree * 10);
    uint8_t d[4] = {(uint8_t)(v >> 24), (uint8_t)(v >> 16), (uint8_t)(v >> 8), (uint8_t)v};
    return _doCmd(FC_SET_MULTI_ANGLE, d, 4);
}

MotorResponse F32CMotor::setSingleAngle(float degree) {
    if (degree < 0 || degree >= 360) {
        MotorResponse r;
        r.parsed_text = "单圈角度必须在 [0, 360) 范围";
        return r;
    }
    int16_t v = (int16_t)(degree * 10);
    uint8_t d[2] = {(uint8_t)(v >> 8), (uint8_t)(v & 0xFF)};
    return _doCmd(FC_SET_SINGLE_ANGLE, d, 2);
}

MotorResponse F32CMotor::setAccel(uint16_t accel_rps2) {
    uint8_t d[2] = {(uint8_t)(accel_rps2 >> 8), (uint8_t)(accel_rps2 & 0xFF)};
    return _doCmd(FC_SET_ACCEL, d, 2);
}

MotorResponse F32CMotor::query(uint8_t type_code) {
    return _doCmd(FC_QUERY, &type_code, 1);
}

MotorResponse F32CMotor::saveParams()        { return _doCmd(FC_SAVE_PARAMS); }
MotorResponse F32CMotor::clearTotalAngle()  { return _doCmd(FC_CLEAR_ANGLE); }
MotorResponse F32CMotor::setSingleZero()    { return _doCmd(FC_SET_SINGLE_ZERO); }
MotorResponse F32CMotor::factoryReset()     { return _doCmd(FC_FACTORY_RESET); }

MotorResponse F32CMotor::setDeviceAddress(uint8_t new_addr) {
    MotorResponse r;
    if (new_addr < 1 || new_addr > 127) {
        r.parsed_text = "新地址必须在 0x01~0x7F 范围";
        return r;
    }
    return _doCmd(FC_SET_ADDR, &new_addr, 1);
}

MotorResponse F32CMotor::setSpeedKp(uint16_t v) { uint8_t d[2] = {(uint8_t)(v>>8),(uint8_t)v}; return _doCmd(FC_SPEED_KP, d, 2); }
MotorResponse F32CMotor::setSpeedKi(uint16_t v) { uint8_t d[2] = {(uint8_t)(v>>8),(uint8_t)v}; return _doCmd(FC_SPEED_KI, d, 2); }
MotorResponse F32CMotor::setPosKp(uint16_t v)   { uint8_t d[2] = {(uint8_t)(v>>8),(uint8_t)v}; return _doCmd(FC_POS_KP, d, 2); }
MotorResponse F32CMotor::setPosKi(uint16_t v)   { uint8_t d[2] = {(uint8_t)(v>>8),(uint8_t)v}; return _doCmd(FC_POS_KI, d, 2); }

// ---------------- 总线扫描 ----------------
size_t F32CMotor::scanBus(uint8_t start, uint8_t end, MotorInfo* out, size_t out_max,
                          uint32_t per_motor_timeout_ms) {
    size_t found = 0;
    uint8_t origAddr = _addr;
    for (uint8_t a = start; a <= end && found < out_max; a++) {
        _addr = a;
        uint8_t typeCode = RT_VOLTAGE;
        sendFrame(FC_QUERY, &typeCode, 1);
        MotorResponse resp = readResponse(per_motor_timeout_ms);
        if (resp.valid && resp.bcc_ok && resp.type_code == RT_VOLTAGE) {
            out[found].addr = a;
            out[found].volt = resp.value / 100.0;
            found++;
        }
    }
    _addr = origAddr;
    return found;
}

// ---------------- 一键联通测试 ----------------
bool F32CMotor::connectivityTest(String& report) {
    MotorResponse r1 = query(RT_VOLTAGE);
    MotorResponse r2 = query(RT_MECH_ANGLE);
    MotorResponse r3 = query(RT_SPEED);
    bool ok1 = r1.valid, ok2 = r2.valid, ok3 = r3.valid;
    report = "电压: " + (ok1 ? r1.parsed_text : ("失败:" + r1.parsed_text)) + "\n" +
             "机械角: " + (ok2 ? r2.parsed_text : ("失败:" + r2.parsed_text)) + "\n" +
             "转速: " + (ok3 ? r3.parsed_text : ("失败:" + r3.parsed_text)) + "\n";
    if (ok1 && ok2 && ok3)      report += "通信完全正常!";
    else if (ok1)               report += "基本联通（电压通，其他可能超时）";
    else                        report += "通信异常，请检查接线/共地/供电";
    return ok1 && ok2 && ok3;
}
