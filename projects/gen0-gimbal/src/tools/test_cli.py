"""
F32C 电机命令行测试脚本（直接 USB-TTL 连电机，支持多电机总线扫描/切换）

接线：
  USB-TTL GND  ->  F32C 电机 GND（必须共地）
  USB-TTL 3.3V TX  ->  F32C 电机 RX
  USB-TTL 3.3V RX  <-  F32C 电机 TX
  F32C V+       ->  8~15V 独立电源

使用方式：
  python test_cli.py                         # 交互式
  python test_cli.py --list                  # 列出 COM 口
  python test_cli.py --port COM7 --scan      # 扫描总线上所有电机
  python test_cli.py --port COM7 --addr 0x02 --cmd voltage   # 指定某个地址直接读
  python test_cli.py --port COM7 --addr 0x02 --cmd setspeed 100
  python test_cli.py --port COM7 --debug     # 打开 TX/RX 调试输出
"""

import sys
import argparse
from f32c_protocol import F32CMotor, list_serial_ports


def show_ports():
    ports = list_serial_ports()
    print("\n======== 可用串口 ========")
    if not ports:
        print("（无可用串口，请检查 USB-TTL 是否插入或驱动是否安装）")
    for i, (dev, desc) in enumerate(ports):
        print(f"  [{i}] {dev} - {desc}")
    print("==========================")
    return ports


def prompt(prefix: str, default=None):
    s = input(prefix).strip()
    if not s:
        return default
    return s


def interactive_mode(motor: F32CMotor):
    print(f"\n进入交互模式，当前控制电机地址: 0x{motor.addr:02X} (ID{motor.addr})")
    print("输入命令按回车，输入 help 查看命令，输入 quit 退出")
    while True:
        try:
            cmd = input(f"[ID{motor.addr}] >> ").strip().lower()
        except EOFError:
            break
        if not cmd:
            continue
        if cmd in ("quit", "exit", "q"):
            print("退出")
            break
        if cmd == "help":
            print("""
======== 命令列表 ========
  扫描与切换
      scan              扫描总线上所有在线电机 (地址 1~16)
      scan 1 32         自定义扫描范围 (地址 1~32)
      use 3             切换当前控制的电机为 ID=3 (地址 0x03)
      where             显示当前控制的电机地址
      setaddr 3         将当前电机自身地址永久改为 3 (需再 save 才掉电保持)

  使能 / 失能
      enable            使能电机 (LED 由常亮变快闪)
      disable           失能电机

  模式 / 目标
      setmode 0         0=速度  1=多圈T  2=单圈T  3=多圈直  4=单圈直
      setspeed 100      设置目标转速 RPM (支持负数)
      setangle 360      设置多圈目标角度 (度, 精度0.1)
      setsingle 90      设置单圈目标角度 (0~359.9)
      setaccel 100      设置加速度 (圈/s²)

  读取反馈
      voltage           母线电压
      speed             当前转速
      angle             转过总角度
      mech              单圈机械角度
      accel             当前加速度

  维护 / PID
      save              保存参数到 Flash (修改地址/加速度/PID后必用)
      zero              转过总角度清零
      setzero           当前位置设为单圈 0 点
      factory           恢复出厂设置
      setspeed_kp 10    速度环 KP
      setspeed_ki 10    速度环 KI
      setpos_kp 10      位置环 KP
      setpos_ki 10      位置环 KI

  一键测试
      test              对当前电机做联通测试 (电压/角度/转速)
""")
            continue

        try:
            parts = cmd.split()
            c = parts[0]
            a = parts[1:]
            if c == "where":
                print(f"  当前控制地址: 0x{motor.addr:02X} (ID{motor.addr})")

            elif c == "scan":
                s = int(a[0]) if len(a) >= 1 else 1
                e = int(a[1]) if len(a) >= 2 else 16
                print(f"\n======== 扫描电机 (地址 {s}~{e}) ========")
                motors = motor.scan_bus(start=s, end=e)
                print("=========================================")

            elif c == "use":
                if not a:
                    print("  用法: use <地址编号>  例: use 3")
                    continue
                new_id = int(a[0], 0)
                motor.set_addr(new_id)
                print(f"  OK 已切换到 ID{motor.addr} (0x{motor.addr:02X})")

            elif c == "setaddr":
                if not a:
                    print("  用法: setaddr <新编号>  例: setaddr 3")
                    continue
                new_addr = int(a[0], 0)
                confirm = input(f"  确认将当前电机 (ID{motor.addr}) 的自身地址改为 {new_addr}? [y/N]: ").strip().lower()
                if confirm != 'y':
                    print("  已取消")
                    continue
                r = motor.set_device_address(new_addr)
                print(f"  → {r.parsed_text}")
                if r.valid:
                    print("  提示: 接下来调用 'save' 保存到 Flash 才永久生效；当前控制地址已同步切换")
                    motor.set_addr(new_addr)

            elif c == "enable":       print_motor_resp(motor.enable())
            elif c == "disable":      print_motor_resp(motor.disable())
            elif c == "test":         test_full(motor)
            elif c == "voltage":      print_motor_resp(motor.read_voltage())
            elif c == "speed":        print_motor_resp(motor.read_speed())
            elif c == "angle":        print_motor_resp(motor.read_total_angle())
            elif c == "mech":         print_motor_resp(motor.read_mech_angle())
            elif c == "accel":        print_motor_resp(motor.read_accel())
            elif c == "save":         print_motor_resp(motor.save_params())
            elif c == "zero":         print_motor_resp(motor.clear_total_angle())
            elif c == "setzero":      print_motor_resp(motor.set_single_zero())
            elif c == "factory":      print_motor_resp(motor.factory_reset())
            elif c == "setmode" and a:    print_motor_resp(motor.set_mode(int(a[0])))
            elif c == "setspeed" and a:   print_motor_resp(motor.set_speed(int(a[0])))
            elif c == "setangle" and a:   print_motor_resp(motor.set_multi_angle(float(a[0])))
            elif c == "setsingle" and a:  print_motor_resp(motor.set_single_angle(float(a[0])))
            elif c == "setaccel" and a:   print_motor_resp(motor.set_accel(int(a[0])))
            elif c == "setspeed_kp" and a:  print_motor_resp(motor.set_speed_kp(int(a[0])))
            elif c == "setspeed_ki" and a:  print_motor_resp(motor.set_speed_ki(int(a[0])))
            elif c == "setpos_kp" and a:    print_motor_resp(motor.set_pos_kp(int(a[0])))
            elif c == "setpos_ki" and a:    print_motor_resp(motor.set_pos_ki(int(a[0])))
            else:
                print("  未知命令，输入 help 查看命令列表")
        except Exception as e:
            print(f"  [错误] {type(e).__name__}: {e}")


def print_motor_resp(r):
    if r.valid:
        print(f"  → {r.parsed_text}")
    else:
        print(f"  → [失败] {r.parsed_text}")


def test_full(motor: F32CMotor):
    print(f"\n======== 当前电机 ID{motor.addr} 联通性测试 ========")
    r = motor.connectivity_test()
    for k, v in r.items():
        print(f"  {k:12s} : {v}")
    print("============================")
    return r


def run_cmd_once(motor: F32CMotor, cmd_parts):
    try:
        cmd = cmd_parts[0].lower()
        a = cmd_parts[1:]
        if cmd == "enable":       return motor.enable()
        elif cmd == "disable":    return motor.disable()
        elif cmd == "voltage":    return motor.read_voltage()
        elif cmd == "speed":      return motor.read_speed()
        elif cmd == "angle":      return motor.read_total_angle()
        elif cmd == "mech":       return motor.read_mech_angle()
        elif cmd == "accel":      return motor.read_accel()
        elif cmd == "save":       return motor.save_params()
        elif cmd == "zero":       return motor.clear_total_angle()
        elif cmd == "setzero":    return motor.set_single_zero()
        elif cmd == "factory":    return motor.factory_reset()
        elif cmd == "setmode" and a:    return motor.set_mode(int(a[0]))
        elif cmd == "setspeed" and a:   return motor.set_speed(int(a[0]))
        elif cmd == "setangle" and a:   return motor.set_multi_angle(float(a[0]))
        elif cmd == "setsingle" and a:  return motor.set_single_angle(float(a[0]))
        elif cmd == "setaccel" and a:   return motor.set_accel(int(a[0]))
        else:
            print(f"未知命令: {cmd}")
            sys.exit(1)
    except Exception as e:
        print(f"[错误] {e}")
        sys.exit(2)


def main():
    parser = argparse.ArgumentParser(description="F32C 电机命令行测试（直连 USB-TTL）")
    parser.add_argument("--port", help="USB-TTL 串口号 (如 COM7)")
    parser.add_argument("--baud", type=int, default=115200, help="波特率 默认115200")
    parser.add_argument("--addr", type=lambda x: int(x, 0), default=0x02,
                        help="初始设备地址，默认0x02 (电机出厂默认)")
    parser.add_argument("--list", action="store_true", help="列出所有可用串口")
    parser.add_argument("--scan", action="store_true", help="连接后自动扫描总线上所有电机")
    parser.add_argument("--scan-range", nargs=2, type=int, metavar=("START", "END"),
                        default=None, help="自定义扫描地址范围 例: --scan-range 1 16")
    parser.add_argument("--test", action="store_true", help="对 --addr 电机一键联通测试")
    parser.add_argument("--cmd", nargs="+", help="执行单个命令 (例: --cmd setspeed 100)")
    parser.add_argument("--debug", action="store_true", help="打开 TX/RX 原始字节调试输出")
    args = parser.parse_args()

    if args.list:
        show_ports()
        return

    # 选端口
    port = args.port
    if not port:
        ports = show_ports()
        if not ports:
            return
        sel = prompt("请选择串口编号（回车=0）：", "0")
        idx = int(sel) if sel.isdigit() else 0
        port = ports[idx][0]

    motor = F32CMotor(port=port, addr=args.addr, baud=args.baud, debug=args.debug)
    if not motor.connect():
        sys.exit(1)

    try:
        if args.scan:
            s, e = args.scan_range if args.scan_range else (1, 16)
            motors = motor.scan_bus(start=s, end=e)
            if motors:
                inp = prompt(f"\n发现 {len(motors)} 台电机，要切换控制到某个编号吗? (输入编号/回车继续当前): ", "")
                if inp.isdigit():
                    new_addr = int(inp)
                    if any(m["addr"] == new_addr for m in motors):
                        motor.set_addr(new_addr)
                        print(f"  已切换到 ID{new_addr}")
                    else:
                        print(f"  编号 {new_addr} 不在扫描结果中，保持当前")
        if args.test:
            test_full(motor)
        elif args.cmd:
            r = run_cmd_once(motor, args.cmd)
            print_motor_resp(r)
        else:
            interactive_mode(motor)
    finally:
        motor.disconnect()


if __name__ == "__main__":
    main()
