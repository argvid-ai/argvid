import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:provider/provider.dart';

import '../ble/ble_service.dart';
import '../control/gimbal_controller.dart';
import '../models/motor_params.dart';
import '../widgets/axis_lever.dart';
import '../widgets/cross_keypad.dart';
import '../widgets/joystick.dart';
import '../widgets/log_panel.dart';

/// 页面 4：云台双轴控制页（多圈位置模式，相对原点）
/// - 十字键盘：点一下走一个步进角（可调），到位自停
/// - 虚拟摇杆：按下记当前位置为圆心，拖到哪跟到哪，松手归位
/// - 单轴拉杆 ×2：水平 ±180° / 垂直 ±90°，左右拖动直接设目标角
/// - PID 调参：速度环/位置环 KP·KI，可选轴下发（缓解到位后来回抖动）
/// - 布局：键盘在上、圆盘在下，纵向排列
class GimbalControlPage extends StatefulWidget {
  const GimbalControlPage({super.key});

  @override
  State<GimbalControlPage> createState() => _GimbalControlPageState();
}

class _GimbalControlPageState extends State<GimbalControlPage> {
  final GlobalKey<JoystickState> _joystickKey = GlobalKey<JoystickState>();

  // 调参输入（留空 = 不下发该参数；预填已调好的默认值，重启 APP 免重敲）
  // 当前调优结果：位置环 KP=3 KI=15 · 加速度=50 · 速度=60
  final _speedKpCtrl = TextEditingController();
  final _speedKiCtrl = TextEditingController();
  final _posKpCtrl = TextEditingController(text: '3');
  final _posKiCtrl = TextEditingController(text: '15');
  final _accelCtrl = TextEditingController(text: '50');
  final _speedCtrl = TextEditingController(text: '60');

  /// 调参作用轴：0 双轴 / 1 水平 / 2 垂直
  int _pidAxis = 0;

  @override
  void dispose() {
    _speedKpCtrl.dispose();
    _speedKiCtrl.dispose();
    _posKpCtrl.dispose();
    _posKiCtrl.dispose();
    _accelCtrl.dispose();
    _speedCtrl.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final ble = context.watch<BleService>();
    final gimbal = context.watch<GimbalController>();
    final ready = ble.gimbal.ready && ble.isConnected;
    // 同步云台轴 ID 给 controller（速度序列下发需要；纯赋值不触发重建，
    // gimbal_state 变化会带动本页重建，因此此同步是及时的）
    gimbal.updateAxisIds(ble.gimbal.panId, ble.gimbal.tiltId);

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

            // ---------- 十字键盘（步进），在上 ----------
            CrossKeypad(
              enabled: ready,
              onJog: gimbal.onKey,
              onCenter: _center,
            ),
            const SizedBox(height: 2),
            Text(
              '点一下走一步（步进角可调）· 中心 = 双轴回原点',
              style: TextStyle(fontSize: 11, color: Colors.grey.shade500),
            ),
            const SizedBox(height: 14),

            // ---------- 摇杆圆盘（相对拖动），在下 ----------
            Joystick(
              key: _joystickKey,
              enabled: ready,
              onStart: gimbal.onJoystickStart,
              onMove: gimbal.onJoystickMove,
              onEnd: gimbal.onJoystickEnd,
            ),
            const SizedBox(height: 4),
            Text(
              '按住拖动相对跟随（当前位置为圆心）· 水平 ±180° · 垂直 ±90° · 松手圆盘归位',
              textAlign: TextAlign.center,
              style: TextStyle(fontSize: 11, color: Colors.grey.shade500),
            ),
            const SizedBox(height: 14),

            // 单轴调试拉杆
            Card(
              margin: EdgeInsets.zero,
              child: Padding(
                padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 10),
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text('单轴拉杆（左右拖动设置目标角，相对原点）',
                        style: TextStyle(
                            color: Colors.lightBlue,
                            fontWeight: FontWeight.bold,
                            fontSize: 14)),
                    const SizedBox(height: 6),
                    AxisLever(
                      label: '水平',
                      min: -180,
                      max: 180,
                      value: gimbal.panAngle,
                      enabled: ready,
                      onChanged: gimbal.onPanLever,
                      onChangeEnd: gimbal.onPanLever,
                    ),
                    AxisLever(
                      label: '垂直',
                      min: -90,
                      max: 90,
                      value: gimbal.tiltAngle,
                      enabled: ready,
                      onChanged: gimbal.onTiltLever,
                      onChangeEnd: gimbal.onTiltLever,
                    ),
                  ],
                ),
              ),
            ),
            const SizedBox(height: 14),

            // 步进角度 / 运动速度滑块
            Card(
              margin: EdgeInsets.zero,
              child: Padding(
                padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 6),
                child: Column(
                  children: [
                    Row(
                      children: [
                        const Text('步进角度'),
                        Expanded(
                          child: Slider(
                            value: gimbal.stepAngle.toDouble(),
                            min: 1,
                            max: 30,
                            divisions: 29,
                            label: '${gimbal.stepAngle}°',
                            onChanged: (v) => gimbal.setStepAngle(v.round()),
                          ),
                        ),
                        SizedBox(
                          width: 62,
                          child: Text(
                            '${gimbal.stepAngle}°',
                            textAlign: TextAlign.right,
                            style: const TextStyle(
                              fontFamily: 'monospace',
                              fontWeight: FontWeight.bold,
                            ),
                          ),
                        ),
                      ],
                    ),
                    Row(
                      children: [
                        const Text('运动速度'),
                        Expanded(
                          child: Slider(
                            value: gimbal.motionSpeed.toDouble(),
                            min: 10,
                            max: 300,
                            divisions: 29,
                            label: '${gimbal.motionSpeed} RPM',
                            onChanged: (v) => gimbal.onSpeedSlider(v.round()),
                            onChangeEnd: (v) => gimbal.onSpeedSliderEnd(v.round()),
                          ),
                        ),
                        SizedBox(
                          width: 62,
                          child: Text(
                            '${gimbal.motionSpeed} RPM',
                            textAlign: TextAlign.right,
                            style: const TextStyle(
                              fontFamily: 'monospace',
                              fontWeight: FontWeight.bold,
                              fontSize: 12,
                            ),
                          ),
                        ),
                      ],
                    ),
                    Padding(
                      padding: const EdgeInsets.only(top: 2),
                      child: Align(
                        alignment: Alignment.centerLeft,
                        child: Text(
                          '运动速度 = 位置模式最大转速（拖动实时生效，双轴同步设置）',
                          style: TextStyle(fontSize: 11, color: Colors.grey.shade500),
                        ),
                      ),
                    ),
                  ],
                ),
              ),
            ),
            const SizedBox(height: 14),

            // ---------- PID 调参 ----------
            _pidCard(context, ready),
            const SizedBox(height: 14),

            // 原点 / 回中按钮
            Row(
              children: [
                Expanded(
                  child: ElevatedButton.icon(
                    onPressed: ready ? gimbal.origin : null,
                    icon: const Icon(Icons.my_location),
                    label: const Text('当前位置设为原点'),
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
                    label: const Text('双轴回原点'),
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
              '提示：所有位置均相对「原点」（多圈位置模式，无过零绕圈问题）；'
              '机械中位标定请用电机控制台的「当前位设零点」+「保存参数」',
              style: TextStyle(fontSize: 11, color: Colors.grey.shade500),
            ),
            const SizedBox(height: 20),
          ],
        ),
      ),
    );
  }

  /// 调参卡片：当前值显示 + PID/加速度/速度 一起下发
  Widget _pidCard(BuildContext context, bool ready) {
    final ble = context.watch<BleService>();
    final panP = ble.motorParams[ble.gimbal.panId];
    final tiltP = ble.motorParams[ble.gimbal.tiltId];

    return Card(
      margin: EdgeInsets.zero,
      child: Padding(
        padding: const EdgeInsets.all(14),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text('电机调参（PID · 加速度 · 速度）',
                style: TextStyle(
                    color: Colors.lightBlue,
                    fontWeight: FontWeight.bold,
                    fontSize: 14)),
            const SizedBox(height: 10),

            // 作用轴 + 读取按钮
            Row(
              children: [
                Expanded(
                  child: SegmentedButton<int>(
                    segments: const [
                      ButtonSegment(value: 0, label: Text('双轴', style: TextStyle(fontSize: 12))),
                      ButtonSegment(value: 1, label: Text('水平', style: TextStyle(fontSize: 12))),
                      ButtonSegment(value: 2, label: Text('垂直', style: TextStyle(fontSize: 12))),
                    ],
                    selected: {_pidAxis},
                    onSelectionChanged: (s) => setState(() => _pidAxis = s.first),
                  ),
                ),
                const SizedBox(width: 8),
                OutlinedButton.icon(
                  onPressed: ready ? _readParams : null,
                  icon: const Icon(Icons.refresh),
                  label: const Text('读取'),
                ),
              ],
            ),
            const SizedBox(height: 8),

            // 当前值显示（读取后刷新）
            _curParamsRow('水平', ble.gimbal.panId, panP),
            const SizedBox(height: 4),
            _curParamsRow('垂直', ble.gimbal.tiltId, tiltP),
            const SizedBox(height: 10),

            // 新值输入（留空不下发）
            Text('新参数（留空 = 不修改该项）',
                style: TextStyle(fontSize: 12, color: Colors.grey.shade500)),
            const SizedBox(height: 6),
            Row(
              children: [
                Expanded(child: _pidField('速度环 KP', '5~50', _speedKpCtrl)),
                const SizedBox(width: 8),
                Expanded(child: _pidField('速度环 KI', '5~100', _speedKiCtrl)),
              ],
            ),
            const SizedBox(height: 8),
            Row(
              children: [
                Expanded(child: _pidField('位置环 KP', '如 5', _posKpCtrl)),
                const SizedBox(width: 8),
                Expanded(child: _pidField('位置环 KI', '如 5', _posKiCtrl)),
              ],
            ),
            const SizedBox(height: 8),
            Row(
              children: [
                Expanded(child: _pidField('加速度', '圈/s² 如 50', _accelCtrl)),
                const SizedBox(width: 8),
                Expanded(child: _pidField('速度', 'RPM 如 60', _speedCtrl)),
              ],
            ),
            const SizedBox(height: 10),
            Row(
              children: [
                Expanded(
                  child: ElevatedButton.icon(
                    onPressed: ready ? _applyPid : null,
                    icon: const Icon(Icons.tune),
                    label: const Text('下发参数'),
                    style: ElevatedButton.styleFrom(
                      backgroundColor: Colors.teal.shade700,
                    ),
                  ),
                ),
                const SizedBox(width: 10),
                Expanded(
                  child: OutlinedButton.icon(
                    onPressed: ready ? _savePid : null,
                    icon: const Icon(Icons.save),
                    label: const Text('保存到 Flash'),
                  ),
                ),
              ],
            ),
            const SizedBox(height: 6),
            Text(
              '「读取」：加速度为实测值；PID/速度协议读不回，显示固件记录的本次上电下发值，'
              '「未设置」= 电机用 Flash 内参数。手册建议：速度环 KP 5~50、KI 5~100 从小往大试，'
              '到位后来回抖动时降低 KP/KI；调试时电机须固定牢固。',
              style: TextStyle(fontSize: 11, color: Colors.grey.shade500),
            ),
          ],
        ),
      ),
    );
  }

  /// 当前参数行（读取结果）
  Widget _curParamsRow(String axisName, int addr, MotorParams? p) {
    final text = p == null ? '未读取' : p.summary();
    return Row(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        SizedBox(
          width: 70,
          child: Text('$axisName ID$addr',
              style: const TextStyle(fontSize: 12, fontWeight: FontWeight.bold)),
        ),
        Expanded(
          child: Text(
            text,
            style: TextStyle(
              fontSize: 11,
              fontFamily: 'monospace',
              color: Colors.grey.shade400,
            ),
          ),
        ),
      ],
    );
  }

  Widget _pidField(String label, String hint, TextEditingController ctrl) {
    return TextField(
      controller: ctrl,
      enabled: true,
      keyboardType: TextInputType.number,
      inputFormatters: [FilteringTextInputFormatter.digitsOnly],
      decoration: InputDecoration(
        labelText: label,
        hintText: hint,
        isDense: true,
        border: const OutlineInputBorder(),
      ),
      style: const TextStyle(fontSize: 14),
    );
  }

  /// 读取当前参数（所选轴；加速度为实测，PID/速度为固件缓存值）
  void _readParams() {
    final ble = context.read<BleService>();
    final g = ble.gimbal;
    final addrs = switch (_pidAxis) {
      1 => [g.panId],
      2 => [g.tiltId],
      _ => [g.panId, g.tiltId],
    };
    for (final addr in addrs) {
      if (addr < 1) continue;
      ble.sendCmd({'cmd': 'get_params', 'addr': addr});
    }
  }

  /// 参数下发到所选轴（留空的参数跳过不发）
  void _applyPid() {
    final ble = context.read<BleService>();
    final g = ble.gimbal;
    final addrs = switch (_pidAxis) {
      1 => [g.panId],
      2 => [g.tiltId],
      _ => [g.panId, g.tiltId],
    };

    // 命令与值的映射（只收集已填写的）
    final params = <String, int>{};
    final skp = int.tryParse(_speedKpCtrl.text);
    final ski = int.tryParse(_speedKiCtrl.text);
    final pkp = int.tryParse(_posKpCtrl.text);
    final pki = int.tryParse(_posKiCtrl.text);
    final acc = int.tryParse(_accelCtrl.text);
    final spd = int.tryParse(_speedCtrl.text);
    if (skp != null) params['set_speed_kp'] = skp;
    if (ski != null) params['set_speed_ki'] = ski;
    if (pkp != null) params['set_pos_kp'] = pkp;
    if (pki != null) params['set_pos_ki'] = pki;
    if (acc != null) params['set_accel'] = acc;
    if (spd != null) params['set_speed'] = spd;
    if (params.isEmpty) {
      ScaffoldMessenger.of(context).showSnackBar(
        const SnackBar(content: Text('请至少填写一项参数')),
      );
      return;
    }

    for (final addr in addrs) {
      if (addr < 1) continue;
      params.forEach((cmd, val) {
        if (cmd == 'set_accel') {
          ble.sendCmd({'cmd': cmd, 'addr': addr, 'accel': val});
        } else if (cmd == 'set_speed') {
          ble.sendCmd({'cmd': cmd, 'addr': addr, 'rpm': val});
        } else {
          ble.sendCmd({'cmd': cmd, 'addr': addr, 'val': val});
        }
      });
    }
  }

  /// 保存参数到所选轴（掉电不丢）
  void _savePid() {
    final ble = context.read<BleService>();
    final g = ble.gimbal;
    final addrs = switch (_pidAxis) {
      1 => [g.panId],
      2 => [g.tiltId],
      _ => [g.panId, g.tiltId],
    };
    for (final addr in addrs) {
      if (addr < 1) continue;
      ble.sendCmd({'cmd': 'save', 'addr': addr});
    }
  }

  /// 回原点（键盘中心按钮 / 回中按钮共用），同时摇杆 UI 归位
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

