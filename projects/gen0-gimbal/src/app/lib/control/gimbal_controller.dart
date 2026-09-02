import 'dart:async';

import 'package:flutter/foundation.dart';

import '../ble/command_sink.dart';

/// 云台控制逻辑（对应 Web 版前端 JS / 方案 6.3 节）
///
/// - 十字键盘点动：按住发 jog(dir=±1)，松开发 jog(dir=0)
/// - 摇杆位置随动：130ms 节流下发 move，松手发最终位置
/// - 角度边界：pan ∈ [-180,180]、tilt ∈ [-90,90]（与固件 L0 钳位一致，
///   App 侧在发送前再钳一次，防御异常输入）
class GimbalController extends ChangeNotifier {
  final CommandSink ble;
  final DateTime Function() _now;

  /// [clock] 仅供单元测试注入假时钟；生产环境缺省用 DateTime.now
  GimbalController(this.ble, {DateTime Function()? clock})
      : _now = clock ?? DateTime.now;

  /// 点动速度（RPM，可调）——云台场景默认低速（10 RPM = 60°/s）
  int jogSpeed = 10;

  /// 摇杆当前角度显示值（±180 / ±90 表示法）
  double panAngle = 0;
  double tiltAngle = 0;

  double _pendPan = 0;
  double _pendTilt = 0;
  DateTime _lastMoveSend = DateTime.fromMillisecondsSinceEpoch(0);

  static const int throttleMs = 130; // 与 Web 版一致

  // ---------------- 角度边界（与固件 L0 钳位一致） ----------------

  static const double panMin = -180.0;
  static const double panMax = 180.0;
  static const double tiltMin = -90.0;
  static const double tiltMax = 90.0;

  static double clampPan(double v) => v < panMin ? panMin : (v > panMax ? panMax : v);
  static double clampTilt(double v) => v < tiltMin ? tiltMin : (v > tiltMax ? tiltMax : v);

  // ---------------- 十字键盘点动 ----------------

  /// axis: pan / tilt；dir: 1 正转 / -1 反转 / 0 停止（松手）
  void onKey(String axis, int dir) {
    ble.sendCmd({'cmd': 'jog', 'axis': axis, 'dir': dir, 'speed': jogSpeed});
  }

  // ---------------- 摇杆位置随动 ----------------

  /// 摇杆拖动回调（pan ∈ [-180,180]，tilt ∈ [-90,90]），130ms 节流
  void onJoystickMove(double pan, double tilt) {
    pan = clampPan(pan);
    tilt = clampTilt(tilt);
    _pendPan = pan;
    _pendTilt = tilt;
    panAngle = pan;
    tiltAngle = tilt;
    notifyListeners(); // 实时刷新角度显示

    final now = _now();
    if (now.difference(_lastMoveSend).inMilliseconds >= throttleMs) {
      _lastMoveSend = now;
      _sendMove();
    }
  }

  /// 摇杆松手：发送最终位置（补齐节流期间漏发的目标值）
  void onJoystickEnd() {
    _sendMove();
  }

  void _sendMove() {
    // 保留 1 位小数，与协议 0.1° 分辨率一致
    ble.sendCmd({
      'cmd': 'move',
      'pan': double.parse(_pendPan.toStringAsFixed(1)),
      'tilt': double.parse(_pendTilt.toStringAsFixed(1)),
    });
  }

  // ---------------- 回中 / 设零 ----------------

  void center() {
    ble.sendCmd({'cmd': 'center'});
    panAngle = 0;
    tiltAngle = 0;
    _pendPan = 0;
    _pendTilt = 0;
    notifyListeners();
  }

  void zero() {
    ble.sendCmd({'cmd': 'zero'});
    panAngle = 0;
    tiltAngle = 0;
    _pendPan = 0;
    _pendTilt = 0;
    notifyListeners();
  }

  /// 摇杆归位（UI 复位，不发命令）
  void resetDisplay() {
    panAngle = 0;
    tiltAngle = 0;
    _pendPan = 0;
    _pendTilt = 0;
    notifyListeners();
  }

  void setJogSpeed(int rpm) {
    jogSpeed = rpm;
    notifyListeners();
  }
}
