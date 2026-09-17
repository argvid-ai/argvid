import 'package:flutter/material.dart';
import 'package:provider/provider.dart';

import 'ble/ble_service.dart';
import 'control/gimbal_controller.dart';
import 'pages/connect_page.dart';
import 'pages/gimbal_control_page.dart';
import 'pages/motor_control_page.dart';
import 'pages/wifi_config_page.dart';

void main() {
  WidgetsFlutterBinding.ensureInitialized();
  final ble = BleService();
  runApp(
    MultiProvider(
      providers: [
        ChangeNotifierProvider.value(value: ble),
        ChangeNotifierProvider(create: (_) => GimbalController(ble)),
      ],
      child: const GimbalApp(),
    ),
  );
}

class GimbalApp extends StatelessWidget {
  const GimbalApp({super.key});

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title: 'F32C 云台控制',
      debugShowCheckedModeBanner: false,
      theme: ThemeData(
        brightness: Brightness.dark,
        colorScheme: ColorScheme.fromSeed(
          seedColor: const Color(0xFF38BDF8),
          brightness: Brightness.dark,
        ),
        scaffoldBackgroundColor: const Color(0xFF0F172A),
        appBarTheme: const AppBarTheme(
          backgroundColor: Color(0xFF1E293B),
          foregroundColor: Color(0xFF38BDF8),
        ),
        cardTheme: const CardThemeData(color: Color(0xFF1E293B)),
        useMaterial3: true,
      ),
      initialRoute: '/',
      routes: {
        '/': (ctx) => const ConnectPage(),
        '/wifi': (ctx) => const WifiConfigPage(),
        '/motors': (ctx) => const MotorControlPage(),
        '/gimbal': (ctx) => const GimbalControlPage(),
      },
    );
  }
}
