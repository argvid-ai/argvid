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
  });

  group('十字键盘点动', () {
    test('按下立即发送 jog 命令（含轴/方向/速度）', () {
      c.onKey('pan', 1);
      expect(sink.cmds.single, {'cmd': 'jog', 'axis': 'pan', 'dir': 1, 'speed': 10});
    });

    test('松开发送 dir=0 停止命令', () {
      c.onKey('tilt', -1);
      c.onKey('tilt', 0);
      expect(sink.cmds.last['dir'], 0);
      expect(sink.cmds.last['axis'], 'tilt');
    });

    test('jogSpeed 修改后随命令下发', () {
      c.setJogSpeed(60);
      c.onKey('pan', 1);
      expect(sink.last['speed'], 60);
    });
  });

  group('摇杆 130ms 节流', () {
    test('首次拖动立即发送 move', () {
      c.onJoystickMove(30, -45);
      expect(sink.moveCount, 1);
      expect(sink.last, {'cmd': 'move', 'pan': 30.0, 'tilt': -45.0});
    });

    test('130ms 内的连续拖动只发一次，之后恢复发送', () {
      c.onJoystickMove(10, 0); // 立即发
      clock.elapseMs(50);
      c.onJoystickMove(20, 0); // 节流窗口内，不发
      expect(sink.moveCount, 1);

      clock.elapseMs(80); // 距上次发送 130ms
      c.onJoystickMove(30, 0); // 恢复发送
      expect(sink.moveCount, 2);
      expect(sink.last['pan'], 30.0);
    });

    test('松手补发最终位置（含节流期间漏发的目标值）', () {
      c.onJoystickMove(10, 0);
      clock.elapseMs(20);
      c.onJoystickMove(15.5, 0); // 被节流
      expect(sink.moveCount, 1);

      c.onJoystickEnd(); // 松手必须送达最终值
      expect(sink.moveCount, 2);
      expect(sink.last['pan'], 15.5);
    });
  });

  group('角度边界钳位（与固件 L0 限位一致）', () {
    test('tilt 超限钳位到 ±90', () {
      c.onJoystickMove(0, 100);
      expect(sink.last['tilt'], 90.0);
      expect(c.tiltAngle, 90.0);

      clock.elapseMs(130);
      c.onJoystickMove(0, -120);
      expect(sink.last['tilt'], -90.0);
    });

    test('pan 超限钳位到 ±180', () {
      c.onJoystickMove(250, 0);
      expect(sink.last['pan'], 180.0);

      clock.elapseMs(130);
      c.onJoystickMove(-200, 0);
      expect(sink.last['pan'], -180.0);
    });

    test('边界内角度原样通过', () {
      c.onJoystickMove(180, -90);
      expect(sink.last['pan'], 180.0);
      expect(sink.last['tilt'], -90.0);
    });
  });

  group('命令精度与回中', () {
    test('角度按协议 0.1° 分辨率保留 1 位小数', () {
      c.onJoystickMove(10.126, 0);
      expect(sink.last['pan'], 10.1);
    });

    test('center 发送回中命令并清零显示与待发值', () {
      c.onJoystickMove(30, 45);
      c.center();
      expect(sink.last['cmd'], 'center');
      expect(c.panAngle, 0);
      expect(c.tiltAngle, 0);

      c.onJoystickEnd(); // 待发值已清零，不应再发 30/45
      expect(sink.last['pan'], 0.0);
      expect(sink.last['tilt'], 0.0);
    });
  });
}
