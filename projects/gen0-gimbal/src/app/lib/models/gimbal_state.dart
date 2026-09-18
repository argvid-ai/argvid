/// 云台与 WiFi 状态模型
library;

class GimbalInfo {
  final int panId; // 水平轴电机 ID（0=未配置）
  final int tiltId; // 垂直轴电机 ID（0=未配置）
  final double panAngle; // 固件最近下发的目标角（±180 表示法），不是实测位置
  final double tiltAngle; // 实测位置需通过 query_result 单独确认

  const GimbalInfo({
    this.panId = 0,
    this.tiltId = 0,
    this.panAngle = 0,
    this.tiltAngle = 0,
  });

  bool get ready => panId > 0 && tiltId > 0;

  GimbalInfo copyWith(
          {int? panId, int? tiltId, double? panAngle, double? tiltAngle}) =>
      GimbalInfo(
        panId: panId ?? this.panId,
        tiltId: tiltId ?? this.tiltId,
        panAngle: panAngle ?? this.panAngle,
        tiltAngle: tiltAngle ?? this.tiltAngle,
      );
}

class WifiInfo {
  final String status; // disconnected / connecting / connected
  final String? ip;
  final String? ssid;
  final int? rssi;

  const WifiInfo({this.status = 'disconnected', this.ip, this.ssid, this.rssi});

  bool get connected => status == 'connected';

  WifiInfo copyWith({String? status, String? ip, String? ssid, int? rssi}) =>
      WifiInfo(
        status: status ?? this.status,
        ip: ip ?? this.ip,
        ssid: ssid ?? this.ssid,
        rssi: rssi ?? this.rssi,
      );
}
