/**
 * cmd_handler.cpp —— BLE JSON 命令路由实现
 */
#include "cmd_handler.h"
#include <ArduinoJson.h>
#include "config.h"

CmdHandler cmdHandler;

void CmdHandler::begin(F32CMotor* motor, GimbalController* gimbal,
                       WifiManager* wifi, BleServiceManager* ble) {
    _motor = motor;
    _gimbal = gimbal;
    _wifi = wifi;
    _ble = ble;
    _prefsReady = _prefs.begin("gimbal_params", false);
}

String CmdHandler::_paramKey(uint8_t addr, const char* suffix) {
    return String("a") + String(addr) + "_" + suffix;
}

void CmdHandler::_loadPersistedParams(uint8_t addr) {
    if (!_prefsReady || addr < 1 || addr > 127) return;
    if (_paramsLoaded[addr]) return;
    _paramsLoaded[addr] = true;
    MotorParams& p = _params[addr];
    p.speed_kp = _prefs.getInt(_paramKey(addr, "skp").c_str(), -1);
    p.speed_ki = _prefs.getInt(_paramKey(addr, "ski").c_str(), -1);
    p.pos_kp = _prefs.getInt(_paramKey(addr, "pkp").c_str(), -1);
    p.pos_ki = _prefs.getInt(_paramKey(addr, "pki").c_str(), -1);
    p.accel = _prefs.getInt(_paramKey(addr, "acc").c_str(), -1);
    p.speed = _prefs.getInt(_paramKey(addr, "spd").c_str(), -1);
}

void CmdHandler::_persistParams(uint8_t addr) {
    if (!_prefsReady || addr < 1 || addr > 127) return;
    const MotorParams& p = _params[addr];
    if (p.speed_kp >= 0) _prefs.putInt(_paramKey(addr, "skp").c_str(), p.speed_kp);
    if (p.speed_ki >= 0) _prefs.putInt(_paramKey(addr, "ski").c_str(), p.speed_ki);
    if (p.pos_kp >= 0) _prefs.putInt(_paramKey(addr, "pkp").c_str(), p.pos_kp);
    if (p.pos_ki >= 0) _prefs.putInt(_paramKey(addr, "pki").c_str(), p.pos_ki);
    if (p.accel >= 0) _prefs.putInt(_paramKey(addr, "acc").c_str(), p.accel);
    if (p.speed >= 0) _prefs.putInt(_paramKey(addr, "spd").c_str(), p.speed);
}

void CmdHandler::restoreSavedParams(uint8_t addr) {
    if (!_motor || addr < 1 || addr > 127) return;
    _loadPersistedParams(addr);
    if (_params[addr].speed >= 0) {
        _gimbal->rememberSpeed(addr, (int16_t)_params[addr].speed);
    }
    // Loading the gateway snapshot is deliberately side-effect free. A scan or
    // configuration command must never write a non-zero speed to a motor.
}

bool CmdHandler::_applyCachedRuntimeParams(uint8_t addr) {
    if (!_motor || addr < 1 || addr > 127) return true;
    _loadPersistedParams(addr);
    const MotorParams& p = _params[addr];
    if (p.speed_kp < 0 && p.speed_ki < 0 && p.pos_kp < 0 &&
        p.pos_ki < 0 && p.accel < 0 && p.speed < 0) return true;

    _motor->setAddr(addr);
    bool ok = true;
    // Apply controller gains only as part of an explicit position transaction.
    if (p.speed_kp >= 0) ok = _motor->setSpeedKp((uint16_t)p.speed_kp).valid && ok;
    if (p.speed_ki >= 0) ok = _motor->setSpeedKi((uint16_t)p.speed_ki).valid && ok;
    if (p.pos_kp >= 0) ok = _motor->setPosKp((uint16_t)p.pos_kp).valid && ok;
    if (p.pos_ki >= 0) ok = _motor->setPosKi((uint16_t)p.pos_ki).valid && ok;
    if (p.accel >= 0) ok = _motor->setAccel((uint16_t)p.accel).valid && ok;
    return ok;
}

// ==================== 命令队列处理 ====================
void CmdHandler::processQueue() {
    // P1-1：BLE 断连事件 → 主任务执行失联停机（串口阻塞在此安全）
    if (_ble->takeDisconnectEvent()) {
        _handleDisconnect();
    }

    // P1-2：停止旁路优先于队列消费（队列满时停止也不丢）
    bool stopPan, stopTilt;
    if (_ble->takeStopRequest(stopPan, stopTilt)) {
        if (stopPan)  _gimbal->jog("pan", 0, 0);
        if (stopTilt) _gimbal->jog("tilt", 0, 0);
        flushLogs();
    }

    // P1-1：未连接时不再消费任何电机/WiFi 命令
    //（残留队列已在断连回调中清空，此处为兜底防御）
    if (!_ble->isConnected()) {
        BleCmdMsg drop;
        while (_ble->popCommand(drop)) {}
        return;
    }

    const uint32_t rejected = _ble->takeQueueRejected();
    if (rejected > 0) {
        _notifyError("BLE 命令队列已满：" + String(rejected) + " 条命令未执行，请降低发送频率后重试");
    }

    BleCmdMsg msg;
    while (_ble->popCommand(msg)) {
        if (msg.isWifi) {
            _handleWifiCmd(String(msg.json));
        } else {
            _handleMotorCmd(String(msg.json));
        }
        // 每处理一条命令顺带 flush 一次日志，保证准实时
        flushLogs();
    }
}

// ==================== 失联停机（P1-1） ====================
void CmdHandler::_handleDisconnect() {
    // 云台就绪则双轴下发速度模式 0 RPM（目标是避免断连瞬间
    // 残留的 jog 继续驱动电机）。停机机制需真机 HIL 验证。
    MotorResponse r = _gimbal->emergencyStop();
    _notifyError(String("BLE 已断开：") + r.parsed_text);
    flushLogs();
}

// ==================== 电机/云台命令路由（FF03） ====================
void CmdHandler::_handleMotorCmd(const String& json) {
    JsonDocument doc;
    DeserializationError err = deserializeJson(doc, json);
    if (err) {
        _notifyError("JSON 解析失败: " + String(err.c_str()));
        return;
    }
    const char* cmd = doc["cmd"] | "";
    if (strlen(cmd) == 0) {
        _notifyError("缺少 cmd 字段");
        return;
    }

    uint8_t addr = doc["addr"] | DEFAULT_ADDR;
    String cmdStr = cmd;

    // ---------------- 总线扫描 ----------------
    if (cmdStr == "scan") {
        MotorInfo motors[16];
        size_t n = _motor->scanBus(SCAN_START_ADDR, SCAN_END_ADDR, motors, 16, SCAN_TIMEOUT_MS);
        _gimbal->autoConfig(motors, n);   // 扫描到 >=2 台自动配置云台（与 Web 版一致）
        _gimbal->invalidateAllModes();
        for (size_t i = 0; i < n; i++) restoreSavedParams(motors[i].addr);
        _notifyScanResult(motors, n, true);
        if (_gimbal->ready()) _notifyGimbalState();
        return;
    }

    // ---------------- 云台命令 ----------------
    if (cmdStr == "gimbal_config") {
        uint8_t pan = doc["pan"] | 0;
        uint8_t tilt = doc["tilt"] | 0;
        if (pan < 1 || pan > 127 || tilt < 1 || tilt > 127) {
            _notifyResult(false, "电机 ID 必须 1~127");
            return;
        }
        if (pan == tilt) {
            _notifyResult(false, "水平和垂直轴不能使用同一个电机 ID");
            return;
        }
        _gimbal->config(pan, tilt);
        restoreSavedParams(pan);
        restoreSavedParams(tilt);
        _notifyResult(true, "云台已配置: 水平=ID" + String(pan) + ", 垂直=ID" + String(tilt));
        _notifyGimbalState();
        return;
    }
    if (cmdStr == "jog") {
        const char* axis = doc["axis"] | "";
        String axisStr = axis;
        // P1-3：未知轴显式拒绝（此前任意值都被当作 tilt 执行）
        if (axisStr != "pan" && axisStr != "tilt") {
            _notifyResult(false, "未知轴 \"" + axisStr + "\"：仅支持 pan/tilt");
            return;
        }
        int dir = doc["dir"] | 0;
        int speed = doc["speed"] | 60;
        MotorResponse r = _gimbal->jog(axisStr, (int8_t)dir, (uint16_t)speed);
        String msg = axisStr + " ";
        msg += (dir == 0 ? "停止" : (dir > 0 ? "正转 " : "反转 "));
        if (dir != 0) msg += String(speed) + " RPM";
        msg += r.valid ? "" : (" -> " + r.parsed_text);
        _notifyResult(r.valid, msg);
        return;
    }
    if (cmdStr == "move") {
        bool hasPan = !doc["pan"].isNull();
        bool hasTilt = !doc["tilt"].isNull();
        float pan = doc["pan"] | 0.0f;
        float tilt = doc["tilt"] | 0.0f;
        int speed = doc["speed"] | -1;
        if (speed < -1 || speed > 300) { _notifyResult(false, "位置速度必须在 0~300 RPM 范围"); return; }
        MotorResponse safety = _gimbal->positionSafety(hasPan, hasTilt);
        if (!safety.valid) { _notifyResult(false, safety.parsed_text); return; }
        if ((hasPan && !_applyCachedRuntimeParams(_gimbal->panAddr())) ||
            (hasTilt && !_applyCachedRuntimeParams(_gimbal->tiltAddr()))) {
            _notifyResult(false, "恢复位置控制参数失败，已拒绝运动"); return;
        }
        MotorResponse r = _gimbal->move(pan, tilt, hasPan, hasTilt, (int16_t)speed);
        _notifyResult(r.valid, r.parsed_text);
        _notifyGimbalState();
        return;
    }
    if (cmdStr == "center") {
        int speed = doc["speed"] | -1;
        if (speed < -1 || speed > 300) { _notifyResult(false, "位置速度必须在 0~300 RPM 范围"); return; }
        MotorResponse safety = _gimbal->positionSafety(true, true);
        if (!safety.valid) { _notifyResult(false, safety.parsed_text); return; }
        if (!_applyCachedRuntimeParams(_gimbal->panAddr()) || !_applyCachedRuntimeParams(_gimbal->tiltAddr())) {
            _notifyResult(false, "恢复位置控制参数失败，已拒绝回中"); return;
        }
        MotorResponse r = _gimbal->center((int16_t)speed);
        _notifyResult(r.valid, r.valid ? "双轴回中目标已发送（到位未确认）" : r.parsed_text);
        _notifyGimbalState();
        return;
    }
    if (cmdStr == "origin") {
        MotorResponse r = _gimbal->origin();
        _notifyResult(r.valid, r.parsed_text);
        _notifyGimbalState();
        return;
    }
    if (cmdStr == "zero") {
        MotorResponse r = _gimbal->zero();
        _notifyResult(r.valid, r.parsed_text);
        _notifyGimbalState();
        return;
    }

    // ---------------- 单电机命令（均需 addr） ----------------
    if (addr < 1 || addr > 127) { _notifyResult(false, "电机 ID 必须 1~127"); return; }
    // Load before any edits so a later scan cannot overwrite unsaved changes.
    _loadPersistedParams(addr);
    _motor->setAddr(addr);
    MotorResponse r;

    if (cmdStr == "enable")         { r = _motor->enable(); }
    else if (cmdStr == "disable") {
        r = _motor->disable();
        // P1-5：失能后云台轴模式缓存失效（下次 jog/move 重新 setMode+enable）
        if (r.valid) _gimbal->invalidateAxis(addr);
    }
    else if (cmdStr == "set_mode")  {
        int mode = doc["mode"] | -1;
        if (mode < 0 || mode > 4) { _notifyResult(false, "模式必须在 0~4 范围"); return; }
        r = _motor->setMode((uint8_t)mode);
        // P1-5：外部改模式后云台轴缓存失效
        if (r.valid) _gimbal->invalidateAxis(addr);
    }
    else if (cmdStr == "set_position_speed") {
        int rpm = doc["rpm"] | -1;
        if (rpm < 0 || rpm > 300) { _notifyResult(false, "位置速度必须在 0~300 RPM 范围"); return; }
        _params[addr].speed = rpm;
        _gimbal->rememberSpeed(addr, (int16_t)rpm);
        r.valid = true;
        r.parsed_text = "位置速度已缓存，不会立即驱动电机";
    }
    else if (cmdStr == "set_speed") {
        // P1-3：单电机速度命令加上限（防爆转）
        int rpm = doc["rpm"] | 0;
        if (rpm > 300 || rpm < -300) { _notifyResult(false, "速度必须在 ±300 RPM 范围"); return; }
        int16_t rpm16 = (int16_t)rpm;
        r = _motor->setSpeed(rpm16);
        // Raw set_speed is the motor console's immediate speed command. It is
        // intentionally not retained as a position-control speed.
    }
    else if (cmdStr == "set_angle") {
        float angle = doc["angle"] | 0.0f;
        // P1-3：云台限位轴（tilt）的目标角必须过限位检查（显式拒绝，不静默裁剪）
        const char* deny = _gimbal->checkAngleLimit(addr, angle, false);
        if (deny) { _notifyResult(false, String(deny)); return; }
        r = _motor->setSingleAngle(angle);
    }
    else if (cmdStr == "set_multi_angle") {
        float angle = doc["angle"] | 0.0f;
        // P1-3：限位轴拒绝多圈命令（圈数基准不受 ±90° 保护）
        const char* deny = _gimbal->checkAngleLimit(addr, angle, true);
        if (deny) { _notifyResult(false, String(deny)); return; }
        r = _motor->setMultiAngle(angle);
    }
    else if (cmdStr == "set_accel") {
        uint16_t accel = (uint16_t)(doc["accel"] | 0);
        r = _motor->setAccel(accel);
        if (r.valid) _params[addr].accel = accel;
    }
    else if (cmdStr == "get_params") {
        // PID/速度配置协议读不回，返回固件缓存的本次会话下发值；
        // 加速度可实测（RT_ACCEL），实测成功则覆盖缓存
        MotorParams p = _params[addr];
        MotorResponse ra = _motor->query(F32CMotor::RT_ACCEL);
        if (ra.valid && ra.bcc_ok) p.accel = ra.value;
        _notifyParamsResult(addr, p);
        return;
    }
    else if (cmdStr == "query") {
        String type = doc["type"] | "";
        uint8_t code;
        if (!_queryTypeCode(type, code)) {
            _notifyResult(false, "未知查询类型: " + type + "（支持 voltage/speed/total_angle/mech_angle/accel）");
            return;
        }
        r = _motor->query(code);
        if (r.valid && r.bcc_ok) {
            // 按类型换算为物理量
            float v = (float)r.value;
            if      (type == "voltage")     v /= 100.0f;
            else if (type == "total_angle") v /= 10.0f;
            else if (type == "mech_angle")  v /= 10.0f;
            _notifyQueryResult(addr, type.c_str(), v, r.parsed_text);
            return;
        }
        // 失败走统一错误返回
        _notifyResult(false, "查询失败: " + r.parsed_text);
        return;
    }
    else if (cmdStr == "save") {
        r = _motor->saveParams();
        if (r.valid) _persistParams(addr);
    }
    else if (cmdStr == "clear_total")    { r = _motor->clearTotalAngle(); }
    else if (cmdStr == "set_zero")       { r = _motor->setSingleZero(); }
    else if (cmdStr == "factory_reset") {
        r = _motor->factoryReset();
        // P1-5：恢复出厂会重置模式与使能，云台轴缓存失效
        if (r.valid) _gimbal->invalidateAxis(addr);
    }
    else if (cmdStr == "setaddr") {
        uint8_t newAddr = doc["new_addr"] | 0;
        r = _motor->setDeviceAddress(newAddr);
        if (r.valid) {
            _motor->setAddr(newAddr);   // 发送成功后切换控制地址（与 Python 一致）
            // P1-5：原地址轴缓存失效（该电机地址已变）
            _gimbal->invalidateAxis(addr);
            _notifyResult(true, "地址已改为 " + String(newAddr) + "，建议再发 save 命令永久写入");
            return;
        }
    }
    else if (cmdStr == "set_speed_kp") { r = _motor->setSpeedKp((uint16_t)(doc["val"] | 0)); if (r.valid) _params[addr].speed_kp = (uint16_t)(doc["val"] | 0); }
    else if (cmdStr == "set_speed_ki") { r = _motor->setSpeedKi((uint16_t)(doc["val"] | 0)); if (r.valid) _params[addr].speed_ki = (uint16_t)(doc["val"] | 0); }
    else if (cmdStr == "set_pos_kp")   { r = _motor->setPosKp((uint16_t)(doc["val"] | 0));   if (r.valid) _params[addr].pos_kp = (uint16_t)(doc["val"] | 0); }
    else if (cmdStr == "set_pos_ki")   { r = _motor->setPosKi((uint16_t)(doc["val"] | 0));   if (r.valid) _params[addr].pos_ki = (uint16_t)(doc["val"] | 0); }
    else if (cmdStr == "test") {
        String report;
        bool ok = _motor->connectivityTest(report);
        _notifyResult(ok, report);
        return;
    }
    else {
        _notifyError("未知命令: " + cmdStr);
        return;
    }

    // 统一返回执行结果
    _notifyResult(r.valid, r.valid ? r.parsed_text : ("执行失败: " + r.parsed_text));
}

// ==================== WiFi 配置命令（FF01） ====================
void CmdHandler::_handleWifiCmd(const String& json) {
    JsonDocument doc;
    DeserializationError err = deserializeJson(doc, json);
    if (err) {
        _notifyError("WiFi 配置 JSON 解析失败: " + String(err.c_str()));
        return;
    }
    const char* ssid = doc["ssid"] | "";
    const char* pass = doc["pass"] | "";
    if (strlen(ssid) == 0) {
        _notifyError("缺少 ssid 字段");
        return;
    }
    _wifi->connect(String(ssid), String(pass));
    // 状态由 wifi loop() 推送（connecting → connected/failed）
}

// ==================== 查询类型映射 ====================
bool CmdHandler::_queryTypeCode(const String& type, uint8_t& code) {
    if      (type == "voltage")     code = F32CMotor::RT_VOLTAGE;
    else if (type == "speed")       code = F32CMotor::RT_SPEED;
    else if (type == "total_angle") code = F32CMotor::RT_TOTAL_ANGLE;
    else if (type == "mech_angle")  code = F32CMotor::RT_MECH_ANGLE;
    else if (type == "accel")       code = F32CMotor::RT_ACCEL;
    else return false;
    return true;
}

// ==================== 日志汇聚 ====================
void CmdHandler::collectLog(const char* dir, const String& hex) {
    if (_logCount < LOG_RING_SIZE) {
        _logLines[(_logHead + _logCount) % LOG_RING_SIZE] =
            String("[") + dir + "] " + hex;
        _logCount++;
    } else {
        _droppedLogs++;   // 环满丢弃（扫描时高频场景）
    }
}

void CmdHandler::flushLogs() {
    if (_logCount == 0) return;
    // 限速：两次推送至少间隔 30ms，避免刷爆 BLE
    if (millis() - _lastLogFlush < 30) return;
    _lastLogFlush = millis();

    JsonDocument doc;
    doc["event"] = "log";
    JsonArray lines = doc["lines"].to<JsonArray>();
    while (_logCount > 0) {
        lines.add(_logLines[_logHead]);
        _logHead = (_logHead + 1) % LOG_RING_SIZE;
        _logCount--;
    }
    String out;
    serializeJson(doc, out);
    _ble->notifyResponse(out);
}

// ==================== 推送构建 ====================
void CmdHandler::_notifyResult(bool ok, const String& msg) {
    JsonDocument doc;
    doc["event"] = "cmd_result";
    doc["ok"] = ok;
    doc["msg"] = msg;
    String out;
    serializeJson(doc, out);
    _ble->notifyResponse(out);
}

void CmdHandler::_notifyError(const String& msg) {
    JsonDocument doc;
    doc["event"] = "error";
    doc["msg"] = msg;
    String out;
    serializeJson(doc, out);
    _ble->notifyResponse(out);
}

void CmdHandler::_notifyQueryResult(uint8_t addr, const char* type, float value, const String& text) {
    JsonDocument doc;
    doc["event"] = "query_result";
    doc["addr"] = addr;
    doc["type"] = type;
    doc["value"] = value;
    doc["text"] = text;
    String out;
    serializeJson(doc, out);
    _ble->notifyResponse(out);
}

void CmdHandler::_notifyScanResult(MotorInfo* motors, size_t count, bool ok) {
    JsonDocument doc;
    doc["event"] = "scan_result";
    doc["ok"] = ok;
    JsonArray arr = doc["motors"].to<JsonArray>();
    for (size_t i = 0; i < count; i++) {
        JsonObject m = arr.add<JsonObject>();
        m["id"] = motors[i].addr;
        m["volt"] = motors[i].volt;
    }
    String out;
    serializeJson(doc, out);
    _ble->notifyResponse(out);
}

void CmdHandler::_notifyGimbalState() {
    _ble->notifyResponse(_gimbal->stateJson());
}

void CmdHandler::_notifyParamsResult(uint8_t addr, const MotorParams& p) {
    // -1 = 没有本次下发值，也没有网关 NVS 快照可供恢复。
    JsonDocument doc;
    doc["event"] = "params_result";
    doc["addr"] = addr;
    doc["speed_kp"] = p.speed_kp;
    doc["speed_ki"] = p.speed_ki;
    doc["pos_kp"] = p.pos_kp;
    doc["pos_ki"] = p.pos_ki;
    doc["accel"] = p.accel;
    doc["speed"] = p.speed;
    String out;
    serializeJson(doc, out);
    _ble->notifyResponse(out);
}

void CmdHandler::pushSystemStatus() {
    // FF02：WiFi 状态 + 云台配置 + BLE 连接情况
    JsonDocument doc;
    doc["event"] = "sys_status";
    doc["ble"] = _ble->isConnected();
    doc["pan"] = _gimbal->panAddr();
    doc["tilt"] = _gimbal->tiltAddr();
    doc["pan_angle"] = _gimbal->panAngle();
    doc["tilt_angle"] = _gimbal->tiltAngle();
    String out;
    serializeJson(doc, out);
    _ble->notifyStatus(out);
}
