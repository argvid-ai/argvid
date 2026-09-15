/// 单电机调参状态（对应固件 get_params 返回）
///
/// 协议读不回 PID/速度配置值：固件返回本次下发或网关 NVS 恢复的值，
/// null = 没有可见的运行参数快照。
/// accel 加速度为实测值。
class MotorParams {
  final int? speedKp;
  final int? speedKi;
  final int? posKp;
  final int? posKi;
  final int? accel;
  final int? speed;

  const MotorParams({
    this.speedKp,
    this.speedKi,
    this.posKp,
    this.posKi,
    this.accel,
    this.speed,
  });

  factory MotorParams.fromJson(Map<String, dynamic> j) {
    int? v(String key) {
      final raw = j[key];
      if (raw is num && raw >= 0) return raw.toInt();
      return null;
    }

    return MotorParams(
      speedKp: v('speed_kp'),
      speedKi: v('speed_ki'),
      posKp: v('pos_kp'),
      posKi: v('pos_ki'),
      accel: v('accel'),
      speed: v('speed'),
    );
  }

  /// 单行摘要：如 "速度KP 10 · KI 20 · 位置KP 5 · KI 5 · 加速度 100 · 速度 60"
  String summary() {
    String s(int? v) => v?.toString() ?? '未设置';
    return '速度KP ${s(speedKp)} · KI ${s(speedKi)} · '
        '位置KP ${s(posKp)} · KI ${s(posKi)} · 加速度 ${s(accel)} · 速度 ${s(speed)}';
  }
}
