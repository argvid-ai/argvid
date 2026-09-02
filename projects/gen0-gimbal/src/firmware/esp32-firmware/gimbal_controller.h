/**
 * gimbal_controller.h —— 云台双轴控制逻辑
 * 移植自 Python web_app.py 的 GIMBAL 状态与 _send_axis()/jog/move/center/zero
 * 关键行为：jog 用速度模式(0)，move 用单圈绝对位置T型(2)；
 *          模式切换时先 set_mode 再 enable，避免重复发模式帧；
 *          负角度自动转换（协议仅收 [0,360)）。
 */
#pragma once
#include <Arduino.h>
#include "f32c_protocol.h"

class GimbalController {
public:
    void begin(F32CMotor* motor) { _motor = motor; }

    // 配置两轴电机 ID（0 = 未配置）
    void config(uint8_t pan_id, uint8_t tilt_id);
    bool ready() const { return _panAddr != 0 && _tiltAddr != 0; }
    uint8_t panAddr() const { return _panAddr; }
    uint8_t tiltAddr() const { return _tiltAddr; }
    float panAngle() const { return _panAngle; }
    float tiltAngle() const { return _tiltAngle; }

    // 扫描到 >=2 台时自动配置（已手动配置过则不覆盖）
    void autoConfig(const MotorInfo* motors, size_t count);

    // 点动：axis "pan"/"tilt"，dir 1/-1/0（0=停止），speed RPM
    MotorResponse jog(const String& axis, int8_t dir, uint16_t speed);

    // 位置随动：pan ∈ [-180,180]，tilt ∈ [-90,90]（nan 表示该轴不变）
    MotorResponse move(float pan, float tilt, bool hasPan, bool hasTilt);

    // 双轴回中 0°
    MotorResponse center();

    // 双轴当前位置设为单圈 0°（需再 saveParams 才掉电保存）
    MotorResponse zero();

    // 状态 JSON：{"event":"gimbal_state","pan":2,"tilt":3,"pan_angle":30.0,"tilt_angle":-45.0}
    String stateJson();

private:
    F32CMotor* _motor = nullptr;
    uint8_t _panAddr = 0;
    uint8_t _tiltAddr = 0;
    int8_t  _panMode = -1;    // 模式缓存：-1=未知, 0=速度, 2=单圈T型
    int8_t  _tiltMode = -1;
    float   _panAngle = 0.0; // 最近一次下发的目标角（±180 表示法）
    float   _tiltAngle = 0.0; // 最近一次下发的目标角（±90 表示法）

    MotorResponse _sendAxis(bool isPan, uint8_t func, const uint8_t* data, size_t len);
    MotorResponse _axisSetMode(bool isPan, uint8_t mode);   // 带模式缓存
    MotorResponse _axisEnable(bool isPan);
    MotorResponse _axisSetSpeed(bool isPan, int16_t rpm);
    MotorResponse _axisSetSingleAngle(bool isPan, float degree);
    MotorResponse _axisSetSingleZero(bool isPan);
};
