import 'package:flutter/material.dart';

/// 单轴拉杆 —— 左右拖动直接设置该轴的绝对目标角（相对原点）
///
/// - 水平轴拉杆范围 ±180°，垂直轴拉杆范围 ±90°
/// - 拖动时节流下发（由 controller 负责），松手发送终值
/// - 中间刻度 0°（原点），两端限位
class AxisLever extends StatefulWidget {
  const AxisLever({
    super.key,
    required this.label,
    required this.min,
    required this.max,
    required this.value,
    required this.onChanged,
    required this.onChangeEnd,
    this.enabled = true,
  });

  final String label;
  final double min;
  final double max;

  /// 当前目标角（外部状态驱动）
  final double value;
  final void Function(double) onChanged;
  final void Function(double) onChangeEnd;
  final bool enabled;

  @override
  State<AxisLever> createState() => _AxisLeverState();
}

class _AxisLeverState extends State<AxisLever> {
  @override
  Widget build(BuildContext context) {
    final clamped = widget.value.clamp(widget.min, widget.max).toDouble();
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Row(
          children: [
            SizedBox(
              width: 64,
              child: Text(widget.label,
                  style: const TextStyle(fontSize: 13, fontWeight: FontWeight.bold)),
            ),
            Expanded(
              child: SliderTheme(
                data: SliderTheme.of(context).copyWith(
                  trackHeight: 10,
                  activeTrackColor: Colors.lightBlue.shade700,
                  inactiveTrackColor: Colors.blueGrey.shade800,
                ),
                child: Slider(
                  value: clamped,
                  min: widget.min,
                  max: widget.max,
                  divisions: ((widget.max - widget.min) / 1).round(),
                  label: '${clamped.toStringAsFixed(0)}°',
                  onChanged: widget.enabled ? widget.onChanged : null,
                  onChangeEnd: widget.enabled ? widget.onChangeEnd : null,
                ),
              ),
            ),
            SizedBox(
              width: 64,
              child: Text(
                '${clamped >= 0 ? '+' : ''}${clamped.toStringAsFixed(1)}°',
                textAlign: TextAlign.right,
                style: const TextStyle(
                  fontFamily: 'monospace',
                  fontWeight: FontWeight.bold,
                  fontSize: 14,
                ),
              ),
            ),
          ],
        ),
      ],
    );
  }
}
