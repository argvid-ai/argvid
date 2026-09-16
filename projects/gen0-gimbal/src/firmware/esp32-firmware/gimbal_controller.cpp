/**
 * gimbal_controller.cpp —— 云台双轴多圈位置控制与实测位置保护
 */
#include "gimbal_controller.h"

void GimbalController::config(uint8_t pan_id, uint8_t tilt_id) {
    _panAddr = pan_id;
    _tiltAddr = tilt_id;
    _panMode = -1;
    _tiltMode = -1;
}

void GimbalController::rememberSpeed(uint8_t addr, int16_t rpm) {
    // 位置模式速度只接受非负上限；速度模式的负向点动不应污染它。
    if (rpm < 0) return;
    if (_panAddr != 0 && addr == _panAddr) _panSpeed = rpm;
    if (_tiltAddr != 0 && addr == _tiltAddr) _tiltSpeed = rpm;
}

void GimbalController::invalidateAllModes() {
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
MotorResponse GimbalController::move(float pan, float tilt, bool hasPan, bool hasTilt, int16_t position_speed) {
    MotorResponse r;
    r.valid = true;
    r.parsed_text = "";

    if (!ready()) {
        r.valid = false;
        r.parsed_text = "云台未配置：请先扫描电机或 gimbal_config";
        return r;
    }
    if ((hasPan && !isfinite(pan)) || (hasTilt && !isfinite(tilt))) {
        r.valid = false;
        r.parsed_text = "位置目标必须是有限角度";
        return r;
    }
    if (pan > 180.0f) pan = 180.0f;
    if (pan < -180.0f) pan = -180.0f;
    if (tilt > 90.0f) tilt = 90.0f;
    if (tilt < -90.0f) tilt = -90.0f;
    float currentPan = 0.0f;
    MotorResponse safe = positionSafety(hasPan, hasTilt, &currentPan);
    if (!safe.valid) return safe;
    if (position_speed >= 0) {
        if (position_speed > 300) position_speed = 300;
        if (hasPan) _panSpeed = position_speed;
        if (hasTilt) _tiltSpeed = position_speed;
    }
    // Derive the nearest equivalent from this request's measured position.
    // No turn offset survives a motor-only reboot.
    const float panTarget = pan + roundf((currentPan - pan) / 360.0f) * 360.0f;

    if (hasPan) {
        if (pan > 180.0f)  pan = 180.0f;
        if (pan < -180.0f) pan = -180.0f;
        {
            // The motor can reboot independently of the gateway. Re-prepare on
            // every explicit position transaction; there is no F32C mode/status
            // readback with which to safely trust this cache.
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
            MotorResponse rs = _axisSetSpeed(true, _panSpeed);
            if (!rs.valid) {
                r.valid = false;
                r.parsed_text = "pan 位置速度恢复失败: " + rs.parsed_text;
                return r;
            }
            _panMode = 1;
        }
        // F32C 位置写入没有即时回包；这里仅报告目标帧已发送，
        // 到位状态需通过后续 query 或实机观察确认。
        MotorResponse rp = _axisSetMultiAngle(true, panTarget, false);
        if (rp.valid) _panAngle = pan;
        r.valid = r.valid && rp.valid;
        r.parsed_text += "pan " + String(pan, 1) + "° " + (rp.valid ? "目标已发送（到位未确认）" : ("✗ " + rp.parsed_text)) + " ";
    }
    if (hasTilt) {
        if (tilt > 90.0f)  tilt = 90.0f;
        if (tilt < -90.0f) tilt = -90.0f;
        {
            // See pan: motor-only power cycles are not observable by ESP32.
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
            MotorResponse rs = _axisSetSpeed(false, _tiltSpeed);
            if (!rs.valid) {
                r.valid = false;
                r.parsed_text = "tilt 位置速度恢复失败: " + rs.parsed_text;
                return r;
            }
            _tiltMode = 1;
        }
        MotorResponse rt = _axisSetMultiAngle(false, tilt, false);
        if (rt.valid) _tiltAngle = tilt;
        r.valid = r.valid && rt.valid;
        r.parsed_text += "tilt " + String(tilt, 1) + "° " + (rt.valid ? "目标已发送（到位未确认）" : ("✗ " + rt.parsed_text));
    }
    if (r.parsed_text.length() == 0) r.parsed_text = "无参数";
    return r;
}

MotorResponse GimbalController::positionSafety(bool hasPan, bool hasTilt, float* panDegrees) {
    MotorResponse r; r.valid = true;
    if (!ready()) { r.valid = false; r.parsed_text = "云台未配置"; return r; }
    if (hasPan) {
        _motor->setAddr(_panAddr);
        MotorResponse q = _motor->query(F32CMotor::RT_TOTAL_ANGLE);
        if (!q.valid || q.type_code != F32CMotor::RT_TOTAL_ANGLE) {
            emergencyStop();
            r.valid = false; r.parsed_text = "水平轴实测角度读取失败，已停机"; return r;
        }
        if (panDegrees) *panDegrees = q.value / 10.0f;
    }
    if (hasTilt) {
        _motor->setAddr(_tiltAddr);
        MotorResponse q = _motor->query(F32CMotor::RT_TOTAL_ANGLE);
        if (!q.valid || q.type_code != F32CMotor::RT_TOTAL_ANGLE || q.value > 900 || q.value < -900) {
            emergencyStop();
            r.valid = false; r.parsed_text = "垂直轴实测越过 ±90° 或角度读取失败，已停机"; return r;
        }
    }
    return r;
}

// ---------------- 双轴回中（原点 0°） ----------------
MotorResponse GimbalController::center(int16_t position_speed) {
    // The ordinary position path already reads both axes and plans the nearest
    // equivalent pan zero. Tilt keeps its bounded multi-turn coordinate.
    return move(0.0f, 0.0f, true, true, position_speed);
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
        r.parsed_text = "两轴设零命令已发送（执行未由反馈确认），建议再执行 save 命令永久写入";
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
    // fail-safe 策略：双轴下发速度模式 + 0 RPM，避免 disable（失能）导致
    // 垂直轴因重力垂头。写入没有即时电机 ACK；切模式失败重试 1 次，
    // 保持力矩与停机时延仍需查询或真机 HIL 验证。
    bool ok = true;
    String detail = "";
    for (uint8_t i = 0; i < 2; i++) {
        bool isPan = (i == 0);
        MotorResponse rm = _axisSetMode(isPan, 0);
        if (!rm.valid) rm = _axisSetMode(isPan, 0);   // 重试一次
        if (!rm.valid) {
            ok = false;
            (isPan ? _panMode : _tiltMode) = -1;
            detail += String(isPan ? "pan" : "tilt") + " 切模式失败 ";
            continue;
        }
        MotorResponse re = _axisEnable(isPan);
        if (!re.valid) {
            ok = false;
            (isPan ? _panMode : _tiltMode) = -1;
            detail += String(isPan ? "pan" : "tilt") + " 使能确认失败 ";
            // F1: enable failure must NOT skip the zero-speed attempt — the motor
            // may already be enabled from a prior session, so setSpeed(0) is still
            // the best available stop for this axis.
        }
        MotorResponse rs = _axisSetSpeed(isPan, 0);
        if (!rs.valid) {
            ok = false;
            if ((isPan ? _panMode : _tiltMode) != -1) (isPan ? _panMode : _tiltMode) = -1;
            detail += String(isPan ? "pan" : "tilt") + " 速度0下发失败 ";
            // F1: still no continue — fall through so the axis mode cache stays
            // invalid and the other axis proceeds independently.
        }
        (isPan ? _panMode : _tiltMode) = 0;   // 缓存与实际一致（速度模式）
    }
    r.valid = ok;
    r.parsed_text = detail + "失联停机命令已发送：双轴速度模式 0 RPM（保持力矩未由反馈确认）";
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
