/**
 * gimbal_controller.cpp —— 云台双轴控制实现（逻辑与 Python web_app.py 完全一致）
 */
#include "gimbal_controller.h"

void GimbalController::config(uint8_t pan_id, uint8_t tilt_id) {
    _panAddr = pan_id;
    _tiltAddr = tilt_id;
    _panMode = -1;
    _tiltMode = -1;
}

void GimbalController::autoConfig(const MotorInfo* motors, size_t count) {
    if (count >= 2 && (_panAddr == 0 || _tiltAddr == 0)) {
        _panAddr = motors[0].addr;
        _tiltAddr = motors[1].addr;
        _panMode = -1;
        _tiltMode = -1;
    }
}

// 各轴命令：先切总线地址再调用电机方法（对应 Python _send_axis）

MotorResponse GimbalController::_axisSetMode(bool isPan, uint8_t mode) {
    _motor->setAddr(isPan ? _panAddr : _tiltAddr);
    return _motor->setMode(mode);
}

MotorResponse GimbalController::_axisEnable(bool isPan) {
    _motor->setAddr(isPan ? _panAddr : _tiltAddr);
    return _motor->enable();
}

MotorResponse GimbalController::_axisSetSpeed(bool isPan, int16_t rpm) {
    _motor->setAddr(isPan ? _panAddr : _tiltAddr);
    return _motor->setSpeed(rpm);
}

MotorResponse GimbalController::_axisSetSingleAngle(bool isPan, float degree) {
    _motor->setAddr(isPan ? _panAddr : _tiltAddr);
    return _motor->setSingleAngle(degree);
}

MotorResponse GimbalController::_axisSetSingleZero(bool isPan) {
    _motor->setAddr(isPan ? _panAddr : _tiltAddr);
    return _motor->setSingleZero();
}

// ---------------- 点动（速度模式，dir=0 停止） ----------------
MotorResponse GimbalController::jog(const String& axis, int8_t dir, uint16_t speed) {
    bool isPan = (axis == "pan");
    if ((isPan && _panAddr == 0) || (!isPan && _tiltAddr == 0)) {
        MotorResponse r;
        r.parsed_text = "云台未配置：请先扫描电机或 gimbal_config";
        return r;
    }
    if (dir < -1) dir = -1;
    if (dir > 1)  dir = 1;
    if (speed > 300) speed = 300;

    int8_t& mode = isPan ? _panMode : _tiltMode;
    if (mode != 0) {
        _axisSetMode(isPan, 0);
        _axisEnable(isPan);
        mode = 0;
    }
    int16_t target = (int16_t)((int)dir * (int)speed);
    return _axisSetSpeed(isPan, target);
}

// ---------------- 位置随动（单圈绝对位置 T 型规划） ----------------
MotorResponse GimbalController::move(float pan, float tilt, bool hasPan, bool hasTilt) {
    MotorResponse r;
    r.valid = true;
    r.parsed_text = "";

    if (!ready()) {
        r.valid = false;
        r.parsed_text = "云台未配置：请先扫描电机或 gimbal_config";
        return r;
    }

    if (hasPan) {
        if (pan > 180.0f)  pan = 180.0f;
        if (pan < -180.0f) pan = -180.0f;
        if (_panMode != 2) {
            _axisSetMode(true, 2);
            _axisEnable(true);
            _panMode = 2;
        }
        // 负角度自动转换：-45° → 315°（协议仅收 [0,360)）
        MotorResponse rp = _axisSetSingleAngle(true, fmodf(pan + 360.0f, 360.0f));
        if (rp.valid) _panAngle = pan;
        r.valid = r.valid && rp.valid;
        r.parsed_text += "pan " + String(pan, 1) + "° " + (rp.valid ? "OK" : ("✗ " + rp.parsed_text)) + " ";
    }
    if (hasTilt) {
        if (tilt > 90.0f)  tilt = 90.0f;
        if (tilt < -90.0f) tilt = -90.0f;
        if (_tiltMode != 2) {
            _axisSetMode(false, 2);
            _axisEnable(false);
            _tiltMode = 2;
        }
        MotorResponse rt = _axisSetSingleAngle(false, fmodf(tilt + 360.0f, 360.0f));
        if (rt.valid) _tiltAngle = tilt;
        r.valid = r.valid && rt.valid;
        r.parsed_text += "tilt " + String(tilt, 1) + "° " + (rt.valid ? "OK" : ("✗ " + rt.parsed_text));
    }
    if (r.parsed_text.length() == 0) r.parsed_text = "无参数";
    return r;
}

// ---------------- 双轴回中 ----------------
MotorResponse GimbalController::center() {
    return move(0.0f, 0.0f, true, true);
}

// ---------------- 双轴设零点 ----------------
MotorResponse GimbalController::zero() {
    MotorResponse r;
    if (!ready()) {
        r.parsed_text = "云台未配置：请先扫描电机或 gimbal_config";
        return r;
    }
    MotorResponse r1 = _axisSetSingleZero(true);
    MotorResponse r2 = _axisSetSingleZero(false);
    if (r1.valid && r2.valid) {
        _panAngle = 0.0f;
        _tiltAngle = 0.0f;
        r.valid = true;
        r.parsed_text = "两轴当前位置已设为 0°，建议再执行 save 命令永久写入";
    } else {
        r.valid = false;
        r.parsed_text = "设零点失败: pan=" + r1.parsed_text + " tilt=" + r2.parsed_text;
    }
    return r;
}

String GimbalController::stateJson() {
    String s = "{\"event\":\"gimbal_state\",\"pan\":";
    s += String(_panAddr);
    s += ",\"tilt\":";
    s += String(_tiltAddr);
    s += ",\"pan_angle\":";
    s += String(_panAngle, 1);
    s += ",\"tilt_angle\":";
    s += String(_tiltAngle, 1);
    s += "}";
    return s;
}
