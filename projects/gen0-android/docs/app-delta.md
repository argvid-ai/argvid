# Gen0 Android App 差量表

状态：第一轮范围已确认；未构成发布、协议接受或硬件验收批准

日期：2026-09-03

## 基准与来源

- Argvid 基准：`58bdc9ec2583a5c3cb626559dbfc73a90ddda3ec`。
- GimTrack 来源：当前贡献者主导的本地工作树；V2.2 主要差量在未提交工作树中。
- 来源确认：贡献者确认 GimTrack 应用源码、测试和文档均由 Codex 根据其要求生成，未输入外部 iOS 源码、其他非公开代码或受限资料。
- 取舍原则：GimTrack 与 Argvid 冲突或不兼容时，以 Argvid 的架构、协议、安全边界和验证方式为准。

## 差量表

| 能力 | GimTrack 已有内容 | Argvid 基线现状 | 决定 | Argvid 目标边界 | 兼容性与验证要求 |
|---|---|---|---|---|---|
| 人体/人脸检测状态 | CameraX 分析帧，输出通用 `person`/`face` 状态，不保存坐标或身份 | 无人脸/人体检测能力 | 纳入候选 | 保持为项目本地检测适配和 L4 状态，不新增根 L2 schema | 模型和依赖须完成第三方审查；主机测试只能证明规则/适配逻辑，真机结果单独记录 |
| 独立检测灵敏度 | 人体、人脸各有 0-100 滑条，默认 50；人体按置信度/面积，人脸按脸宽过滤 | 无对应 UI 或策略 | 纳入候选 | 放在 `feature/session` 与 capture 适配边界；不把阈值写入共享协议 | 保留边界、误报/漏报单测；实际默认值和效果须在授权设备上验证 |
| 自动录像时间规则 | 主体连续出现 2 秒开始，丢失 3 秒停止，停止后冷却 15 秒 | 只有手动启动会话和 15 秒缓冲 rescue | 纳入纯规则与测试；第一轮不接动作 | 规则保持项目本地；不得把“直接 CameraX 录像”强行替换 Argvid rescue 语义 | 先覆盖纯状态机测试；自动录像、自动 rescue 或其他触发动作暂不实现 |
| 手动拍照 | CameraX `ImageCapture` 保存到 `Pictures/GimTrack` | 没有单张拍照流程，主要是 JPEG 采样和 MP4 rescue | 暂不纳入第一轮 | 本轮不新增拍照端口、MediaStore 照片路径或拍照 UI | 后续若重新纳入，须按 Argvid 媒体边界单独设计和验证 |
| 直接视频录制 | CameraX VideoCapture 直接写入 `Movies/GimTrack` | 以短时内存代理帧编码最近 15 秒 MP4 | 当前排除 | 保留 Argvid 的缓冲、编码、MediaStore、catalog 和删除语义 | 不能宣称两者等价；如需直接录像，必须先取得新的范围和接口决定 |
| 云台控制 | 简单命令接口、模拟控制器、静止 1 分钟触发一次 `SEARCH` | 已有语义云台模拟器、能力/状态/错误/stop/watchdog 测试 | 不移植 GimTrack 类型；保留 Argvid 模拟器 | 只通过 Argvid 既有 gimbal 边界；不修改 L2/L0 安全语义 | 真实 API、BLE、固件和 HIL 保持 pending，等待明确硬件交接 |
| 人体模型资产 | `efficientdet_lite0.tflite` 随 APK 安装 | 基线没有检测模型 | 暂缓纳入公开交付 | 只有完成模型来源、许可证、SHA 和包体积/性能审查后才可进入项目 | 模型未审查前不得推送或宣称可公开再分发；可先保留检测接口和合成测试 |
| 第三方依赖 | CameraX、ML Kit、MediaPipe 等 | Argvid 有自己锁定的公开依赖与 notices | 不直接复制依赖声明 | 沿用 Argvid 的依赖验证和 notices 机制 | 每个新增坐标、许可证、校验和都必须记录；严格验证失败时停止交付 |
| 事件日志/隐私状态 | App 私有 NDJSON 事件日志，不写入图像、人脸框或身份 | 基线已有 capture diagnostics、catalog 和证据状态 | 只复用行为，不复制文件日志格式 | 使用 Argvid 既有状态/证据端口，保持数据最小化 | 验证不包含个人媒体、设备序列号或未脱敏日志；宿主通过不代表设备隐私验证 |
| 外部 iOS 参考与云台交接 | 本地待办说明提到外部 iOS 逻辑和云台 API | 当前公开项目不依赖这些输入 | 排除当前差量 | 仅在明确授权和可测试 revision 到位后重新评估 | 不搜索其他工作区，不导入旧历史，不以 Draft 协议代替 accepted contract |

## 当前建议的首个 App 差量边界

纳入第一轮范围：

- 项目本地的人体/人脸状态模型及其 UI 展示；
- 人体/人脸灵敏度策略与纯规则单元测试；
- 必要的项目文档和公开安全的测试证据。

暂缓或排除：

- 直接 CameraX 视频录制替换 Argvid rescue；
- 手动拍照和新增照片 MediaStore 流程；
- 未完成审查的模型资产和新增第三方依赖；
- GimTrack 自定义云台协议、真实 BLE、固件和硬件安全；
- 根 `protocol`、`conformance`、共享 fixtures、L2 schema 或任何私有材料。

## 验证边界

- 每项纳入差量都必须增加相应 host/unit 测试，并运行 Argvid 项目规定的 Gradle、lint、build 和选定扫描。
- 真机、instrumentation、真实 BLE 和 HIL 结果必须用实际设备/固件/接口 identity 记录；未运行项保持 `pending`。
- 本表只确定候选范围，不替代维护者批准、许可证审查、发布前审查或产品验收。

## 当前工作树实现状态

- 已在 `core:domain` 增加主体观测、人体/人脸灵敏度和 `2s present / 3s absent / 15s cooldown` 纯规则；规则只输出建议，不拥有采集副作用。
- 已在 `feature:session` 展示检测状态并提供两类灵敏度配置；第一轮没有接入直接录像、自动 rescue、手动拍照或 MediaStore 新流程。
- 已增加 domain、ViewModel 和 Compose UI 测试；Windows 主机已在 JDK17/Android SDK36/Gradle9.3.1 下通过项目规定的严格 unit test、lint、debug APK 和 instrumentation APK 编译。instrumentation 仅完成编译，未安装或执行。
