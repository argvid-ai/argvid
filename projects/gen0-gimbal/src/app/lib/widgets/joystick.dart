import 'package:flutter/material.dart';

/// 虚拟摇杆圆盘（复刻 Web 版）
///
/// - 按住拖动 → 输出归一化坐标 panNorm/tiltNorm ∈ [-1, 1]
/// - 父级负责映射为角度（pan ±180°，tilt ±90°）与 130ms 节流
/// - 松手回调 onEnd（父级发送最终位置）
/// - 可通过 key 调用 reset() 将摇杆归位（回中按钮联动）
class Joystick extends StatefulWidget {
  const Joystick({
    super.key,
    required this.onMove,
    required this.onEnd,
    this.enabled = true,
    this.size = 220,
  });

  final void Function(double panNorm, double tiltNorm) onMove;
  final VoidCallback onEnd;
  final bool enabled;
  final double size;

  @override
  State<Joystick> createState() => JoystickState();
}

class JoystickState extends State<Joystick> {
  Offset _knob = Offset.zero;
  bool _dragging = false;

  static const double _knobRadius = 29;

  double get _maxTravel => widget.size / 2 - _knobRadius - 6;

  void reset() {
    setState(() => _knob = Offset.zero);
  }

  void _update(Offset localPos) {
    final center = Offset(widget.size / 2, widget.size / 2);
    var d = localPos - center;
    final dist = d.distance;
    final max = _maxTravel;
    if (dist > max && dist > 0) {
      d = d / dist * max;
    }
    setState(() => _knob = d);
    // 屏幕坐标 y 向下为正 → 上拖 = tilt 正角度，取负号
    widget.onMove(d.dx / max, -d.dy / max);
  }

  @override
  Widget build(BuildContext context) {
    final size = widget.size;
    return SizedBox(
      width: size,
      height: size,
      child: Listener(
        onPointerDown: (e) {
          if (!widget.enabled) return;
          _dragging = true;
          _update(e.localPosition);
        },
        onPointerMove: (e) {
          if (_dragging) _update(e.localPosition);
        },
        onPointerUp: (_) {
          if (_dragging) {
            _dragging = false;
            widget.onEnd();
          }
        },
        onPointerCancel: (_) {
          if (_dragging) {
            _dragging = false;
            widget.onEnd();
          }
        },
        child: Stack(
          alignment: Alignment.center,
          children: [
            // 底盘
            Container(
              width: size,
              height: size,
              decoration: BoxDecoration(
                shape: BoxShape.circle,
                color: widget.enabled ? Colors.blueGrey.shade900 : Colors.grey.shade800,
                border: Border.all(
                  color: widget.enabled ? Colors.blueGrey.shade500 : Colors.grey.shade600,
                  width: 2,
                ),
                boxShadow: const [
                  BoxShadow(color: Colors.black45, blurRadius: 10, spreadRadius: 2),
                ],
              ),
            ),
            // 十字参考线
            SizedBox(
              width: size - 32,
              height: size - 32,
              child: CustomPaint(painter: _CrossLinesPainter()),
            ),
            // 内圈虚线参考
            Container(
              width: size - 68,
              height: size - 68,
              decoration: BoxDecoration(
                shape: BoxShape.circle,
                border: Border.all(
                  color: Colors.blueGrey.shade600,
                  width: 1,
                  style: widget.enabled ? BorderStyle.solid : BorderStyle.none,
                ),
              ),
            ),
            // 摇杆头
            Transform.translate(
              offset: _knob,
              child: Container(
                width: _knobRadius * 2,
                height: _knobRadius * 2,
                decoration: BoxDecoration(
                  shape: BoxShape.circle,
                  gradient: const LinearGradient(
                    begin: Alignment.topLeft,
                    end: Alignment.bottomRight,
                    colors: [Color(0xFF7DD3FC), Color(0xFF0284C7)],
                  ),
                  border: Border.all(color: const Color(0xFFBAE6FD), width: 2),
                  boxShadow: [
                    BoxShadow(
                      color: const Color(0xFF0284C7).withOpacity(_dragging ? 0.7 : 0.4),
                      blurRadius: 12,
                      spreadRadius: _dragging ? 3 : 1,
                    ),
                  ],
                ),
              ),
            ),
          ],
        ),
      ),
    );
  }
}

class _CrossLinesPainter extends CustomPainter {
  @override
  void paint(Canvas canvas, Size size) {
    final paint = Paint()
      ..color = Colors.blueGrey.shade600
      ..strokeWidth = 1;
    canvas.drawLine(Offset(0, size.height / 2), Offset(size.width, size.height / 2), paint);
    canvas.drawLine(Offset(size.width / 2, 0), Offset(size.width / 2, size.height), paint);
  }

  @override
  bool shouldRepaint(covariant CustomPainter oldDelegate) => false;
}
