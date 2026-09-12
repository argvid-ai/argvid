import 'package:flutter_test/flutter_test.dart';

import 'package:gimbal_app/ble/command_sink.dart';
import 'package:gimbal_app/control/gimbal_controller.dart';

/// 假命令发送端：记录所有下发的命令
class _FakeSink implements CommandSink {
  final List<Map<String, dynamic>> cmds = [];

  @override
  Future<void> sendCmd(Map<String, dynamic> cmd) async => cmds.add(cmd);

  Map<String, dynamic> get last => cmds.last;
  int get moveCount => cmds.where((c) => c['cmd'] == 'move').length;
}

/// 假时钟：手动推进，验证 130ms 节流
class _FakeClock {
  DateTime _now = DateTime(2026, 1, 1, 0, 0, 0);
  DateTime call() => _now;
  void elapseMs(int ms) => _now = _now.add(Duration(milliseconds: ms));
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
    test('点一下走一个步进角：发 move 而非 jog', () {
      c.onKey('pan', 1);
      expect(sink.cmds.single['cmd'], 'move');
      expect(sink.cmds.single['pan'], 5.0);   // 默认步进 5°
      expect(sink.cmds.single['tilt'], 0.0);
    });

    test('连续点击累积步进，反方向递减', () {
      c.onKey('tilt', 1);   // +5
      c.onKey('tilt', 1);   // +10
      c.onKey('tilt', -1);  // +5
      expect(sink.last['tilt'], 5.0);
    });

    test('松开（dir=0）不发任何命令：位置模式到位自停', () {
      c.onKey('pan', 1);
      final n = sink.cmds.length;
      c.onKey('pan', 0);
      expect(sink.cmds.length, n);   // 无新增命令
    });

    test('步进越界钳位：tilt 最多到 ±90', () {
      for (int i = 0; i < 30; i++) {
        c.onKey('tilt', 1);
      }
      expect(c.tiltAngle, 90.0);
      expect(sink.last['tilt'], 90.0);
    });

    test('步进角可调', () {
      c.setStepAngle(10);
      c.onKey('pan', -1);
      expect(sink.last['pan'], -10.0);
    });
  });

  group('摇杆相对拖动', () {
    test('按下记录圆心：拖动目标 = 起点 + 偏移×满量程', () {
      c.onKey('pan', 1);      // 当前 pan = 5
      c.onKey('tilt', 1);     // 当前 tilt = 5
      c.onJoystickStart();    // 圆心 = (5, 5)
      c.onJoystickMove(0.5, -1.0);
      // pan = 5 + 0.5*180 = 95；tilt = 5 + (-1)*90 = -85
      expect(sink.last['pan'], 95.0);
      expect(sink.last['tilt'], -85.0);
    });

    test('130ms 节流：窗口内只发一次，之后恢复', () {
      c.onJoystickStart();
      c.onJoystickMove(0.1, 0);   // 立即发
      clock.elapseMs(50);
      c.onJoystickMove(0.2, 0);   // 节流窗口内
      expect(sink.moveCount, 1);

      clock.elapseMs(80);         // 距上次发送 130ms
      c.onJoystickMove(0.3, 0);
      expect(sink.moveCount, 2);
    });

    test('相对拖动钳位：圆心+偏移不超 pan ±180 / tilt ±90', () {
      c.onJoystickStart();        // 圆心 (0,0)
      c.onJoystickMove(1.5, 1.5); // 偏移超满量程
      expect(sink.last['pan'], 180.0);
      expect(sink.last['tilt'], 90.0);
    });

    test('松手补发最终位置', () {
      c.onJoystickStart();
      c.onJoystickMove(0.1, 0);
      clock.elapseMs(20);
      c.onJoystickMove(0.7, 0.2);  // 被节流
      expect(sink.moveCount, 1);

      c.onJoystickEnd();           // 松手必须送达最终值
      expect(sink.moveCount, 2);
      expect(sink.last['pan'], 126.0);   // 0.7*180
    });
  });

  group('拉杆：单轴绝对目标角', () {
    test('水平拉杆直接设置目标并节流', () {
      c.onPanLever(-30.0);
      expect(sink.last['cmd'], 'move');
      expect(sink.last['pan'], -30.0);

      clock.elapseMs(130);
      c.onPanLever(45.5);
      expect(sink.last['pan'], 45.5);
    });

    test('垂直拉杆钳位 ±90', () {
      c.onTiltLever(120);
      expect(sink.last['tilt'], 90.0);
      clock.elapseMs(130);
      c.onTiltLever(-100);
      expect(sink.last['tilt'], -90.0);
    });
  });

  group('角度精度与原点操作', () {
    test('角度按协议 0.1° 分辨率保留 1 位小数', () {
      c.onPanLever(10.126);
      expect(sink.last['pan'], 10.1);
    });

    test('center 发命令并清零全部坐标（含拖动圆心）', () {
      c.onKey('pan', 1);
      c.onKey('tilt', 1);
      c.center();
      expect(sink.last['cmd'], 'center');
      expect(c.panAngle, 0);
      expect(c.tiltAngle, 0);

      c.onJoystickStart();   // 圆心也应已归零
      c.onJoystickMove(0.0, 0.0);
      expect(sink.last['pan'], 0.0);
    });

    test('origin 发 origin 命令并清零坐标（多圈原点）', () {
      c.onKey('pan', 2);
      c.origin();
      expect(sink.last['cmd'], 'origin');
      expect(c.panAngle, 0);
      expect(c.tiltAngle, 0);
    });
  });

  group('运动速度序列（T 型规划最大速度）', () {
    test('滑块拖动中仅更新显示，松手下发完整序列', () {
      c.onSpeedSlider(90);
      expect(sink.cmds, isEmpty);          // 拖动中不下发

      c.onSpeedSliderEnd(90);
      // 双轴各 3 条（set_mode/enable/set_speed）+ 1 条 move 回当前目标
      expect(sink.cmds.length, 7);
      expect(sink.cmds[0], {'cmd': 'set_mode', 'addr': 1, 'mode': 1});
      expect(sink.cmds[1], {'cmd': 'enable', 'addr': 1});
      expect(sink.cmds[2], {'cmd': 'set_speed', 'addr': 1, 'rpm': 90});
      expect(sink.cmds[3], {'cmd': 'set_mode', 'addr': 2, 'mode': 1});
      expect(sink.cmds[6]['cmd'], 'move');
      expect(c.motionSpeed, 90);
    });

    test('轴 ID 未同步（0）时跳过该轴', () {
      c.updateAxisIds(0, 2);   // pan 未配置
      c.onSpeedSliderEnd(60);
      expect(sink.cmds.length, 4);   // tilt 3 条 + move
      expect(sink.cmds.any((x) => x['addr'] == 0), isFalse);
    });
  });
}
