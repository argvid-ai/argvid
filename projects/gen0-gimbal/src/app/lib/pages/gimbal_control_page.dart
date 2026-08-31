import 'package:flutter/material.dart';
import 'package:provider/provider.dart';

import '../ble/ble_service.dart';
import '../control/gimbal_controller.dart';
import '../widgets/cross_keypad.dart';
import '../widgets/joystick.dart';
import '../widgets/log_panel.dart';

/// 页面 4：云台双轴控制页
/// - 十字键盘：按住持续转 / 松开停 / 中心回中（两轴可同时按）
/// - 虚拟摇杆：拖动位置随动（pan ±180°，tilt ±90°，130ms 节流）
/// - 设零按钮：当前位置设为单圈 0°
/// - 实时角度显示
class GimbalControlPage extends StatefulWidget {
  const GimbalControlPage({super.key});

  @override
  State<GimbalControlPage> createState() => _GimbalControlPageState();
}

class _GimbalControlPageState extends State<GimbalControlPage> {
  final GlobalKey<JoystickState> _joystickKey = GlobalKey<JoystickState>();

  @override
  Widget build(BuildContext context) {
    final ble = context.watch<BleService>();
    final gimbal = context.watch<GimbalController>();
    final ready = ble.gimbal.ready && ble.isConnected;

    return Scaffold(
      appBar: AppBar(
        title: const Text('云台双轴控制'),
        actions: [
          IconButton(
            icon: const Icon(Icons.list_alt),
            tooltip: '通信日志',
            onPressed: () => showLogPanel(context),
          ),
        ],
      ),
      body: SingleChildScrollView(
        padding: const EdgeInsets.all(12),
        child: Column(
          children: [
            // 状态提示条
            if (!ready)
              Container(
                width: double.infinity,
                padding: const EdgeInsets.all(10),
                margin: const EdgeInsets.only(bottom: 10),
                decoration: BoxDecoration(
                  color: Colors.orange.shade900,
                  borderRadius: BorderRadius.circular(8),
                ),
                child: Text(
                  ble.isConnected
                      ? '云台未配置：请返回电机控制台扫描或填写两轴 ID'
                      : '蓝牙未连接，请返回重连',
                  style: const TextStyle(color: Colors.white, fontSize: 13),
                ),
              ),

            // 配置信息
            Card(
              margin: EdgeInsets.zero,
              child: Padding(
                padding: const EdgeInsets.all(12),
                child: Row(
                  mainAxisAlignment: MainAxisAlignment.spaceAround,
                  children: [
                    _infoChip('水平轴', 'ID ${ble.gimbal.panId}'),
                    _infoChip('垂直轴', 'ID ${ble.gimbal.tiltId}'),
                    _infoChip(
                      '角度',
                      'pan ${gimbal.panAngle.toStringAsFixed(1)}° / '
                      'tilt ${gimbal.tiltAngle.toStringAsFixed(1)}°',
                    ),
                  ],
                ),
              ),
            ),
            const SizedBox(height: 14),

            // 操作区：十字键盘 + 摇杆
            Row(
              mainAxisAlignment: MainAxisAlignment.spaceEvenly,
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                // 左：十字键盘
                Column(
                  children: [
                    CrossKeypad(
                      enabled: ready,
                      onJog: gimbal.onKey,
                      onCenter: _center,
                    ),
                  ],
                ),
                // 右：摇杆
                Column(
                  children: [
                    Joystick(
                      key: _joystickKey,
                      enabled: ready,
                      onMove: (panNorm, tiltNorm) =>
                          gimbal.onJoystickMove(panNorm * 180, tiltNorm * 90),
                      onEnd: gimbal.onJoystickEnd,
                    ),
                    const SizedBox(height: 4),
                    Text(
                      '拖动摇杆位置随动\n水平 ±180° · 垂直 ±90°',
                      textAlign: TextAlign.center,
                      style: TextStyle(fontSize: 11, color: Colors.grey.shade500),
                    ),
                  ],
                ),
              ],
            ),
            const SizedBox(height: 14),

            // 点动速度滑块
            Card(
              margin: EdgeInsets.zero,
              child: Padding(
                padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 6),
                child: Row(
                  children: [
                    const Text('点动速度'),
                    Expanded(
                      child: Slider(
                        value: gimbal.jogSpeed.toDouble(),
                        min: 1,
                        max: 120,
                        divisions: 119,
                        label: '${gimbal.jogSpeed} RPM',
                        onChanged: (v) => gimbal.setJogSpeed(v.round()),
                      ),
                    ),
                    SizedBox(
                      width: 62,
                      child: Text(
                        '${gimbal.jogSpeed} RPM',
                        textAlign: TextAlign.right,
                        style: const TextStyle(
                          fontFamily: 'monospace',
                          fontWeight: FontWeight.bold,
                        ),
                      ),
                    ),
                  ],
                ),
              ),
            ),
            const SizedBox(height: 12),

            // 设零 / 回中按钮
            Row(
              children: [
                Expanded(
                  child: ElevatedButton.icon(
                    onPressed: ready ? gimbal.zero : null,
                    icon: const Icon(Icons.my_location),
                    label: const Text('设当前位置为 0°'),
                    style: ElevatedButton.styleFrom(
                      backgroundColor: Colors.amber.shade800,
                      padding: const EdgeInsets.symmetric(vertical: 12),
                    ),
                  ),
                ),
                const SizedBox(width: 10),
                Expanded(
                  child: ElevatedButton.icon(
                    onPressed: ready ? _center : null,
                    icon: const Icon(Icons.center_focus_strong),
                    label: const Text('双轴回中'),
                    style: ElevatedButton.styleFrom(
                      backgroundColor: Colors.blue.shade700,
                      padding: const EdgeInsets.symmetric(vertical: 12),
                    ),
                  ),
                ),
              ],
            ),
            const SizedBox(height: 12),

            // 最近结果
            Card(
              margin: EdgeInsets.zero,
              child: Padding(
                padding: const EdgeInsets.all(12),
                child: SizedBox(
                  width: double.infinity,
                  child: Text(
                    ble.lastResult,
                    style: TextStyle(
                      color: ble.lastResultOk ? Colors.greenAccent : Colors.redAccent,
                      fontFamily: 'monospace',
                      fontSize: 13,
                      height: 1.5,
                    ),
                  ),
                ),
              ),
            ),
            const SizedBox(height: 8),
            Text(
              '提示：设零后建议在电机控制台执行「保存参数」永久写入',
              style: TextStyle(fontSize: 11, color: Colors.grey.shade500),
            ),
            const SizedBox(height: 20),
          ],
        ),
      ),
    );
  }

  /// 回中（键盘中心按钮 / 回中按钮共用），同时摇杆 UI 归位
  void _center() {
    context.read<GimbalController>().center();
    _joystickKey.currentState?.reset();
  }

  Widget _infoChip(String k, String v) {
    return Column(
      children: [
        Text(k, style: TextStyle(fontSize: 11, color: Colors.grey.shade500)),
        const SizedBox(height: 2),
        Text(
          v,
          style: const TextStyle(
            fontSize: 13,
            fontWeight: FontWeight.bold,
            fontFamily: 'monospace',
          ),
        ),
      ],
    );
  }
}
