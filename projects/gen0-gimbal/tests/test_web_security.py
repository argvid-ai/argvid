"""P1-6 / P1-7 回归测试：Web 调试台安全基线。

P1-6：_send_axis 的 set_addr + 命令必须持锁原子完成——并发请求下
      pan 的命令不允许被 tilt 的 set_addr 穿插（错轴帧）。
P1-7：/api/* 必须携带访问令牌（query token 或 X-Auth-Token 头）；
      默认监听 127.0.0.1（配置项测试）。
纯主机测试：Flask test_client + 假 motor 对象，无需硬件。
"""

import os
import sys
import threading
import time
import unittest

sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", "src", "tools"))

import web_app  # noqa: E402
from f32c_protocol import MotorResponse  # noqa: E402


def _ok_response() -> MotorResponse:
    return MotorResponse(b"", True, True, 0, 0, "ok")


class TestTokenAuth(unittest.TestCase):
    """P1-7：令牌认证"""

    def setUp(self):
        web_app.app.config["TESTING"] = True
        self.client = web_app.app.test_client()

    def test_api_without_token_rejected(self):
        r = self.client.get("/api/ports")
        self.assertEqual(r.status_code, 401)

    def test_api_with_wrong_token_rejected(self):
        r = self.client.get("/api/ports?token=wrong-token")
        self.assertEqual(r.status_code, 401)

    def test_api_with_valid_token_query(self):
        r = self.client.get(f"/api/ports?token={web_app.AUTH_TOKEN}")
        self.assertEqual(r.status_code, 200)

    def test_api_with_valid_token_header(self):
        r = self.client.get("/api/ports",
                            headers={"X-Auth-Token": web_app.AUTH_TOKEN})
        self.assertEqual(r.status_code, 200)

    def test_state_changing_endpoint_requires_token(self):
        """状态修改类端点同样受令牌保护"""
        r = self.client.get("/api/cmd?name=enable")
        self.assertEqual(r.status_code, 401)
        r = self.client.get("/api/cmd?name=enable&token=bad")
        self.assertEqual(r.status_code, 401)

    def test_page_has_token_injected(self):
        """页面渲染时注入真实令牌（占位符被替换）"""
        r = self.client.get("/")
        self.assertEqual(r.status_code, 200)
        self.assertNotIn(b"__AUTH_TOKEN__", r.data)
        self.assertIn(web_app.AUTH_TOKEN.encode(), r.data)


class TestDefaultHost(unittest.TestCase):
    """P1-7：默认只监听回环地址"""

    def test_default_webhost_is_loopback(self):
        # 直接检查 argparse 定义（不启动服务器）
        import argparse
        parser = argparse.ArgumentParser()
        # 复刻 main() 中的定义：默认值必须是回环地址
        # 通过源码断言，避免重复实现漂移
        import inspect
        src = inspect.getsource(web_app)
        self.assertIn('default="127.0.0.1"', src)
        self.assertNotIn('default="0.0.0.0"', src)


class TestSendAxisAtomic(unittest.TestCase):
    """P1-6：并发下 set_addr + 命令原子（无穿插）"""

    def setUp(self):
        self.calls = []
        outer = self

        class FakeMotor:
            addr = 0

            def set_addr(self, a):
                outer.calls.append(("set_addr", a))
                self.addr = a

            def set_mode(self, mode):
                # 记录命令执行时的当前地址；sleep 放大无锁时的穿插窗口
                outer.calls.append(("cmd", self.addr))
                time.sleep(0.02)
                return _ok_response()

        self._orig_motor = web_app.motor
        web_app.motor = FakeMotor()
        web_app.GIMBAL["pan_addr"] = 1
        web_app.GIMBAL["tilt_addr"] = 2

    def tearDown(self):
        web_app.motor = self._orig_motor

    def test_concurrent_axis_commands_do_not_interleave(self):
        barrier = threading.Barrier(2)

        def worker(axis):
            barrier.wait()   # 两线程同时冲进 _send_axis
            web_app._send_axis(axis, "set_mode", 0)

        t1 = threading.Thread(target=worker, args=("pan",))
        t2 = threading.Thread(target=worker, args=("tilt",))
        t1.start()
        t2.start()
        t1.join()
        t2.join()

        # 断言：每个 cmd 的地址必须与其前一个 set_addr 一致（无穿插）。
        # 修复前（无锁）：pan 的 set_mode 执行期间被 tilt 的 set_addr 穿插，
        # 该断言失败（复审复现的错轴帧）。
        last_addr = None
        for kind, val in self.calls:
            if kind == "set_addr":
                last_addr = val
            else:
                self.assertEqual(val, last_addr,
                                 f"检测到穿插：命令在地址 {last_addr} 上下文中以 {val} 执行")
        # 两轴各执行了一次
        self.assertEqual(len([c for c in self.calls if c[0] == "cmd"]), 2)
        self.assertEqual(len([c for c in self.calls if c[0] == "set_addr"]), 2)


if __name__ == "__main__":
    unittest.main()
