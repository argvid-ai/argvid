import 'dart:convert';

import 'package:flutter_test/flutter_test.dart';
import 'package:gimbal_app/ble/ble_json_receiver.dart';

// Wire-format fixture builder, independent of the receiver.
List<List<int>> fragments(List<int> bytes, {int mtu = 247, int id = 0x1234}) {
  final result = <List<int>>[];
  final size = mtu - 3 - 8;
  for (var offset = 0; offset < bytes.length; offset += size) {
    final end = (offset + size).clamp(0, bytes.length);
    result.add([
      0xf3,
      0x2c,
      id & 255,
      id >> 8,
      offset & 255,
      offset >> 8,
      bytes.length & 255,
      bytes.length >> 8,
      ...bytes.sublist(offset, end),
    ]);
  }
  return result;
}

void main() {
  late List<Map<String, dynamic>> events;
  late List<String> errors;
  late BleJsonReceiver receiver;
  final scanLog = {
    'event': 'log',
    'lines':
        List.generate(40, (i) => '[TX] 7A ${i.toRadixString(16)} 0E 04 74 7B'),
  };

  setUp(() {
    events = [];
    errors = [];
    receiver = BleJsonReceiver(onEvent: events.add, onError: errors.add);
  });
  tearDown(() => receiver.reset());

  test('初始空值忽略，旧固件完整 JSON 正常分发', () {
    receiver.add([]);
    receiver.add(utf8.encode('{"event":"cmd_result","ok":true}'));
    expect(errors, isEmpty);
    expect(events.single['ok'], isTrue);
  });

  for (final mtu in [23, 247]) {
    test('MTU $mtu：扫描 40 行日志收齐前不解析，最后完整分发一次', () {
      final packets = fragments(utf8.encode(jsonEncode(scanLog)), mtu: mtu);
      expect(packets.length, greaterThan(1));
      for (final packet in packets.take(packets.length - 1)) {
        expect(packet.length, lessThanOrEqualTo(mtu - 3));
        receiver.add(packet);
        expect(events, isEmpty);
        expect(errors, isEmpty);
      }
      receiver.add(packets.last);
      expect(events, [scanLog]);
      expect(errors, isEmpty);
    });
  }

  test('中文 UTF-8 字节跨包，收齐后解码且保留转义字符', () {
    final value = {'event': 'cmd_result', 'msg': '两轴当前位置已记为原点（0°）\n"确认"\\'};
    final packets = fragments(utf8.encode(jsonEncode(value)), mtu: 23);
    expect(packets.any((p) => p[8] >= 0x80 && p[8] < 0xc0), isTrue);
    packets.forEach(receiver.add);
    expect(events, [value]);
    expect(errors, isEmpty);
  });

  test('实际截断的旧日志仍报错，不把无效 JSON 隐藏掉', () {
    receiver.add(utf8.encode(jsonEncode(scanLog)).take(244).toList());
    expect(events, isEmpty);
    expect(errors.single, contains('JSON 解析失败'));
  });

  test('缺包拒绝，下一条新消息可恢复', () {
    final packets = fragments(utf8.encode(jsonEncode(scanLog)));
    receiver.add(packets[0]);
    receiver.add(packets[2]);
    expect(events, isEmpty);
    expect(errors.single, contains('分包缺失或顺序错误'));
    receiver.add(utf8.encode('{"event":"query_result","value":12}'));
    expect(events.single['value'], 12);
  });

  test('截短中间分包不能被后续包当作完整消息', () {
    final packets = fragments(utf8.encode(jsonEncode(scanLog)));
    receiver.add(packets.first.sublist(0, 100));
    receiver.add(packets[1]);
    expect(events, isEmpty);
    expect(errors.single, contains('分包缺失或顺序错误'));
  });

  test('不同消息 ID 和重复包拒绝', () {
    final packets = fragments(utf8.encode(jsonEncode(scanLog)));
    receiver.add(packets.first);
    final wrongId = List<int>.of(packets[1])..[2] = 99;
    receiver.add(wrongId);
    expect(events, isEmpty);
    expect(errors.length, 1);
    errors.clear();
    receiver.add(packets.first);
    receiver.add(packets[1]);
    receiver.add(packets[1]);
    expect(events, isEmpty);
    expect(errors.length, 1);
  });

  test('新分包取代未完成消息时报告一次丢失，能接收新消息', () {
    receiver.add(fragments(utf8.encode(jsonEncode(scanLog))).first);
    final next = {'event': 'cmd_result', 'ok': false, 'msg': '验证失败'};
    fragments(utf8.encode(jsonEncode(next)), mtu: 23, id: 0x1235)
        .forEach(receiver.add);
    expect(events, [next]);
    expect(errors.single, contains('未收齐'));
  });

  testWidgets('末包丢失超时报错，清理后允许重试', (tester) async {
    receiver.add(fragments(utf8.encode(jsonEncode(scanLog))).first);
    await tester.pump(const Duration(seconds: 4));
    expect(events, isEmpty);
    expect(errors.single, contains('接收超时'));
    fragments(utf8.encode(jsonEncode(scanLog))).forEach(receiver.add);
    expect(events, [scanLog]);
  });

  testWidgets('断链 reset 清空残包并取消计时器', (tester) async {
    final packets = fragments(utf8.encode(jsonEncode(scanLog)));
    receiver.add(packets.first);
    receiver.reset();
    await tester.pump(const Duration(seconds: 4));
    expect(errors, isEmpty);
    receiver.add(packets[1]);
    expect(events, isEmpty);
    expect(errors.single, contains('分包缺失或顺序错误'));
  });

  test('两个特征可交错接收，不混用重组缓冲', () {
    final statusEvents = <Map<String, dynamic>>[];
    final status =
        BleJsonReceiver(onEvent: statusEvents.add, onError: errors.add);
    addTearDown(status.reset);
    final responsePackets =
        fragments(utf8.encode(jsonEncode(scanLog)), mtu: 23);
    final state = {'event': 'sys_status', 'pan': 1, 'tilt': 2};
    final statusPackets = fragments(utf8.encode(jsonEncode(state)), mtu: 23);
    for (var i = 0; i < responsePackets.length; i++) {
      receiver.add(responsePackets[i]);
      if (i < statusPackets.length) status.add(statusPackets[i]);
    }
    expect(events, [scanLog]);
    expect(statusEvents, [state]);
    expect(errors, isEmpty);
  });

  test('无效包头、超限长度和非对象 JSON 拒绝', () {
    for (final packet in [
      [0xf3],
      [0xf3, 0x2c, 1, 0, 0, 0, 1, 0x40, 0x7b],
      [0xf3, 0x2c, 1, 0, 0, 0, 0, 0, 0x7b],
      utf8.encode('[]'),
      utf8.encode('x' * 8193),
    ]) {
      receiver.add(packet);
    }
    expect(events, isEmpty);
    expect(errors.length, 5);
  });
}
