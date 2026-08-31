import 'package:flutter/material.dart';
import 'package:provider/provider.dart';

import '../ble/ble_service.dart';
import '../models/gimbal_state.dart';
import '../widgets/log_panel.dart';

/// 页面 2：WiFi 配网页
/// - 输入 SSID + 密码，通过 BLE 写入 ESP32（NVS 保存并连接）
/// - 状态卡片：显示 ESP32 的 WiFi 连接状态 / IP / 信号强度
/// - 可跳过直接进入电机控制台（WiFi 非控制必需）
class WifiConfigPage extends StatefulWidget {
  const WifiConfigPage({super.key});

  @override
  State<WifiConfigPage> createState() => _WifiConfigPageState();
}

class _WifiConfigPageState extends State<WifiConfigPage> {
  final _ssidCtrl = TextEditingController();
  final _passCtrl = TextEditingController();
  bool _obscure = true;

  @override
  void dispose() {
    _ssidCtrl.dispose();
    _passCtrl.dispose();
    super.dispose();
  }

  Future<void> _send() async {
    final ssid = _ssidCtrl.text.trim();
    if (ssid.isEmpty) {
      ScaffoldMessenger.of(context).showSnackBar(
        const SnackBar(content: Text('请输入 WiFi 名称 (SSID)')),
      );
      return;
    }
    await context.read<BleService>().sendWifiConfig(ssid, _passCtrl.text);
    if (mounted) {
      ScaffoldMessenger.of(context).showSnackBar(
        const SnackBar(content: Text('配置已发送，等待 ESP32 连接结果…')),
      );
    }
  }

  @override
  Widget build(BuildContext context) {
    final ble = context.watch<BleService>();

    return Scaffold(
      appBar: AppBar(
        title: const Text('WiFi 配网'),
        actions: [
          IconButton(
            icon: const Icon(Icons.list_alt),
            tooltip: '通信日志',
            onPressed: () => showLogPanel(context),
          ),
        ],
      ),
      body: SingleChildScrollView(
        padding: const EdgeInsets.all(16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            Card(
              color: Colors.blueGrey.shade900,
              child: const Padding(
                padding: EdgeInsets.all(14),
                child: Text(
                  '通过蓝牙将 WiFi 账号密码发送给 ESP32 云台，'
                  'ESP32 会保存并连接（掉电保持，重启自动重连）。\n'
                  'WiFi 仅用于 OTA 升级 / 远程访问，不配网也能控制电机。',
                  style: TextStyle(fontSize: 13, height: 1.6),
                ),
              ),
            ),
            const SizedBox(height: 16),

            TextField(
              controller: _ssidCtrl,
              decoration: const InputDecoration(
                labelText: 'WiFi 名称 (SSID)',
                prefixIcon: Icon(Icons.wifi),
                border: OutlineInputBorder(),
                hintText: '例如：MyHomeWiFi',
              ),
            ),
            const SizedBox(height: 12),

            TextField(
              controller: _passCtrl,
              obscureText: _obscure,
              decoration: InputDecoration(
                labelText: 'WiFi 密码',
                prefixIcon: const Icon(Icons.lock_outline),
                border: const OutlineInputBorder(),
                suffixIcon: IconButton(
                  icon: Icon(_obscure ? Icons.visibility_off : Icons.visibility),
                  onPressed: () => setState(() => _obscure = !_obscure),
                ),
              ),
            ),
            const SizedBox(height: 16),

            ElevatedButton.icon(
              onPressed: ble.isConnected ? _send : null,
              icon: const Icon(Icons.send),
              label: const Text('配置 WiFi'),
              style: ElevatedButton.styleFrom(
                padding: const EdgeInsets.symmetric(vertical: 14),
              ),
            ),
            const SizedBox(height: 8),
            OutlinedButton.icon(
              onPressed: () =>
                  Navigator.of(context).pushReplacementNamed('/motors'),
              icon: const Icon(Icons.skip_next),
              label: const Text('跳过，直接进入电机控制台'),
              style: OutlinedButton.styleFrom(
                padding: const EdgeInsets.symmetric(vertical: 12),
              ),
            ),
            const SizedBox(height: 20),

            _StatusCard(wifi: ble.wifi),
          ],
        ),
      ),
    );
  }
}

class _StatusCard extends StatelessWidget {
  const _StatusCard({required this.wifi});

  final WifiInfo wifi;

  @override
  Widget build(BuildContext context) {
    final connected = wifi.connected;

    return Card(
      color: connected ? Colors.green.shade900 : Colors.blueGrey.shade900,
      child: Padding(
        padding: const EdgeInsets.all(14),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Row(
              children: [
                Icon(
                  connected
                      ? Icons.wifi
                      : wifi.status == 'connecting'
                          ? Icons.sync
                          : Icons.wifi_off,
                  size: 18,
                  color: connected ? Colors.greenAccent : Colors.orangeAccent,
                ),
                const SizedBox(width: 8),
                Text(
                  switch (wifi.status) {
                    'connected' => 'ESP32 已连接 WiFi',
                    'connecting' => 'ESP32 正在连接 WiFi…',
                    _ => 'ESP32 未连接 WiFi',
                  },
                  style: const TextStyle(fontWeight: FontWeight.bold, fontSize: 14),
                ),
              ],
            ),
            if (connected) ...[
              const SizedBox(height: 10),
              _kv('IP 地址', wifi.ip ?? '-'),
              _kv('SSID', wifi.ssid ?? '-'),
              _kv('信号强度', wifi.rssi != null ? '${wifi.rssi} dBm' : '-'),
              if (wifi.rssi != null)
                Padding(
                  padding: const EdgeInsets.only(top: 8),
                  child: LinearProgressIndicator(
                    value: ((100 + wifi.rssi!) / 100).clamp(0.0, 1.0),
                    minHeight: 5,
                    backgroundColor: Colors.white24,
                  ),
                ),
            ],
          ],
        ),
      ),
    );
  }

  Widget _kv(String k, String v) => Padding(
        padding: const EdgeInsets.symmetric(vertical: 2),
        child: Row(
          children: [
            SizedBox(
              width: 70,
              child: Text(k,
                  style: TextStyle(color: Colors.grey.shade400, fontSize: 12)),
            ),
            Expanded(
              child: Text(v,
                  style: const TextStyle(fontFamily: 'monospace', fontSize: 13)),
            ),
          ],
        ),
      );
}
