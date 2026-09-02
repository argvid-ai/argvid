import 'dart:async';
import 'dart:convert';

import 'package:flutter/foundation.dart';
import 'package:flutter_blue_plus/flutter_blue_plus.dart';

import '../models/gimbal_state.dart';
import '../models/motor.dart';
import '../utils/log_formatter.dart';
import 'ble_uuids.dart';
import 'command_sink.dart';

/// BLE 连接状态
enum BleConnState { disconnected, connecting, connected, discovering }

/// BLE 通信服务：扫描 / 连接 / 特征绑定 / Notify 分发 / 自动重连
///
/// 所有 ESP32 → APP 的事件（cmd_result / scan_result / query_result / log /
/// error / gimbal_state / wifi_status / sys_status）都在这里解析并更新状态，
/// 页面通过 provider 监听刷新。
class BleService extends ChangeNotifier implements CommandSink {
  // ---------------- 对外状态 ----------------
  BleConnState connState = BleConnState.disconnected;
  String statusMsg = '';
  String deviceName = '';

  bool scanning = false;
  List<ScanResult> scanResults = [];

  List<Motor> motors = [];
  bool motorScanning = false;
  int? selectedAddr;

  WifiInfo wifi = const WifiInfo();
  GimbalInfo gimbal = const GimbalInfo();

  String lastResult = '等待操作…';
  bool lastResultOk = false;

  List<LogEntry> logs = [];

  // ---------------- 内部 ----------------
  BluetoothDevice? _device;
  BluetoothCharacteristic? _charWifi;
  BluetoothCharacteristic? _charStatus;
  BluetoothCharacteristic? _charCmd;
  BluetoothCharacteristic? _charResp;

  StreamSubscription? _scanSub;
  StreamSubscription? _scanningSub;
  StreamSubscription? _connSub;
  StreamSubscription? _respSub;
  StreamSubscription? _statusSub;

  bool _wantConnected = false;    // 用户主动断开后置 false，意外断开后保持 true 用于自动重连
  int _reconnectAttempts = 0;

  static const int maxLogs = 300;

  // ============================================================
  // 扫描
  // ============================================================

  /// 开始扫描 BLE 设备（超时自动停止）
  Future<void> startScan({Duration timeout = const Duration(seconds: 6)}) async {
    if (scanning) return;
    scanning = true;
    statusMsg = '';
    notifyListeners();

    _scanSub?.cancel();
    _scanSub = FlutterBluePlus.scanResults.listen((results) {
      scanResults = results;
      notifyListeners();
    });

    _scanningSub?.cancel();
    _scanningSub = FlutterBluePlus.isScanning.where((v) => !v).listen((_) {
      scanning = false;
      notifyListeners();
    });

    try {
      await FlutterBluePlus.startScan(timeout: timeout);
    } catch (e) {
      statusMsg = '扫描失败: $e';
      scanning = false;
      notifyListeners();
    }
  }

  Future<void> stopScan() async {
    try {
      await FlutterBluePlus.stopScan();
    } catch (_) {}
    scanning = false;
    notifyListeners();
  }

  /// 扫描结果过滤：名称含 F32C 或广播了云台服务 UUID
  List<ScanResult> get gimbalDevices => scanResults.where((r) {
        final name = _nameOf(r);
        if (name.toUpperCase().contains(BleUuids.deviceNamePrefix)) return true;
        try {
          return r.advertisementData.serviceUuids
              .any((u) => u.str128 == BleUuids.service);
        } catch (_) {
          return false;
        }
      }).toList();

  static String _nameOf(ScanResult r) => nameOf(r);

  /// 扫描结果设备名（广播名优先，其次平台名）
  static String nameOf(ScanResult r) {
    final advName = r.advertisementData.advName;
    return advName.isNotEmpty ? advName : r.device.platformName;
  }

  // ============================================================
  // 连接 / 断开
  // ============================================================

  Future<bool> connect(BluetoothDevice device) async {
    if (connState == BleConnState.connecting || connState == BleConnState.connected) {
      return false;
    }
    connState = BleConnState.connecting;
    statusMsg = '连接中…';
    notifyListeners();

    try {
      await device.connect(timeout: const Duration(seconds: 15));
    } catch (e) {
      connState = BleConnState.disconnected;
      statusMsg = '连接失败: $e';
      notifyListeners();
      return false;
    }

    try {
      _device = device;
      _wantConnected = true;
      deviceName = _platformName(device);

      // 监听连接状态（意外断开 → 自动重连）
      _connSub?.cancel();
      _connSub = device.connectionState.listen((state) {
        if (state == BluetoothConnectionState.disconnected) {
          _onUnexpectedDisconnect();
        }
      });

      // Android 请求大 MTU（iOS 系统自动协商）
      try {
        await device.requestMtu(BleUuids.mtu);
      } catch (_) {}

      connState = BleConnState.discovering;
      statusMsg = '发现服务中…';
      notifyListeners();

      final services = await device.discoverServices();
      _bindCharacteristics(services);

      if (_charCmd == null || _charResp == null) {
        // 诊断：打印设备实际暴露的所有服务与特征，便于对照固件排查
        final dump = services
            .map((s) =>
                '${s.uuid.str} => ${s.characteristics.map((c) => c.uuid.str).join(', ')}')
            .join(' | ');
        _addLog('ERR', '服务发现结果: $dump');
        throw Exception('未找到云台 GATT 特征（FF03/FF04），请确认固件版本');
      }

      // 订阅 Notify
      await _charResp!.setNotifyValue(true);
      _respSub?.cancel();
      _respSub = _charResp!.lastValueStream.listen(_onRespNotify);

      if (_charStatus != null) {
        await _charStatus!.setNotifyValue(true);
        _statusSub?.cancel();
        _statusSub = _charStatus!.lastValueStream.listen(_onStatusNotify);
      }

      connState = BleConnState.connected;
      statusMsg = '已连接 $deviceName';
      _addLog('APP', '连接成功: $deviceName');
      notifyListeners();
      return true;
    } catch (e) {
      connState = BleConnState.disconnected;
      statusMsg = '初始化失败: $e';
      _addLog('ERR', '连接初始化失败: $e');
      notifyListeners();
      await _safeDisconnect();
      return false;
    }
  }

  static String _platformName(BluetoothDevice d) =>
      d.platformName.isNotEmpty ? d.platformName : d.remoteId.str;

  void _bindCharacteristics(List<BluetoothService> services) {
    for (final s in services) {
      for (final c in s.characteristics) {
        final uuid = c.uuid.str128;
        if (uuid == BleUuids.wifi) _charWifi = c;
        if (uuid == BleUuids.status) _charStatus = c;
        if (uuid == BleUuids.cmd) _charCmd = c;
        if (uuid == BleUuids.resp) _charResp = c;
      }
    }
  }

  /// 用户主动断开
  Future<void> disconnect() async {
    _wantConnected = false;
    connState = BleConnState.disconnected;
    statusMsg = '已断开';
    notifyListeners();
    await _safeDisconnect();
  }

  Future<void> _safeDisconnect() async {
    try {
      _respSub?.cancel();
      _statusSub?.cancel();
      _connSub?.cancel();
      await _charResp?.setNotifyValue(false);
      await _charStatus?.setNotifyValue(false);
    } catch (_) {}
    try {
      await _device?.disconnect();
    } catch (_) {}
    _charWifi = null;
    _charStatus = null;
    _charCmd = null;
    _charResp = null;
  }

  void _onUnexpectedDisconnect() {
    if (!_wantConnected) {
      connState = BleConnState.disconnected;
      notifyListeners();
      return;
    }
    connState = BleConnState.disconnected;
    statusMsg = '连接断开，尝试自动重连…';
    notifyListeners();
    _autoReconnect();
  }

  /// 自动重连：最多 3 次，间隔 2 秒
  Future<void> _autoReconnect() async {
    final device = _device;
    if (device == null) return;
    _reconnectAttempts = 0;
    while (_wantConnected && _reconnectAttempts < 3 && connState != BleConnState.connected) {
      _reconnectAttempts++;
      await Future.delayed(const Duration(seconds: 2));
      if (!_wantConnected) return;
      final ok = await connect(device);
      if (ok) return;
    }
    if (_wantConnected) {
      statusMsg = '自动重连失败，请返回连接页手动重连';
      notifyListeners();
    }
  }

  bool get isConnected => connState == BleConnState.connected;

  // ============================================================
  // 发送
  // ============================================================

  /// 发送电机/云台命令（写 FF03）
  Future<void> sendCmd(Map<String, dynamic> cmd) async {
    final char = _charCmd;
    if (char == null || !isConnected) {
      lastResult = '未连接设备';
      lastResultOk = false;
      notifyListeners();
      return;
    }
    final json = jsonEncode(cmd);
    try {
      await char.write(utf8.encode(json), withoutResponse: false);
      _addLog('APP', json);
    } catch (e) {
      lastResult = '发送失败: $e';
      lastResultOk = false;
      notifyListeners();
    }
  }

  /// 发送 WiFi 配置（写 FF01）
  Future<void> sendWifiConfig(String ssid, String pass) async {
    final char = _charWifi;
    if (char == null || !isConnected) {
      lastResult = '未连接设备';
      lastResultOk = false;
      notifyListeners();
      return;
    }
    final json = jsonEncode({'ssid': ssid, 'pass': pass});
    try {
      await char.write(utf8.encode(json), withoutResponse: false);
      wifi = wifi.copyWith(status: 'connecting', ssid: ssid);
      lastResult = 'WiFi 配置已发送，等待 ESP32 连接…';
      lastResultOk = true;
      _addLog('APP', json);
      notifyListeners();
    } catch (e) {
      lastResult = '发送失败: $e';
      lastResultOk = false;
      notifyListeners();
    }
  }

  // ============================================================
  // Notify 分发
  // ============================================================

  void _onRespNotify(List<int> value) {
    _handleJson(value, _handleRespEvent);
  }

  void _onStatusNotify(List<int> value) {
    _handleJson(value, _handleStatusEvent);
  }

  void _handleJson(List<int> value, void Function(Map<String, dynamic>) handler) {
    try {
      final obj = jsonDecode(utf8.decode(value));
      if (obj is Map<String, dynamic>) handler(obj);
    } catch (e) {
      _addLog('ERR', 'JSON 解析失败: $e');
      notifyListeners();
    }
  }

  /// FF04：cmd_result / scan_result / query_result / log / error / gimbal_state
  void _handleRespEvent(Map<String, dynamic> evt) {
    final type = evt['event'] as String?;
    switch (type) {
      case 'cmd_result':
        lastResult = (evt['msg'] ?? '').toString();
        lastResultOk = evt['ok'] == true;
        break;

      case 'scan_result':
        final list = evt['motors'];
        motors = (list is List)
            ? list
                .map((m) => Motor(
                      id: (m['id'] as num).toInt(),
                      volt: (m['volt'] as num).toDouble(),
                    ))
                .toList()
            : <Motor>[];
        motorScanning = false;
        lastResult = motors.isEmpty
            ? '未发现电机，请检查接线/供电/共地'
            : '发现 ${motors.length} 台电机';
        lastResultOk = motors.isNotEmpty;
        // 选中电机失效则清除
        if (selectedAddr != null && !motors.any((m) => m.id == selectedAddr)) {
          selectedAddr = null;
        }
        break;

      case 'query_result':
        lastResult = (evt['text'] ?? '').toString();
        lastResultOk = true;
        break;

      case 'log':
        final lines = evt['lines'];
        if (lines is List) {
          for (final l in lines) {
            final s = l.toString();
            if (s.startsWith('[TX]')) {
              _addLog('TX', s.substring(4).trim());
            } else if (s.startsWith('[RX]')) {
              _addLog('RX', s.substring(4).trim());
            } else {
              _addLog('ERR', s);
            }
          }
        }
        notifyListeners();
        return; // 日志事件不触发整体刷新

      case 'error':
        lastResult = '错误: ${evt['msg']}';
        lastResultOk = false;
        _addLog('ERR', evt['msg'].toString());
        break;

      case 'gimbal_state':
        gimbal = GimbalInfo(
          panId: (evt['pan'] as num?)?.toInt() ?? gimbal.panId,
          tiltId: (evt['tilt'] as num?)?.toInt() ?? gimbal.tiltId,
          panAngle: (evt['pan_angle'] as num?)?.toDouble() ?? gimbal.panAngle,
          tiltAngle: (evt['tilt_angle'] as num?)?.toDouble() ?? gimbal.tiltAngle,
        );
        break;

      default:
        break;
    }
    notifyListeners();
  }

  /// FF02：wifi_status / sys_status
  void _handleStatusEvent(Map<String, dynamic> evt) {
    final type = evt['event'] as String?;
    switch (type) {
      case 'wifi_status':
        final status = (evt['status'] ?? 'disconnected').toString();
        wifi = WifiInfo(
          status: status,
          ip: evt['ip']?.toString(),
          ssid: evt['ssid']?.toString(),
          rssi: evt['rssi'] is num ? (evt['rssi'] as num).toInt() : null,
        );
        break;

      case 'sys_status':
        gimbal = gimbal.copyWith(
          panId: (evt['pan'] as num?)?.toInt(),
          tiltId: (evt['tilt'] as num?)?.toInt(),
          panAngle: (evt['pan_angle'] as num?)?.toDouble(),
          tiltAngle: (evt['tilt_angle'] as num?)?.toDouble(),
        );
        break;

      default:
        break;
    }
    notifyListeners();
  }

  // ============================================================
  // 高层动作（页面直接调用）
  // ============================================================

  Future<void> scanMotors() async {
    if (motorScanning) return;
    motorScanning = true;
    lastResult = '扫描电机总线中（约 2~3 秒）…';
    notifyListeners();
    await sendCmd({'cmd': 'scan'});
  }

  void selectMotor(int addr) {
    selectedAddr = addr;
    notifyListeners();
  }

  void clearLogs() {
    logs.clear();
    notifyListeners();
  }

  void _addLog(String dir, String text) {
    logs.add(LogEntry(time: DateTime.now(), dir: dir, text: text));
    if (logs.length > maxLogs) {
      logs.removeRange(0, logs.length - maxLogs);
    }
    if (kDebugMode) {
      debugPrint('[$dir] $text');
    }
  }

  @override
  void dispose() {
    _wantConnected = false;
    _scanSub?.cancel();
    _scanningSub?.cancel();
    _connSub?.cancel();
    _respSub?.cancel();
    _statusSub?.cancel();
    _safeDisconnect();
    super.dispose();
  }
}
