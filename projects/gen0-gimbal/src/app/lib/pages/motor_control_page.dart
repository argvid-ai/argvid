import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:provider/provider.dart';

import '../ble/ble_service.dart';
import '../widgets/log_panel.dart';
import '../widgets/motor_card.dart';

/// 页面 3：电机控制台
/// - 扫描总线，电机列表选择当前控制对象
/// - 单电机控制：使能/失能、模式、速度、角度、查询、维护
/// - 云台配置区（pan/tilt ID）→ 进入云台控制页
class MotorControlPage extends StatefulWidget {
  const MotorControlPage({super.key});

  @override
  State<MotorControlPage> createState() => _MotorControlPageState();
}

class _MotorControlPageState extends State<MotorControlPage> {
  // 单电机控制输入
  final _speedCtrl = TextEditingController(text: '60');
  final _singleAngleCtrl = TextEditingController(text: '90');
  final _multiAngleCtrl = TextEditingController(text: '360');
  final _accelCtrl = TextEditingController(text: '100');
  final _newAddrCtrl = TextEditingController();
  final _panIdCtrl = TextEditingController();
  final _tiltIdCtrl = TextEditingController();
  int _mode = 0;

  @override
  void initState() {
    super.initState();
    // 固件上报的云台配置自动填充到输入框
    final g = context.read<BleService>().gimbal;
    if (g.panId > 0) _panIdCtrl.text = g.panId.toString();
    if (g.tiltId > 0) _tiltIdCtrl.text = g.tiltId.toString();
  }

  @override
  void dispose() {
    _speedCtrl.dispose();
    _singleAngleCtrl.dispose();
    _multiAngleCtrl.dispose();
    _accelCtrl.dispose();
    _newAddrCtrl.dispose();
    _panIdCtrl.dispose();
    _tiltIdCtrl.dispose();
    super.dispose();
  }

  int get _addr => context.read<BleService>().selectedAddr ?? 2;

  void _send(Map<String, dynamic> cmd) {
    context.read<BleService>().sendCmd(cmd);
  }

  void _sendWithAddr(Map<String, dynamic> cmd) {
    _send({'addr': _addr, ...cmd});
  }

  @override
  Widget build(BuildContext context) {
    final ble = context.watch<BleService>();
    final selected = ble.selectedAddr;

    return Scaffold(
      appBar: AppBar(
        title: Text(selected != null ? '电机控制台 · ID $selected' : '电机控制台'),
        actions: [
          IconButton(
            icon: const Icon(Icons.list_alt),
            tooltip: '通信日志',
            onPressed: () => showLogPanel(context),
          ),
          IconButton(
            icon: const Icon(Icons.link_off),
            tooltip: '断开蓝牙',
            onPressed: () async {
              await ble.disconnect();
              if (context.mounted) {
                Navigator.of(context).pushNamedAndRemoveUntil('/', (r) => false);
              }
            },
          ),
        ],
      ),
      body: SingleChildScrollView(
        padding: const EdgeInsets.all(12),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            // ---------- 扫描与电机列表 ----------
            _sectionCard(
              title: '电机总线',
              child: Column(
                children: [
                  Row(
                    children: [
                      Expanded(
                        child: ElevatedButton.icon(
                          onPressed:
                              (ble.motorScanning || !ble.isConnected) ? null : ble.scanMotors,
                          icon: ble.motorScanning
                              ? const SizedBox(
                                  width: 16,
                                  height: 16,
                                  child: CircularProgressIndicator(strokeWidth: 2, color: Colors.white),
                                )
                              : const Icon(Icons.search),
                          label: Text(ble.motorScanning ? '扫描中…' : '扫描总线电机 (ID 1~16)'),
                        ),
                      ),
                    ],
                  ),
                  if (ble.motors.isEmpty)
                    Padding(
                      padding: const EdgeInsets.symmetric(vertical: 14),
                      child: Text(
                        ble.motorScanning ? '正在扫描…' : '尚未扫描或未发现电机',
                        style: TextStyle(color: Colors.grey.shade500, fontSize: 13),
                        textAlign: TextAlign.center,
                      ),
                    )
                  else
                    ...ble.motors.map(
                      (m) => MotorCard(
                        motor: m,
                        selected: m.id == selected,
                        onTap: () => ble.selectMotor(m.id),
                      ),
                    ),
                ],
              ),
            ),
            const SizedBox(height: 12),

            // ---------- 使能 / 模式 ----------
            _sectionCard(
              title: '使能 / 模式',
              child: Column(
                children: [
                  Row(
                    children: [
                      Expanded(
                        child: ElevatedButton(
                          onPressed: selected == null
                              ? null
                              : () => _sendWithAddr({'cmd': 'enable'}),
                          style: ElevatedButton.styleFrom(backgroundColor: Colors.green.shade700),
                          child: const Text('使能电机'),
                        ),
                      ),
                      const SizedBox(width: 8),
                      Expanded(
                        child: ElevatedButton(
                          onPressed: selected == null
                              ? null
                              : () => _sendWithAddr({'cmd': 'disable'}),
                          style: ElevatedButton.styleFrom(backgroundColor: Colors.red.shade700),
                          child: const Text('失能电机'),
                        ),
                      ),
                      const SizedBox(width: 8),
                      Expanded(
                        child: ElevatedButton(
                          onPressed: selected == null
                              ? null
                              : () => _sendWithAddr({'cmd': 'test'}),
                          style: ElevatedButton.styleFrom(backgroundColor: Colors.purple.shade700),
                          child: const Text('联通测试'),
                        ),
                      ),
                    ],
                  ),
                  const SizedBox(height: 10),
                  Row(
                    children: [
                      Expanded(
                        child: DropdownButtonFormField<int>(
                          value: _mode,
                          decoration: const InputDecoration(
                            labelText: '控制模式',
                            border: OutlineInputBorder(),
                            isDense: true,
                          ),
                          items: const [
                            DropdownMenuItem(value: 0, child: Text('0 速度模式', style: TextStyle(fontSize: 13))),
                            DropdownMenuItem(value: 1, child: Text('1 多圈位置 (T型)', style: TextStyle(fontSize: 13))),
                            DropdownMenuItem(value: 2, child: Text('2 单圈位置 (T型)', style: TextStyle(fontSize: 13))),
                            DropdownMenuItem(value: 3, child: Text('3 多圈位置 (直通)', style: TextStyle(fontSize: 13))),
                            DropdownMenuItem(value: 4, child: Text('4 单圈位置 (直通)', style: TextStyle(fontSize: 13))),
                          ],
                          onChanged: (v) => setState(() => _mode = v ?? 0),
                        ),
                      ),
                      const SizedBox(width: 8),
                      ElevatedButton(
                        onPressed: selected == null
                            ? null
                            : () => _sendWithAddr({'cmd': 'set_mode', 'mode': _mode}),
                        child: const Text('设置'),
                      ),
                    ],
                  ),
                ],
              ),
            ),
            const SizedBox(height: 12),

            // ---------- 速度 / 位置 / 加速度 ----------
            _sectionCard(
              title: '速度 / 位置 / 加速度',
              child: Column(
                children: [
                  _numFieldRow(
                    label: '目标速度',
                    unit: 'RPM',
                    controller: _speedCtrl,
                    onSend: () => _sendWithAddr({
                      'cmd': 'set_speed',
                      'rpm': int.tryParse(_speedCtrl.text) ?? 0,
                    }),
                  ),
                  _numFieldRow(
                    label: '多圈角度',
                    unit: '°',
                    controller: _multiAngleCtrl,
                    onSend: () => _sendWithAddr({
                      'cmd': 'set_multi_angle',
                      'angle': double.tryParse(_multiAngleCtrl.text) ?? 0,
                    }),
                  ),
                  _numFieldRow(
                    label: '单圈角度',
                    unit: '° (0~359.9)',
                    controller: _singleAngleCtrl,
                    onSend: () => _sendWithAddr({
                      'cmd': 'set_angle',
                      'angle': double.tryParse(_singleAngleCtrl.text) ?? 0,
                    }),
                  ),
                  _numFieldRow(
                    label: '加速度',
                    unit: '圈/s²',
                    controller: _accelCtrl,
                    onSend: () => _sendWithAddr({
                      'cmd': 'set_accel',
                      'accel': int.tryParse(_accelCtrl.text) ?? 0,
                    }),
                  ),
                ],
              ),
            ),
            const SizedBox(height: 12),

            // ---------- 读取反馈 ----------
            _sectionCard(
              title: '读取电机反馈',
              child: Wrap(
                spacing: 8,
                runSpacing: 8,
                children: [
                  for (final e in const [
                    ('voltage', '母线电压'),
                    ('speed', '当前转速'),
                    ('total_angle', '总转角'),
                    ('mech_angle', '机械角度'),
                    ('accel', '加速度'),
                  ])
                    ElevatedButton(
                      onPressed: selected == null
                          ? null
                          : () => _sendWithAddr({'cmd': 'query', 'type': e.$1}),
                      style: ElevatedButton.styleFrom(
                        backgroundColor: Colors.amber.shade800,
                      ),
                      child: Text(e.$2, style: const TextStyle(fontSize: 13)),
                    ),
                ],
              ),
            ),
            const SizedBox(height: 12),

            // ---------- 维护 ----------
            _sectionCard(
              title: '维护 / 地址',
              child: Column(
                children: [
                  Wrap(
                    spacing: 8,
                    runSpacing: 8,
                    children: [
                      ElevatedButton(
                        onPressed: selected == null
                            ? null
                            : () => _sendWithAddr({'cmd': 'save'}),
                        child: const Text('保存参数'),
                      ),
                      ElevatedButton(
                        onPressed: selected == null
                            ? null
                            : () => _sendWithAddr({'cmd': 'clear_total'}),
                        child: const Text('总角度清零'),
                      ),
                      ElevatedButton(
                        onPressed: selected == null
                            ? null
                            : () => _sendWithAddr({'cmd': 'set_zero'}),
                        child: const Text('当前位设零点'),
                      ),
                      ElevatedButton(
                        onPressed: selected == null
                            ? null
                            : () async {
                                final ok = await showDialog<bool>(
                                  context: context,
                                  builder: (ctx) => AlertDialog(
                                    title: const Text('恢复出厂设置？'),
                                    content: const Text('将清除电机全部参数，此操作不可撤销。'),
                                    actions: [
                                      TextButton(
                                        onPressed: () => Navigator.pop(ctx, false),
                                        child: const Text('取消'),
                                      ),
                                      TextButton(
                                        onPressed: () => Navigator.pop(ctx, true),
                                        child: const Text('确认'),
                                      ),
                                    ],
                                  ),
                                );
                                if (ok == true) {
                                  _sendWithAddr({'cmd': 'factory_reset'});
                                }
                              },
                        style: ElevatedButton.styleFrom(backgroundColor: Colors.red.shade700),
                        child: const Text('恢复出厂'),
                      ),
                    ],
                  ),
                  const SizedBox(height: 10),
                  _numFieldRow(
                    label: '改电机地址',
                    unit: 'ID 1~127',
                    controller: _newAddrCtrl,
                    hint: '新编号',
                    onSend: () => _sendWithAddr({
                      'cmd': 'setaddr',
                      'new_addr': int.tryParse(_newAddrCtrl.text) ?? 0,
                    }),
                  ),
                  const Padding(
                    padding: EdgeInsets.only(top: 4),
                    child: Text(
                      '改地址后需再点「保存参数」才永久写入 Flash',
                      style: TextStyle(fontSize: 11, color: Colors.grey),
                    ),
                  ),
                ],
              ),
            ),
            const SizedBox(height: 12),

            // ---------- 云台配置 ----------
            _sectionCard(
              title: '云台双轴配置',
              child: Column(
                children: [
                  Row(
                    children: [
                      Expanded(
                        child: TextField(
                          controller: _panIdCtrl,
                          keyboardType: TextInputType.number,
                          inputFormatters: [FilteringTextInputFormatter.digitsOnly],
                          decoration: const InputDecoration(
                            labelText: '水平轴 ID',
                            border: OutlineInputBorder(),
                            isDense: true,
                          ),
                        ),
                      ),
                      const SizedBox(width: 10),
                      Expanded(
                        child: TextField(
                          controller: _tiltIdCtrl,
                          keyboardType: TextInputType.number,
                          inputFormatters: [FilteringTextInputFormatter.digitsOnly],
                          decoration: const InputDecoration(
                            labelText: '垂直轴 ID',
                            border: OutlineInputBorder(),
                            isDense: true,
                          ),
                        ),
                      ),
                    ],
                  ),
                  const SizedBox(height: 10),
                  Row(
                    children: [
                      Expanded(
                        child: OutlinedButton(
                          onPressed: () {
                            final p = int.tryParse(_panIdCtrl.text) ?? 0;
                            final t = int.tryParse(_tiltIdCtrl.text) ?? 0;
                            _send({'cmd': 'gimbal_config', 'pan': p, 'tilt': t});
                          },
                          child: const Text('应用电机配置'),
                        ),
                      ),
                      const SizedBox(width: 10),
                      Expanded(
                        child: ElevatedButton.icon(
                          onPressed: ble.gimbal.ready
                              ? () => Navigator.of(context).pushNamed('/gimbal')
                              : null,
                          icon: const Icon(Icons.gamepad),
                          label: const Text('进入云台控制'),
                          style: ElevatedButton.styleFrom(
                            backgroundColor: Colors.blue.shade700,
                          ),
                        ),
                      ),
                    ],
                  ),
                  const SizedBox(height: 6),
                  Text(
                    ble.gimbal.ready
                        ? '当前配置：水平=ID ${ble.gimbal.panId} · 垂直=ID ${ble.gimbal.tiltId}（扫描后前两台自动配置）'
                        : '云台未配置：请先扫描电机（前两台自动配置为水平/垂直），或在上方手动填写',
                    style: TextStyle(fontSize: 11, color: Colors.grey.shade500),
                  ),
                ],
              ),
            ),
            const SizedBox(height: 12),

            // ---------- 最近执行结果 ----------
            _sectionCard(
              title: '最近执行结果',
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
            const SizedBox(height: 20),
          ],
        ),
      ),
    );
  }

  Widget _sectionCard({required String title, required Widget child}) {
    return Card(
      margin: EdgeInsets.zero,
      child: Padding(
        padding: const EdgeInsets.all(14),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text(title,
                style: const TextStyle(
                  color: Colors.lightBlue,
                  fontWeight: FontWeight.bold,
                  fontSize: 14,
                )),
            const SizedBox(height: 10),
            child,
          ],
        ),
      ),
    );
  }

  Widget _numFieldRow({
    required String label,
    required String unit,
    required TextEditingController controller,
    required VoidCallback onSend,
    String? hint,
  }) {
    return Padding(
      padding: const EdgeInsets.symmetric(vertical: 4),
      child: Row(
        children: [
          SizedBox(
            width: 76,
            child: Text(label, style: const TextStyle(fontSize: 13)),
          ),
          Expanded(
            child: TextField(
              controller: controller,
              keyboardType: const TextInputType.numberWithOptions(signed: true, decimal: true),
              inputFormatters: [
                FilteringTextInputFormatter.allow(RegExp(r'^-?\d*\.?\d*')),
              ],
              decoration: InputDecoration(
                isDense: true,
                border: const OutlineInputBorder(),
                hintText: hint,
                suffixText: unit,
                suffixStyle: TextStyle(fontSize: 11, color: Colors.grey.shade500),
              ),
              style: const TextStyle(fontSize: 14),
              onSubmitted: (_) => onSend(),
            ),
          ),
          const SizedBox(width: 8),
          ElevatedButton(onPressed: onSend, child: const Text('发送')),
        ],
      ),
    );
  }
}
