import 'package:flutter/material.dart';

import '../models/motor.dart';

/// 电机列表卡片（ID + 电压 + 选中态）
class MotorCard extends StatelessWidget {
  const MotorCard({
    super.key,
    required this.motor,
    required this.selected,
    required this.onTap,
  });

  final Motor motor;
  final bool selected;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    return Card(
      margin: const EdgeInsets.symmetric(horizontal: 4, vertical: 4),
      color: selected ? Colors.blue.shade900 : null,
      shape: RoundedRectangleBorder(
        borderRadius: BorderRadius.circular(10),
        side: BorderSide(
          color: selected ? Colors.lightBlue : Colors.blueGrey.shade700,
          width: selected ? 2 : 1,
        ),
      ),
      child: InkWell(
        onTap: onTap,
        borderRadius: BorderRadius.circular(10),
        child: Padding(
          padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 10),
          child: Row(
            children: [
              Container(
                padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 4),
                decoration: BoxDecoration(
                  color: selected ? Colors.lightBlue : Colors.indigo.shade800,
                  borderRadius: BorderRadius.circular(8),
                ),
                child: Text(
                  'ID ${motor.id}',
                  style: const TextStyle(
                    color: Colors.white,
                    fontWeight: FontWeight.bold,
                    fontSize: 13,
                  ),
                ),
              ),
              const SizedBox(width: 8),
              Text(
                motor.addrHex,
                style: TextStyle(
                  color: Colors.grey.shade500,
                  fontFamily: 'monospace',
                  fontSize: 12,
                ),
              ),
              const Spacer(),
              Text(
                '${motor.volt.toStringAsFixed(2)} V',
                style: const TextStyle(
                  color: Colors.amber,
                  fontWeight: FontWeight.bold,
                  fontFamily: 'monospace',
                  fontSize: 14,
                ),
              ),
              const SizedBox(width: 6),
              Icon(
                selected ? Icons.check_circle : Icons.radio_button_unchecked,
                color: selected ? Colors.lightBlue : Colors.grey.shade600,
                size: 18,
              ),
            ],
          ),
        ),
      ),
    );
  }
}
