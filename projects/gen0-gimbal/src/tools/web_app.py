"""
F32C 电机 Web 调试界面（多电机级联总线版，直接 USB-TTL 连电机）

运行：
    pip install -r requirements.txt
    python web_app.py              # 启动后网页里选 COM 口
    python web_app.py --port COM7  # 启动时直接绑定 COM 口
    python web_app.py --port COM7 --scan

浏览器访问：http://127.0.0.1:5000
"""

import argparse
import time
from collections import deque
from flask import Flask, Response, request, jsonify
from werkzeug.exceptions import HTTPException
from f32c_protocol import F32CMotor, list_serial_ports

app = Flask(__name__)


# 全局兜底：任何未捕获异常（如串口读超时、电机无响应）都返回 JSON，
# 而不是 Flask 默认的 500 HTML 页面 —— 否则前端 ajax 的 r.json() 会解析失败
@app.errorhandler(Exception)
def handle_any_error(e):
    if isinstance(e, HTTPException):
        return e
    add_log(f"[错误] 服务器内部: {type(e).__name__}: {e}")
    return jsonify({"success": False, "msg": f"服务器内部错误: {type(e).__name__}: {e}",
                    "result_html": f"[错误] 服务器内部错误: {type(e).__name__}: {e}"})

# 所有响应都加 no-cache，防止浏览器缓存旧版 HTML/JS（这就是你之前看到旧占位文字的根因）
@app.after_request
def disable_cache(response):
    response.headers["Cache-Control"] = "no-cache, no-store, must-revalidate, max-age=0"
    response.headers["Pragma"] = "no-cache"
    response.headers["Expires"] = "0"
    return response

# 全局状态
motor: F32CMotor = None
online_motors = []  # 扫描到的在线电机列表: [{addr, addr_hex, volt}]
log_lock = None  # 简化：不使用多线程
log_lines: deque = deque(maxlen=300)

# ======================== 云台双轴控制状态 ========================
# 1号电机=水平轴(pan, 左右, 不限位)  2号电机=垂直轴(tilt, 上下, 限位±90°)
GIMBAL = {
    "pan_addr": None,
    "tilt_addr": None,
    "pan_mode": None,   # None/0(速度)/2(单圈位置) 缓存避免重复切模式
    "tilt_mode": None,
    "pan_angle": 0.0,   # 最近一次下发的目标角（±180 表示法）
    "tilt_angle": 0.0,  # 最近一次下发的目标角（±90 表示法）
}


def gimbal_auto_config(motors):
    """扫描后自动配置云台：第1台=水平，第2台=垂直（已手动配置过则不覆盖）"""
    if len(motors) >= 2 and (GIMBAL["pan_addr"] is None or GIMBAL["tilt_addr"] is None):
        GIMBAL["pan_addr"] = motors[0]["addr"]
        GIMBAL["tilt_addr"] = motors[1]["addr"]
        GIMBAL["pan_mode"] = None
        GIMBAL["tilt_mode"] = None
        add_log(f"[云台] 自动配置: 水平=ID{GIMBAL['pan_addr']}, 垂直=ID{GIMBAL['tilt_addr']}")


def _gimbal_ready():
    ok, err = needs_motor()
    if not ok:
        return False, err
    if GIMBAL["pan_addr"] is None or GIMBAL["tilt_addr"] is None:
        return False, "云台未配置：请先扫描电机（前两台自动配置为 水平/垂直），或在云台卡片中手动填写 ID"
    return True, ""


def _send_axis(axis: str, method: str, *args):
    """向指定轴电机发命令：临时切总线地址 → 调用 → 恢复不需要（每次发送前都会 set_addr）"""
    motor.set_addr(GIMBAL[f"{axis}_addr"])
    r = getattr(motor, method)(*args)
    add_log(f"[云台][{axis}] {method}({', '.join(str(a) for a in args)}) -> {'OK' if r.valid else r.parsed_text}")
    return r


def add_log(text: str):
    ts = time.strftime("%H:%M:%S")
    log_lines.append(f"[{ts}] {text}")


# ======================== HTML 模板 ========================
# ⚠️ 必须用 r"""原始字符串"""：普通字符串里 Python 会把 \n \t \\ 等转义处理掉，
#    曾经导致 JS 里的正则 /\n/g 被转成真实换行 → 浏览器报
#    "SyntaxError: Invalid regular expression: missing /" → 整个 script 加载失败
#    → "listPorts is not defined" → 刷新按钮没反应。
#    raw string 保证斜杠/反斜杠原封不动到达浏览器。
PAGE = r"""
<!DOCTYPE html>
<html lang="zh-CN">
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width, initial-scale=1.0">
<meta http-equiv="Cache-Control" content="no-cache, no-store, must-revalidate">
<meta http-equiv="Pragma" content="no-cache">
<meta http-equiv="Expires" content="0">
<title>F32C 多电机总线调试台  [BUILD 2026-08-22 E]</title>
<style>
* { box-sizing: border-box; }
body { font-family:"Microsoft YaHei","Segoe UI",sans-serif;background:#0f172a;color:#e2e8f0;margin:0;padding:20px }
.wrap { max-width: 1280px; margin:0 auto }
h1 { color:#38bdf8; text-align:center; margin:0 0 14px;letter-spacing:1px }
h1 small { font-size:13px;color:#94a3b8;font-weight:400;margin-left:8px }
.badge { display:inline-block;padding:3px 10px;border-radius:10px;font-size:12px;font-weight:600 }
.ok  { background:#166534;color:#bbf7d0 }
.err { background:#991b1b;color:#fecaca }
.warn{ background:#854d0e;color:#fde68a }
.info{ background:#1e40af;color:#bfdbfe }
.dim { background:#334155;color:#cbd5e1 }

/* 步骤条 */
.steps { display:flex;gap:6px;margin-bottom:16px;flex-wrap:wrap;
         background:#1e293b;border:1px solid #334155;border-radius:10px;padding:12px 14px;align-items:center }
.steps .step { display:flex;align-items:center;gap:8px;padding:6px 14px;border-radius:8px;
               font-size:13px;font-weight:600;background:#334155;color:#94a3b8;flex:1;min-width:160px;justify-content:center }
.steps .step .num { width:22px;height:22px;border-radius:50%;background:#475569;color:#cbd5e1;
                    display:inline-flex;align-items:center;justify-content:center;font-size:12px }
.steps .step.done  { background:#0c4a6e;color:#bae6fd }
.steps .step.done .num  { background:#0ea5e9;color:#082f49 }
.steps .step.active{ background:#166534;color:#bbf7d0;box-shadow:0 0 0 2px #22c55e inset }
.steps .step.active .num{ background:#22c55e;color:#052e16 }
.steps .arrow { color:#475569;font-size:18px;display:none }

/* 顶部状态 */
.topbar { background:#1e293b;border:1px solid #334155;border-radius:10px;padding:14px 20px;
          display:flex;justify-content:space-between;align-items:center;flex-wrap:wrap;gap:14px;margin-bottom:16px }
.topbar .info-group { display:flex;gap:22px;flex-wrap:wrap }
.topbar .it .lb { font-size:11px;color:#94a3b8;margin-bottom:2px }
.topbar .it .vl { color:#22d3ee;font-weight:bold;font-size:14px }

/* 2 列主布局 */
.main { display:grid;grid-template-columns: 380px 1fr;gap:16px }
@media(max-width:900px){ .main { grid-template-columns:1fr } }

.card { background:#1e293b;border:1px solid #334155;border-radius:10px;padding:16px;position:relative }
.card h3 { margin:0 0 12px;color:#38bdf8;font-size:15px;border-bottom:1px dashed #334155;padding-bottom:8px;display:flex;align-items:center;gap:8px }
.card h3 .tip { margin-left:auto;font-size:11px;color:#64748b;font-weight:400 }
.card h3 .tag { font-size:11px;font-weight:600;padding:2px 8px;border-radius:8px;background:#334155;color:#cbd5e1 }

/* 遮罩禁用 */
.lock-mask { position:absolute;inset:0;background:rgba(15,23,42,0.72);border-radius:10px;
             display:flex;align-items:center;justify-content:center;text-align:center;padding:20px;z-index:2;backdrop-filter:blur(1px) }
.lock-mask .btx { color:#cbd5e1;font-size:13px;line-height:1.8 }
.lock-mask .btx b { color:#fbbf24;display:block;font-size:14px;margin-bottom:6px }

button { padding:9px 16px;margin:3px 2px;border:none;border-radius:6px;cursor:pointer;font-size:13px;font-weight:600;transition:all .15s;flex-shrink:0 }
button:hover:not(:disabled) { transform:translateY(-1px);filter:brightness(1.15) }
button:disabled { opacity:0.4; cursor:not-allowed; transform:none; filter:none }
.bb { background:#0284c7; color:#fff }
.bg { background:#16a34a; color:#fff }
.br { background:#dc2626; color:#fff }
.by { background:#ca8a04; color:#fff }
.bp { background:#7c3aed; color:#fff }
.bs { background:#475569; color:#fff }
.big-btn { padding:11px 22px;font-size:14px }
.btn-refresh { padding:9px 12px;min-width:48px;text-align:center }

.row { display:flex;align-items:center;margin:7px 0;gap:6px;flex-wrap:wrap }
.row label { width:92px;color:#94a3b8;font-size:12px;flex-shrink:0 }
.row input, .row select { padding:7px 9px;background:#0f172a;border:1px solid #334155;color:#e2e8f0;border-radius:5px;flex:1;min-width:60px;font-size:13px }
.row input:focus, .row select:focus { outline:none;border-color:#38bdf8;box-shadow:0 0 0 2px rgba(56,189,248,0.2) }

/* 串口连接卡片 */
.conn-row { display:flex;gap:6px;margin-bottom:10px;align-items:center }
.conn-row .port-sel { flex:1;padding:9px;background:#0f172a;color:#e2e8f0;border:1px solid #334155;border-radius:5px;font-size:13px }
.baud-sel { width:120px;padding:9px;background:#0f172a;color:#e2e8f0;border:1px solid #334155;border-radius:5px;font-size:13px }

/* 电机列表 */
.motor-list { background:#020617;border-radius:6px;padding:8px;max-height:300px;overflow-y:auto;border:1px solid #1e293b }
.motor-list .hint { color:#64748b;text-align:center;padding:20px 8px;font-size:12px }
.motor-list .err { color:#f87171;text-align:center;padding:20px 8px;font-size:12px }
.motor-list .m { display:flex;justify-content:space-between;align-items:center;padding:9px 10px;margin:4px 0;border-radius:5px;border:1px solid #334155;cursor:pointer;transition:all .12s }
.motor-list .m:hover { background:#1e293b;border-color:#22d3ee }
.motor-list .m.active { background:#0c4a6e;border-color:#38bdf8;box-shadow:0 0 0 1px #38bdf8 inset }
.motor-list .m .id-box { display:flex;align-items:center;gap:6px }
.motor-list .m .addr_hex { color:#94a3b8;font-size:11px;font-family:Consolas,monospace }
.motor-list .m .volt { color:#fbbf24;font-weight:600;font-family:Consolas,monospace }

/* 控制网格 */
.ctrl-grid { display:grid; grid-template-columns: repeat(auto-fit, minmax(320px, 1fr)); gap:14px }

/* 其他 */
.result { margin-top:10px;padding:12px;background:#0f172a;border:1px solid #334155;border-radius:6px;
          font-family:Consolas,monospace;font-size:13px;min-height:60px;white-space:pre-wrap;line-height:1.6 }
.log { background:#020617;border:1px solid #334155;border-radius:10px;padding:14px;margin-top:16px }
.log h3 { color:#38bdf8;margin:0 0 8px;font-size:14px;display:flex;justify-content:space-between;align-items:center;border:none;padding:0 }
.logc { font-family:Consolas,"Courier New",monospace;font-size:12.5px;background:#000;color:#a7f3d0;padding:12px;border-radius:6px;height:280px;overflow-y:auto;line-height:1.75 }
.logc .tx { color:#60a5fa }
.logc .rx { color:#fbbf24 }
.logc .er { color:#f87171;font-weight:600 }
.sub-hint { color:#64748b;font-size:11px;margin:6px 0 0;line-height:1.5 }
.divider { border:none;border-top:1px dashed #334155;margin:12px 0 }

.btn-group { display:flex;flex-wrap:wrap;gap:3px }

/* 串口按钮卡片列表（可视化选择）*/
.port-cards { display:flex;flex-direction:column;gap:6px;margin:6px 0 }
.port-cards .pc { display:flex;align-items:center;gap:10px;padding:10px 12px;
                  border:1px solid #334155;border-radius:7px;background:#0f172a;cursor:pointer;
                  transition:all .15s }
.port-cards .pc:hover { border-color:#38bdf8;transform:translateX(2px);background:#1e293b }
.port-cards .pc.active { border-color:#22c55e;background:#052e16;
                         box-shadow:0 0 0 2px rgba(34,197,94,0.35) inset }
.port-cards .pc .name-tag { background:#1e3a8a;color:#bfdbfe;font-family:Consolas,monospace;
                            font-weight:700;padding:4px 10px;border-radius:6px;min-width:72px;text-align:center;flex-shrink:0 }
.port-cards .pc.active .name-tag { background:#166534;color:#bbf7d0 }
.port-cards .pc .desc { flex:1;color:#cbd5e1;font-size:13px;overflow:hidden;text-overflow:ellipsis;white-space:nowrap }
.port-cards .pc.active .desc { color:#bbf7d0 }
.port-cards .pc .chip { font-size:11px;color:#94a3b8;background:#1e293b;padding:2px 8px;border-radius:6px;flex-shrink:0 }
.port-cards .pc.active .chip { background:#14532d;color:#86efac }
.port-cards-empty { text-align:center;padding:18px 10px;color:#64748b;font-size:13px;border:1px dashed #475569;border-radius:7px }
.port-cards-empty b { color:#f87171 }
.refresh-row { display:flex;gap:8px;align-items:center;margin-bottom:8px }
.refresh-row .btn-refresh-big { padding:10px 16px }
.refresh-row .count-badge { font-size:13px;font-weight:600 }

/* ============ 云台双轴控制 ============ */
.gimbal-wrap { display:flex;gap:28px;flex-wrap:wrap;justify-content:center;align-items:flex-start;padding:6px 0 }
.g-ops { position:relative;display:flex;gap:28px;flex-wrap:wrap;justify-content:center }
.g-left,.g-mid,.g-right { display:flex;flex-direction:column;align-items:center;gap:10px }
.g-hint { color:#64748b;font-size:11px;text-align:center;line-height:1.6 }

/* 方向键盘：3x3 网格十字 */
.pad { display:grid;grid-template-columns:repeat(3,58px);grid-template-rows:repeat(3,58px);gap:7px;
       background:#0f172a;padding:12px;border-radius:12px;border:1px solid #334155 }
.pad-btn { font-size:22px;font-weight:700;background:#334155;color:#e2e8f0;border-radius:9px;
           touch-action:none;user-select:none;-webkit-user-select:none;margin:0 }
.pad-btn:hover:not(:disabled) { filter:brightness(1.25);transform:none }
.pad-btn.pressed { background:#16a34a;color:#fff;box-shadow:0 0 0 2px #22c55e inset }
.pad-btn:disabled { opacity:.35 }
#jog-up    { grid-column:2;grid-row:1 }
#jog-left  { grid-column:1;grid-row:2 }
#jog-right { grid-column:3;grid-row:2 }
#jog-down  { grid-column:2;grid-row:3 }
.pad-mid   { grid-column:2;grid-row:2;background:#ca8a04;font-size:20px }

/* 摇杆圆盘 */
.joy-base { width:206px;height:206px;border-radius:50%;position:relative;touch-action:none;
            background:radial-gradient(circle at 50% 40%, #1e293b 0%, #0f172a 70%);
            border:2px solid #334155;box-shadow:0 4px 18px rgba(0,0,0,.5) inset }
.joy-base::after { content:'';position:absolute;inset:34px;border-radius:50%;
                   border:1px dashed #334155;pointer-events:none }
.joy-cross-h,.joy-cross-v { position:absolute;background:#334155;pointer-events:none }
.joy-cross-h { left:16px;right:16px;top:50%;height:1px }
.joy-cross-v { top:16px;bottom:16px;left:50%;width:1px }
.joy-knob { width:58px;height:58px;border-radius:50%;position:absolute;left:50%;top:50%;
            background:radial-gradient(circle at 35% 30%, #7dd3fc, #0284c7 70%);
            border:2px solid #bae6fd;cursor:grab;box-shadow:0 3px 12px rgba(2,132,199,.55);
            transform:translate(-50%,-50%);transition:box-shadow .12s;z-index:2 }
.joy-knob:active { cursor:grabbing;box-shadow:0 0 0 6px rgba(56,189,248,.18),0 3px 16px rgba(2,132,199,.7) }
.joy-base.off .joy-knob { filter:grayscale(.8);opacity:.5;cursor:not-allowed }

/* 右侧参数列 */
.g-right { min-width:210px }
.g-right .row { margin:6px 0 }
.g-right .row input { max-width:90px }
.g-right button { width:100%;margin:5px 0 0 }
.g-angles { display:flex;gap:14px;background:#0f172a;border:1px solid #334155;border-radius:8px;
            padding:9px 14px;margin-top:10px;font-size:13px }
.g-angles b { color:#22d3ee;font-family:Consolas,monospace;font-size:15px }

</style>
</head>
<body>
<div class="wrap">
    <h1>⚡ F32C 多电机总线调试台 <small>· USB-TTL 直连控制 · 无需 ESP32</small>
        <span style="display:inline-block;float:right;font-size:12px;padding:4px 10px;background:#1e293b;border:1px solid #334155;border-radius:8px;color:#fbbf24;font-weight:700">
            版本 BUILD 2026-08-22_E
        </span>
    </h1>

    <!-- 操作步骤条 -->
    <div class="steps">
        <div class="step" id="st1"><span class="num">1</span>选择 COM 口</div>
        <div class="arrow">→</div>
        <div class="step" id="st2"><span class="num">2</span>打开串口</div>
        <div class="arrow">→</div>
        <div class="step" id="st3"><span class="num">3</span>扫描电机编号</div>
        <div class="arrow">→</div>
        <div class="step" id="st4"><span class="num">4</span>选择要控制的电机</div>
        <div class="arrow">→</div>
        <div class="step" id="st5"><span class="num">5</span>发送命令操作</div>
    </div>

    <!-- 顶部状态 -->
    <div class="topbar">
        <div class="info-group">
            <div class="it"><div class="lb">USB-TTL 串口</div><div class="vl" id="v-port">（未打开）</div></div>
            <div class="it"><div class="lb">波特率</div><div class="vl" id="v-baud">115200</div></div>
            <div class="it"><div class="lb">在线电机数</div><div class="vl" id="v-count"><span class="badge dim">0 台</span></div></div>
            <div class="it"><div class="lb">当前控制电机</div><div class="vl" id="v-current"><span class="badge dim">未选择</span></div></div>
        </div>
        <div>
            <span class="badge dim" id="v-connect">🔴 串口未打开</span>
        </div>
    </div>

    <div class="main">
        <!-- ============ 左列：串口 + 扫描 + 电机列表 ============ -->
        <div>
            <!-- 串口连接卡 -->
            <div class="card">
                <h3>🔌 第一步 · 选择串口并打开 <span class="tag">必须先做</span></h3>

                <!-- ① 刷新按钮 + 计数 badge -->
                <div class="refresh-row">
                    <button class="bs btn-refresh-big" onclick="listPorts()" title="刷新 COM 口" style="flex:1;font-size:14px">
                        🔄 刷新 COM 列表
                    </button>
                    <span class="count-badge badge dim" id="port-count">未刷新</span>
                </div>

                <!-- ② 可视化按钮卡片列表（主选方式）：一卡一口，直接点卡片选中 -->
                <div style="font-size:12px;color:#94a3b8;margin-bottom:4px">
                    ▼ 检测到的串口（<b style="color:#22d3ee">点下面的卡片</b>来选中）：
                </div>
                <div class="port-cards" id="port-cards">
                    <div class="port-cards-empty">
                        请先点上方「🔄 刷新 COM 列表」<br>
                        刷新后这里会出现一个一个可点击的串口卡片。
                    </div>
                </div>

                <!-- ③ 备选下拉 -->
                <div style="text-align:center;color:#64748b;font-size:11px;margin:10px 0 2px">
                    ——— 或者用传统下拉方式选择 ———
                </div>
                <div class="row">
                    <label>下拉选择</label>
                    <select id="sel-port" style="flex:2;padding:7px 9px;background:#0f172a;color:#e2e8f0;border:1px solid #334155;border-radius:5px" onchange="onPortSelect()">
                        <option value="" disabled selected>（请先刷新）</option>
                    </select>
                </div>

                <!-- ④ 兜底：手动输入 -->
                <div style="text-align:center;color:#64748b;font-size:11px;margin:10px 0 2px">
                    ——— 上面两种都不行？直接打字输入 ———
                </div>
                <div class="row">
                    <label>手动输入</label>
                    <input type="text" id="port-manual" style="flex:2" placeholder="例：COM3   （或只输数字 3）" oninput="onManualInput()">
                </div>

                <div class="divider"></div>

                <!-- 波特率 -->
                <div class="row">
                    <label>波特率</label>
                    <select id="sel-baud" style="flex:2;padding:7px 9px;background:#0f172a;color:#e2e8f0;border:1px solid #334155;border-radius:5px">
                        <option value="115200" selected>115200（出厂默认 / 推荐）</option>
                        <option value="9600">9600</option>
                        <option value="19200">19200</option>
                        <option value="38400">38400</option>
                        <option value="57600">57600</option>
                        <option value="230400">230400</option>
                    </select>
                </div>

                <div style="display:flex;gap:8px;justify-content:stretch;margin-top:10px">
                    <button class="bg big-btn" style="flex:1" id="btn-open" onclick="openPort()" disabled>
                        ✅ 打开串口
                    </button>
                    <button class="br big-btn" style="flex:1" id="btn-close" onclick="closePort()" disabled>
                        ❌ 关闭串口
                    </button>
                </div>
                <div id="port-tip" style="margin-top:8px;font-size:12px;color:#64748b;line-height:1.6"></div>

                <p class="sub-hint" style="margin-top:10px">
                    💡 接线：GND 必须共地 · TX→RX · RX←TX · 电机 V+ 接 8~15V 独立电源<br>
                    ⚠️ 电平必须是 3.3V TTL（CH340/CH9102/CP2102/FT232 皆可）
                </p>
            </div>

            <!-- 扫描卡 -->
            <div class="card" style="margin-top:16px">
                <h3>🔍 第二步 · 扫描电机总线 <span class="tag">串口打开后可用</span></h3>
                <div class="row">
                    <label>扫描范围</label>
                    <input type="number" id="scan-s" value="1" min="1" max="127" style="max-width:80px" disabled>
                    <span style="color:#64748b">~</span>
                    <input type="number" id="scan-e" value="16" min="1" max="127" style="max-width:80px" disabled>
                    <button class="bp" style="flex:1" id="btn-scan" onclick="scanBus()" disabled>
                        🔍 扫描总线上的电机
                    </button>
                </div>
                <div class="motor-list" id="motor-list">
                    <div class="hint">请先完成「打开串口」，再扫描电机编号</div>
                </div>
                <div class="divider"></div>
                <div class="row">
                    <label>改电机地址</label>
                    <input type="number" id="new-addr" min="1" max="127" placeholder="新编号 1~127" disabled>
                    <button class="by" id="btn-setaddr" onclick="changeAddr()" disabled>改地址</button>
                </div>
                <p class="sub-hint">改地址后需要再点「保存参数」才会永久写入 Flash</p>
                <div id="mask-scan" class="lock-mask" style="display:flex">
                    <div class="btx"><b>🔌 请先打开串口</b>扫描电机需要通过串口发数据，<br>请先在上方打开串口后再继续。</div>
                </div>
            </div>
        </div>

        <!-- ============ 右列：控制操作卡片 ============ -->
        <div>
            <div class="ctrl-grid">
                <!-- 使能/模式 -->
                <div class="card">
                    <h3>🎛 使能 / 模式 / 测试</h3>
                    <div class="btn-group">
                        <button class="bg" onclick="cmd('enable')">使能电机</button>
                        <button class="br" onclick="cmd('disable')">失能电机</button>
                        <button class="bp" onclick="cmd('test')">一键联通测试</button>
                    </div>
                    <hr class="divider">
                    <div class="row">
                        <label>控制模式</label>
                        <select id="mode">
                            <option value="0">0 速度模式</option>
                            <option value="1">1 多圈位置 (T型)</option>
                            <option value="2">2 单圈位置 (T型)</option>
                            <option value="3">3 多圈位置 (直通)</option>
                            <option value="4">4 单圈位置 (直通)</option>
                        </select>
                        <button class="bb" onclick="cmd('setmode', 'mode')">设置</button>
                    </div>
                    <div id="mask-1" class="lock-mask"><div class="btx"><b>📌 请先完成前序步骤</b>打开串口 → 扫描电机 →<br>在左侧列表点击要控制的电机。</div></div>
                </div>

                <!-- 速度/位置 -->
                <div class="card">
                    <h3>🚀 速度 / 位置 / 加速度</h3>
                    <div class="row"><label>目标速度</label><input type="number" id="v-speed" value="100"><span style="font-size:11px;color:#94a3b8">RPM</span><button class="bb" onclick="cmd('setspeed','v-speed')">发送</button></div>
                    <div class="row"><label>多圈角度</label><input type="number" id="v-angle" value="360" step="0.1"><span style="font-size:11px;color:#94a3b8">°</span><button class="bb" onclick="cmd('setangle','v-angle')">发送</button></div>
                    <div class="row"><label>单圈角度</label><input type="number" id="v-single" value="90" step="0.1" min="0" max="359.9"><span style="font-size:11px;color:#94a3b8">°</span><button class="bb" onclick="cmd('setsingle','v-single')">发送</button></div>
                    <div class="row"><label>加速度</label><input type="number" id="v-accel" value="100"><span style="font-size:11px;color:#94a3b8">圈/s²</span><button class="bb" onclick="cmd('setaccel','v-accel')">发送</button></div>
                    <div id="mask-2" class="lock-mask"><div class="btx"><b>📌 请先完成前序步骤</b>打开串口 → 扫描电机 →<br>在左侧列表点击要控制的电机。</div></div>
                </div>

                <!-- 读取反馈 -->
                <div class="card">
                    <h3>📡 读取电机反馈</h3>
                    <div class="btn-group">
                        <button class="by" onclick="cmd('voltage')">母线电压</button>
                        <button class="by" onclick="cmd('speed')">当前转速</button>
                        <button class="by" onclick="cmd('angle')">总转角</button>
                        <button class="by" onclick="cmd('mech')">机械角度</button>
                        <button class="by" onclick="cmd('accel')">加速度</button>
                    </div>
                    <div id="mask-3" class="lock-mask"><div class="btx"><b>📌 请先完成前序步骤</b>打开串口 → 扫描电机 →<br>在左侧列表点击要控制的电机。</div></div>
                </div>

                <!-- 维护 / PID -->
                <div class="card">
                    <h3>🔧 维护 / PID 参数</h3>
                    <div class="btn-group">
                        <button class="bs" onclick="cmd('save')">💾 保存参数</button>
                        <button class="bs" onclick="cmd('zero')">总角度清零</button>
                        <button class="bs" onclick="cmd('setzero')">当前位设0点</button>
                        <button class="br" onclick="if(confirm('确认恢复出厂设置?'))cmd('factory')">恢复出厂</button>
                    </div>
                    <hr class="divider">
                    <div class="row"><label>速度 KP</label><input type="number" id="skp" value="10"><button class="bb" onclick="pid('speed_kp','skp')">设置</button></div>
                    <div class="row"><label>速度 KI</label><input type="number" id="ski" value="10"><button class="bb" onclick="pid('speed_ki','ski')">设置</button></div>
                    <div class="row"><label>位置 KP</label><input type="number" id="pkp" value="10"><button class="bb" onclick="pid('pos_kp','pkp')">设置</button></div>
                    <div class="row"><label>位置 KI</label><input type="number" id="pki" value="10"><button class="bb" onclick="pid('pos_ki','pki')">设置</button></div>
                    <div id="mask-4" class="lock-mask"><div class="btx"><b>📌 请先完成前序步骤</b>打开串口 → 扫描电机 →<br>在左侧列表点击要控制的电机。</div></div>
                </div>
            </div>

            <!-- 云台双轴控制 -->
            <div class="card" style="margin-top:16px" id="gimbal-card">
                <h3>🎮 云台双轴控制 <span class="tag">需 2 台电机</span><span class="tip" id="g-state">未配置</span></h3>
                <div class="gimbal-wrap">
                    <!-- 左+中：操作区（未就绪时遮罩盖住，防止误触发电机） -->
                    <div class="g-ops">
                        <!-- 左：方向键盘（按住持续转/松开停） -->
                        <div class="g-left">
                            <div class="pad">
                                <button class="pad-btn" id="jog-up"    title="2号电机 上仰">↑</button>
                                <button class="pad-btn" id="jog-left"  title="1号电机 左转">←</button>
                                <button class="pad-btn pad-mid" id="g-center" title="双轴回中(0°)">⌂</button>
                                <button class="pad-btn" id="jog-right" title="1号电机 右转">→</button>
                                <button class="pad-btn" id="jog-down"  title="2号电机 下俯">↓</button>
                            </div>
                            <div class="g-hint"><b style="color:#94a3b8">按住持续转 · 松开停</b><br>⌂ = 双轴回中</div>
                        </div>

                        <!-- 中：摇杆圆盘（拖动位置随动） -->
                        <div class="g-mid">
                            <div class="joy-base off" id="joy-base">
                                <div class="joy-cross-h"></div>
                                <div class="joy-cross-v"></div>
                                <div class="joy-knob" id="joy-knob"></div>
                            </div>
                            <div class="g-hint"><b style="color:#94a3b8">按住中心圆钮拖动 → 位置随动</b><br>水平 ±180°（不限位） · 垂直 ±90°（限位）</div>
                        </div>

                        <div id="mask-gimbal" class="lock-mask" style="display:flex;border-radius:12px">
                            <div class="btx"><b>🎮 云台未就绪</b>打开串口 → 扫描电机（前两台自动配置）<br>或在右侧手动填电机 ID 后点「应用电机配置」。</div>
                        </div>
                    </div>

                    <!-- 右：参数配置（始终可操作） -->
                    <div class="g-right">
                        <div class="row"><label>1号·左右</label><input type="number" id="g-pan-addr" min="1" max="127" placeholder="ID"></div>
                        <div class="row"><label>2号·上下</label><input type="number" id="g-tilt-addr" min="1" max="127" placeholder="ID"></div>
                        <button class="bb" onclick="gimbalApply()">应用电机配置</button>
                        <div class="row"><label>点动速度</label><input type="number" id="g-speed" value="60" min="1" max="300"><span style="font-size:11px;color:#94a3b8">RPM</span></div>
                        <button class="by" onclick="gimbalZero()">📍 设当前位置为 0°</button>
                        <div class="g-angles">
                            <div>水平 <b id="g-pan-val">+0.0°</b></div>
                            <div>垂直 <b id="g-tilt-val">+0.0°</b></div>
                        </div>
                    </div>
                </div>
            </div>

            <!-- 结果+日志 始终可用显示 -->
            <div class="card" style="margin-top:16px">
                <h3>📤 最近执行结果</h3>
                <div class="result" id="result">等待操作… 开始时请在左上点击「🔄 刷新 COM 口」，然后选择 COM，点击「✅ 打开串口」。</div>
            </div>

            <!-- 自定义命令 -->
            <div class="card" style="margin-top:16px">
                <h3>✍️ 自定义命令 <span class="tip">直接发送文本指令</span></h3>
                <div class="row">
                    <input type="text" id="cust" style="flex:2" placeholder="例: enable / setspeed 100 / voltage / scan 1 16 / use 3"
                           onkeydown="if(event.key==='Enter')custom()">
                    <button class="bb" onclick="custom()" id="btn-cust" disabled>发送</button>
                </div>
                <p class="sub-hint">
                    命令：enable · disable · test · voltage · speed · angle · mech · accel · save · zero · setzero · factory<br>
                    带参数：setmode N · setspeed N · setangle N · setsingle N · setaccel N · use N · scan [S [E]] · setaddr N
                </p>
            </div>
        </div>
    </div>

    <!-- 日志 -->
    <div class="log">
        <h3>
            <span>📋 操作日志 (每 1.5 秒自动刷新)</span>
            <button class="bs" style="padding:5px 14px;font-size:12px" onclick="clearLog()">清空日志</button>
        </h3>
        <div class="logc" id="logc"></div>
    </div>
</div>

<script>
// ====== BUILD 2026-08-22_E（云台双轴控制 + 遮罩只盖操作区） ======
// 在浏览器 Console 里看到这行 = script 块解析/加载成功，不存在语法错误
var BUILD_ID = 'BUILD_2026-08-22_E';
try { console.log('%c[F32C WebUI] Script loaded: ' + BUILD_ID, 'color:#22c55e;font-weight:700'); } catch(e) {}

// —— 修复历史：showResult 里原来是 /\n/g 字面量。PAGE 曾是普通 Python 字符串，
//    Python 把 \n 转成了真实换行符，浏览器拿到跨行正则 →
//    "Invalid regular expression: missing /" → 整段 script 报废（listPorts 未定义）。
// —— 现在：PAGE 改用 Python 原始字符串（raw string），且所有正则一律 new RegExp 构造，双保险。
var RE_NEWLINES = new RegExp('\\n', 'g');

var $ = function(id) { return document.getElementById(id); };
var state = {
    connected:false,   // 串口是否已打开
    motorCount:0,      // 扫描到的电机数
    selectedAddr:null  // 当前选中的电机地址
};

function showResult(text, ok) {
    if (ok === undefined) ok = true;
    const el = $('result');
    el.style.color = ok ? '#4ade80' : '#f87171';
    el.innerHTML = String(text).replace(RE_NEWLINES, '<br>');
}
function fmtLog(t){
    return String(t).replaceAll('[TX]','<span class="tx">[TX]</span>')
                    .replaceAll('[RX]','<span class="rx">[RX]</span>')
                    .replaceAll('[错误]','<span class="er">[错误]</span>');
}

async function ajax(url, method='GET') {
    try {
        // 加时间戳防止浏览器缓存（每次请求都强制向服务器拿最新）
        const sep = url.includes('?') ? '&' : '?';
        const nocache = sep + '_t=' + Date.now();
        const r = await fetch(url + nocache, {method, cache:'no-store'});
        return await r.json();
    } catch(e) {
        return {success:false, msg:'请求失败: ' + e.message};
    }
}

function currentPort() {
    const manual = ($('port-manual') || {}).value || '';
    if (manual.trim()) return manual.trim().toUpperCase();
    return $('sel-port').value || '';
}

// ============ 状态控制：步骤条 + 遮罩 + 按钮禁用 ============
function updateUI() {
    // 步骤条
    const s1 = $('st1'), s2 = $('st2'), s3 = $('st3'), s4 = $('st4'), s5 = $('st5');
    const portPicked = !!currentPort();
    s1.className = 'step ' + ((portPicked || state.connected) ? 'done' : 'active');
    s2.className = 'step ' + (state.connected ? 'done' : (portPicked ? 'active' : ''));
    s3.className = 'step ' + (state.connected ? (state.motorCount>0 ? 'done' : 'active') : '');
    s4.className = 'step ' + (state.selectedAddr ? 'done' : (state.motorCount>0 ? 'active' : ''));
    s5.className = 'step ' + (state.selectedAddr ? 'active' : '');

    // 顶部 status badge
    const bc = $('v-connect');
    if (state.connected) {
        bc.textContent = '🟢 串口已打开';
        bc.className = 'badge ok';
    } else {
        bc.textContent = '🔴 串口未打开';
        bc.className = 'badge dim';
    }

    // 打开/关闭按钮：端口来自下拉或手动任一即可
    const port = currentPort();
    $('btn-open').disabled = state.connected || !port;
    $('btn-close').disabled = !state.connected;

    // 扫描区域：未连接就遮罩
    $('mask-scan').style.display = state.connected ? 'none' : 'flex';
    ['scan-s','scan-e'].forEach(id => $(id).disabled = !state.connected);
    $('btn-scan').disabled = !state.connected;

    // 改地址 + 自定义命令：需先选电机
    const ready = state.connected && state.selectedAddr !== null;
    $('new-addr').disabled = !ready;
    $('btn-setaddr').disabled = !ready;
    $('btn-cust').disabled = !state.connected;   // 自定义命令放宽到「串口打开」即可（因里面还有 scan 命令）

    // 右侧 4 张控制卡遮罩：未就绪就遮罩
    ['mask-1','mask-2','mask-3','mask-4'].forEach(id => {
        $(id).style.display = ready ? 'none' : 'flex';
    });
}
function updateStep1() { updateUI(); }

// ============ 串口选择辅助：绘制卡片列表 + 保持联动 ============
// 全部用 RegExp 构造函数，避免 /pattern/ 字面量字符被任何解析层处理冲突
var RE_CH   = new RegExp('ch', 'i');
var RE_WCH  = new RegExp('wch', 'i');
var RE_CP   = new RegExp('cp', 'i');
var RE_FT   = new RegExp('ft', 'i');
var RE_BLU  = new RegExp('blu|蓝牙', 'i');

function renderPortCards(ports) {
    const box = $('port-cards');
    const countBadge = $('port-count');
    if (!ports || ports.length === 0) {
        countBadge.textContent = '0 个串口';
        countBadge.className = 'count-badge badge warn';
        box.innerHTML = '<div class="port-cards-empty"><b>未检测到任何串口</b><br>' +
            '请确认 USB-TTL 已插到电脑、驱动已安装；<br>' +
            '或在下方「手动输入」里直接填 COM3 继续使用。</div>';
        return;
    }
    countBadge.textContent = '检测到 ' + ports.length + ' 个串口';
    countBadge.className = 'count-badge badge ' + (ports.length >= 3 ? 'ok' : 'info');
    box.innerHTML = '';
    const selected = $('sel-port').value;
    ports.forEach(function(item) {
        var d = item[0], n = item[1];
        var desc = (n && String(n).trim()) ? String(n) : '(USB-SERIAL)';
        var el = document.createElement('div');
        el.className = 'pc' + (selected === d ? ' active' : '');
        el.dataset.port = d;
        // 简单的 chip 信息：优先取厂家/VID 简单判断
        var chipInfo = '串口';
        if (RE_CH.test(desc) || RE_WCH.test(desc)) chipInfo = 'CH系列';
        else if (RE_CP.test(desc)) chipInfo = 'CP210x';
        else if (RE_FT.test(desc)) chipInfo = 'FTDI';
        else if (RE_BLU.test(desc)) chipInfo = '蓝牙';
        el.innerHTML = '<span class="name-tag">' + d + '</span>' +
                       '<span class="desc" title="' + desc + '">' + desc + '</span>' +
                       '<span class="chip">' + chipInfo + '</span>';
        el.onclick = function() { selectPortByClick(d); };
        box.appendChild(el);
    });
}
function selectPortByClick(port) {
    // 点卡片 → 同步到下拉，清手动框
    const sel = $('sel-port');
    // 确保下拉里有这个 option
    let found = false;
    for (let i=0;i<sel.options.length;i++) if (sel.options[i].value === port) { sel.selectedIndex = i; found = true; break; }
    if (!found) {
        const o = document.createElement('option'); o.value = port; o.textContent = `${port} (手动选择)`;
        sel.appendChild(o);
        sel.value = port;
    }
    $('port-manual').value = '';
    renderPortCards(getCachedPorts());   // 重绘高亮
    updateUI();
}
let __cachedPorts = [];
function getCachedPorts() { return __cachedPorts; }

function onPortSelect() {
    const v = $('sel-port').value;
    if (v) $('port-manual').value = '';
    renderPortCards(__cachedPorts);
    updateUI();
}
function onManualInput() {
    // 一旦开始手动输入，卡片高亮清空（手动优先级更高）
    const v = $('port-manual').value;
    if (v) {
        document.querySelectorAll('#port-cards .pc').forEach(x => x.classList.remove('active'));
        const sel = $('sel-port');
        for (let i=0;i<sel.options.length;i++) if (sel.options[i].value === '') { sel.selectedIndex = i; break; }
    }
    updateUI();
}

// ============ 串口操作 ============
async function listPorts() {
    const sel = $('sel-port');
    const tip = $('port-tip');
    const badge = $('port-count');
    badge.textContent = '⏳ 刷新中…';
    badge.className = 'count-badge badge info';
    tip.innerHTML = '<span style="color:#38bdf8">🔄 正在向服务器请求 COM 列表…</span>';
    const j = await ajax('/api/ports');
    if (!j.success) {
        sel.innerHTML = '<option value="" disabled selected>❌ 请求失败</option>';
        tip.innerHTML = `<span style="color:#f87171">❌ 请求端口列表失败：${j.msg || '（无详细信息）'}<br>👉 请确认 Flask 服务是否正在运行；也可在下方「手动输入」直接填 COM3。</span>`;
        __cachedPorts = [];
        renderPortCards([]);
        updateUI();
        return;
    }
    const ports = j.ports || [];
    __cachedPorts = ports;

    // 填充下拉
    sel.innerHTML = '';
    if (ports.length === 0) {
        sel.innerHTML = '<option value="" disabled selected>❌ 未检测到任何 COM 口</option>';
        tip.innerHTML = '<span style="color:#f87171">⚠️ 后端 pyserial 没识别到任何串口。<br>👉 检查 USB-TTL 是否插好、驱动是否安装；也可以直接在「手动输入」填 COM3 继续。</span>';
        renderPortCards([]);
        updateUI();
        return;
    }
    const o0 = document.createElement('option'); o0.value = ''; o0.disabled = true; o0.selected = true;
    o0.textContent = `（请选择一个串口，共 ${ports.length} 个）`;
    sel.appendChild(o0);
    ports.forEach(([d, n]) => {
        const o = document.createElement('option'); o.value = d;
        const desc = n && String(n).trim() ? String(n) : '(USB-SERIAL)';
        o.textContent = `【${d}】  ${desc}`;
        sel.appendChild(o);
    });
    tip.innerHTML = `<span style="color:#4ade80">✅ 刷新完成，共检测到 ${ports.length} 个串口：<br>
        👉 直接点下方卡片里的 <b style="color:#fbbf24">COM3</b> 条目即可选中；<br>
        👉 也可以打开下拉列表选择，或者在最下面手动输入 COM3。</span>`;
    renderPortCards(ports);
    updateUI();
}

var RE_DIGITS_ONLY = new RegExp('^[0-9]+$');
function normalizePort(p) {
    if (!p) return '';
    p = String(p).trim().toUpperCase();
    // 允许用户只输数字 3，自动补成 COM3
    if (RE_DIGITS_ONLY.test(p)) p = 'COM' + p;
    return p;
}

async function openPort() {
    const p = normalizePort(currentPort());
    const bd = $('sel-baud').value;
    if (!p) { alert('请先选择或输入 COM 口（在「刷新 COM 列表」里选，或在手动输入里填 COM3）'); return; }
    showResult('正在打开串口 ' + p + ' @ ' + bd + ' …');
    const j = await ajax(`/api/connect?port=${encodeURIComponent(p)}&baud=${encodeURIComponent(bd)}`);
    showResult(j.msg, j.ok);
    if (j.ok) {
        state.connected = true;
        state.motorCount = 0;
        state.selectedAddr = null;
        $('v-port').textContent = p;
        $('v-baud').textContent = bd;
        $('v-count').innerHTML = '<span class="badge dim">0 台</span>';
        $('v-current').innerHTML = '<span class="badge dim">未选择</span>';
        window.__CURRENT_ADDR = null;
        // 手动输入框同步成最终打开的端口，方便用户知道当前用的哪个
        if ($('port-manual')) $('port-manual').value = p;
        if ($('port-tip')) $('port-tip').innerHTML = `<span style="color:#4ade80">✅ ${p} 打开成功，下一步：扫描电机</span>`;
        gimbalRefresh();   // 串口打开后，若云台已配置则启用
    } else {
        if ($('port-tip')) $('port-tip').innerHTML = `<span style="color:#f87171">❌ 打开失败：${j.msg || ''}。<br>👉 常见原因：该串口被 BLDC-Control/串口助手占用，请先关闭后再试。</span>`;
    }
    updateUI();
    refreshLog();
}

async function closePort() {
    const j = await ajax('/api/disconnect');
    showResult(j.msg, j.ok);
    state.connected = false;
    state.motorCount = 0;
    state.selectedAddr = null;
    window.__CURRENT_ADDR = null;
    $('v-port').textContent = '（未打开）';
    $('v-count').innerHTML = '<span class="badge dim">0 台</span>';
    $('v-current').innerHTML = '<span class="badge dim">未选择</span>';
    $('motor-list').innerHTML = '<div class="hint">请先完成「打开串口」，再扫描电机编号</div>';
    gSetState(false, '串口已断开');
    updateUI();
    refreshLog();
}

// ============ 扫描 / 切换电机 ============
async function scanBus() {
    if (!state.connected) { alert('请先打开串口'); return; }
    const s = parseInt($('scan-s').value) || 1;
    const e = parseInt($('scan-e').value) || 16;
    $('btn-scan').disabled = true;
    $('btn-scan').textContent = '⏳ 扫描中…';
    showResult(`扫描地址 ${s} ~ ${e} …`);
    const j = await ajax(`/api/scan?start=${s}&end=${e}`);
    $('btn-scan').disabled = false;
    $('btn-scan').textContent = '🔍 扫描总线上的电机';
    showResult(j.result_html || j.msg, j.success);
    const motors = j.motors || [];
    state.motorCount = motors.length;
    // 重置当前选择（电机列表已变）
    if (!motors.find(m => m.addr === state.selectedAddr)) {
        state.selectedAddr = null;
        window.__CURRENT_ADDR = null;
        $('v-current').innerHTML = '<span class="badge dim">未选择</span>';
    }
    refreshMotors(motors);
    updateUI();
    gimbalRefresh();   // 扫描后云台可能已自动配置（前2台=水平/垂直）
    refreshLog();
}

function refreshMotors(motors) {
    $('v-count').innerHTML = `<span class="badge ${motors.length?'ok':'warn'}">${motors.length} 台</span>`;
    const box = $('motor-list');
    if (motors.length === 0) {
        box.innerHTML = '<div class="err">🔴 没有发现任何电机<br><br>请检查：<br>· 电机上电后 LED 是否常亮？<br>· GND 是否共地（USB-TTL GND ↔ 电机 GND ↔ 电源负极）<br>· TX/RX 是否接反？对调一下试试<br>· 是不是 5V TTL 接了 3.3V 电机？</div>';
        return;
    }
    box.innerHTML = '';
    motors.forEach(m => {
        const d = document.createElement('div');
        d.className = 'm';
        d.onclick = () => selectMotor(m.addr);
        d.dataset.addr = m.addr;
        const hex = m.addr_hex || ('0x' + m.addr.toString(16).padStart(2,'0').toUpperCase());
        d.innerHTML = `<div class="id-box"><span class="badge info">ID ${m.addr}</span><span class="addr_hex">${hex}</span></div>
                       <div class="volt">${m.volt.toFixed(2)} V</div>`;
        box.appendChild(d);
    });
    markActiveMotor();
}

function markActiveMotor() {
    const cur = state.selectedAddr;
    document.querySelectorAll('#motor-list .m').forEach(el => {
        if (parseInt(el.dataset.addr) === cur) el.classList.add('active');
        else el.classList.remove('active');
    });
    if (cur) {
        $('v-current').innerHTML = `<span class="badge info">ID ${cur}</span>`;
    }
}

async function selectMotor(addr) {
    const j = await ajax(`/api/use?addr=${addr}`);
    showResult(j.msg, j.ok);
    if (j.ok) {
        state.selectedAddr = addr;
        window.__CURRENT_ADDR = addr;
        markActiveMotor();
        updateUI();
    }
    refreshLog();
}

// ============ 改地址 ============
async function changeAddr() {
    if (state.selectedAddr === null) { alert('请先在列表里点击要操作的电机'); return; }
    const v = parseInt($('new-addr').value);
    if (!v || v < 1 || v > 127) { alert('新编号必须 1~127'); return; }
    if (!confirm(`确认将当前电机（ID ${state.selectedAddr}）的自身地址改为 ${v}?\n\n改完请记得再点「保存参数」才会永久写入 Flash。`)) return;
    const j = await ajax(`/api/setaddr?new=${v}`);
    showResult(j.msg, j.ok);
    if (j.ok) {
        state.selectedAddr = j.addr;
        window.__CURRENT_ADDR = j.addr;
        markActiveMotor();
        updateUI();
    }
    refreshLog();
}

// ============ 命令分发 ============
async function cmd(name, inputId=null) {
    if (!state.connected) { alert('请先打开串口'); return; }
    if (state.selectedAddr === null && !['voltage'].includes(name)) {
        if (!confirm('还未选择控制的电机。\n当前会向默认地址 0x02 发送命令，继续吗？')) return;
    }
    let val = '';
    if (inputId) val = $(inputId).value;
    const url = inputId
        ? `/api/cmd?name=${name}&val=${encodeURIComponent(val)}`
        : `/api/cmd?name=${name}`;
    const j = await ajax(url);
    showResult(j.result_html || j.msg, j.success);
    refreshLog();
}
async function pid(type, id) {
    if (!state.connected || state.selectedAddr === null) { alert('请先打开串口并选择电机'); return; }
    const v = $(id).value;
    const j = await ajax(`/api/pid?type=${type}&val=${v}`);
    showResult(j.result_html || j.msg, j.success);
    refreshLog();
}
async function custom() {
    if (!state.connected) { alert('请先打开串口'); return; }
    const c = $('cust').value.trim();
    if (!c) return;
    const j = await ajax('/api/custom?cmd=' + encodeURIComponent(c));
    showResult(j.result_html || j.msg, j.success);
    if (j.scanned) {
        const motors = j.motors || [];
        state.motorCount = motors.length;
        refreshMotors(motors);
    }
    if (j.switched) {
        state.selectedAddr = j.addr;
        window.__CURRENT_ADDR = j.addr;
        markActiveMotor();
    }
    updateUI();
    refreshLog();
}

async function clearLog() { await ajax('/api/log?clear=1'); refreshLog(); }

async function refreshLog() {
    try {
        const t = await fetch('/api/log').then(r=>r.text());
        const el = $('logc');
        const nh = fmtLog(t);
        if (el.innerHTML !== nh) { el.innerHTML = nh; el.scrollTop = el.scrollHeight; }
    } catch(e){}
}

// ============ 云台双轴控制 ============
var G = { ready:false, dragging:false, lastSend:0, pendPan:null, pendTilt:null };

function fmtDeg(v) { return (v >= 0 ? '+' : '') + Number(v).toFixed(1) + '\u00B0'; }

function gSetState(ok, txt) {
    G.ready = ok;
    if ($('mask-gimbal')) $('mask-gimbal').style.display = ok ? 'none' : 'flex';
    var el = $('g-state');
    if (el) { el.textContent = txt; el.style.color = ok ? '#4ade80' : '#f87171'; }
    if ($('joy-base')) $('joy-base').classList.toggle('off', !ok);
}

async function gimbalRefresh() {
    var j = await ajax('/api/gimbal/config');
    if (j.pan && j.tilt) {
        if ($('g-pan-addr')) $('g-pan-addr').value = j.pan;
        if ($('g-tilt-addr')) $('g-tilt-addr').value = j.tilt;
        gSetState(state.connected, '水平 ID' + j.pan + ' · 垂直 ID' + j.tilt);
    } else {
        gSetState(false, '未配置');
    }
}

async function gimbalApply() {
    var p = parseInt($('g-pan-addr').value), t = parseInt($('g-tilt-addr').value);
    if (!p || !t || p < 1 || p > 127 || t < 1 || t > 127) { alert('请填写两个电机的 ID (1~127)'); return; }
    var j = await ajax('/api/gimbal/config?pan=' + p + '&tilt=' + t);
    showResult(j.msg, j.success);
    gimbalRefresh();
    refreshLog();
}

// ---- 四方向点动：按住持续转 / 松开停 ----
function bindJog(id, axis, dir) {
    var b = $(id);
    if (!b) return;
    b.addEventListener('pointerdown', function(ev) {
        ev.preventDefault();
        if (!G.ready) { showResult('云台未就绪：请先打开串口并扫描/配置 2 台电机', false); return; }
        b.classList.add('pressed');
        jog(axis, dir);
    });
    function stop() {
        if (b.classList.contains('pressed')) { b.classList.remove('pressed'); jog(axis, 0); }
    }
    ['pointerup','pointerleave','pointercancel'].forEach(function(t){ b.addEventListener(t, stop); });
    window.addEventListener('pointerup', stop);   // 按住拖出窗口再松开
}
async function jog(axis, dir) {
    var sp = dir === 0 ? 0 : (parseInt($('g-speed').value) || 60);
    var j = await ajax('/api/gimbal/jog?axis=' + axis + '&dir=' + dir + '&speed=' + sp);
    if (!j.success && dir !== 0) showResult(j.msg, false);
}
bindJog('jog-up',    'tilt',  1);   // 上仰 = 垂直正转
bindJog('jog-down',  'tilt', -1);   // 下俯
bindJog('jog-left',  'pan',  -1);   // 左转
bindJog('jog-right', 'pan',   1);   // 右转

// ---- 摇杆：拖动位置随动（130ms 节流，松手发最终位置） ----
(function initJoy(){
    var base = $('joy-base'), knob = $('joy-knob');
    if (!base || !knob) return;
    function setKnob(dx, dy) {
        knob.style.transform = 'translate(-50%,-50%) translate(' + dx + 'px,' + dy + 'px)';
    }
    function joyMove(ev) {
        var rect = base.getBoundingClientRect();
        var cx = rect.left + rect.width / 2, cy = rect.top + rect.height / 2;
        var R = rect.width / 2 - 33;   // knob 半径 29 + 余量
        var dx = ev.clientX - cx, dy = ev.clientY - cy;
        var dist = Math.sqrt(dx * dx + dy * dy);
        if (dist > R) { dx = dx / dist * R; dy = dy / dist * R; }
        setKnob(dx, dy);
        G.pendPan = dx / R * 180;      // 水平 ±180°
        G.pendTilt = -dy / R * 90;     // 垂直 ±90°（屏幕 y 向下为正，取负 = 上拖为正角度）
        $('g-pan-val').textContent = fmtDeg(G.pendPan);
        $('g-tilt-val').textContent = fmtDeg(G.pendTilt);
        gMove(false);
    }
    base.addEventListener('pointerdown', function(ev) {
        if (!G.ready) { showResult('云台未就绪：请先打开串口并扫描/配置 2 台电机', false); return; }
        ev.preventDefault();
        G.dragging = true;
        try { base.setPointerCapture(ev.pointerId); } catch(e) {}
        joyMove(ev);
    });
    base.addEventListener('pointermove', function(ev) { if (G.dragging) joyMove(ev); });
    ['pointerup','pointercancel'].forEach(function(t){
        base.addEventListener(t, function() { if (G.dragging) { G.dragging = false; gMove(true); } });
    });
})();

function gMove(final) {
    var now = Date.now();
    if (G.pendPan === null) return;
    if (!final && now - G.lastSend < 130) return;   // 拖动中节流
    G.lastSend = now;
    var pan = G.pendPan.toFixed(1), tilt = G.pendTilt.toFixed(1);
    // 不 await：拖动要跟手，网络慢也不卡 UI
    fetch('/api/gimbal/move?pan=' + pan + '&tilt=' + tilt + '&_t=' + now)
        .then(function(r){ return r.json(); })
        .then(function(j){ if (!j.success) showResult(j.msg, false); })
        .catch(function(){});
}

// ---- 回中 / 设零点 ----
async function gCenter() {
    if (!G.ready) return;
    var j = await ajax('/api/gimbal/center');
    showResult(j.msg, j.success);
    var knob = $('joy-knob');
    if (knob) knob.style.transform = 'translate(-50%,-50%)';
    G.pendPan = 0; G.pendTilt = 0;
    $('g-pan-val').textContent = fmtDeg(0);
    $('g-tilt-val').textContent = fmtDeg(0);
    refreshLog();
}
if ($('g-center')) $('g-center').addEventListener('click', gCenter);

async function gimbalZero() {
    var j = await ajax('/api/gimbal/zero');
    showResult(j.msg, j.success);
    refreshLog();
}

// 初始化
window.__CURRENT_ADDR = null;
setInterval(refreshLog, 1500);
listPorts();
setTimeout(listPorts, 1200);   // 1.2s 后再刷新一次（防止首屏偶发空）
gimbalRefresh();
updateUI();
</script>
</body>
</html>
"""


# ======================== 工具函数 ========================
def colorize(s: str) -> str:
    s = s.replace('[TX]', '<span class="tx">[TX]</span>')
    s = s.replace('[RX]', '<span class="rx">[RX]</span>')
    return s


def resp(success, text, **extra):
    return jsonify({
        "success": success,
        "msg": str(text),
        "result_html": colorize(str(text)),
        **extra,
    })


def needs_motor():
    """检查电机连接状态（不检查在线列表，只检查串口）"""
    if not motor or not motor.is_connected:
        return False, "USB-TTL 未连接，请在左上角选择 COM 口并点连接"
    return True, ""


# ======================== 路由 ========================
@app.route("/")
def index():
    # 注意：**不经过 Jinja2**，直接把字符串当 HTML 返回
    # 之前用 render_template_string 时，jinja2 解析层会对 JS 正则字面量 /pattern/ 的
    # 斜杠字符做潜在处理，导致浏览器端抛 "Invalid regular expression: missing /"。
    # 现在直接返回原字符串，任何字符都原封不动到达浏览器。
    return Response(PAGE, mimetype="text/html; charset=utf-8")


@app.route("/api/ports")
def api_ports():
    return jsonify({"success": True, "ports": list_serial_ports()})


@app.route("/api/connect")
def api_connect():
    global motor, online_motors
    port = request.args.get("port", "").strip()
    baud = request.args.get("baud", 115200, type=int)
    if not port:
        return resp(False, "缺少 port 参数")
    try:
        if motor:
            try: motor.disconnect()
            except Exception: pass
        motor = F32CMotor(port=port, baud=baud, debug=True)
        ok = motor.connect()
        add_log(("USB-TTL 连接成功: " if ok else "[错误] USB-TTL 连接失败: ") + f"{port} @ {baud}")
        online_motors = []
        return resp(ok, f"串口 {port} @ {baud}" + (" 打开成功 ✅" if ok else " 打开失败 ❌（端口被占用或不存在）"),
                    ok=ok, connected=ok)
    except Exception as e:
        add_log(f"[错误] 连接异常: {e}")
        return resp(False, f"打开串口异常: {e}")


@app.route("/api/disconnect")
def api_disconnect():
    global motor, online_motors
    try:
        if motor:
            motor.disconnect()
            motor = None
        online_motors = []
        add_log("已断开串口")
        return resp(True, "已断开串口", ok=True)
    except Exception as e:
        return resp(False, f"断开失败: {e}", ok=False)


@app.route("/api/scan")
def api_scan():
    ok, err = needs_motor()
    if not ok: return resp(False, err)
    s = request.args.get("start", 1, type=int)
    e = request.args.get("end", 16, type=int)
    s, e = max(1, s), min(127, e)
    if e < s: s, e = e, s
    add_log(f">>> 扫描总线 地址 {s} ~ {e}")
    global online_motors
    raw = motor.scan_bus(start=s, end=e, print_progress=False)
    # 补齐 addr_hex 前端用，兼容两种命名
    for m in raw:
        if "addr_hex" not in m:
            m["addr_hex"] = f"0x{m['addr']:02X}"
    online_motors = raw
    gimbal_auto_config(online_motors)
    if online_motors:
        for m in online_motors:
            add_log(f"  [发现] ID {m['addr']} (0x{m['addr']:02X})  电压: {m['volt']:.2f} V")
    add_log(f"扫描完成: 发现 {len(online_motors)} 台电机")
    html = f"共发现 <b>{len(online_motors)}</b> 台在线电机:<br>"
    for m in online_motors:
        html += f"  · <b>ID {m['addr']}</b> (0x{m['addr']:02X}) &nbsp; 电压: <b style='color:#fbbf24'>{m['volt']:.2f} V</b><br>"
    if not online_motors:
        html += "<span style='color:#f87171'>未发现任何电机，请检查：接线是否正确？GND 是否共地？TX/RX 是否接反？电机是否上电（LED常亮）？</span>"
    return resp(True, html, motors=online_motors)


@app.route("/api/use")
def api_use():
    ok, err = needs_motor()
    if not ok: return resp(False, err)
    addr = request.args.get("addr", 0, type=int)
    if addr < 1 or addr > 127:
        return resp(False, "地址必须 1~127")
    motor.set_addr(addr)
    add_log(f"切换当前控制到 ID{addr} (0x{addr:02X})")
    return resp(True, f"已切换当前控制到 <b>ID {addr} (0x{addr:02X})</b>，后续所有命令会下发到该电机",
                ok=True, addr=addr)


@app.route("/api/setaddr")
def api_setaddr():
    ok, err = needs_motor()
    if not ok: return resp(False, err)
    new = request.args.get("new", 0, type=int)
    if new < 1 or new > 127:
        return resp(False, "新地址必须 1~127")
    add_log(f">>> 将当前电机 ID{motor.addr} 自身地址改为 {new}")
    r = motor.set_device_address(new)
    if r.valid:
        add_log("地址写入成功，建议随后调用 '保存参数' 永久生效")
        motor.set_addr(new)
        return resp(True, f"地址已改为 <b>{new}</b>，请再点「保存参数」使其永久写入 Flash；当前控制地址已同步切换为 {new}",
                    addr=new, ok=True)
    return resp(False, "地址写入失败: " + r.parsed_text)


# ------------------------- 统一命令执行 -------------------------
def exec_motor_method(method, *args):
    ok, err = needs_motor()
    if not ok: return resp(False, err)
    try:
        fn = getattr(motor, method)
        add_log(f">>> [ID{motor.addr}] {method}{args if args else ''}")
        r = fn(*args)
        txt = r.parsed_text if hasattr(r, 'parsed_text') else str(r)
        ok2 = r.valid if hasattr(r, 'valid') else True
        return resp(ok2, txt)
    except Exception as e:
        add_log(f"[错误] {e}")
        return resp(False, f"执行失败: {type(e).__name__}: {e}")


@app.route("/api/cmd")
def api_cmd():
    name = request.args.get("name", "")
    val = request.args.get("val")
    if name == "enable":       return exec_motor_method("enable")
    if name == "disable":      return exec_motor_method("disable")
    if name == "voltage":      return exec_motor_method("read_voltage")
    if name == "speed":        return exec_motor_method("read_speed")
    if name == "angle":        return exec_motor_method("read_total_angle")
    if name == "mech":         return exec_motor_method("read_mech_angle")
    if name == "accel":        return exec_motor_method("read_accel")
    if name == "save":         return exec_motor_method("save_params")
    if name == "zero":         return exec_motor_method("clear_total_angle")
    if name == "setzero":      return exec_motor_method("set_single_zero")
    if name == "factory":      return exec_motor_method("factory_reset")
    if name == "test":
        ok, err = needs_motor()
        if not ok: return resp(False, err)
        add_log(f">>> [ID{motor.addr}] 联通性测试")
        r = motor.connectivity_test()
        txt = "\n".join(f"{k}: {v}" for k, v in r.items())
        return resp("正常" in r["summary"] or "联通" in r["summary"], txt)
    if name == "setmode" and val: return exec_motor_method("set_mode", int(val))
    if name == "setspeed" and val: return exec_motor_method("set_speed", int(val))
    if name == "setangle" and val: return exec_motor_method("set_multi_angle", float(val))
    if name == "setsingle" and val: return exec_motor_method("set_single_angle", float(val))
    if name == "setaccel" and val: return exec_motor_method("set_accel", int(val))
    return resp(False, f"未知命令: {name}")


@app.route("/api/pid")
def api_pid():
    t = request.args.get("type")
    v = request.args.get("val", 0, type=int)
    m = {"speed_kp": "set_speed_kp", "speed_ki": "set_speed_ki",
         "pos_kp": "set_pos_kp", "pos_ki": "set_pos_ki"}
    if t not in m: return resp(False, "未知 PID 类型")
    return exec_motor_method(m[t], v)


@app.route("/api/custom")
def api_custom():
    cmd = request.args.get("cmd", "").strip().lower()
    if not cmd: return resp(False, "空命令")
    ok, err = needs_motor()
    if not ok: return resp(False, err)

    parts = cmd.split()
    c, a = parts[0], parts[1:]
    result_extra = {}

    try:
        # 本地类命令（不走协议）
        if c == "scan":
            s = int(a[0]) if len(a) >= 1 else 1
            e = int(a[1]) if len(a) >= 2 else 16
            global online_motors
            raw = motor.scan_bus(start=s, end=e, print_progress=False)
            for m in raw:
                if "addr_hex" not in m:
                    m["addr_hex"] = f"0x{m['addr']:02X}"
            online_motors = raw
            gimbal_auto_config(online_motors)
            for m in online_motors:
                add_log(f"  [发现] ID {m['addr']} (0x{m['addr']:02X})  电压: {m['volt']:.2f} V")
            add_log(f"扫描完成: 发现 {len(online_motors)} 台电机")
            html = f"共发现 {len(online_motors)} 台电机<br>"
            for m in online_motors:
                html += f"  · ID {m['addr']} (0x{m['addr']:02X}) - {m['volt']:.2f} V<br>"
            result_extra = {"scanned": True, "motors": online_motors}
            return resp(True, html, **result_extra)

        if c == "use" and a:
            new_addr = int(a[0], 0)
            motor.set_addr(new_addr)
            add_log(f"切换当前控制到 ID{new_addr} (0x{new_addr:02X})")
            return resp(True, f"已切换到 ID {new_addr} (0x{new_addr:02X})",
                        switched=True, addr=new_addr)

        if c == "setaddr" and a:
            new_addr = int(a[0], 0)
            add_log(f">>> 将当前电机地址改为 {new_addr}")
            r = motor.set_device_address(new_addr)
            if r.valid:
                motor.set_addr(new_addr)
                return resp(True, f"地址已改为 {new_addr}（记得 save 保存）", addr=new_addr)
            return resp(False, "改地址失败: " + r.parsed_text)

        # 协议命令
        if c == "enable":       return exec_motor_method("enable")
        elif c == "disable":    return exec_motor_method("disable")
        elif c == "voltage":    return exec_motor_method("read_voltage")
        elif c == "speed":      return exec_motor_method("read_speed")
        elif c == "angle":      return exec_motor_method("read_total_angle")
        elif c == "mech":       return exec_motor_method("read_mech_angle")
        elif c == "accel":      return exec_motor_method("read_accel")
        elif c == "save":       return exec_motor_method("save_params")
        elif c == "zero":       return exec_motor_method("clear_total_angle")
        elif c == "setzero":    return exec_motor_method("set_single_zero")
        elif c == "factory":    return exec_motor_method("factory_reset")
        elif c == "setmode" and a:    return exec_motor_method("set_mode", int(a[0]))
        elif c == "setspeed" and a:   return exec_motor_method("set_speed", int(a[0]))
        elif c == "setangle" and a:   return exec_motor_method("set_multi_angle", float(a[0]))
        elif c == "setsingle" and a:  return exec_motor_method("set_single_angle", float(a[0]))
        elif c == "setaccel" and a:   return exec_motor_method("set_accel", int(a[0]))
        elif c == "test":
            r = motor.connectivity_test()
            txt = "\n".join(f"{k}: {v}" for k, v in r.items())
            return resp("正常" in r["summary"] or "联通" in r["summary"], txt)
        else:
            return resp(False, f"未知命令: {cmd}")
    except Exception as e:
        add_log(f"[错误] {type(e).__name__}: {e}")
        return resp(False, f"执行失败: {e}")


# ======================== 云台双轴控制 API ========================
@app.route("/api/gimbal/config")
def api_gimbal_config():
    pan = request.args.get("pan", 0, type=int)
    tilt = request.args.get("tilt", 0, type=int)
    if pan and tilt:
        if not (1 <= pan <= 127 and 1 <= tilt <= 127):
            return resp(False, "电机 ID 必须 1~127")
        GIMBAL["pan_addr"] = pan
        GIMBAL["tilt_addr"] = tilt
        GIMBAL["pan_mode"] = None
        GIMBAL["tilt_mode"] = None
        add_log(f"[云台] 手动配置: 水平=ID{pan}, 垂直=ID{tilt}")
    return resp(True, f"水平轴=ID{GIMBAL['pan_addr']}, 垂直轴=ID{GIMBAL['tilt_addr']}",
                pan=GIMBAL["pan_addr"], tilt=GIMBAL["tilt_addr"],
                pan_angle=GIMBAL["pan_angle"], tilt_angle=GIMBAL["tilt_angle"], ok=True)


@app.route("/api/gimbal/jog")
def api_gimbal_jog():
    """点动：dir=1/-1 持续转，dir=0 停止（速度模式）"""
    ok, err = _gimbal_ready()
    if not ok: return resp(False, err)
    axis = request.args.get("axis", "")
    if axis not in ("pan", "tilt"): return resp(False, "axis 必须是 pan 或 tilt")
    d = max(-1, min(1, request.args.get("dir", 0, type=int)))
    speed = max(0, min(300, request.args.get("speed", 60, type=int)))
    try:
        if GIMBAL[f"{axis}_mode"] != 0:
            _send_axis(axis, "set_mode", 0)
            _send_axis(axis, "enable")
            GIMBAL[f"{axis}_mode"] = 0
        r = _send_axis(axis, "set_speed", d * speed)
        return resp(r.valid, f"{axis} {'停止' if d == 0 else ('正转' if d > 0 else '反转')} {'' if d == 0 else str(speed) + ' RPM'} " + ("" if r.valid else "-> " + r.parsed_text))
    except Exception as e:
        GIMBAL[f"{axis}_mode"] = None
        return resp(False, f"点动失败: {e}")


@app.route("/api/gimbal/move")
def api_gimbal_move():
    """位置随动：pan∈[-180,180] tilt∈[-90,90]（单圈绝对位置模式，T型规划）"""
    ok, err = _gimbal_ready()
    if not ok: return resp(False, err)
    pan = request.args.get("pan", None, type=float)
    tilt = request.args.get("tilt", None, type=float)
    sent = []
    try:
        if pan is not None:
            pan = max(-180.0, min(180.0, pan))
            if GIMBAL["pan_mode"] != 2:
                _send_axis("pan", "set_mode", 2)
                _send_axis("pan", "enable")
                GIMBAL["pan_mode"] = 2
            r = _send_axis("pan", "set_single_angle", round(pan % 360, 1))
            if r.valid: GIMBAL["pan_angle"] = pan
            sent.append(f"水平 {pan:+.1f}°{'✓' if r.valid else '✗'}")
        if tilt is not None:
            tilt = max(-90.0, min(90.0, tilt))
            if GIMBAL["tilt_mode"] != 2:
                _send_axis("tilt", "set_mode", 2)
                _send_axis("tilt", "enable")
                GIMBAL["tilt_mode"] = 2
            r = _send_axis("tilt", "set_single_angle", round(tilt % 360, 1))
            if r.valid: GIMBAL["tilt_angle"] = tilt
            sent.append(f"垂直 {tilt:+.1f}°{'✓' if r.valid else '✗'}")
        return resp(True, " · ".join(sent) if sent else "无参数",
                    pan_angle=GIMBAL["pan_angle"], tilt_angle=GIMBAL["tilt_angle"])
    except Exception as e:
        return resp(False, f"位置随动失败: {e}")


@app.route("/api/gimbal/center")
def api_gimbal_center():
    """双轴回中（0°）"""
    ok, err = _gimbal_ready()
    if not ok: return resp(False, err)
    try:
        for axis in ("pan", "tilt"):
            if GIMBAL[f"{axis}_mode"] != 2:
                _send_axis(axis, "set_mode", 2)
                _send_axis(axis, "enable")
                GIMBAL[f"{axis}_mode"] = 2
            _send_axis(axis, "set_single_angle", 0)
            GIMBAL[f"{axis}_angle"] = 0.0
        add_log("[云台] 双轴回中")
        return resp(True, "双轴已回中 (0°)")
    except Exception as e:
        return resp(False, f"回中失败: {e}")


@app.route("/api/gimbal/zero")
def api_gimbal_zero():
    """把两台电机当前位置设为单圈 0°（需再点保存参数才掉电保存）"""
    ok, err = _gimbal_ready()
    if not ok: return resp(False, err)
    try:
        r1 = _send_axis("pan", "set_single_zero")
        r2 = _send_axis("tilt", "set_single_zero")
        if r1.valid and r2.valid:
            GIMBAL["pan_angle"] = 0.0
            GIMBAL["tilt_angle"] = 0.0
            return resp(True, "两轴当前位置已设为 0°，建议再点「保存参数」永久写入")
        return resp(False, f"设零点失败: pan={r1.parsed_text} tilt={r2.parsed_text}")
    except Exception as e:
        return resp(False, f"设零点失败: {e}")


@app.route("/api/log")
def api_log():
    if request.args.get("clear"):
        log_lines.clear()
        return "OK"
    with app.app_context():
        return "\n".join(log_lines)


# ======================== 入口 ========================
def main():
    parser = argparse.ArgumentParser(description="F32C 多电机总线 Web 调试台（USB-TTL 直连）")
    parser.add_argument("--port", help="USB-TTL 串口号，不填请在网页里选择")
    parser.add_argument("--baud", type=int, default=115200)
    parser.add_argument("--scan", action="store_true", help="启动时自动扫描 (地址1~16)")
    parser.add_argument("--webhost", default="0.0.0.0")
    parser.add_argument("--webport", type=int, default=5000)
    parser.add_argument("--nodebug", action="store_true", help="关闭 TX/RX 字节打印")
    args = parser.parse_args()

    global motor, online_motors

    if args.port:
        motor = F32CMotor(port=args.port, baud=args.baud, debug=not args.nodebug)
        if motor.connect():
            add_log(f"启动时自动连接串口 {args.port}: 成功")
            if args.scan:
                add_log(">>> 自动扫描总线 1~16")
                online_motors = motor.scan_bus(print_progress=False)
                for m in online_motors:
                    add_log(f"  [发现] ID {m['addr']} (0x{m['addr']:02X})  电压: {m['volt']:.2f} V")
        else:
            add_log(f"[错误] 启动时连接 {args.port} 失败")
    else:
        motor = F32CMotor(port="", baud=args.baud, debug=not args.nodebug)
        add_log("启动时未指定串口，请在网页左上角选择 COM 口连接")

    print("\n" + "=" * 60)
    print("  F32C 多电机总线 Web 调试台")
    print("=" * 60)
    print(f"  浏览器访问: http://127.0.0.1:{args.webport}")
    if args.port: print(f"  指定串口: {args.port}")
    else:         print("  指定串口: 未指定 (请在网页选择)")
    print("  接线提示:")
    print("    USB-TTL GND     →  电机 GND (必须共地)")
    print("    USB-TTL TX (3.3V) →  电机 RX")
    print("    USB-TTL RX (3.3V) ←  电机 TX")
    print("    电机 V+         →  8~15V 独立电源")
    print("=" * 60 + "\n")
    try:
        app.run(host=args.webhost, port=args.webport, debug=False, use_reloader=False)
    except KeyboardInterrupt:
        if motor: motor.disconnect()
        print("\n已退出")


if __name__ == "__main__":
    main()
