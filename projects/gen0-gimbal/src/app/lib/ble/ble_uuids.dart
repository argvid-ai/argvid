/// BLE UUID 常量 —— 与 ESP32 固件 config.h 严格对应，两端契约
class BleUuids {
  BleUuids._();

  /// 云台控制自定义服务
  static const String service = '0000ff00-0000-1000-8000-00805f9b34fb';

  /// WiFi 配置（Write，APP→ESP32）
  static const String wifi = '0000ff01-0000-1000-8000-00805f9b34fb';

  /// 系统状态（Read + Notify，ESP32→APP）
  static const String status = '0000ff02-0000-1000-8000-00805f9b34fb';

  /// 电机命令（Write，APP→ESP32）
  static const String cmd = '0000ff03-0000-1000-8000-00805f9b34fb';

  /// 响应/日志（Notify，ESP32→APP）
  static const String resp = '0000ff04-0000-1000-8000-00805f9b34fb';

  /// 固件广播名前缀（用于扫描过滤）
  static const String deviceNamePrefix = 'F32C';

  /// 连接后请求的 MTU（容纳 200B JSON 命令）
  static const int mtu = 247;
}
