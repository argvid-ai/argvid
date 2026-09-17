import 'package:flutter/material.dart';
import 'package:flutter_blue_plus/flutter_blue_plus.dart';
import 'package:provider/provider.dart';

import '../ble/ble_service.dart';
import '../ble/ble_uuids.dart';

/// 页面 1：蓝牙连接页
/// - 扫描 BLE 设备（优先显示名称含 F32C 的设备）
/// - 点击设备连接 → 成功后跳转 WiFi 配网页
class ConnectPage extends StatefulWidget {
  const ConnectPage({super.key});

  @override
  State<ConnectPage> createState() => _ConnectPageState();
}

class _ConnectPageState extends State<ConnectPage> {
  bool _showAll = false; // 显示全部设备开关

  @override
  void initState() {
    super.initState();
    _ensureBluetoothOn();
  }

  Future<void> _ensureBluetoothOn() async {
    // Android 可尝试自动打开蓝牙；iOS 需用户在控制中心开启
    try {
      await FlutterBluePlus.turnOn();
    } catch (_) {}
  }

  Future<void> _onConnect(BluetoothDevice device) async {
    final ble = context.read<BleService>();
    final ok = await ble.connect(device);
    if (!mounted) return;
    if (ok) {
      Navigator.of(context).pushReplacementNamed('/wifi');
    } else {
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(content: Text('连接失败: ${ble.statusMsg}')),
      );
    }
  }

  @override
  Widget build(BuildContext context) {
    final ble = context.watch<BleService>();
    final devices = _showAll ? ble.scanResults : ble.gimbalDevices;

    return Scaffold(
      appBar: AppBar(
        title: const Text('云台控制 · 蓝牙连接'),
        actions: [
          IconButton(
            icon: const Icon(Icons.refresh),
            tooltip: '重新扫描',
            onPressed: ble.scanning ? null : () => ble.startScan(),
          ),
        ],
      ),
      body: Column(
        children: [
          // 蓝牙适配器状态
          StreamBuilder<BluetoothAdapterState>(
            stream: FlutterBluePlus.adapterState,
            initialData: FlutterBluePlus.adapterStateNow,
            builder: (ctx, snap) {
              final on = snap.data == BluetoothAdapterState.on;
              return Container(
                width: double.infinity,
                padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 8),
                color: on ? Colors.green.shade900 : Colors.red.shade900,
                child: Row(
                  children: [
                    Icon(on ? Icons.bluetooth_connected : Icons.bluetooth_disabled,
                        size: 16, color: Colors.white),
                    const SizedBox(width: 8),
                    Text(
                      on ? '手机蓝牙已开启' : '手机蓝牙未开启，请先打开蓝牙',
                      style: const TextStyle(color: Colors.white, fontSize: 13),
                    ),
                  ],
                ),
              );
            },
          ),
          // 扫描控制区
          Padding(
            padding: const EdgeInsets.all(12),
            child: Row(
              children: [
                Expanded(
                  child: ElevatedButton.icon(
                    onPressed: ble.scanning ? null : () => ble.startScan(),
                    icon: ble.scanning
                        ? const SizedBox(
                            width: 16,
                            height: 16,
                            child: CircularProgressIndicator(
                                strokeWidth: 2, color: Colors.white),
                          )
                        : const Icon(Icons.search),
                    label: Text(ble.scanning ? '扫描中…' : '扫描 BLE 设备'),
                    style: ElevatedButton.styleFrom(
                      padding: const EdgeInsets.symmetric(vertical: 12),
                    ),
                  ),
                ),
                const SizedBox(width: 10),
                FilterChip(
                  label: const Text('显示全部'),
                  selected: _showAll,
                  onSelected: (v) => setState(() => _showAll = v),
                ),
              ],
            ),
          ),
          if (ble.statusMsg.isNotEmpty)
            Padding(
              padding: const EdgeInsets.symmetric(horizontal: 16),
              child: Text(
                ble.statusMsg,
                style: const TextStyle(color: Colors.orange, fontSize: 12),
              ),
            ),
          // 设备列表
          Expanded(
            child: devices.isEmpty
                ? Center(
                    child: Column(
                      mainAxisAlignment: MainAxisAlignment.center,
                      children: [
                        Icon(Icons.bluetooth_searching,
                            size: 48, color: Colors.grey.shade600),
                        const SizedBox(height: 12),
                        Text(
                          ble.scanning ? '正在搜索附近设备…' : '未发现设备',
                          style: TextStyle(color: Colors.grey.shade500),
                        ),
                        if (!ble.scanning) ...[
                          const SizedBox(height: 6),
                          Text(
                            '请确认 ESP32 已上电并广播 "${BleUuids.deviceNamePrefix}*"',
                            style: TextStyle(
                                color: Colors.grey.shade600, fontSize: 12),
                          ),
                        ],
                      ],
                    ),
                  )
                : ListView.builder(
                    padding: const EdgeInsets.symmetric(horizontal: 12),
                    itemCount: devices.length,
                    itemBuilder: (ctx, i) {
                      final r = devices[i];
                      final name = BleService.nameOf(r);
                      final isGimbal = ble.gimbalDevices.contains(r);
                      return Card(
                        margin: const EdgeInsets.symmetric(vertical: 4),
                        child: ListTile(
                          leading: Icon(
                            isGimbal ? Icons.videocam : Icons.bluetooth,
                            color: isGimbal ? Colors.lightBlue : Colors.grey,
                          ),
                          title: Text(
                            name.isEmpty ? '(未知设备)' : name,
                            style: TextStyle(
                              fontWeight:
                                  isGimbal ? FontWeight.bold : FontWeight.normal,
                            ),
                          ),
                          subtitle: Text(
                            r.device.remoteId.str,
                            style: TextStyle(
                                fontFamily: 'monospace',
                                fontSize: 11,
                                color: Colors.grey.shade500),
                          ),
                          trailing: ble.connState == BleConnState.connecting
                              ? const SizedBox(
                                  width: 18,
                                  height: 18,
                                  child: CircularProgressIndicator(strokeWidth: 2),
                                )
                              : const Icon(Icons.chevron_right),
                          onTap: () => _onConnect(r.device),
                        ),
                      );
                    },
                  ),
          ),
          const Padding(
            padding: EdgeInsets.all(10),
            child: Text(
              '提示：云台设备名以 F32C 开头，名称为空的设备一般为其他蓝牙设备',
              style: TextStyle(fontSize: 11, color: Colors.grey),
            ),
          ),
        ],
      ),
    );
  }
}
