/// 电机模型（扫描结果条目）
class Motor {
  final int id;      // 总线地址 ID（1~127）
  final double volt; // 母线电压 V

  Motor({required this.id, required this.volt});

  String get addrHex => '0x${id.toRadixString(16).padLeft(2, '0').toUpperCase()}';
}
