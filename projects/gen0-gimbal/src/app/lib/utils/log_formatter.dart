/// 日志条目与十六进制格式化工具

class LogEntry {
  final DateTime time;
  final String dir;   // TX / RX / APP / ERR
  final String text;  // 内容（十六进制帧或文本）

  LogEntry({required this.time, required this.dir, required this.text});

  String get timeStr =>
      '${time.hour.toString().padLeft(2, '0')}:'
      '${time.minute.toString().padLeft(2, '0')}:'
      '${time.second.toString().padLeft(2, '0')}';
}

/// 字节转十六进制字符串：[0x7A, 0x02] → "7A 02"
String formatHex(List<int> bytes) {
  return bytes.map((b) => b.toRadixString(16).padLeft(2, '0').toUpperCase()).join(' ');
}

/// 日志方向 → 显示颜色
int logColor(String dir) {
  switch (dir) {
    case 'TX':  return 0xFF60A5FA;  // 蓝（APP → 电机）
    case 'RX':  return 0xFFFBBF24;  // 黄（电机 → APP）
    case 'APP': return 0xFF4ADE80;  // 绿（APP 发出的 JSON 命令）
    default:    return 0xFFF87171;  // 红（错误）
  }
}
