import 'package:flutter_test/flutter_test.dart';

import 'package:gimbal_app/ble/command_sink.dart';
import 'package:gimbal_app/control/gimbal_controller.dart';

/// 假命令发送端：记录所有下发的命令
class _FakeSink implements CommandSink {
  final List<Map<String, dynamic>> cmds = [];

  @override
  Future<bool> sendCmd(Map<String, dynamic> cmd) async {
    cmds.add(cmd);
    return true;
  }

  Map<String, dynamic> get last => cmds.last;
  int get moveCount => cmds.where((c) => c['cmd'] == 'move').length;
}

/// 假时钟：手动推进，验证 130ms 节流
class _FakeClock {
  DateTime _now = DateTime(2026, 1, 1, 0, 0, 0);
  DateTime call() => _now;
  void elapseMs(int ms) => _now = _now.add(Duration(milliseconds: ms));
}

class _DelayedSink implements CommandSink {
  final List<Map<String, dynamic>> cmds = [];
  final Duration delay;
  final int? failAt;
  int active = 0;
  int maxActive = 0;

  _DelayedSink({this.delay = const Duration(milliseconds: 1), this.failAt});

  @override
  Future<bool> sendCmd(Map<String, dynamic> cmd) async {
    active++;
    if (active > maxActive) maxActive = active;
    await Future<void>.delayed(delay);
    final index = cmds.length;
    cmds.add(cmd);
    active--;
    return failAt != index;
  }
}

void main() {
  late _FakeSink sink;
  late _FakeClock clock;
  late GimbalController c;

  setUp(() {
    sink = _FakeSink();
    clock = _FakeClock();
    c = GimbalController(sink, clock: clock.call);
    c.updateAxisIds(1, 2);
  });

  group('十字键盘步进（位置模式）', () {
    test('点一下走一个步进角：发 move 而非 jog', () async {
      c.onKey('pan', 1);
      await c.waitForPendingCommands();
      expect(sink.cmds.single['cmd'], 'move');
      await c.waitForPendingCommands();
      expect(sink.cmds.single['pan'], 5.0); // 默认步进 5°
      await c.waitForPendingCommands();
      expect(sink.cmds.single.containsKey('tilt'), isFalse);
      expect(sink.cmds.single['speed'], 60);
    });

    test('连续点击累积步进，反方向递减', () async {
      c.onKey('tilt', 1); // +5
      c.onKey('tilt', 1); // +10
      c.onKey('tilt', -1); // +5
      await c.waitForPendingCommands();
      expect(sink.last['tilt'], 5.0);
    });

    test('松开（dir=0）不发任何命令：位置模式到位自停', () async {
      c.onKey('pan', 1);
      await c.waitForPendingCommands();
      final n = sink.cmds.length;
      c.onKey('pan', 0);
      await c.waitForPendingCommands();
      expect(sink.cmds.length, n); // 无新增命令
    });

    test('步进越界钳位：tilt 最多到 ±90', () async {
      for (int i = 0; i < 30; i++) {
        c.onKey('tilt', 1);
      }
      await c.waitForPendingCommands();
      expect(c.tiltAngle, 90.0);
      await c.waitForPendingCommands();
      expect(sink.last['tilt'], 90.0);
    });

    test('步进角可调', () async {
      c.setStepAngle(10);
      c.onKey('pan', -1);
      await c.waitForPendingCommands();
      expect(sink.last['pan'], -10.0);
    });
  });

  group('摇杆相对拖动', () {
    test('按下记录圆心：拖动目标 = 起点 + 偏移×满量程', () async {
      c.onKey('pan', 1); // 当前 pan = 5
      c.onKey('tilt', 1); // 当前 tilt = 5
      c.onJoystickStart(); // 圆心 = (5, 5)
      c.onJoystickMove(0.5, -1.0);
      // pan = 5 + 0.5*180 = 95；tilt = 5 + (-1)*90 = -85
      await c.waitForPendingCommands();
      expect(sink.last['pan'], 95.0);
      await c.waitForPendingCommands();
      expect(sink.last['tilt'], -85.0);
    });

    test('130ms 节流：窗口内只发一次，之后恢复', () async {
      c.onJoystickStart();
      c.onJoystickMove(0.1, 0); // 立即发
      clock.elapseMs(50);
      c.onJoystickMove(0.2, 0); // 节流窗口内
      await c.waitForPendingCommands();
      expect(sink.moveCount, 1);

      clock.elapseMs(80); // 距上次发送 130ms
      c.onJoystickMove(0.3, 0);
      await c.waitForPendingCommands();
      expect(sink.moveCount, 2);
    });

    test('相对拖动钳位：圆心+偏移不超 pan ±180 / tilt ±90', () async {
      c.onJoystickStart(); // 圆心 (0,0)
      c.onJoystickMove(1.5, 1.5); // 偏移超满量程
      await c.waitForPendingCommands();
      expect(sink.last['pan'], 180.0);
      await c.waitForPendingCommands();
      expect(sink.last['tilt'], 90.0);
    });

    test('松手补发最终位置', () async {
      c.onJoystickStart();
      c.onJoystickMove(0.1, 0);
      clock.elapseMs(20);
      c.onJoystickMove(0.7, 0.2); // 被节流
      await c.waitForPendingCommands();
      expect(sink.moveCount, 1);

      c.onJoystickEnd(); // 松手必须送达最终值
      await c.waitForPendingCommands();
      expect(sink.moveCount, 2);
      await c.waitForPendingCommands();
      expect(sink.last['pan'], 126.0); // 0.7*180
    });
  });

  group('拉杆：单轴绝对目标角', () {
    test('水平拉杆直接设置目标并节流', () async {
      c.onPanLever(-30.0);
      await c.waitForPendingCommands();
      expect(sink.last['cmd'], 'move');
      await c.waitForPendingCommands();
      expect(sink.last['pan'], -30.0);

      clock.elapseMs(130);
      c.onPanLever(45.5);
      await c.waitForPendingCommands();
      expect(sink.last['pan'], 45.5);
    });

    test('垂直拉杆钳位 ±90', () async {
      c.onTiltLever(120);
      await c.waitForPendingCommands();
      expect(sink.last['tilt'], 90.0);
      clock.elapseMs(130);
      c.onTiltLever(-100);
      await c.waitForPendingCommands();
      expect(sink.last['tilt'], -90.0);
    });
  });

  group('角度精度与原点操作', () {
    test('角度按协议 0.1° 分辨率保留 1 位小数', () async {
      c.onPanLever(10.126);
      await c.waitForPendingCommands();
      expect(sink.last['pan'], 10.1);
    });

    test('center 发命令并清零全部坐标（含拖动圆心）', () async {
      c.onKey('pan', 1);
      c.onKey('tilt', 1);
      c.center();
      await c.waitForPendingCommands();
      expect(sink.last['cmd'], 'center');
      await c.waitForPendingCommands();
      expect(c.panAngle, 0);
      await c.waitForPendingCommands();
      expect(c.tiltAngle, 0);

      c.onJoystickStart(); // 圆心也应已归零
      c.onJoystickMove(0.0, 0.0);
      await c.waitForPendingCommands();
      expect(sink.last['pan'], 0.0);
    });

    test('origin 发 origin 命令并清零坐标（多圈原点）', () async {
      c.onKey('pan', 2);
      c.origin();
      await c.waitForPendingCommands();
      expect(sink.last['cmd'], 'origin');
      await c.waitForPendingCommands();
      expect(c.panAngle, 0);
      await c.waitForPendingCommands();
      expect(c.tiltAngle, 0);
    });
  });

  group('运动速度序列（T 型规划最大速度）', () {
    test('滑块只更新本地速度，松手不触发任何电机命令', () async {
      c.panAngle = 30;
      c.tiltAngle = -20;
      c.onSpeedSlider(90);
      await c.waitForPendingCommands();
      expect(sink.cmds, isEmpty); // 拖动中不下发

      c.onSpeedSliderEnd(90);
      // 速度设置不应触发 set_mode、enable 或 move
      await c.waitForPendingCommands();
      expect(sink.cmds, isEmpty);
      await c.waitForPendingCommands();
      expect(c.motionSpeed, 90);
    });

    test('轴 ID 未同步（0）时跳过该轴', () async {
      c.updateAxisIds(0, 2); // pan 未配置
      c.onSpeedSliderEnd(60);
      await c.waitForPendingCommands();
      expect(sink.cmds, isEmpty); // 调速不直接下发任何轴命令
    });

    test('调速后必须由明确位置命令才准备电机并移动', () async {
      c.onSpeedSliderEnd(90);
      c.onKey('pan', 1);
      await c.waitForPendingCommands();
      expect(sink.cmds, [
        {'cmd': 'move', 'pan': 5.0, 'speed': 90},
      ]);
    });
  });

  group('异步发送事务', () {
    test('命令严格串行，上一条失败后停止后续位置命令', () async {
      final delayed = _DelayedSink(failAt: 0);
      final controller = GimbalController(delayed);
      controller.updateAxisIds(1, 2);
      controller.onSpeedSliderEnd(90);
      controller.onKey('pan', 1);
      controller.onKey('tilt', 1);
      await controller.waitForPendingCommands();
      expect(delayed.maxActive, 1);
      expect(delayed.cmds.map((c) => c['cmd']), ['move']);
      expect(controller.motionLocked, isTrue);
    });

    test('连续速度设置不会并发写 BLE', () async {
      final delayed = _DelayedSink(delay: const Duration(milliseconds: 2));
      final controller = GimbalController(delayed);
      controller.updateAxisIds(1, 2);
      controller.onSpeedSliderEnd(60);
      controller.onKey('pan', 1);
      controller.onSpeedSliderEnd(90);
      controller.onKey('tilt', 1);
      await controller.waitForPendingCommands();
      expect(delayed.maxActive, 1);
      expect(delayed.cmds.map((c) => c['speed']), [60, 90]);
    });

    test('参数事务前清空旧位置命令并发送双轴停止', () async {
      final delayed = _DelayedSink(delay: const Duration(milliseconds: 2));
      final controller = GimbalController(delayed);
      controller.updateAxisIds(1, 2);
      controller.onKey('pan', 1);
      controller.onKey('tilt', 1);

      await controller.haltAndLock();
      await controller.waitForPendingCommands();

      expect(delayed.cmds.where((c) => c['cmd'] == 'move'), isEmpty);
      expect(
        delayed.cmds.where((c) => c['cmd'] == 'jog').map((c) => c['axis']),
        ['pan', 'tilt'],
      );
      controller.unlockAfterParameterApply();
      controller.onKey('pan', 1);
      await controller.waitForPendingCommands();
      expect(delayed.cmds.any((c) => c['cmd'] == 'move'), isTrue);
    });

    test('下发参数后保持停止，不会在速度模式下写非零 RPM', () async {
      // Model the motor behavior that exposed the regression: jog(0) leaves
      // speed mode enabled; set_speed(60) would restart it immediately.
      c.onKey('pan', 1);
      final ok = await c.applyParameters([
        1,
        2
      ], {
        'set_pos_kp': 3,
        'set_pos_ki': 15,
        'set_accel': 50,
        'set_speed': 60,
      });
      final rpm = <String, int>{};
      for (final cmd in sink.cmds) {
        if (cmd['cmd'] == 'jog') rpm[cmd['axis'] as String] = 0;
        if (cmd['cmd'] == 'set_speed') {
          rpm[cmd['addr'] == 1 ? 'pan' : 'tilt'] = cmd['rpm'] as int;
        }
      }
      expect(ok, isTrue);
      expect(rpm, {'pan': 0, 'tilt': 0});
      expect(sink.moveCount, 0);
      expect(
          sink.cmds.where((c) => c['cmd'] == 'set_position_speed').length, 2);
      expect(c.motionLocked, isFalse);
    });

    test('停止写入失败时不会继续配置或解锁运动', () async {
      final delayed = _DelayedSink(failAt: 0);
      final controller = GimbalController(delayed)..updateAxisIds(1, 2);
      expect(
          await controller.applyParameters([1, 2], {'set_speed': 60}), isFalse);
      controller.onKey('pan', 1);
      await controller.waitForPendingCommands();
      expect(delayed.cmds.every((c) => c['cmd'] == 'jog'), isTrue);
      expect(controller.motionLocked, isTrue);
    });

    test('参数写入失败时保持锁定', () async {
      final delayed = _DelayedSink(failAt: 2);
      final controller = GimbalController(delayed)..updateAxisIds(1, 2);
      expect(
          await controller.applyParameters([1, 2], {'set_speed': 60}), isFalse);
      expect(controller.motionLocked, isTrue);
      expect(delayed.cmds.length, 3);
    });

    test('回原点取消尚未发送的旧目标，并携带当前限速', () async {
      c.onSpeedSliderEnd(30);
      c.onKey('pan', 1);
      c.onKey('tilt', 1);
      c.center();
      await c.waitForPendingCommands();
      expect(sink.cmds, [
        {'cmd': 'center', 'speed': 30}
      ]);
    });

    test('重新操作仍带完整位置意图，不依赖 App 电机模式缓存', () async {
      c.onKey('pan', 1);
      await c.waitForPendingCommands();
      // A motor can restart while BLE and this controller remain alive.
      c.onKey('pan', 1);
      await c.waitForPendingCommands();
      expect(sink.cmds, [
        {'cmd': 'move', 'pan': 5.0, 'speed': 60},
        {'cmd': 'move', 'pan': 10.0, 'speed': 60},
      ]);
    });

    test('单轴拉杆松手补发节流窗口内的最终目标', () async {
      c.onPanLever(10);
      await c.waitForPendingCommands();
      clock.elapseMs(10);
      c.onPanLever(20);
      c.onPanLever(20, finalValue: true);
      await c.waitForPendingCommands();
      expect(sink.moveCount, 2);
      expect(sink.last, {'cmd': 'move', 'pan': 20.0, 'speed': 60});
    });
  });
}
