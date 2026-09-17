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

MotorResponse GimbalController::_axisSetMultiAngle(bool isPan, float degree, bool wait_response) {
    _motor->setAddr(isPan ? _panAddr : _tiltAddr);
    return _motor->setMultiAngle(degree, wait_response);
}

MotorResponse GimbalController::_axisClearTotal(bool isPan) {
    _motor->setAddr(isPan ? _panAddr : _tiltAddr);
    return _motor->clearTotalAngle();
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
        // P1-5：模式切换/使能失败时立即返回失败且不写缓存（下次重试），
        // 不得掩盖失败后仍把命令标记为成功
        MotorResponse rm = _axisSetMode(isPan, 0);
        if (!rm.valid) return rm;
        mode = 0;   // 仅在 setMode 成功后缓存（模式确已切换）
        MotorResponse re = _axisEnable(isPan);
        if (!re.valid) return re;
    }
    int16_t target = (int16_t)((int)dir * (int)speed);
    return _axisSetSpeed(isPan, target);
}

// ---------------- 位置随动（多圈位置 T 型规划，相对原点带符号角度） ----------------
// 单圈模式在 0/360 过零附近会绕远路，故改用多圈模式：
// APP 维护相对原点的目标角（原点 = origin() 执行时刻的位置），带符号直发。
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
        if (_panMode != 1) {
            // P1-5：切模式/使能失败即返回失败且不写缓存
            MotorResponse rm = _axisSetMode(true, 1);
            if (!rm.valid) {
                r.valid = false;
                r.parsed_text = "pan 切位置模式失败: " + rm.parsed_text;
                return r;
            }
            MotorResponse re = _axisEnable(true);
            if (!re.valid) {
                r.valid = false;
                r.parsed_text = "pan 使能失败: " + re.parsed_text;
                return r;
            }
            _panMode = 1;
        }
        // 不等回帧：pan 帧发出后立即发 tilt，保证两轴同时起步
        MotorResponse rp = _axisSetMultiAngle(true, pan, false);
        if (rp.valid) _panAngle = pan;
        r.valid = r.valid && rp.valid;
        r.parsed_text += "pan " + String(pan, 1) + "° " + (rp.valid ? "OK" : ("✗ " + rp.parsed_text)) + " ";
    }
    if (hasTilt) {
        if (tilt > 90.0f)  tilt = 90.0f;
        if (tilt < -90.0f) tilt = -90.0f;
        if (_tiltMode != 1) {
            // P1-5：切模式/使能失败即返回失败且不写缓存
            MotorResponse rm = _axisSetMode(false, 1);
            if (!rm.valid) {
                r.valid = false;
                r.parsed_text = "tilt 切位置模式失败: " + rm.parsed_text;
                return r;
            }
            MotorResponse re = _axisEnable(false);
            if (!re.valid) {
                r.valid = false;
                r.parsed_text = "tilt 使能失败: " + re.parsed_text;
                return r;
            }
            _tiltMode = 1;
        }
        MotorResponse rt = _axisSetMultiAngle(false, tilt, false);
        if (rt.valid) _tiltAngle = tilt;
        r.valid = r.valid && rt.valid;
        r.parsed_text += "tilt " + String(tilt, 1) + "° " + (rt.valid ? "OK" : ("✗ " + rt.parsed_text));
    }
    if (r.parsed_text.length() == 0) r.parsed_text = "无参数";
    return r;
}

// ---------------- 双轴回中（原点 0°） ----------------
MotorResponse GimbalController::center() {
    return move(0.0f, 0.0f, true, true);
}

// ---------------- 双轴当前位置记为多圈原点 ----------------
MotorResponse GimbalController::origin() {
    MotorResponse r;
    if (!ready()) {
        r.parsed_text = "云台未配置：请先扫描电机或 gimbal_config";
        return r;
    }
    MotorResponse r1 = _axisClearTotal(true);
    MotorResponse r2 = _axisClearTotal(false);
    if (r1.valid && r2.valid) {
        _panAngle = 0.0f;
        _tiltAngle = 0.0f;
        r.valid = true;
        r.parsed_text = "两轴当前位置已记为原点（0°），后续位置控制以此为基准";
    } else {
        r.valid = false;
        r.parsed_text = "设置原点失败: pan=" + r1.parsed_text + " tilt=" + r2.parsed_text;
    }
    return r;
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

// ---------------- 失联停机（P1-1） ----------------
MotorResponse GimbalController::emergencyStop() {
    MotorResponse r;
    if (!ready()) {
        r.parsed_text = "云台未配置，失联停机无需动作";
        return r;
    }
    // fail-safe 策略：双轴强制速度模式 + 0 RPM——保持力矩锁定当前位置，
    // 避免 disable（失能）导致垂直轴因重力垂头。切模式失败重试 1 次。
    // ⚠️ 该停机机制的正确性需真机 HIL 验证（见 docs/README.md 安全边界节）
    bool ok = true;
    String detail = "";
    for (uint8_t i = 0; i < 2; i++) {
        bool isPan = (i == 0);
        MotorResponse rm = _axisSetMode(isPan, 0);
        if (!rm.valid) rm = _axisSetMode(isPan, 0);   // 重试一次
        if (!rm.valid) {
            ok = false;
            detail += String(isPan ? "pan" : "tilt") + " 切模式失败 ";
            continue;
        }
        _axisEnable(isPan);
        MotorResponse rs = _axisSetSpeed(isPan, 0);
        if (!rs.valid) {
            ok = false;
            detail += String(isPan ? "pan" : "tilt") + " 速度0下发失败 ";
        }
        (isPan ? _panMode : _tiltMode) = 0;   // 缓存与实际一致（速度模式）
    }
    r.valid = ok;
    r.parsed_text = detail + "失联停机：双轴速度模式 0 RPM（保持力矩锁定）";
    return r;
}

// ---------------- 模式缓存失效（P1-5） ----------------
void GimbalController::invalidateAxis(uint8_t addr) {
    if (_panAddr != 0 && addr == _panAddr)  _panMode = -1;
    if (_tiltAddr != 0 && addr == _tiltAddr) _tiltMode = -1;
}

// ---------------- 云台轴角度限位检查（P1-3） ----------------
const char* GimbalController::checkAngleLimit(uint8_t addr, float angle, bool multiTurn) const {
    if (_tiltAddr != 0 && addr == _tiltAddr) {
        if (multiTurn) {
            // 多圈模式：angle 为相对原点的带符号角，直接校验 ±90°（显式报错，不静默降级）
            if (angle > 90.0f || angle < -90.0f) {
                return "tilt 轴限位 ±90°：拒绝越界目标角（显式报错，不做静默裁剪）";
            }
        } else {
            // 单圈 [0,360) 折算 ±180 表示法后校验 ±90°
            float a = (angle > 180.0f) ? (angle - 360.0f) : angle;
            if (a > 90.0f || a < -90.0f) {
                return "tilt 轴限位 ±90°：拒绝越界目标角（显式报错，不做静默裁剪）";
            }
        }
    }
    // pan 轴 ±180° 覆盖全范围（无限位）；非云台轴的电机不受云台限位约束
    return nullptr;
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
