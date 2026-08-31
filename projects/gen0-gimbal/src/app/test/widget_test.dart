import 'package:flutter_test/flutter_test.dart';

import 'package:gimbal_app/models/gimbal_state.dart';
import 'package:gimbal_app/models/motor.dart';
import 'package:gimbal_app/utils/log_formatter.dart';

void main() {
  group('Motor 模型', () {
    test('addrHex 十六进制格式', () {
      final m = Motor(id: 2, volt: 12.05);
      expect(m.addrHex, '0x02');
      final m2 = Motor(id: 16, volt: 11.98);
      expect(m2.addrHex, '0x10');
    });
  });

  group('GimbalInfo 模型', () {
    test('ready 判断', () {
      expect(const GimbalInfo().ready, isFalse);
      expect(const GimbalInfo(panId: 2, tiltId: 3).ready, isTrue);
    });

    test('copyWith 保留未指定字段', () {
      final g = const GimbalInfo(panId: 2, tiltId: 3, panAngle: 30, tiltAngle: -45);
      final g2 = g.copyWith(panAngle: 90);
      expect(g2.panId, 2);
      expect(g2.tiltId, 3);
      expect(g2.panAngle, 90);
      expect(g2.tiltAngle, -45);
    });
  });

  group('WifiInfo 模型', () {
    test('connected 状态', () {
      expect(const WifiInfo(status: 'connected').connected, isTrue);
      expect(const WifiInfo(status: 'connecting').connected, isFalse);
      expect(const WifiInfo().connected, isFalse);
    });
  });

  group('日志工具', () {
    test('formatHex 十六进制格式化', () {
      expect(formatHex([0x7A, 0x02, 0x06, 0x04]), '7A 02 06 04');
      expect(formatHex([]), '');
      expect(formatHex([0x7B]), '7B');
    });

    test('logColor 方向颜色', () {
      expect(logColor('TX'), isNot(equals(logColor('RX'))));
      expect(logColor('ERR'), 0xFFF87171);
    });
  });
}
