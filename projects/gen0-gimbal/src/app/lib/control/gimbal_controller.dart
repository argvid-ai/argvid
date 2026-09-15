import 'dart:async';

import 'package:flutter/foundation.dart';

import '../ble/command_sink.dart';

/// 云台控制逻辑 —— 多圈位置模式（相对原点）
///
/// 位置坐标：原点 = 执行 origin（设为原点）时刻的位置，记 0°；
/// pan ∈ [-180,180]，tilt ∈ [-90,90]，超界自动钳位。
///
/// - 十字键盘：点一下走一个步进角（stepAngle），松开不发命令（位置模式到位自停）
/// - 摇杆圆盘：按下时记录当前位置为圆心，圆盘拖到哪里就相对跟随到哪里，
///   130ms 节流下发，松手发最终位置
/// - 拉杆：直接设置单轴绝对目标角（相对原点），130ms 节流
/// - 所有 move 均走多圈位置模式，规避单圈 0/360 过零问题
///
/// 可测试性：依赖 CommandSink 接口（真实实现为 BleService.sendCmd），
/// 时钟可注入（[clock]），单元测试无需硬件/BLE。
class GimbalController extends ChangeNotifier {
  final CommandSink ble;
  final DateTime Function() _now;

  /// [clock] 仅供单元测试注入假时钟；生产环境缺省用 DateTime.now
  GimbalController(this.ble, {DateTime Function()? clock})
      : _now = clock ?? DateTime.now;

  /// 箭头一次点击的步进角度（°，位置模式下每点一次最多走这么多）
  int stepAngle = 5;

  /// 运动速度（RPM）—— 位置模式下 T 型规划的最大速度（手册 4.3：模式→速度→角度）
  int motionSpeed = 60;

  /// 云台轴电机 ID（由页面从 BleService.gimbal 同步，供速度序列下发）
  int panId = 0;
  int tiltId = 0;

  /// 当前目标角（相对原点，多圈坐标）
  double panAngle = 0;
  double tiltAngle = 0;

  /// 摇杆拖动起点（按下时的当前位置）
  double _dragStartPan = 0;
  double _dragStartTilt = 0;

  /// 节流用
  DateTime _lastMoveSend = DateTime.fromMillisecondsSinceEpoch(0);
  Future<void> _sendTail = Future<void>.value();

  /// 速度滑杆只修改本地值；限速随明确位置目标一起交给固件。
  bool _motionLocked = false;
  int _motionGeneration = 0;

  static const int throttleMs = 130;

  static const double panMax = 180;
  static const double tiltMax = 90;

  static double clampPan(double v) =>
      v < -panMax ? (-panMax) : (v > panMax ? panMax : v);
  static double clampTilt(double v) =>
      v < -tiltMax ? (-tiltMax) : (v > tiltMax ? tiltMax : v);

  /// Wait until all commands queued by this controller have completed.
  Future<void> waitForPendingCommands() => _sendTail;

  bool get motionLocked => _motionLocked;

  /// Invalidate queued position commands before a parameter transaction.
  ///
  /// The BLE gateway has a finite command queue. A stale move must never be
  /// allowed to run after a parameter apply, because it could release a
  /// burst of old targets at the exact moment the user expects a quiet setup.
  Future<bool> haltAndLock() async {
    _motionLocked = true;
    _motionGeneration++;
    notifyListeners();
    await waitForPendingCommands();
    var ok = true;
    for (final addr in [panId, tiltId]) {
      if (addr < 1) continue;
      final sent = await ble.sendCmd({
        'cmd': 'jog',
        'axis': addr == panId ? 'pan' : 'tilt',
        'dir': 0,
        'speed': 0
      });
      ok = sent && ok;
    }
    return ok;
  }

  /// Release only after the complete setup transaction was sent successfully.
  void unlockAfterParameterApply() {
    _motionLocked = false;
    notifyListeners();
  }

  /// Configuration must never write nonzero RPM into the stopped speed mode.
  /// set_position_speed changes only the gateway's position speed setting.
  Future<bool> applyParameters(
      List<int> addresses, Map<String, int> parameters) async {
    if (parameters.isEmpty ||
        addresses.isEmpty ||
        addresses.any((addr) => addr < 1 || addr > 127)) {
      return false;
    }
    if (!await haltAndLock()) return false;
    for (final addr in addresses) {
      for (final entry in parameters.entries) {
        final cmd = entry.key == 'set_speed' ? 'set_position_speed' : entry.key;
        final field = cmd == 'set_position_speed'
            ? 'rpm'
            : cmd == 'set_accel'
                ? 'accel'
                : 'val';
        if (!await ble
            .sendCmd({'cmd': cmd, 'addr': addr, field: entry.value})) {
          // Keep movement locked until setup is explicitly retried.
          return false;
        }
      }
    }
    unlockAfterParameterApply();
    return true;
  }

  void _enqueue(Future<void> Function() operation) {
    _sendTail = _sendTail.then((_) => operation()).catchError((_) {});
  }

  /// 页面同步云台轴 ID（gimbal_state 变化时调用）
  void updateAxisIds(int pan, int tilt) {
    panId = pan;
    tiltId = tilt;
  }

  /// 进入云台页或外部电机状态发生变化后，要求下一次明确位置命令重新准备电机。
  void resetPositionSession() {
    _motionGeneration++;
  }

  // ---------------- 十字键盘：步进移动 ----------------

  /// axis: pan / tilt；dir: 1 正方向 / -1 负方向 / 0 松开（位置模式下忽略）
  void onKey(String axis, int dir) {
    if (_motionLocked) return;
    if (dir == 0) return; // 位置模式到位自然停，松开无需发命令
    final isPan = axis == 'pan';
    final cur = isPan ? panAngle : tiltAngle;
    final target = (isPan ? clampPan : clampTilt)(cur + dir * stepAngle);
    if (isPan) {
      panAngle = target;
    } else {
      tiltAngle = target;
    }
    notifyListeners();
    _sendMove(hasPan: isPan, hasTilt: !isPan);
  }

  // ---------------- 摇杆圆盘：相对拖动 ----------------

  /// 摇杆按下：记录当前位置为圆心
  void onJoystickStart() {
    _dragStartPan = panAngle;
    _dragStartTilt = tiltAngle;
  }

  /// 摇杆拖动回调（panNorm/tiltNorm ∈ [-1,1]），
  /// 目标 = 按下时位置 + 圆盘偏移映射（pan ±180°，tilt ±90°），130ms 节流
  void onJoystickMove(double panNorm, double tiltNorm) {
    if (_motionLocked) return;
    panAngle = clampPan(_dragStartPan + panNorm * panMax);
    tiltAngle = clampTilt(_dragStartTilt + tiltNorm * tiltMax);
    notifyListeners();

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

  // ---------------- 拉杆：单轴绝对目标角 ----------------

  /// 水平拉杆（angle ∈ [-180,180]，相对原点），130ms 节流
  void onPanLever(double angle, {bool finalValue = false}) {
    if (_motionLocked) return;
    panAngle = clampPan(angle);
    notifyListeners();
    final now = _now();
    if (finalValue ||
        now.difference(_lastMoveSend).inMilliseconds >= throttleMs) {
      _lastMoveSend = now;
      _sendMove(hasTilt: false);
    }
  }

  /// 垂直拉杆（angle ∈ [-90,90]，相对原点），130ms 节流
  void onTiltLever(double angle, {bool finalValue = false}) {
    if (_motionLocked) return;
    tiltAngle = clampTilt(angle);
    notifyListeners();
    final now = _now();
    if (finalValue ||
        now.difference(_lastMoveSend).inMilliseconds >= throttleMs) {
      _lastMoveSend = now;
      _sendMove(hasPan: false);
    }
  }

  // ---------------- 回中 / 原点 / 设零 ----------------

  /// 回中：两轴回到原点 0°
  void center() {
    if (_motionLocked) return;
    final generation = ++_motionGeneration;
    final speed = motionSpeed;
    panAngle = 0;
    tiltAngle = 0;
    _dragStartPan = 0;
    _dragStartTilt = 0;
    notifyListeners();
    _enqueue(() async {
      if (_motionLocked || generation != _motionGeneration) return;
      await ble.sendCmd({'cmd': 'center', 'speed': speed});
    });
  }

  /// 设为原点：当前位置记为 0°（多圈总角度清零），本地坐标同步归零
  void origin() {
    if (_motionLocked) return;
    final generation = _motionGeneration;
    panAngle = 0;
    tiltAngle = 0;
    _dragStartPan = 0;
    _dragStartTilt = 0;
    notifyListeners();
    _enqueue(() async {
      if (_motionLocked || generation != _motionGeneration) return;
      await ble.sendCmd({'cmd': 'origin'});
    });
  }

  /// 当前位置设为单圈 0°（机械零点标定，需 save 永久写入）
  void zero() {
    if (_motionLocked) return;
    final generation = _motionGeneration;
    _enqueue(() async {
      if (_motionLocked || generation != _motionGeneration) return;
      await ble.sendCmd({'cmd': 'zero'});
    });
    panAngle = 0;
    tiltAngle = 0;
    _dragStartPan = 0;
    _dragStartTilt = 0;
    notifyListeners();
  }

  /// UI 复位（不发命令）
  void resetDisplay() {
    panAngle = 0;
    tiltAngle = 0;
    _dragStartPan = 0;
    _dragStartTilt = 0;
    notifyListeners();
  }

  void setStepAngle(int deg) {
    stepAngle = deg;
    notifyListeners();
  }

  // ---------------- 运动速度（位置模式 T 型规划最大速度） ----------------

  /// 滑块拖动中：仅更新本地显示，松手确认下次位置运动速度。
  void onSpeedSlider(int rpm) {
    motionSpeed = rpm;
    notifyListeners();
  }

  /// 滑块松手：只更新本地位置速度，不改变电机使能状态或目标位置。
  void onSpeedSliderEnd(int rpm) {
    motionSpeed = rpm;
    notifyListeners();
  }

  void _sendMove({bool hasPan = true, bool hasTilt = true}) {
    if (_motionLocked) return;
    final generation = _motionGeneration;
    // One gateway transaction owns preparation and target dispatch. In
    // particular, a motor-only restart cannot leave the App's arm cache stale.
    final command = <String, dynamic>{
      'cmd': 'move',
      if (hasPan) 'pan': double.parse(panAngle.toStringAsFixed(1)),
      if (hasTilt) 'tilt': double.parse(tiltAngle.toStringAsFixed(1)),
      'speed': motionSpeed,
    };
    _enqueue(() async {
      if (_motionLocked || generation != _motionGeneration) return;
      if (!await ble.sendCmd(command)) {
        _motionGeneration++;
        _motionLocked = true;
        notifyListeners();
      }
    });
  }
}
