import 'dart:async';
import 'dart:convert';

/// One receiver per characteristic. Short notifications remain plain JSON.
/// Long messages use F3 2C + uint16 LE (id, byte offset, total byte length).
/// Decode UTF-8 only after all bytes arrive, including split Chinese characters.
class BleJsonReceiver {
  BleJsonReceiver({required this.onEvent, required this.onError});

  final void Function(Map<String, dynamic>) onEvent;
  final void Function(String) onError;
  static const maxBytes = 8192;
  static const fragmentTimeout = Duration(seconds: 3);

  final List<int> _bytes = [];
  int? _id;
  int _total = 0;
  Timer? _timer;

  void reset() {
    _timer?.cancel();
    _timer = null;
    _bytes.clear();
    _id = null;
    _total = 0;
  }

  void _fail(String message) {
    reset();
    onError(message);
  }

  void add(List<int> packet) {
    // A newly bound characteristic may have no value yet.
    if (packet.isEmpty) return;
    if (packet.first != 0xf3) {
      if (_id != null) _fail('BLE 长消息未收齐，被下一条消息替代');
      _decode(packet);
      return;
    }
    if (packet.length <= 8 || packet[1] != 0x2c) {
      _fail('BLE 分包头无效');
      return;
    }
    final id = packet[2] | (packet[3] << 8);
    final offset = packet[4] | (packet[5] << 8);
    final total = packet[6] | (packet[7] << 8);
    final payload = packet.sublist(8);
    if (total == 0 || total > maxBytes || offset + payload.length > total) {
      _fail('BLE 分包长度无效');
      return;
    }
    if (offset == 0) {
      if (_id != null) _fail('BLE 长消息未收齐，被新的分包替代');
      _id = id;
      _total = total;
    }
    if (_id != id || _total != total || offset != _bytes.length) {
      _fail('BLE 分包缺失或顺序错误，请重试查询');
      return;
    }
    _bytes.addAll(payload);
    _timer?.cancel();
    if (_bytes.length == _total) {
      final complete = List<int>.of(_bytes);
      reset();
      _decode(complete);
    } else {
      _timer = Timer(fragmentTimeout, () => _fail('BLE 长消息接收超时，请重试查询'));
    }
  }

  void _decode(List<int> bytes) {
    if (bytes.length > maxBytes) {
      _fail('BLE 消息超过 $maxBytes 字节');
      return;
    }
    try {
      final obj = jsonDecode(utf8.decode(bytes));
      if (obj is! Map<String, dynamic>) {
        throw const FormatException('消息必须是 JSON 对象');
      }
      onEvent(obj);
    } catch (e) {
      onError('JSON 解析失败: $e');
    }
  }
}
