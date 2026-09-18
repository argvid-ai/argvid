/**
 * ble_service.cpp —— BLE GATT 服务端实现
 */
#include "ble_service.h"
#include <ArduinoJson.h>

// FreeRTOS 命令队列（BLE 栈任务 → 主循环）
static QueueHandle_t s_cmdQueue = nullptr;
static BleServiceManager* s_self = nullptr;

// ---------------- 服务端连接回调 ----------------
class ServerCallbacksImpl : public BLEServerCallbacks {
    void onConnect(BLEServer* pServer) override {
        if (s_self) s_self->setConnected(true);
    }
#if defined(CONFIG_BLUEDROID_ENABLED)
    // 请求更优连接参数降低点动延迟：interval 单位 1.25ms（6=7.5ms，12=15ms），timeout 单位 10ms
    void onConnect(BLEServer* pServer, esp_ble_gatts_cb_param_t* param) override {
        (void)pServer;
        pServer->requestConnParams(param->connect.remote_bda, 6, 12, 0, 400);
    }
#endif
    void onDisconnect(BLEServer* pServer) override {
        if (s_self) s_self->setConnected(false);
        // 断开后重新广播，等待 APP 重连
        pServer->startAdvertising();
    }
};

// ---------------- 特征写回调（只入队，不执行！） ----------------
class WifiWriteCallbacks : public BLECharacteristicCallbacks {
    void onWrite(BLECharacteristic* pChar) override {
        String json = pChar->getValue().c_str();
        if (json.length() == 0 || json.length() >= sizeof(BleCmdMsg().json)) return;
        if (s_self) s_self->_queueCmdPublic(true, json);
    }
};

class CmdWriteCallbacks : public BLECharacteristicCallbacks {
    void onWrite(BLECharacteristic* pChar) override {
        String json = pChar->getValue().c_str();
        if (json.length() == 0 || json.length() >= sizeof(BleCmdMsg().json)) return;
        if (s_self) s_self->_queueCmdPublic(false, json);
    }
};

// ---------------- 读回调（FF02 状态读取） ----------------
class StatusReadCallbacks : public BLECharacteristicCallbacks {
    void onRead(BLECharacteristic* pChar) override {
        (void)pChar;   // 值由主循环通过 updateStatusRead 持续更新
    }
};

// ---------------- 对外实现 ----------------
void BleServiceManager::begin(const char* deviceName) {
    s_self = this;
    s_cmdQueue = xQueueCreate(CMD_QUEUE_LEN, sizeof(BleCmdMsg));

    BLEDevice::init(deviceName);
    BLEDevice::setMTU(BLE_MTU_SIZE);

    _server = BLEDevice::createServer();
    _server->setCallbacks(new ServerCallbacksImpl());

    BLEService* service = _server->createService(BLE_SERVICE_UUID);

    // FF01 WiFi 配置（Write）
    _charWifi = service->createCharacteristic(
        BLE_CHAR_WIFI, BLECharacteristic::PROPERTY_WRITE);
    _charWifi->setCallbacks(new WifiWriteCallbacks());

    // FF02 系统状态（Read + Notify）
    _charStatus = service->createCharacteristic(
        BLE_CHAR_STATUS, BLECharacteristic::PROPERTY_READ | BLECharacteristic::PROPERTY_NOTIFY);
    _charStatus->addDescriptor(new BLE2902());
    _charStatus->setCallbacks(new StatusReadCallbacks());
    // 字节指针形式：core 2.x 无 setValue(String)、core 3.x 无 setValue(std::string)，此重载两版通用
    _charStatus->setValue((uint8_t*)_statusReadValue.c_str(), _statusReadValue.length());

    // FF03 电机命令（Write）
    _charCmd = service->createCharacteristic(
        BLE_CHAR_CMD, BLECharacteristic::PROPERTY_WRITE);
    _charCmd->setCallbacks(new CmdWriteCallbacks());

    // FF04 响应/日志（Notify）
    _charResp = service->createCharacteristic(
        BLE_CHAR_RESP, BLECharacteristic::PROPERTY_NOTIFY);
    _charResp->addDescriptor(new BLE2902());

    service->start();

    // 广播
    BLEAdvertising* adv = BLEDevice::getAdvertising();
    adv->addServiceUUID(BLE_SERVICE_UUID);
    adv->setScanResponse(true);
    adv->setMinPreferred(0x06);   // 有助于 iOS 连接稳定性
    BLEDevice::startAdvertising();
}

// P1-2：在回调中只解析停止命令所需的三个字段，避免依赖 JSON 排版和键顺序。
static bool _parseJogStop(const String& json, bool& isStop, bool& pan, bool& tilt) {
    isStop = false;
    pan = false;
    tilt = false;
    JsonDocument doc;
    if (deserializeJson(doc, json)) return false;
    const char* cmd = doc["cmd"] | "";
    const int dir = doc["dir"] | 99;
    if (strcmp(cmd, "jog") != 0 || dir != 0) return true;
    isStop = true;
    const char* axis = doc["axis"] | "";
    if (strcmp(axis, "pan") == 0) pan = true;
    if (strcmp(axis, "tilt") == 0) tilt = true;
    return true;
}

void BleServiceManager::_queueCmd(bool isWifi, const String& json) {
    if (!s_cmdQueue) return;

    // P1-2：松手停止走原子标志旁路——队列满也不会丢失；同时清空队列中
    // 残留的旧运动命令，保证停止之后不会继续执行它们。
    if (!isWifi) {
        bool isStop, pan, tilt;
        if (_parseJogStop(json, isStop, pan, tilt) && isStop && (pan || tilt)) {
            requestStop(pan, tilt);
            return;
        }
    }

    BleCmdMsg msg;
    msg.isWifi = isWifi;
    strncpy(msg.json, json.c_str(), sizeof(msg.json) - 1);
    msg.json[sizeof(msg.json) - 1] = '\0';
    // 队列满时丢弃本条非停止命令（jog/move 幂等，App 会重发；停止已走旁路不受影响）
    if (xQueueSend(s_cmdQueue, &msg, 0) != pdTRUE) {
        _queueRejected.fetch_add(1);
    }
}

// 供回调类调用（公开入口）
void BleServiceManager::_queueCmdPublic(bool isWifi, const String& json) {
    _queueCmd(isWifi, json);
}

bool BleServiceManager::popCommand(BleCmdMsg& msg) {
    if (!s_cmdQueue) return false;
    return xQueueReceive(s_cmdQueue, &msg, 0) == pdTRUE;
}

size_t BleServiceManager::pendingCommands() const {
    if (!s_cmdQueue) return 0;
    return uxQueueMessagesWaiting(s_cmdQueue);
}

uint32_t BleServiceManager::takeQueueRejected() {
    return _queueRejected.exchange(0);
}

void BleServiceManager::setConnected(bool connected) {
    if (_connected == connected) return;
    _connectionGeneration.fetch_add(1);
    _connected = connected;
    if (!connected) {
        // P1-1：断连安全——丢弃所有待执行命令（蓝牙栈任务中调用，仅用非阻塞 API），
        // 并置断连事件标志，由主循环执行失联停机（串口阻塞只能在主任务做）
        if (s_cmdQueue) xQueueReset(s_cmdQueue);
        _stopFlags.store(0);
        _disconnectPending.store(true);
        _fireMotionAbort();  // F2: abort in-flight move blocked in positionSafety
    }
    if (_connectCb) _connectCb(connected);
}

void BleServiceManager::_fireMotionAbort() {
    // BLE task / connect callback: non-blocking flag only.
    if (_motionAbortHook) _motionAbortHook();
}

bool BleServiceManager::takeDisconnectEvent() {
    if (!_disconnectPending.load()) return false;
    _disconnectPending.store(false);
    return true;
}

void BleServiceManager::requestStop(bool pan, bool tilt) {
    // P1-2：清空队列（丢弃堆积的旧运动命令）+ 原子置位停止请求。
    // 在蓝牙栈任务中调用：xQueueReset 与原子操作均非阻塞、线程安全。
    if (s_cmdQueue) xQueueReset(s_cmdQueue);
    if (pan)  _stopFlags.fetch_or(0x01);
    if (tilt) _stopFlags.fetch_or(0x02);
    _fireMotionAbort();  // F2: abort in-flight move blocked in positionSafety
}

bool BleServiceManager::takeStopRequest(bool& pan, bool& tilt) {
    uint8_t f = _stopFlags.load();
    if (f == 0) { pan = false; tilt = false; return false; }
    // 按位清除已消费的标志（不清并发新增的请求）
    if (f & 0x01) _stopFlags.fetch_and((uint8_t)~0x01);
    if (f & 0x02) _stopFlags.fetch_and((uint8_t)~0x02);
    pan  = (f & 0x01) != 0;
    tilt = (f & 0x02) != 0;
    return true;
}

void BleServiceManager::notifyResponse(const String& json) {
    _notifyJson(_charResp, json);
}

void BleServiceManager::notifyStatus(const String& json) {
    _statusReadValue = json;   // 同步供 APP 主动 Read
    _notifyJson(_charStatus, json);
    // Notify 分包后，Read 仍保留完整 JSON，而不是最后一个二进制分包。
    if (_charStatus) _charStatus->setValue((uint8_t*)json.c_str(), json.length());
}

void BleServiceManager::_notifyJson(BLECharacteristic* characteristic, const String& json) {
    if (!characteristic || !_server || !_connected || json.isEmpty()) return;
    if (json.length() > 8192) {
        _notifyJson(_charResp, "{\"event\":\"error\",\"msg\":\"BLE response exceeds 8192 bytes\"}");
        return;
    }
    const uint32_t generation = _connectionGeneration.load();
    uint16_t mtu = _server->getPeerMTU(_server->getConnId());
    if (mtu < 23) mtu = 23;
    if (mtu > BLE_MTU_SIZE) mtu = BLE_MTU_SIZE;
    const size_t capacity = mtu - 3;
    if (json.length() <= capacity) {
        characteristic->setValue((uint8_t*)json.c_str(), json.length());
        characteristic->notify();
        return;
    }

    // 与 App BleJsonReceiver 契约一致：F3 2C + LE uint16(id, offset, total)。
    // 按 UTF-8 字节分包，接收端收齐才解码；小包继续使用原有 JSON 格式。
    const uint16_t id = ++_notifyMessageId;
    const uint16_t total = json.length();
    const size_t chunkSize = capacity - 8;
    uint8_t packet[BLE_MTU_SIZE - 3];
    for (size_t offset = 0; offset < total; offset += chunkSize) {
        // 每片之间让出执行机会，停止/断链优先于剩余日志。
        if (!_connected || generation != _connectionGeneration.load() ||
            _disconnectPending.load() || _stopFlags.load() != 0) return;
        const size_t remaining = total - offset;
        const size_t count = remaining < chunkSize ? remaining : chunkSize;
        packet[0] = 0xF3; packet[1] = 0x2C;
        packet[2] = id & 0xFF; packet[3] = id >> 8;
        packet[4] = offset & 0xFF; packet[5] = offset >> 8;
        packet[6] = total & 0xFF; packet[7] = total >> 8;
        memcpy(packet + 8, json.c_str() + offset, count);
        characteristic->setValue(packet, count + 8);
        characteristic->notify();
        delay(10);
    }
}

