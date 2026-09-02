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
}

// ==================== 命令队列处理 ====================
void CmdHandler::processQueue() {
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
        _gimbal->config(pan, tilt);
        _notifyResult(true, "云台已配置: 水平=ID" + String(pan) + ", 垂直=ID" + String(tilt));
        _notifyGimbalState();
        return;
    }
    if (cmdStr == "jog") {
        const char* axis = doc["axis"] | "";
        int dir = doc["dir"] | 0;
        int speed = doc["speed"] | 60;
        MotorResponse r = _gimbal->jog(String(axis), (int8_t)dir, (uint16_t)speed);
        String msg = String(axis) + " ";
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
        MotorResponse r = _gimbal->move(pan, tilt, hasPan, hasTilt);
        _notifyResult(r.valid, r.parsed_text);
        _notifyGimbalState();
        return;
    }
    if (cmdStr == "center") {
        MotorResponse r = _gimbal->center();
        _notifyResult(r.valid, r.valid ? "双轴已回中 (0°)" : r.parsed_text);
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
    _motor->setAddr(addr);
    MotorResponse r;

    if (cmdStr == "enable")         { r = _motor->enable(); }
    else if (cmdStr == "disable")   { r = _motor->disable(); }
    else if (cmdStr == "set_mode")  {
        int mode = doc["mode"] | -1;
        if (mode < 0 || mode > 4) { _notifyResult(false, "模式必须在 0~4 范围"); return; }
        r = _motor->setMode((uint8_t)mode);
    }
    else if (cmdStr == "set_speed") { r = _motor->setSpeed((int16_t)(doc["rpm"] | 0)); }
    else if (cmdStr == "set_angle") {
        float angle = doc["angle"] | 0.0f;
        r = _motor->setSingleAngle(angle);
    }
    else if (cmdStr == "set_multi_angle") {
        float angle = doc["angle"] | 0.0f;
        r = _motor->setMultiAngle(angle);
    }
    else if (cmdStr == "set_accel") { r = _motor->setAccel((uint16_t)(doc["accel"] | 0)); }
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
    else if (cmdStr == "save")           { r = _motor->saveParams(); }
    else if (cmdStr == "clear_total")    { r = _motor->clearTotalAngle(); }
    else if (cmdStr == "set_zero")       { r = _motor->setSingleZero(); }
    else if (cmdStr == "factory_reset")  { r = _motor->factoryReset(); }
    else if (cmdStr == "setaddr") {
        uint8_t newAddr = doc["new_addr"] | 0;
        r = _motor->setDeviceAddress(newAddr);
        if (r.valid) {
            _motor->setAddr(newAddr);   // 发送成功后切换控制地址（与 Python 一致）
            _notifyResult(true, "地址已改为 " + String(newAddr) + "，建议再发 save 命令永久写入");
            return;
        }
    }
    else if (cmdStr == "set_speed_kp") { r = _motor->setSpeedKp((uint16_t)(doc["val"] | 0)); }
    else if (cmdStr == "set_speed_ki") { r = _motor->setSpeedKi((uint16_t)(doc["val"] | 0)); }
    else if (cmdStr == "set_pos_kp")   { r = _motor->setPosKp((uint16_t)(doc["val"] | 0)); }
    else if (cmdStr == "set_pos_ki")   { r = _motor->setPosKi((uint16_t)(doc["val"] | 0)); }
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
