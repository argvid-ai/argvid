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

    // 点动：axis "pan"/"tilt"，dir 1/-1/0（0=停止），speed RPM（速度模式；App 已改用位置步进，保留作调试接口）
    MotorResponse jog(const String& axis, int8_t dir, uint16_t speed);

    // 位置随动（多圈位置 T 型规划，相对原点带符号角度）：
    // pan ∈ [-180,180]，tilt ∈ [-90,90]。单圈模式在 0/360 过零附近会绕远路，
    // 故用多圈模式；原点 = origin() 执行时刻的位置。
    MotorResponse move(float pan, float tilt, bool hasPan, bool hasTilt);

    // 双轴回中 0°
    MotorResponse center();

    // 双轴当前位置记为多圈原点（清多圈总角度，位置控制以此为基准）
    MotorResponse origin();

    // 双轴当前位置设为单圈 0°（需再 saveParams 才掉电保存）
    MotorResponse zero();

    // P1-1 失联停机（fail-safe）：双轴强制速度模式 + 0 RPM（保持力矩锁定）。
    // 只能在主任务调用（内部有串口阻塞）。停机机制的正确性需真机 HIL 验证。
    MotorResponse emergencyStop();

    // P1-5 模式缓存失效：外部（电机控制台）对某电机 set_mode/disable/
    // factory_reset/setaddr 成功后调用；addr 命中云台轴时使该轴缓存失效
    void invalidateAxis(uint8_t addr);

    // P1-3 云台轴角度限位检查：返回 nullptr=允许；非空=拒绝原因。
    // tilt 轴（限位 ±90°）：单圈目标角折算 ±180 表示法校验；多圈命令按相对
    // 原点的带符号角度校验（多圈位置模式下 tilt 也受限位约束）。pan 轴
    // ±180° 覆盖全范围、非云台轴不受限。
    const char* checkAngleLimit(uint8_t addr, float angle, bool multiTurn) const;

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
    MotorResponse _axisSetMultiAngle(bool isPan, float degree, bool wait_response);
    MotorResponse _axisClearTotal(bool isPan);
    MotorResponse _axisSetSingleAngle(bool isPan, float degree);
    MotorResponse _axisSetSingleZero(bool isPan);
};
