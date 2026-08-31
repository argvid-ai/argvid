"""
F32C 无刷电机 - 串口通信协议封装
参考文档：F32C无刷电机使用手册.md

帧格式：0x7A + 地址 + 功能码 + [数据...] + BCC + 0x7B
BCC = 0x7A ^ 地址 ^ 功能码 ^ 数据所有字节（异或）

使用示例：
    from f32c_protocol import F32CMotor
    m = F32CMotor(port='COM7', addr=0x02, debug=True)
    m.connect()
    print(m.read_voltage())   # 读电压
    m.enable()                 # 使能
    m.set_speed(100)           # 100 RPM 正转
"""

import serial
import serial.tools.list_ports
import struct
import time
from dataclasses import dataclass
from typing import Optional, List, Tuple

FRAME_HEADER = 0x7A
FRAME_TAIL = 0x7B


@dataclass
class MotorResponse:
    """电机反馈帧解析结果"""
    raw: bytes
    valid: bool
    bcc_ok: bool
    type_code: int
    value: int
    parsed_text: str


def calc_bcc(data: bytes) -> int:
    """BCC 校验：逐字节异或（从头帧到最后一字节数据）"""
    bcc = 0
    for b in data:
        bcc ^= b
    return bcc & 0xFF


def list_serial_ports() -> List[Tuple[str, str]]:
    """列出所有可用串口"""
    result = []
    for p in serial.tools.list_ports.comports():
        result.append((p.device, p.description))
    return result


class F32CMotor:
    """F32C 无刷电机控制器"""

    # 功能码定义（与手册 4.3 节对应）
    FC_ENABLE = 0x06
    FC_DISABLE = 0x05
    FC_SET_MODE = 0x00
    FC_SET_SPEED = 0x01
    FC_SET_MULTI_ANGLE = 0x02
    FC_SET_SINGLE_ANGLE = 0x03
    FC_SET_ACCEL = 0x07
    FC_SAVE_PARAMS = 0x08
    FC_CLEAR_ANGLE = 0x09
    FC_SET_SINGLE_ZERO = 0x0A
    FC_FACTORY_RESET = 0x0B
    FC_SET_ADDR = 0x0D
    FC_QUERY = 0x0E          # 读取反馈（数据为类型码）
    FC_SPEED_KP = 0x0F
    FC_SPEED_KI = 0x10
    FC_POS_KP = 0x11
    FC_POS_KI = 0x12

    # 反馈类型码（用于 FC_QUERY 的数据位）
    RT_SPEED = 0x00          # 当前转速
    RT_TOTAL_ANGLE = 0x01    # 转过总角度
    RT_MECH_ANGLE = 0x02     # 单圈机械角度
    RT_ACCEL = 0x03          # 加速度
    RT_VOLTAGE = 0x04        # 母线电压

    # 模式定义
    MODES = {
        0: "速度模式",
        1: "多圈位置模式 (T型规划)",
        2: "单圈绝对位置模式 (T型规划)",
        3: "多圈位置模式 (直通)",
        4: "单圈绝对位置模式 (直通)",
    }

    def __init__(self, port: str, addr: int = 0x02,
                 baud: int = 115200, timeout: float = 0.5,
                 debug: bool = False):
        self.port = port
        self.addr = addr
        self.baud = baud
        self.timeout = timeout
        self.debug = debug
        self._ser: Optional[serial.Serial] = None

    # 动态切换控制的电机地址（多电机总线场景）
    def set_addr(self, new_addr: int) -> None:
        if not (0x01 <= new_addr <= 0x7F):
            raise ValueError("地址必须在 0x01~0x7F 范围")
        if self.debug:
            print(f"[电机] 切换控制地址: 0x{self.addr:02X} -> 0x{new_addr:02X}")
        self.addr = new_addr

    # ------------------------- 连接管理 -------------------------
    def connect(self) -> bool:
        """打开串口"""
        if self._ser and self._ser.is_open:
            return True
        try:
            self._ser = serial.Serial(
                port=self.port,
                baudrate=self.baud,
                timeout=self.timeout,
                write_timeout=0.3,
            )
            # 清空缓冲
            self._ser.reset_input_buffer()
            self._ser.reset_output_buffer()
            time.sleep(0.1)
            if self.debug:
                print(f"[串口] 已连接 {self.port} @ {self.baud}")
            return True
        except PermissionError as e:
            print(f"[错误] 串口 {self.port} 被占用! 请关闭 Arduino IDE 串口监视器或其他程序: {e}")
            return False
        except Exception as e:
            print(f"[错误] 打开串口失败: {e}")
            return False

    def disconnect(self):
        if self._ser:
            self._ser.close()
            if self.debug:
                print(f"[串口] {self.port} 已断开")

    @property
    def is_connected(self) -> bool:
        return bool(self._ser and self._ser.is_open)

    # ------------------------- 底层帧发送 -------------------------
    def _build_frame(self, func: int, data: bytes = b"") -> bytes:
        """构建完整协议帧"""
        payload = bytearray()
        payload.append(FRAME_HEADER)
        payload.append(self.addr)
        payload.append(func)
        payload.extend(data)
        bcc = calc_bcc(bytes(payload))
        payload.append(bcc)
        payload.append(FRAME_TAIL)
        return bytes(payload)

    def _send_frame(self, frame: bytes) -> None:
        """发送一帧（含清缓冲、帧间隔）"""
        assert self._ser, "请先 connect()"
        self._ser.reset_input_buffer()
        self._ser.write(frame)
        self._ser.flush()
        # 协议要求帧间至少 1ms
        time.sleep(0.003)
        if self.debug:
            hex_str = " ".join(f"{b:02X}" for b in frame)
            print(f"[TX] {hex_str}")

    # ------------------------- 底层帧接收 -------------------------
    def _read_response(self, timeout_ms: int = 500) -> MotorResponse:
        """接收并解析电机反馈帧"""
        assert self._ser
        buf = bytearray()
        t0 = time.time()
        tail_found = False
        while (time.time() - t0) * 1000 < timeout_ms:
            ch = self._ser.read(1)
            if not ch:
                if tail_found:
                    break
                continue
            buf.append(ch[0])
            if ch[0] == FRAME_TAIL:
                tail_found = True
                # 多等 5ms 以防还有后续字节
                time.sleep(0.005)
                # 读空缓冲区
                more = self._ser.read(64)
                buf.extend(more)
                # 如果还有更后的 tail，以最后一个为准
                break

        raw = bytes(buf)
        if self.debug:
            if raw:
                hex_str = " ".join(f"{b:02X}" for b in raw)
                print(f"[RX] {hex_str}")
            else:
                print("[RX] （空）")

        return self._parse_response(raw)

    def _parse_response(self, raw: bytes) -> MotorResponse:
        """解析反馈帧"""
        if len(raw) < 5:
            return MotorResponse(raw, False, False, 0, 0, f"帧太短 ({len(raw)} 字节)")
        if raw[0] != FRAME_HEADER:
            return MotorResponse(raw, False, False, 0, 0, f"帧头错误 期望 7A 实际 {raw[0]:02X}")
        if raw[-1] != FRAME_TAIL:
            return MotorResponse(raw, False, False, 0, 0, "帧尾错误")

        # BCC = 倒数第 2 字节
        expected_bcc = calc_bcc(raw[:-2])
        actual_bcc = raw[-2]
        bcc_ok = expected_bcc == actual_bcc
        if not bcc_ok:
            return MotorResponse(
                raw, False, False, 0, 0,
                f"BCC 校验失败: 期望 {expected_bcc:02X} 实际 {actual_bcc:02X}"
            )

        # 地址
        if raw[1] != self.addr:
            return MotorResponse(raw, True, True, 0, 0,
                                 f"地址不匹配 期望 {self.addr:02X} 实际 {raw[1]:02X}")

        # 反馈类型 + 数据
        type_code = raw[2]
        if len(raw) >= 9:
            # 有 4 字节数据
            value = struct.unpack(">i", raw[3:7])[0]
        else:
            value = 0

        text = self._format_feedback(type_code, value)
        return MotorResponse(raw, True, True, type_code, value, text)

    def _format_feedback(self, type_code: int, value: int) -> str:
        """根据反馈类型解析数值为可读文本"""
        if type_code == self.RT_SPEED:
            return f"当前转速: {value} RPM"
        elif type_code == self.RT_TOTAL_ANGLE:
            return f"转过总角度: {value / 10:.1f} 度"
        elif type_code == self.RT_MECH_ANGLE:
            return f"单圈机械角度: {value / 10:.1f} 度"
        elif type_code == self.RT_ACCEL:
            return f"当前加速度: {value} 圈/s²"
        elif type_code == self.RT_VOLTAGE:
            return f"母线电压: {value / 100:.2f} V"
        else:
            return f"未知反馈类型 0x{type_code:02X}, 原始值={value}"

    # ------------------------- 高层命令接口 -------------------------
    def _do_cmd(self, func: int, data: bytes = b"",
                expect_response: bool = False) -> MotorResponse:
        """发送命令并（可选）读取响应"""
        frame = self._build_frame(func, data)
        self._send_frame(frame)
        if expect_response:
            return self._read_response()
        # 不读响应的情况也尽量清空
        time.sleep(0.03)
        if self._ser and self._ser.in_waiting:
            return self._read_response()
        return MotorResponse(b"", False, False, 0, 0, "（不等待响应）")

    # --- 使能/失能 ---
    def enable(self) -> MotorResponse:
        return self._do_cmd(self.FC_ENABLE, expect_response=True)

    def disable(self) -> MotorResponse:
        return self._do_cmd(self.FC_DISABLE, expect_response=True)

    # --- 模式 ---
    def set_mode(self, mode: int) -> MotorResponse:
        """0=速度, 1=多圈T, 2=单圈T, 3=多圈直, 4=单圈直"""
        if mode not in self.MODES:
            raise ValueError(f"模式 {mode} 不在 0~4 范围内")
        data = bytes([0x00, mode])
        return self._do_cmd(self.FC_SET_MODE, data, expect_response=True)

    # --- 速度 ---
    def set_speed(self, rpm: int) -> MotorResponse:
        """速度模式 RPM，支持正负数"""
        data = struct.pack(">h", int(rpm))
        return self._do_cmd(self.FC_SET_SPEED, data, expect_response=True)

    # --- 位置 ---
    def set_multi_angle(self, degree: float) -> MotorResponse:
        """多圈绝对角度（度，精度0.1，需放大10倍传输）"""
        val = int(degree * 10)
        data = struct.pack(">i", val)
        return self._do_cmd(self.FC_SET_MULTI_ANGLE, data, expect_response=True)

    def set_single_angle(self, degree: float) -> MotorResponse:
        """单圈绝对角度（0~359.9 度）"""
        if degree < 0 or degree >= 360:
            raise ValueError("单圈角度必须在 [0, 360) 范围")
        val = int(degree * 10)
        data = struct.pack(">h", val)
        return self._do_cmd(self.FC_SET_SINGLE_ANGLE, data, expect_response=True)

    # --- 加速度 ---
    def set_accel(self, accel_rps2: int) -> MotorResponse:
        """加速度（圈/s²）"""
        data = struct.pack(">H", int(accel_rps2))
        return self._do_cmd(self.FC_SET_ACCEL, data, expect_response=True)

    # --- 读取反馈 ---
    def read_speed(self) -> MotorResponse:
        return self._do_cmd(self.FC_QUERY, bytes([self.RT_SPEED]), expect_response=True)

    def read_total_angle(self) -> MotorResponse:
        return self._do_cmd(self.FC_QUERY, bytes([self.RT_TOTAL_ANGLE]), expect_response=True)

    def read_mech_angle(self) -> MotorResponse:
        return self._do_cmd(self.FC_QUERY, bytes([self.RT_MECH_ANGLE]), expect_response=True)

    def read_accel(self) -> MotorResponse:
        return self._do_cmd(self.FC_QUERY, bytes([self.RT_ACCEL]), expect_response=True)

    def read_voltage(self) -> MotorResponse:
        return self._do_cmd(self.FC_QUERY, bytes([self.RT_VOLTAGE]), expect_response=True)

    # --- 维护 ---
    def save_params(self) -> MotorResponse:
        return self._do_cmd(self.FC_SAVE_PARAMS, expect_response=True)

    def clear_total_angle(self) -> MotorResponse:
        return self._do_cmd(self.FC_CLEAR_ANGLE, expect_response=True)

    def set_single_zero(self) -> MotorResponse:
        """把当前位置设为单圈0度（需 save_params 才掉电保存）"""
        return self._do_cmd(self.FC_SET_SINGLE_ZERO, expect_response=True)

    def factory_reset(self) -> MotorResponse:
        return self._do_cmd(self.FC_FACTORY_RESET, expect_response=True)

    # --- PID ---
    def set_speed_kp(self, v: int) -> MotorResponse:
        data = struct.pack(">H", int(v))
        return self._do_cmd(self.FC_SPEED_KP, data, expect_response=True)

    def set_speed_ki(self, v: int) -> MotorResponse:
        data = struct.pack(">H", int(v))
        return self._do_cmd(self.FC_SPEED_KI, data, expect_response=True)

    def set_pos_kp(self, v: int) -> MotorResponse:
        data = struct.pack(">H", int(v))
        return self._do_cmd(self.FC_POS_KP, data, expect_response=True)

    def set_pos_ki(self, v: int) -> MotorResponse:
        data = struct.pack(">H", int(v))
        return self._do_cmd(self.FC_POS_KI, data, expect_response=True)

    # --- 设备地址设置（写进电机 Flash，掉电保持） ---
    def set_device_address(self, new_addr: int) -> MotorResponse:
        """
        修改电机自身的设备地址（写入 Flash，需 save_params 才永久保存）
        功能码 0x0D，参数为新地址（单字节）
        注意：
            - 新地址必须 1~127 且总线上唯一
            - 发送成功后，self.addr 不会自动更新，需要调用 set_addr(new_addr) 或 self.addr = new_addr
            - 为了永久生效，发送后要再调 save_params()
        """
        if not (0x01 <= new_addr <= 0x7F):
            raise ValueError("新地址必须在 0x01~0x7F 范围")
        data = bytes([new_addr])
        return self._do_cmd(self.FC_SET_ADDR, data, expect_response=True)

    # ------------------------- 总线扫描（多电机级联） -------------------------
    def scan_bus(self, start: int = 1, end: int = 16,
                 per_motor_timeout_ms: int = 120,
                 print_progress: bool = True) -> List[dict]:
        """
        扫描总线上所有在线的 F32C 电机（地址从 start 到 end，含两端）
        方法：给每个地址依次发送「读取母线电压」查询，能回正确响应即视为在线
        返回：[{addr: 2, volt: 12.05, volt_raw: 1205}, ...]
        """
        assert self._ser and self._ser.is_open, "请先 connect()"
        motors: List[dict] = []
        orig_addr = self.addr  # 扫描完成后恢复
        try:
            for a in range(start, end + 1):
                self.set_addr(a)
                # 发送读电压查询
                frame = self._build_frame(self.FC_QUERY, bytes([self.RT_VOLTAGE]))
                self._send_frame(frame)
                resp = self._read_response(timeout_ms=per_motor_timeout_ms)
                if resp.valid and resp.bcc_ok and resp.type_code == self.RT_VOLTAGE:
                    info = {
                        "addr": a,
                        "addr_hex": f"0x{a:02X}",
                        "volt_raw": resp.value,
                        "volt": resp.value / 100.0,
                    }
                    motors.append(info)
                    if print_progress:
                        print(f"  [发现] 地址 0x{a:02X} (ID{a})  电压: {info['volt']:.2f} V")
                elif print_progress:
                    print(f"  [扫描] 地址 0x{a:02X}: 无响应 ({resp.parsed_text})")
        finally:
            self.set_addr(orig_addr)  # 恢复原地址

        if print_progress:
            print(f"\n扫描完成，共发现 {len(motors)} 台电机")
            for m in motors:
                print(f"  - ID {m['addr']} (0x{m['addr']:02X})  电压: {m['volt']:.2f} V")
        return motors

    # --- 一键联通测试 ---
    def connectivity_test(self) -> dict:
        """自动测 3 项（电压/角度/转速），返回字典报告"""
        result = {"voltage": None, "mech_angle": None, "speed": None, "summary": ""}
        try:
            r = self.read_voltage()
            result["voltage"] = r.parsed_text if r.valid else "失败:" + r.parsed_text
            r = self.read_mech_angle()
            result["mech_angle"] = r.parsed_text if r.valid else "失败:" + r.parsed_text
            r = self.read_speed()
            result["speed"] = r.parsed_text if r.valid else "失败:" + r.parsed_text
            ok1 = "母线电压" in result["voltage"]
            ok2 = "单圈机械角度" in result["mech_angle"]
            ok3 = "当前转速" in result["speed"]
            if ok1 and ok2 and ok3:
                result["summary"] = "通信完全正常!"
            elif ok1:
                result["summary"] = "基本联通（电压通，其他可能超时）"
            else:
                result["summary"] = "通信异常，请检查接线/共地/供电"
        except Exception as e:
            result["summary"] = f"测试异常: {e}"
        return result
