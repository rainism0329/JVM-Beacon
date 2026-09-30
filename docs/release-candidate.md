# 首发候选验收

## 1.0.0-rc.2 · 2026-09-30

本轮准备 Marketplace 发布材料，免费闭源、源码私有、发布者 Philip Zhang / PhilZ Dev。用户自行发布；本轮不上传、不签约、不公开仓库。完整材料与手动检查入口：[Marketplace 素材](marketplace/README.md)。

最终安装包 `jvm-beacon-1.0.0-rc.2.zip` 为 492,109 bytes，SHA-256 `2cde88b40c4da2037a1780b3474bdadc9a3354fda73a68e9c05a4b8df90dfccc`；JAR `adba2ca07218027783383a9f9b4018c2f7902db0e3111d46498f63837e507071`。包含原创新 Logo、Philip Zhang Vendor 信息与最终免费闭源 EULA。全量 169 tests，0 failure/error/skipped；最终包 IC/IU 251.26927.53 Verifier Compatible，各一条既有 API 提示。[全量检查](../build/reports/checks-1.0.0-rc.2-final.txt) / [最终 EULA 包检查](../build/reports/checks-1.0.0-rc.2-package.txt)。

GUI 与上传截图以 [新版本 GUI 记录](gui-validation.md) 和 `marketplace/media/manifest.json` 为准，不继承下面 rc.1 的实测结论。当前支持范围仍为 Windows / IDEA 2025.1.3 / JBR 21，目标测试 JVM Corretto 21.0.9。未扩展 IDEA build 或跨 OS 支持。

最终 IC 同包运行 15:25:13.196—16:00:30.996，ERROR 0 / runIde exit 0；安装信息、三个原生标签及六种离线证据视图实际操作并采集未修改的 1707×1019 PNG。实时认证 GUI 人工步骤未完成、Ultimate GUI 未复测；不由截图推断全部功能 GUI 已通过。本地发布素材 Ready gate 与关闭沙箱包检查通过，无账号提交或上传。当前仍推荐候选 EAP/Hidden 小范围试用；正式稳定版本须另行定版与对应验收。

## 1.0.0-rc.1 · 历史候选

目标：现有工作流可用、失败可解释、证据和资源有边界。不是实现所有愿景，也不使用“完美”替代验证。仅制作可安装的私有候选包；不公开仓库、不创建 GitHub Release、不上传 Marketplace。

## 范围与门槛

保留本地/远程 JMX、8 标签、连接配置、按需 MBean 读取/写入/调用/通知、指标与 Timeline、平台线程/锁链/Hot threads、现场保存比较、JFR 录制及离线分析。暂缓 Run/Debug 关联、自动重连、完整诊断工作空间、虚拟线程专用分析、SSH/Jolokia/容器和多实例并排分析。

- 全量回归、构建、IC/IU 官方兼容检查；安装包内容与加载 JAR 一致。
- 当前包实际加载并操作核心端到端流程，分别记录 IC、IU，不从旧包推断。
- 拒绝/取消/超时凭据任务清理；反复关闭/重连、8 标签上限和独立采集资源观察。
- English 用户指南覆盖安装、回滚、远程连接、读写影响、导出边界和错误处理。

首发验证目标：Windows x64、完整 IC/IU 2025.1.3（251.26927.53）/ JBR 21；目标 JVM Corretto 21.0.9。构建 JDK 21。其他 IDEA 版本、OS/JDK、Remote Development、mTLS/企业证书/WAN/容器、完整屏幕阅读器与多缩放矩阵未纳入此次认证；描述符范围不是实测矩阵。

## 当前执行记录

结论：**可作为 Windows / IDEA Community 2025.1.3 的首次私有试用发布候选**。核心端到端流程在最终包内实际走通，已知阻断问题已修复；不宣称全部功能在所有环境都已验收。Ultimate 当前仅通过兼容性与加载检查，GUI 仍待用户处理新沙箱的项目信任提示。不能把此项写成通过，扩大支持范围前须补验。

最终 [安装包](../build/distributions/jvm-beacon-1.0.0-rc.1.zip)：488,429 bytes；SHA-256 `6db9582a6ec181e1e19b808cda128950893fab72fa667b9bad627ce0bf614c44`。包内 JAR 与 IC/IU 验收沙箱 JAR 一致：`3e527c20646bc675384215edc79594c91199bf61a4f01ef9d1f6b614f1e2e13f`。原始产物位于本机 `build/`，不随私有源码仓库提交。

| 门槛 | 2026-09-28 实际结果 |
|---|---|
| 全量测试与构建 | `test buildPlugin verifyPlugin` exit 0；168 tests / 0 failures / 0 errors / 0 skipped。含独立认证 loopback / TLS fixture 与 Swing 回归。[最终日志](../build/reports/checks-1.0.0-rc.1-final.txt) |
| 兼容性 | IC/IU 251.26927.53 均 Compatible；各 1 条既有 deprecated API 提示。不是零警告或跨版本认证 |
| 包/加载核对 | `verify-release.ps1` 通过；只分发插件 JAR，没有测试类、fixture、证书或诊断文件。IC 最终会话 23:21:25.950—23:39:52.457，ERROR 0，runIde exit 0。[核对 JSON](../build/reports/release-checks-1.0.0-rc.1.json) |
| 最终包 GUI | 自有 JVM 连接/趋势、Counter 7→12 回读、inspectRows() 12/13、13 条平台线程、保存并重开 56 点现场、真实目标退出显示 STALE；8 标签上限/Close All、新空页、项目关闭；JFR 本地录制读取/NativeMethodSample 火焰图、Auto 下筛选弹窗保持打开；Dark→Light。[逐项记录](gui-validation.md) |
| 资源观察 | 独立核心采集 180 秒 / 90 点 / 18 次线程与现场读写 / 9 次订阅取消完成；自有目标退出、executor 终止。IDE 8 混合标签关闭后观察到网络/本地 I/O 工作线程退出，仅应用级 deadline 线程保留。[方法与范围](soak-validation.md) |
| 使用文档 | [English user guide](user-guide.md) 包括安装、回滚、连接、方法调用、证据边界及排错；[中文测试步骤](testing.md) 可复现 |

首轮候选 GUI 完成了实际 JFR 录制、停止、下载与分析，但发现 Auto 会关闭筛选下拉框；修正后新增两项组件用例、重跑全部 168 项并重新打包，最终 GUI 验证下拉框跨采样周期仍保持打开。首轮完整 JFR 录制/下载证据与最终包复验严格分开记录，不把它标为最终包完整重测。开发过程中新增测试曾因受检异常的 lambda 编译失败，已修正并全量重跑，没有遗留失败。

## 已知边界与后续门槛

- Ultimate 独立沙箱加载了同一包，但停在 `Trust and Open Project`，尚未操作插件；按 Computer Use 的安全规则未代点信任或 Defender 排除项。该窗口保留给用户处理，不修改日常 IDEA。完成后还需补同包 GUI 验收。
- 最终包 GUI 未逐项覆盖远程认证/TLS、全部通知/Watch/Hot threads、全部 JFR 内存/等待/区间和异常场景。自动回归覆盖对应核心/组件路径，不能替代 GUI 或真实 WAN/企业证书验证。
- 当前 JFR 分析适合有界小录制；64 MiB 文件 / 200k 扫描事件 / 5 s 协作预算，单次 JDK 解析没有进程级硬隔离。复杂目标 getter/操作和 RMI 调用超时后可能继续执行，不自动重试修改。
- 8 标签观察为一个隐藏活动连接、一个离线现场和六个空页，未做八个重负载 JFR 页或多项目小时级压力。180 秒独立采集不是整个 IDEA 的性能结论；未认证完整缩放/屏幕阅读器矩阵。
- `.jvmb` 保存指标、可选平台线程、身份与备注；Watch、任意 MBean 值、通知、Hot threads、JFR 分析状态不包含其中。录制内容和用户备注不自动脱敏。

后续顺序：补 Ultimate GUI 和更长多连接资源观察 → 收集首批真实使用反馈、修复阻断 → 决定公开分发事项。下一批功能优先 Run/Debug 目标关联、可恢复诊断工作空间，再考虑 SSH/Jolokia、虚拟线程专门视图与并排实例比较。

## 仍需在公开发布前决定

Marketplace 名称/plugin ID 可用性、发布者账号与源码/分发许可仍未正式定案；当前开发名不代表完成商标核查。现有插件运行时无新分发第三方依赖，组件说明见 [THIRD_PARTY](../THIRD_PARTY.md)。这些公开分发事项不通过本次私有候选自动授权。

## 工具链核对

2026-09-28 查阅 [JetBrains Gradle extension 文档](https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin-extension.html#sandboxcontainer)（官方声明）：`sandboxContainer` 为 DirectoryProperty，可隔离验收沙箱。工程增加 `-PbeaconSandboxPath=<工作区内独立目录>`；本次用于避免覆盖仍运行的旧 IU 沙箱，实际是否成功见最终执行记录。
