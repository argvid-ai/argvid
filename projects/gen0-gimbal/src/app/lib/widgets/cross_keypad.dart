import 'package:flutter/material.dart';

/// 十字方向键盘（复刻 Web 版）
///
/// - ↑↓ = 垂直轴 tilt（上仰 / 下俯）
/// - ←→ = 水平轴 pan（左转 / 右转）
/// - 按住持续转（速度模式），松开立即停
/// - 两轴可同时按（每个按钮独立的原始指针监听）
/// - 中心 ⌂ = 双轴回中
class CrossKeypad extends StatelessWidget {
  const CrossKeypad({
    super.key,
    required this.onJog,
    required this.onCenter,
    this.enabled = true,
    this.size = 62,
  });

  /// axis: pan / tilt；dir: 1 / -1 / 0（0=停止）
  final void Function(String axis, int dir) onJog;
  final VoidCallback onCenter;
  final bool enabled;
  final double size;

  @override
  Widget build(BuildContext context) {
    const gap = 7.0;
    return Column(
      mainAxisSize: MainAxisSize.min,
      children: [
        Row(
          mainAxisSize: MainAxisSize.min,
          children: [
            SizedBox(width: size + gap),
            _DirButton(
              size: size,
              label: '↑',
              enabled: enabled,
              onDir: () => onJog('tilt', 1),
              onStop: () => onJog('tilt', 0),
            ),
            SizedBox(width: size + gap),
          ],
        ),
        SizedBox(height: gap),
        Row(
          mainAxisSize: MainAxisSize.min,
          children: [
            _DirButton(
              size: size,
              label: '←',
              enabled: enabled,
              onDir: () => onJog('pan', -1),
              onStop: () => onJog('pan', 0),
            ),
            SizedBox(width: gap),
            _CenterButton(
              size: size,
              enabled: enabled,
              onPressed: onCenter,
            ),
            SizedBox(width: gap),
            _DirButton(
              size: size,
              label: '→',
              enabled: enabled,
              onDir: () => onJog('pan', 1),
              onStop: () => onJog('pan', 0),
            ),
          ],
        ),
        SizedBox(height: gap),
        Row(
          mainAxisSize: MainAxisSize.min,
          children: [
            SizedBox(width: size + gap),
            _DirButton(
              size: size,
              label: '↓',
              enabled: enabled,
              onDir: () => onJog('tilt', -1),
              onStop: () => onJog('tilt', 0),
            ),
            SizedBox(width: size + gap),
          ],
        ),
        const SizedBox(height: 6),
        Text(
          '按住持续转 · 松开停 · 中心 = 回中',
          style: TextStyle(fontSize: 11, color: Colors.grey.shade500),
        ),
      ],
    );
  }
}

/// 方向按钮：按住触发 onDir，松开触发 onStop
class _DirButton extends StatefulWidget {
  const _DirButton({
    required this.size,
    required this.label,
    required this.onDir,
    required this.onStop,
    this.enabled = true,
  });

  final double size;
  final String label;
  final bool enabled;
  final VoidCallback onDir;
  final VoidCallback onStop;

  @override
  State<_DirButton> createState() => _DirButtonState();
}

class _DirButtonState extends State<_DirButton> {
  bool _pressed = false;

  void _down() {
    if (!widget.enabled) return;
    setState(() => _pressed = true);
    widget.onDir();
  }

  void _up() {
    if (_pressed) {
      setState(() => _pressed = false);
      widget.onStop();
    }
  }

  @override
  Widget build(BuildContext context) {
    return Listener(
      onPointerDown: (_) => _down(),
      onPointerUp: (_) => _up(),
      onPointerCancel: (_) => _up(),
      child: Container(
        width: widget.size,
        height: widget.size,
        decoration: BoxDecoration(
          color: _pressed ? Colors.green.shade600 : Colors.blueGrey.shade700,
          borderRadius: BorderRadius.circular(12),
          border: Border.all(
            color: _pressed ? Colors.greenAccent : Colors.blueGrey.shade500,
            width: 1.5,
          ),
        ),
        alignment: Alignment.center,
        child: Text(
          widget.label,
          style: TextStyle(
            fontSize: 22,
            fontWeight: FontWeight.bold,
            color: widget.enabled ? Colors.white : Colors.white38,
          ),
        ),
      ),
    );
  }
}

/// 中心回中按钮
class _CenterButton extends StatelessWidget {
  const _CenterButton({
    required this.size,
    required this.onPressed,
    this.enabled = true,
  });

  final double size;
  final VoidCallback onPressed;
  final bool enabled;

  @override
  Widget build(BuildContext context) {
    return SizedBox(
      width: size,
      height: size,
      child: ElevatedButton(
        onPressed: enabled ? onPressed : null,
        style: ElevatedButton.styleFrom(
          backgroundColor: Colors.amber.shade700,
          foregroundColor: Colors.white,
          shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(12)),
          padding: EdgeInsets.zero,
        ),
        child: const Text('⌂', style: TextStyle(fontSize: 22, fontWeight: FontWeight.bold)),
      ),
    );
  }
}
