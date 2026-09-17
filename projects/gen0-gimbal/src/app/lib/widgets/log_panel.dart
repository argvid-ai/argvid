import 'package:flutter/material.dart';
import 'package:provider/provider.dart';

import '../ble/ble_service.dart';
import '../utils/log_formatter.dart';

/// 日志面板（底部抽屉）：TX/RX/APP/ERR 着色，可清空
Future<void> showLogPanel(BuildContext context) {
  return showModalBottomSheet(
    context: context,
    isScrollControlled: true,
    backgroundColor: const Color(0xFF020617),
    shape: const RoundedRectangleBorder(
      borderRadius: BorderRadius.vertical(top: Radius.circular(16)),
    ),
    builder: (ctx) => SizedBox(
      height: 420,
      child: const LogPanel(),
    ),
  );
}

class LogPanel extends StatelessWidget {
  const LogPanel({super.key});

  @override
  Widget build(BuildContext context) {
    final ble = context.watch<BleService>();
    final logs = ble.logs;

    return Column(
      children: [
        Container(
          padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 10),
          decoration: BoxDecoration(
            border: Border(
              bottom: BorderSide(color: Colors.blueGrey.shade700),
            ),
          ),
          child: Row(
            children: [
              const Icon(Icons.receipt_long, size: 18, color: Colors.lightBlue),
              const SizedBox(width: 8),
              Text(
                '通信日志（${logs.length} 条）',
                style: const TextStyle(
                  color: Colors.lightBlue,
                  fontWeight: FontWeight.bold,
                  fontSize: 14,
                ),
              ),
              const Spacer(),
              TextButton.icon(
                onPressed: ble.clearLogs,
                icon: const Icon(Icons.delete_sweep, size: 16),
                label: const Text('清空', style: TextStyle(fontSize: 12)),
                style: TextButton.styleFrom(foregroundColor: Colors.grey.shade400),
              ),
            ],
          ),
        ),
        Expanded(
          child: logs.isEmpty
              ? Center(
                  child: Text(
                    '暂无日志\n操作电机后 TX/RX 十六进制帧会显示在这里',
                    textAlign: TextAlign.center,
                    style: TextStyle(color: Colors.grey.shade600, fontSize: 13),
                  ),
                )
              : ListView.builder(
                  padding: const EdgeInsets.all(10),
                  itemCount: logs.length,
                  itemBuilder: (ctx, i) {
                    final e = logs[i];
                    return Padding(
                      padding: const EdgeInsets.symmetric(vertical: 1),
                      child: Text.rich(
                        TextSpan(
                          children: [
                            TextSpan(
                              text: '${e.timeStr} ',
                              style: TextStyle(
                                color: Colors.grey.shade600,
                                fontSize: 11,
                                fontFamily: 'monospace',
                              ),
                            ),
                            TextSpan(
                              text: '[${e.dir}]'.padRight(6),
                              style: TextStyle(
                                color: Color(logColor(e.dir)),
                                fontWeight: FontWeight.bold,
                                fontFamily: 'monospace',
                                fontSize: 12,
                              ),
                            ),
                            TextSpan(
                              text: e.text,
                              style: TextStyle(
                                color: Color(logColor(e.dir)),
                                fontFamily: 'monospace',
                                fontSize: 12,
                              ),
                            ),
                          ],
                        ),
                      ),
                    );
                  },
                ),
        ),
      ],
    );
  }
}
