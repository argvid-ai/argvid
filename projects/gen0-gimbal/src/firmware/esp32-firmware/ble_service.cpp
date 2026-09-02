/**
 * ble_service.cpp —— BLE GATT 服务端实现
 */
#include "ble_service.h"

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

void BleServiceManager::_queueCmd(bool isWifi, const String& json) {
    if (!s_cmdQueue) return;
    BleCmdMsg msg;
    msg.isWifi = isWifi;
    strncpy(msg.json, json.c_str(), sizeof(msg.json) - 1);
    msg.json[sizeof(msg.json) - 1] = '\0';
    // 队列满时丢弃（jog 类命令幂等，丢失一条无害；重复 move 也无害）
    xQueueSend(s_cmdQueue, &msg, 0);
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

void BleServiceManager::setConnected(bool connected) {
    if (_connected == connected) return;
    _connected = connected;
    if (_connectCb) _connectCb(connected);
}

void BleServiceManager::notifyResponse(const String& json) {
    if (!_charResp) return;
    // 超长自动截断保护（协议约定单条 < 200B，MTU 247 内）
    _charResp->setValue((uint8_t*)json.c_str(), json.length());
    _charResp->notify();
}

void BleServiceManager::notifyStatus(const String& json) {
    if (!_charStatus) return;
    _charStatus->setValue((uint8_t*)json.c_str(), json.length());
    _charStatus->notify();
    _statusReadValue = json;   // 同步供 APP 主动 Read
}
