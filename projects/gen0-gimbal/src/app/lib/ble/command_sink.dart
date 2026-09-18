/// 命令发送抽象：控制层只依赖此接口，便于单元测试注入假实现
/// （真实实现为 BleService.sendCmd，写 BLE FF03 特征）
abstract interface class CommandSink {
  /// Returns true only when the BLE write was accepted by the transport.
  Future<bool> sendCmd(Map<String, dynamic> cmd);
}
