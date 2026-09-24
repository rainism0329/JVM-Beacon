# 0.8.0 验证与接续状态

日期：2026-09-24，Windows 11 x64。这是**功能开发预览**，完成了下列具体场景，尚未完成全部验收或整个产品愿景。历史记录按版本保留，不由旧构建外推新包通过。

## 0.8.0 当前验证

本轮交付连接工作区：远程配置保存、别名/分组/搜索、最近成功项、每页显式重连与报告身份变化提示。修正了重连说明挤压目标信息和趋势图的问题：顶部信息使用整行，小高度图表保留数据绘制。不是自动重启关联或自动重连。

最终包 [jvm-beacon-0.8.0.zip](../build/distributions/jvm-beacon-0.8.0.zip)：**23:48:23.991 +08:00，294,028 bytes**，SHA-256 `8df29b45f53f952c604cd4905ba5c16b672f4d46b384c43b1c394fd9e09855f1`。ZIP 内 JAR 与最终 IC 沙箱完整字节一致：[产物记录](../build/reports/release-checks-0.8.0.json)。

| 检查 | 实际结果与边界 |
|---|---|
| 最后代码修改后构建 | `test buildPlugin verifyPlugin` exit 0；**86/86，0 失败/错误/跳过** |
| 新增自动验证 | 6 个工作区测试：真实 SDK XML 序列化、40/10 上限、删除/改端点后的迟到结果、跨页改名、非法配置、完整/变化/缺失身份；2 个重连安全测试：不沿用 agent-start/保存密码许可、超时后秘密擦除；1 个真实认证 JMX 测试：同 JVM 三次重连及另一个子 JVM 身份变化 |
| 原有集成 | 本次共 11 个 JMX 集成 + 1 个隔离 TLS 集成通过；包括类型、复杂值、权限、通知、目标退出、双连接、锁链、Hot threads。临时 keytool 材料与独立 JVM 不修改 IDE 信任库 |
| 官方兼容性 | Verifier 1.408，IC/IU-251.26927.53 均 Compatible；保留既有 SslRMIClientSocketFactory 规则提示与 IDE layout warnings。实际 Java 21 TLS 集成通过，未扩大版本矩阵 |
| 实际 GUI | Windows / IC 2025.1.3 / JBR 21 / 1388×974 / 125%。候选保存元数据；重启后恢复、Use setup 预填；最终包验证本地显式重连、Disconnect → Reconnect、Light/Dark、单点和递增趋势、保存项搜索、最近列表空状态。逐项证据与候选区分见 [GUI 记录](gui-validation.md) |
| 退出与清理 | 最终 fixture 定时退出后保留 80 点并停止采样，Stale 原因持久可见；显式重连报告 ATTACH 阶段失败。最终包 23:48:56.790 加载、23:55:17.137 正常退出，runIde exit 0，ERROR 级日志 0。两轮自有 GUI fixture 均按时退出；[日志](../build/reports/ide-load-0.8.0.txt) 保留平台 WARN |
| GitHub | 用户确认私有后，以现有 gh keyring 账号 rainism0329 创建 [JVM-Beacon](https://github.com/rainism0329/JVM-Beacon)，查询 isPrivate=true；源码、测试和文档进入 main。未公开仓库、创建令牌、发布 Release 或 Marketplace |

本轮实际发现及修正：重连后的两行提示使 TrendChart 在 125% 下空间不足；仅扩宽顶部仍不足，最后按图表高度省略放不下的横轴文字，保留数据点/曲线、采集窗口和精确悬停。最终 GUI 复查已显示单点及连续采样曲线。首次连接也将不完整身份标为 INCOMPLETE；未用首次连接冒充身份匹配。

资源边界：配置最多 40 项、最近成功最多 10 项，没有新增后台采集线程或自动连接；复用已有网络/本地 I/O 线程与连接许可。跨页工作区 API 同步保护且返回不可变配置，IDE 框架处理设置持久化。**本版没有重新做整个 IDE 长时 CPU/堆测量或 180 秒独立 soak**，历史报告不升级为本版性能结论。

未验证/限制：远程认证/TLS/PasswordSafe GUI、同端点进程重启 GUI、完整旧功能 GUI、多项目/8 个真实目标、Ultimate GUI、其他系统/JDK/WAN、动态卸载及长期泄漏仍未完成。保存项限远程 JNDI/RMI，普通元数据不是加密秘密存储；不支持保存本地 PID、自动恢复活动页、自动重连或 Run/Debug 关联。下一阶段为多指标时间线与可保存采集区间，再做有界 JFR，见 [决策](decisions.md)。

## 0.7.0 历史验证

本轮交付 **Lock chains、固定 A/B 线程比较、v2 现场保存/重开**，并补充只操作自有 fixture 的一键现场脚本。不是全量锁分析器或方法级 profiler；仅处理有界平台线程快照，不宣称两帧之间持续阻塞或同一时刻的完整关系。

最终安装包 [jvm-beacon-0.7.0.zip](../build/distributions/jvm-beacon-0.7.0.zip)：**22:07:30.065 +08:00，273,862 bytes**，SHA-256 `5812ffd97a02525bc079a57da6b13f0ad1e8ac47acfc1d7a2b3c54fde3c210a6`。ZIP 仅包含插件 JAR，与最终加载沙箱完整字节一致。[产物记录](../build/reports/release-checks-0.7.0.json)。

| 检查 | 实际结果与边界 |
|---|---|
| 最后源码修改后构建 | Java 21.0.9 / Gradle 9.5.0 / IntelliJ Platform Gradle Plugin 2.18.1，`test buildPlugin verifyPlugin` 成功；**77/77，0 失败/错误/跳过**；XML 时间 14:06:51–14:07:28 UTC |
| 锁关系模型 | 5 个新增测试覆盖同名线程仅按 ID 连边、未知/缺失/-1 owner、独立死锁查询、非原子环、64 成员/512 记录上限、非法 ID/状态/时窗，以及仅双方已采集 owner ID 时计入 ID 变化 |
| 真实 JMX 与格式 | 10 项 JMX 集成通过，包括新增受控三线程锁竞争、释放后 STATE/LOCK 变化、重复启动清理、observer 禁止操作、保存重开同一链；v2 owner ID round-trip、v1 unknown 保留和非法字段拒绝通过。原有 Attach、双目标、目标退出、方法/通知/复杂值、Hot threads、隔离 TLS 回归通过 |
| 官方兼容性 | Verifier 1.408 对 IC/IU-251.26927.53 均 `Compatible. 1 usage of deprecated API`；保留已有 SSL RMI 规则提示与 IDE layout 缺项警告。JDK 21 隔离 TLS 实测通过，不把规则提示静默删除，不扩大版本矩阵 |
| 一键真实现场 | `capture-lock-demo.ps1` 21:52:48 输出 `LOCK_CAPTURE_PASS`、exit 0；认证 loopback 子 JVM PID 18212，A/B 各 24 线程，state 2 / stack 4 / lock 3；脚本 finally 关闭自有 JVM。[原始 A/B 与文本](../build/examples/locks-20260924-215244-228) 可离线重放 |
| 候选包 GUI | 真实在线 fixture 的显式操作/采集/Pin A/保存；后续候选重开真实 A/B、结构比较、三段链、源码定位反馈、v1 缺失 owner、保存副本、Dark/Light 125%。开发中修复说明挤压、按钮换行及清空后的旧提示；按构建分开的证据见 [GUI 记录](gui-validation.md) |
| 最终包 GUI | 22:08:56.247 实际加载 0.7.0；重新打开 B、选择 A 文件比较、Light 下按 STATE 筛选出 bridge/waiter 两行，选 waiter 显示 A BLOCKED / owner #53，B TIMED_WAITING / No owner reported；Clear 后无基线、详情禁用并显示 No comparison yet。不是完整旧功能回归 |
| 沙箱退出 | 最终 22:11:47.848 正常关闭、runIde exit 0；最新启动到退出 ERROR 级日志 **0**，平台 WARN 保留在 [日志摘录](../build/reports/ide-load-0.7.0.txt)。在线交互 fixture PID 20736 已输入 quit、exit 0；没有关闭用户日常 IDEA 或既有 Ultimate 沙箱 |

本轮实际失败及修正：最初集成断言直接比较完整 StackTraceElement，发现既有现场格式不保存 module/class-loader；随后按格式承诺比较 ID/owner/链终止原因与类/方法/文件/行，而非声称这些元数据被保存。脚本最初使用了不存在的 `inspect` 方法而编译失败，改为实际 `info` API 后通过。手动 GUI 测试一次超过 fixture 截止时间，只观察到 no longer observed；没有将其写成提前释放成功的 GUI 证据，改用真实有界自动采集 A/B 重放。最终自动检查均在最后修改后重跑。

资源预算：读取沿用原批量 ThreadMXBean 请求，没有新增持续采集任务或逐线程网络回退；锁视图最多 512 条记录/64 成员路径，每页一个基线和固定报告，比较最多 200 条明细。fixture 只在显式操作时创建 3 个 daemon 平台线程，总时限 1–120 秒，重复启动先清理上一组。这些是实现上限与测试约束，**不是本版 IDE CPU/保留堆测量结论**；本轮没有重新做 180 秒 soak，0.6.1 报告仍是历史基线。

已知限制与未验证：虚拟线程、全量持有锁清单/同步器、等待持续时间推断不在本版范围。v2 仍保存一次指标与一次线程窗口，Hot threads/趋势/比较报告不进入现场；旧 0.6.x 不能读取 v2。小视口长名称和栈需要滚动/拖动细分隔，尚未完成更多 DPI/窗口宽度验收。未完成 Ultimate GUI、远程认证/TLS/PasswordSafe GUI、完整旧功能 GUI 矩阵、多项目/8 个真实活动目标、WAN、动态卸载及 IDE 长期资源观察。

该版本交付时计划下一步连接工作区；其实际完成范围见本文 0.8.0，自动重连/重启关联、多指标联动与 JFR 仍未实现。

## 0.6.1 历史验证

本轮响应默认 fixture 偶尔无法连接、Trend 不更新和 Stale 反馈；没有用户当时的错误分类，因此不宣称个案根因已确定。实现了容量拒绝保留连接、连接阶段/20 秒截止时间、持久失败原因、明确开始采样与数据年龄；单点/恒定值居中、单位转换、精确悬停、长间隔/缺失/倒退时间断线。交互 fixture 空行不退出、quit 或输入流关闭输出原因。

最终包 [jvm-beacon-0.6.1.zip](../build/distributions/jvm-beacon-0.6.1.zip)：**17:09:11.311 +08:00，243,037 bytes**，SHA-256 `c9c77fedfc404fc4012cb778e0f9894aa4ecdd4380bc7b4360d2083e53bc6b84`；包内 JAR 与最终 GUI 沙箱完整字节一致。[产物记录](../build/reports/release-checks-0.6.1.json)。

| 检查 | 实际结果与边界 |
|---|---|
| 最后源码修改后构建 | `test buildPlugin verifyPlugin` 成功；**70/70，0 失败/错误/跳过**，测试 XML 09:08:33—09:09:09 UTC；不是 GUI 替代 |
| 新增回归 | 4 个趋势模型测试覆盖单点/真零/恒值、缺失/NaN/无效窗口、长间隔/时间倒退、120 上限与极端数值；新增真实交互 fixture 空行及 3 次本地重连；新增容量拒绝不执行请求及独立连接截止时间测试 |
| 原有集成 | 9 个真实 JMX 集成与 1 个隔离 TLS 测试通过，包含 hostname:port、服务端权限、复杂值/方法/通知、双目标隔离、目标退出及 Hot threads；临时证书不修改用户信任库 |
| 官方兼容性 | Verifier 1.408，IC/IU-251.26927.53 均 `Compatible. 1 usage of deprecated API`；保留既有 SSL RMI 规则提示，不扩大兼容矩阵 |
| 独立资源观察 | 当前核心 180.001 秒、90 样本，18 次线程与现场 save/load，9 次订阅/周期取消，真实目标退出及清理；`RESOURCE_SOAK_PASS`、exit 0。sample() p95 89.925 ms；详见 [方法和原始报告](soak-validation.md)，不是 IDEA 整体开销 |
| 最终包加载与交互 | 17:10:00.448 加载 0.6.1，真实 PID 9404 重连、单点/开始采样、递增 uptime、暂停/恢复空档、换行精确悬停和 Dark/Light 125%；超过 5 分钟观察并达到 120 点继续滚动，含一次暂停；quit 后持久 Stale，新 PID 34232 冷连接后清为 1 点。[GUI 逐项记录](gui-validation.md) 按最终与候选构建分开 |
| 沙箱退出 | 活动连接下正常 Exit，17:19:35.789 结束，runIde exit 0；本次启动至退出 ERROR 级日志 0，平台 WARN 保留在 [摘录](../build/reports/ide-load-0.6.1.txt)。自有 PID 9404 按 quit、34232 按 180 秒上限退出，均 exit 0；不替代长期资源验收 |

本轮发现并修复的实际问题：旧 fixture 收到 Enter 后退出，下一次读取变 Stale；容量拒绝的错误分类曾释放已有连接；候选 GUI 的说明区域压扁图表、单行悬停超出屏幕，最终改为自适应高度和换行。旧 0.6.0 在自有测试中约 7 分钟仍能滚动到 120 点，未复现用户所述的所有偶发连接失败；不能将修正写成所有连接问题已消除。

未验证：本版远程认证/TLS/PasswordSafe 的 GUI、WAN/其他 JDK/系统、完整旧功能 GUI 矩阵、长期整个 IDEA CPU/保留堆、永久底层阻塞后的动态卸载。容量压力有执行器及断连策略自动测试，未在 GUI 人为卡死 4 个远程请求。没有自动重连/重启进程关联；锁关系、JFR、连接配置工作区与多指标联动继续留在路线图。

## 0.6.0 历史验证

新增按需 Hot threads CPU 增量测量、数值排序、局部筛选、末次栈/源码入口和可复制报告。每连接页独立保留一个报告，不自动开启 CPU 监控，不扩大现场格式。使用方法和后续功能路线见 [README](../README.md)、[测试指南](testing.md)、[决策](decisions.md)。

最终包 [jvm-beacon-0.6.0.zip](../build/distributions/jvm-beacon-0.6.0.zip)：**16:31:57.441 +08:00**，**234,257 bytes**；SHA-256 `137574c4a55a6b2499c8569b2da169ebbeeb569bd1d614906265d91a7a79e2fa`。仅包含插件 JAR，完整字节与实际最终 GUI 沙箱 JAR 一致。[产物记录](../build/reports/release-checks-0.6.0.json) 包含测试和 Verifier 原始汇总。

| 检查 | 实际结果 | 范围与限制 |
| --- | --- | --- |
| 自动测试、打包 | 最终代码与 fixture 修正后 `test buildPlugin verifyPlugin` 成功，**63/63，0 失败/错误/跳过** | 8 JMX 集成、1 隔离 TLS、5 新 CPU 模型测试及原回归；XML 为 08:31:23–56 UTC，不等于 GUI |
| CPU 证据 | 真实认证 loopback 批量计数测到脉冲增量与末次栈，虚拟线程未混入；observer 权限拒绝后普通指标仍可读；disabled 目标保持 false | 单元另覆盖真零/不可得、名称变化/缺失/重置、ID 重复、512 上限、中点时序及超过 100% 不裁值。未验证其他供应商实现 |
| 官方兼容性 | Verifier 1.408，IC/IU-251.26927.53 均为 `Compatible. 1 usage of deprecated API` | 既有 SSL RMI 规则提示保留，解释见历史记录；没有扩大版本/系统矩阵 |
| 最终包加载与 GUI | 16:33:12.187 加载 0.6.0；自有 PID 35232 的真实 CPU 排名、数值升序、14→1→0 本地筛选、详情清理、完整时窗、复制动作反馈、断开 Stale；Dark/Light 125% | Community / JBR 21.0.7 / Windows，1388×974；[逐项记录](gui-validation.md)。没有将旧构建观察冒充最终验收 |
| 退出与资源边界 | 最终沙箱 16:38:31.188 正常关闭，runIde exit 0，最新启动至退出 ERROR 级日志 0；中间沙箱也 exit 0 | [WARN 摘录](../build/reports/ide-load-0.6.0.txt) 保留平台初始化/网络/主题警告。CPU fixture PID 8608、35232 分别到 240/300 秒退出，exit 0；脉冲均有 120 秒上限 |

本轮确实发生过失败：最初 5 ms / 45 ms 脉冲的一次回归读到零增量，导致 1 个集成测试失败（初轮曾通过）。短脉冲与 Windows CPU 计时粒度混叠是推断，未独立证明底层原因。fixture 改为 40 ms / 160 ms，仍有总时限，保留非零断言；完整回归与最终 GUI 都测得非零，不将失败轮次写成通过。GUI 初轮还发现列头截断与 Double 默认 renderer 导致格式不统一，最终包已修正并复测。

未验证：本次没有重跑 Hot threads 在途取消/换目标/离线打开的完整 GUI 矩阵、源码实际导航、剪贴板粘贴内容比对、CPU 不支持供应商和 WAN/高线程量真实目标；已有 SessionRunner 取消/超时/迟到清理自动回归通过。没有本版 IDE 长时 CPU/堆性能结论，原资源报告仍是历史基线。当前 Hot threads 不包含虚拟线程、不做方法级 CPU 归因、不进 `.jvmb`。锁关系、JFR、多指标联动与持久连接工作区仍未实现。

## 0.5.0 历史验证

新增原生多连接标签，每项目最多 8 页，独立会话/数据/只读状态，关闭一页不关闭其他连接。README 提供双 JVM 快速流程，[testing.md](testing.md) 提供可执行步骤、预期结果、远程测试边界与排错；说明步骤和已经通过的验收分别记录。

最终包 [jvm-beacon-0.5.0.zip](../build/distributions/jvm-beacon-0.5.0.zip) 于 **15:44:13** 生成，**205,688 bytes**，SHA-256 `50869c249a056463aea8d59a11feff1094e4e5916416c0b60b86bc7c1722d911`。仅含插件 JAR，完整字节与最终 GUI 沙箱 JAR 一致，见 [产物记录](../build/reports/release-checks-0.5.0.json)。

| 检查 | 实际结果 | 边界 |
| --- | --- | --- |
| 编译、测试、打包 | 最后代码修改后 `test buildPlugin verifyPlugin` 成功；**56/56，0 失败/错误/跳过**，XML 为 07:43–07:44 UTC | 6 项真实 JMX 集成、1 项隔离 TLS 集成、15 项异步生命周期测试及其他既有测试；不是 GUI 替代 |
| 新增隔离测试 | 两个认证 loopback JVM 的不同身份、A 写 Counter=12/B 仍为7、通知不串台、A 关闭/退出后 B 仍能采样和抓线程；关闭一 SessionRunner 丢弃/清理迟到连接，另一正常回调 | 受控 JVM 与模拟不可中断任务，不代表生产 WAN 或永久阻塞的完整 IDE 行为 |
| 官方兼容性 | Verifier 1.408 对 IC/IU-251.26927.53 均为 `Compatible. 1 usage of deprecated API` | 既有 SSL RMI 提示和 7 个 IDE layout 缺项保留，没有扩大矩阵 |
| 最终包加载/GUI | 0.5.0 正常加载；PID 32416/30736 双连接、切回保留指标、关闭第一连接后第二再次采样、Alt+Insert 新建、8 页上限不落入无关菜单、溢出切换、关闭空页后恢复名额 | Community / JBR 21.0.7 / Windows，1388×974，Dark/Light 125%；[分构建 GUI 记录](gui-validation.md) |
| 生命周期观察 | 最终 IDE 有一个活动连接和 7 个空页时正常 Exit，runIde exit 0，最新启动至关闭 ERROR 级日志为 0；四个 GUI fixture 均到各自600秒上限退出，exit 0，无本轮沙箱遗留 | [日志摘录](../build/reports/ide-load-0.5.0.txt) 保留平台初始化、WorkspaceFileIndex 时序、网络和主题 WARN；不等同长期泄漏检测 |

中间构建还实际观察了 A Counter=12/B=7、每页 Read-only/搜索隔离、收藏从 A 同步到 B、切回不重新读 getter（窗口时间未变）、隐藏 A 暂停自动轮询、关闭最后页后重新打开为空页。随后只调整快捷键在上限处的消费方式与标签单行 tooltip 长度；上述中间构建证据没有冒充最终包重复验收。

开发中首次编译发现 251 SDK 的快捷键构造器、ContentManager listener 签名及 Content disposal API 与预期不同，已修正；移除了新增弃用 API。GUI 发现第 8 页后 Alt+Insert 落入 IDE Generate，以及过长原生 tooltip 产生宽度 WARN；最终包已修复，快捷键实际重测，日志不再出现该 tooltip 宽度警告。

未验证：最终包完整 MBean/Watch/通知/现场/源码 GUI 回归、离线页与活动页共存的 GUI、远程认证/TLS/PasswordSafe GUI、多项目并发与长时 IDE CPU/堆、8 个真实活动目标、其他 IDE/JDK/OS。当前只对可见页自动轮询，隐藏通知仍有界接收；无跨重启恢复、连接别名/配置集、自动重连或多目标联合图表。后续优先补充这些交互与资源检查，再推进连接配置和运行配置关联。

## 0.4.0 历史验证

本轮交付属性/方法结果的 Value explorer：有界结构树、已采集字段搜索、顶层 TabularData 表格、精确值复制及结果选择保护。最终包 [jvm-beacon-0.4.0.zip](../build/distributions/jvm-beacon-0.4.0.zip) 于 **15:08:14** 生成，**199,819 bytes**，SHA-256 `74d5fe31254d60f4e7283f37f646568ef19f3d25c87a66323a75ee9cabaedbd4`；ZIP 仅含插件 JAR，与最终 GUI 沙箱 JAR 完整字节一致，见 [产物记录](../build/reports/release-checks-0.4.0.json)。

| 检查 | 实际结果 | 范围与限制 |
|---|---|---|
| 自动测试、打包 | 最后位置修正后执行 `test buildPlugin verifyPlugin`，**54/54 通过，0 失败/错误/跳过**；XML 时间为 07:07–07:08 UTC | 包含新增 6 个结构模型测试及原有 48 个测试；5 项真实 JMX 集成和 1 项隔离 TLS 集成均通过 |
| 结构模型 | 真实 CompositeData/TabularData 属性和操作返回；null、零、空容器、未知对象、部分字段异常、循环/重复引用、深度、节点/字符/共享预算、取消及巨大数值转换 | 不限制 RMI 在接收对象时的反序列化成本；不是恶意服务端隔离方案 |
| 官方兼容性 | Verifier 1.408 对 IC/IU-251.26927.53 均为 `Compatible. 1 usage of deprecated API` | 既有 SSL RMI 提示、7 项 IDE layout 警告仍保留；没有扩大兼容版本声明 |
| 最终包加载与 GUI | **15:09:02.942** 加载 0.4.0，受控 PID 26384 连接与指标、真实 `inspectRows()`、结果树/搜索/空结果、表格升降序与单元格映射、切换操作清旧结果、Label 属性 Copy→粘贴验证 | Community/JBR 21.0.7/Windows，1388×974，Dark/Light 125%；详情见 [GUI 记录](gui-validation.md)。最终包未重复 Summary 的 GUI；它在此前候选包观察，核心已重测 |
| 退出与日志 | 本轮 3 个沙箱均正常 Exit，runIde exit 0；PID 34056、26384 各到 600 秒退出，exit 0；最终启动至关闭无 ERROR 级日志 | 平台初始化、网络和主题 WARN 保留在 [日志摘录](../build/reports/ide-load-0.4.0.txt)；不代表长期无泄漏 |

初轮编译发现 CopyPasteManager 包名和残留字段引用，修正后通过。受限运行环境直接启动 fixture 的 javac 报“无法关闭编译器资源”，普通本机执行同一脚本成功；未修改安全配置。GUI 初轮发现新窗口高度过大，第二轮发现修正高度后原位置仍裁切标题，最终包按当前屏幕工作区限尺寸并居中，已在最终截图复核。

当前限制：Rows 仅顶层表格、按显示文本排序；长内容可滚动、内部比例可拖动；结构不进入 `.jvmb`、不自动脱敏。无搜索结果时 Copy value 点击无动作（仍显示按钮），待进一步完善可访问性/禁用状态。本轮没有重跑远程认证/TLS/PasswordSafe GUI、通知/现场/线程完整 GUI 矩阵、WAN/IPv6 网络、多个项目长期 CPU/堆测量。上述旧能力的自动回归通过不能代替这些未验证项。下一步优先运行配置关联与重启身份提示，并继续补齐界面及资源验收。

## 0.3.0 历史验证

新增远程简写与单数值属性追踪。最终包 [jvm-beacon-0.3.0.zip](../build/distributions/jvm-beacon-0.3.0.zip) 于 **12:13:04** 生成，**178,656 bytes**；SHA-256 `00803b982e8d014e1ad020ea2d84a6e375f2abc2c35a8f416e0182e5dfebbf09`。仅含插件 JAR，完整字节与实际 GUI 沙箱 JAR 一致，见 [产物记录](../build/reports/release-checks-0.3.0.json)。这不是整个愿景的完成声明。

| 检查 | 最终包实际结果 | 边界 |
|---|---|---|
| 自动测试与打包 | **48/48 通过，0 失败/错误/跳过**；最后产品代码修改后执行 `test buildPlugin verifyPlugin`。收尾仅收紧测试清理的异常捕获范围，再次 `test buildPlugin` 通过，JAR/ZIP 为 UP-TO-DATE、摘要不变；最新 XML 时间见产物记录 | 5 JMX 集成、1 TLS 集成、3 地址、3 数值序列、1 凭据键、5 现场、11 线程比较、5 类型、14 异步生命周期；不是 GUI 替代 |
| 简写远程 | 实际认证 loopback 子 JVM 用简写连接，读数值和标准指标；测试非法端口、路径混入、歧义 IPv6、完整自定义 binding、等价地址凭据键与用户/端口隔离 | IPv6 只有解析测试；未测 IPv6 网络、WAN、NAT、生产服务端；不由 URL 转换推断 TLS 配置正确 |
| 数值证据 | long/decimal 精确文本、真零/null/NaN/无穷、拒绝任意 Number 子类、巨大数值边界、120 点上限、清空和不可变读取；真实 getter 权限、错误类型、缺失属性及退出后失败 | 巨大 RMI 对象接收成本不受显示限制控制；每次只追踪一个属性，历史未导出 |
| 官方兼容性 | Verifier 1.408 对 IC/IU-251.26927.53 均为 `Compatible. 1 usage of deprecated API` | SSL RMI 规则提示及 7 个 IDE layout 缺项仍保留，解释见下文；未扩大 IDE/JDK/OS 矩阵 |
| 加载与交互 | **12:14:36** 加载 0.3.0；真实 PID 4472 连接、指标、递增属性、暂停/恢复、null 失败暂停和目标退出；Dark/Light 125%，1388×974 | Community/JBR 21.0.7/Windows；本轮未重跑方法调用、通知、线程导航和现场 GUI。具体时窗和原图见 [GUI 记录](gui-validation.md) |
| 收尾 | 最终沙箱正常 Exit，`runIde` **exit 0**；本轮两个受控 fixture PID 15160、4472 均到 600 秒上限退出，exit 0。最新启动至关闭无 ERROR 级日志 | 保留平台初始化、网络握手及主题 WARN，见 [摘录](../build/reports/ide-load-0.3.0.txt)；不据此宣称长期无泄漏 |

GUI 初轮发现 Watch 被重复元数据说明挤压，修正后又将曲线与表格改为并排，最终包完成上述实操；两次中间沙箱也正常退出。远程地址输入/预览在 GUI 已查看，认证/TLS/PasswordSafe **完整界面流程仍未验证**，核心认证及 TLS 测试通过不代替该项。现有 `.jvmb` 范围不变，数值追踪不会偷偷扩大导出。

## 0.2.1 历史验证

本轮基于 0.2.0 重构统一英文界面和自有诊断输出，使用原生字体、JetBrains Mono 技术字段、青绿色信号、LIVE/SNAPSHOT/STALE 状态，以及完整趋势起止时间。最终安装包 [jvm-beacon-0.2.1.zip](../build/distributions/jvm-beacon-0.2.1.zip) 于 **11:23:58** 生成，**166,298 bytes**，SHA-256 `b43f7355c2d2d737b6c043b10f3e73aeda8df868f2dfb8ce9c760e67f82c97f7`。仅含插件 JAR，已按完整字节核对与本轮 Community 沙箱 JAR 一致。[产物汇总](../build/reports/release-checks-0.2.1.json) 与 [加载/关闭摘录](../build/reports/ide-load-0.2.1.txt) 保留原始事实；下方旧版不外推到此包。

| 检查 | 已有证据 | 当前限制 |
|---|---|---|
| 自动测试与打包 | 最终字体修正后重新执行，**40/40 通过，0 失败/错误/跳过**；4 JMX 集成、1 TLS 集成（6 情形）、5 现场、11 线程比较、5 类型、14 异步生命周期。`buildPlugin` 成功 | 当前 XML 为 03:23 UTC 此轮结果；不外推 GUI |
| 官方兼容检查 | Verifier 1.408 对同一包的 IC/IU-251.26927.53 均为 `Compatible. 1 usage of deprecated API` | 保留 SSL RMI 规则提示与 7 个平台 layout 缺项，解释见下文；未测其他构建 |
| 线程比较核心 | 覆盖目标身份/重启、缺失与无效采集、ID 候选、截断、持久化栈字段、首个不同栈帧、锁名、输出上限和窗口顺序 | 核心测试不代表所有比较 GUI 流程通过 |
| 实际加载与 GUI | 11:24:28 加载 0.2.1；独立 PID 33540 的真实指标、68/120 个趋势样本、`add(2,3)=5`、13→1→0 线程筛选、真实 A/B 离线比较；深浅主题 100%/125% | Community / JBR 21.0.7 / Windows / 1388×974，具体时窗见 [GUI 记录](gui-validation.md)；本次未在 Ultimate 做 GUI |
| 收尾与日志 | 正常退出最终沙箱，`runIde` exit 0；自有 PID 6080、33540 均到时限退出，exit 0。该次启动至退出没有 `ERROR` 级日志，存在平台启动/网络/主题 WARN | 不代表所有平台功能无问题或长期无泄漏；此前主题 ERROR 另列历史 |
| 视觉基线 | [旧概览截图](../build/reports/ui-0.2.0/01-before-overview.png)，1388×974 深色主题，可见粗白分隔 | 最终前后图与实际观察见 [GUI 记录](gui-validation.md)，不把初轮截图冒充最终包 |

## 0.2.0 重构阶段的真实证据

- 10:52:12 生成包 164,923 bytes，SHA-256 `eb5d4bf417ae94d57c5e3bddc34d690738eb220ed619b4c17052a8a6be0a3715`；包内 JAR 与该轮 Community 沙箱字节一致。双 IDE 均为 `Compatible. 1 usage of deprecated API`。40 项测试通过发生在最后纯布局修正之前，具体轮次见 [产物记录](../build/reports/release-checks-0.2.0.json)。
- 实际连接自有 PID 772，10:55:18.856—10:55:19.885 读取首次指标，开启 2 秒采样；10:57:41 图中为 63/120 个样本。MBean 侧栏往返拖动后按钮不覆盖搜索，读取 Probe 的 Summary 以及故意失败的 Forbidden/Broken 属性。离线打开真实 B 文件并与 A 比较，观察计数 2/1/16、状态/栈变化各 1，长说明自动换行。
- 这轮 GUI 随后因英文产品要求进入 0.2.1；不将中间包称为最终英文交付。最后沙箱退出码未收集，不补写通过。先前两个 0.2.0 沙箱轮次观察到正常 exit 0。
- 受限执行环境中的 fixture 编译失败；使用已有 classes 启动的 PID 26556 首次 JMX 连接超时，而 jcmd 可读管理代理状态。普通本机环境运行同一脚本的 PID 8404 成功连接。此对比提示运行环境相关，但未进一步证明具体系统原因；未关闭任何 TLS 或授权机制。
- 10:42:43 切换主题出现平台 `EditorMarkupModelImpl: Error stripe marker has no color`；该栈仅见 IntelliJ/Swing，未见 Beacon 栈帧。单独记录，不宣称全局无异常。

## 0.1.4 历史交付与结果

- 安装包：`build/distributions/jvm-beacon-0.1.4.zip`，126,357 bytes。
- SHA-256：`AFB30C2130CD1E1865814BBC735154ADBB7B563F713FE58FF7B19742963CD51C`。
- 开发 plugin ID：`dev.jvmbeacon`；仅本地生成，未发布、推送或购买服务。名称/发布者/正式许可仍待核查。
- [0.1.4 产物汇总](../build/reports/release-checks-0.1.4.json) 保留 ZIP 摘要、29 项测试、双 IDE verdict 和 `sandboxJarMatchesZip=true`；[加载/关闭摘录](../build/reports/ide-load-0.1.4.txt) 记录实际加载 0.1.4 及项目关闭。已核对本次 GUI 所用 JAR 与此 ZIP 一致，旧包证据另留在 [分版本 GUI 记录](gui-validation.md)。
- 安装及操作见 [README](../README.md)。可复跑脚本：`scripts/run-fixture.ps1`、`scripts/smoke-core.ps1`、`scripts/soak-core.ps1`。
- 相对 0.1.3：修复目标操作异常被误归输入错误（GUI-02），新增单元回归；实际执行 `fail()` 已显示 `[TARGET]` 并保持连接。参数布局修复来自 0.1.3，分版本事实见 [GUI 记录](gui-validation.md)。

下列是 **0.1.4 历史结果**，不是当前包的验证；原始报告和 GUI 版本边界保留以便追溯。

| 检查 | 结果与证据 | 范围限制 |
|---|---|---|
| 编译与打包 | `buildPlugin` 成功；ZIP 只含插件 JAR，不含测试、IDE 或新增第三方运行时库 | 编译不证明交互正确 |
| 统一 Gradle 测试 | **29/29 通过，0 失败、0 错误、0 跳过**。4 项真实 JVM 集成、1 项 TLS 集成（含 6 种情形）、5 项快照、5 项类型/格式化、14 项异步生命周期与错误分类。报告：`build/reports/tests/test/index.html`；XML：`build/test-results/test/` | 未覆盖平台 UI |
| 真实核心流程 | 本地 Attach/显式启代理/已有代理重连，认证远程，服务端 observer 拒写、错误密码、复杂值/异常 getter、属性/数组操作、通知、目标退出、保存重开均通过 | 远程测试仅本机 loopback |
| TLS | 受信的 TLS registry+connector 成功；observer 拒写/拒操作、错误/缺失凭据、不受信证书、误用明文 registry 均失败。临时材料清理，父 JVM 信任配置不变；独立运行与 Gradle 均通过 | 未测双向 TLS、主机名不匹配或生产网络；见 [TLS 验证](tls-validation.md) |
| 截止与生命周期 | 工作不在 EDT、回调在 EDT；取消丢弃回调、迟到连接关闭；模拟无响应最多 4 个网络 worker。其中 4 项测试验证网络池耗尽后本地 I/O 仍能工作、本地最多 2 个任务无队列且不阻塞网络、同视图跨池不重叠、本地超时丢弃迟到回调。16 个连接许可覆盖活动/连接/清理；重复关闭只入队一次；dispose 保留排队并接受预留连接的迟到清理 | 中断不保证真实网络调用终止；模拟不等于 IDE 项目关闭、动态卸载和长时网络故障实验 |
| 目标错误分类 | 新增回归覆盖 MBean 包装内的参数、权限、I/O、TLS 等异常；GUI 中实际 `fail()` 显示 `[TARGET]`、不自动重试，连接保持，GUI-02 回归通过 | GUI 仅执行此 fixture 的目标异常，不代表所有远程异常均已实操 |
| 官方二进制验证 | 同一个 ZIP 经 Plugin Verifier **1.408** 检查 **IC-251.26927.53** 与 **IU-251.26927.53 / JBR 21.0.7**，均为 **Compatible. 1 usage of deprecated API**。报告：`build/reports/pluginVerifier/{IC,IU}-251.26927.53/report.html` | 未验证 251 分支其他构建、2025.2+ 或其他 IDE；见下方剩余警告说明 |
| 实际 IDE 启动 | 0.1.4 Community 于 00:59:24 启动并实际加载，JAR 与 ZIP 一致；活动项目正常关闭，随后 `runIde` exit 0，限时 fixture exit 0 | 本轮未在 Ultimate 启动 0.1.4；任务运行时长不作为性能测量 |
| GUI 交互/视觉 | 0.1.4 实操连接、自动采样、保留 MBean 选择/读取窗口、GUI-02 回归、通知收发/刷新/取消、固定比较跨至少 36 秒采样保留及活动项目关闭。属性/数组操作、线程源码、保存重开等旧包观察另列 | **尚未完成全部 GUI 验收**。具体环境、版本、单点线程观察与待测项统一见 [GUI 记录](gui-validation.md) |
| 用户脚本（2026-09-23） | `smoke-core.ps1` 输出 `CORE_SMOKE_PASS`，原始输出在 `build/reports/core-smoke.txt`；`run-fixture.ps1 -DurationSeconds 600` 成功启动并按时限退出 | 这是该日脚本记录；本轮 fixture GUI 运行另列，不是整个 IDEA 长时开销测量 |
| 三分钟资源观察（2026-09-23） | `soak-core.ps1` 输出 `RESOURCE_SOAK_PASS`，180.015 秒/90 个样本，18 次线程快照及 save/load、9 轮订阅/取消；目标退出后读取失败，采集 executor 和自有 fixture 已退出 | 独立采集进程的短时测试，本轮未重跑；不是整个 IDEA 或长期无泄漏证明；见 [方法与原始报告](soak-validation.md) |

## Community 验证环境与复现

2026-09-24 从 [JetBrains 官方发行数据](https://data.services.jetbrains.com/products/releases?code=IIC&latest=false&type=release) 查得 Community 2025.1.3 Windows ZIP，实际下载 [官方安装档案](https://download.jetbrains.com/idea/ideaIC-2025.1.3.win.zip)，并核对 [官方 SHA-256](https://download.jetbrains.com/idea/ideaIC-2025.1.3.win.zip.sha256)：`0c67ec7db671db27d255552fd02ab7297543bf976800699f94679a5b3ad0975e`，1,182,416,957 bytes。校验后仅解压到项目 `.intellijPlatform/ides/community-2025.1.3`，未安装或替换日常 IDEA。证据类型：官方发行元数据 + 本机下载校验；原始记录 `build/reports/community-download.json`。

0.2.1 已成功执行以下检查；最终 GUI 随后通过 `runIde` 在同一 Community 沙箱进行：

```powershell
$env:JAVA_HOME = 'C:\Users\lenovo\.jdks\corretto-21.0.9'
.\gradlew.bat test buildPlugin '-PlocalIdePath=D:\IdeaProjects\JVM-Beacon\.intellijPlatform\ides\community-2025.1.3' --offline --console=plain
.\gradlew.bat verifyPlugin '-PlocalIdePath=D:\IdeaProjects\JVM-Beacon\.intellijPlatform\ides\community-2025.1.3' '-PadditionalVerificationIdePath=E:\JetBrains\IntelliJ IDEA 2025.1.3' --offline --console=plain
```

兼容检查首轮依赖解析网络连接超时；重试额外传入本机已有 HTTP/HTTPS 代理 JVM 参数后成功，未把代理写进项目或关闭 TLS 校验。`--offline` 不保证构建插件自己发出的所有网络请求离线。GUI 使用独立 `build/gui-project`，只包含与 fixture 相同的 Java 源码；通过 `runIde --args=<该项目绝对路径>` 启动。首次系统提示已由用户处理，后续证据按插件版本记录。

## 兼容提示处理

1. 验证器拒绝最初包含保留词的 ID `dev.jvmbeacon.intellij`，已改为 `dev.jvmbeacon` 并重新打包验证，没有屏蔽问题。
2. 收藏存储从弃用的 `PropertiesComponent.getValues/setValues` 改为 `getList/setList`，两项提示消失。
3. 剩余提示称 `javax.rmi.ssl.SslRMIClientSocketFactory` 只存在于 JDK 8。**本机反证**：在实际 IDEA JBR `21.0.7+9-b895.130` 中成功实例化该类，模块为 `java.rmi`，见 `build/reports/jbr-tls-class-probe.txt`。[JDK21 官方 API](https://docs.oracle.com/en/java/javase/21/docs/api/java.rmi/javax/rmi/ssl/SslRMIClientSocketFactory.html)也列出该类。判断为该规则对本目标的误报，保留原报告，不设置 suppress/mute。真实 TLS 握手另在 Corretto 21.0.9 子 JVM 验证，不能与类存在性混为一项。
4. 两个 IDE 的 product-info 均报告 7 个 layout 路径缺失（Android declarative、JSON、Qodana、Git localHistory、GitHub、YAML、emoji），验证器默认警告并跳过。Community 来源为已核对官方 SHA-256 的完整 ZIP，仍出现这些警告；不把它解释成下载损坏，也不隐去验证覆盖限制。结论限于报告实际检查到的 API，不宣称整个版本矩阵全通过。本轮验证器对两个目标均使用 Community 随附的 JBR 21.0.7。
5. `verifyPluginProjectConfiguration` 建议移除 `until-build`。首版有意保留 `251.*`，避免允许未经验证的未来版本。

## 资源与性能

初次基线见 [core-notes](core-notes.md)。**2026-09-23** 脚本复跑：Corretto 21.0.9、16 逻辑 CPU、目标 `-Xms32m -Xmx96m`；预热 10 次后连续采样 100 次，median **69.613 ms**、p95 **83.019 ms**、max **93.197 ms**，调用线程 CPU 共 **250 ms**。当时其他 Gradle/验证器进程在运行；初次较空闲的 median/p95 为 46.379/51.308 ms，不能当固定性能值，也不是 0.1.4 重测结果。

同次 2026-09-23 核心验证中，20 轮连接/订阅/采样/取消订阅/关闭后，JMX/RMI 线程分组与预热基线相同；260 条通知只保留最后 200 条；现场 34,275 bytes、身份重开一致；平台等待线程出现、虚拟等待线程未出现。初次受限环境枚举漏掉 fixture；最终普通用户权限下脚本报告 `Fixture discoverable via Attach.list: true`。之后 GUI 也有自有进程发现记录，但仍不能承诺枚举完整，保留手填 PID 回退。

2026-09-23 在 0.1.1 的独立三分钟观察：90 个样本，均值 63.358 ms、中位数 61.908 ms、p95 76.628 ms、最大 86.802 ms。采集进程 CPU 2,718.750 ms，约一个逻辑 CPU 的 1.51029%；观察到的最大堆 used 20,643,096 bytes，包含测试装置开销且没有强制 GC。线程观察窗口内均为 RMI 3 个 + 装置 2 个，关闭后 8 秒 RMI 线程仍存在，不能声称立即清零或无泄漏。详情、CSV 与现场见 [资源观察](soak-validation.md)。

0.1.4 全应用网络工作最多 4 线程，本地现场/凭据 I/O 最多 2 线程，两个池均无工作队列；最多 16 个连接许可，覆盖连接中/活动/待关闭；清理最多 2 线程+16 队列位置，每个资源只入队一次。网络池耗尽不占用本地文件任务容量，但同一视图仍需等截止或取消当前请求后操作。默认手动采样，可选 2 秒周期、最多 120 点。健康 loopback 的采样 p95 预算 <250 ms，上轮独立核心基线满足。0.1.2 离线项目关闭、0.1.4 活动连接且开启采样时关闭后，各有一次 [线程观察](gui-validation.md)，不能证明无泄漏；**尚未测** IDE 长时 CPU/堆增量、24 小时采集、真实网络永久卡住恢复及关闭、多个项目并发、重复 GUI 连接矩阵和动态卸载资源。

## 环境问题与修复

- PATH 默认 Java 11、原 JAVA_HOME 17，构建显式使用 Corretto 21.0.9；IDE 使用安装版 JBR 21.0.7。
- 官方插桩依赖缺缓存且下载停滞；代码无 `.form` 或运行时 null 插桩需求，明确关闭 `instrumentCode`。随后仍执行编译、真实测试和官方验证。
- 使用本机既有代理下载缺失 JUnit launcher，保持 TLS 校验；未把代理/凭据写入工程。IntelliJ 测试 bootstrap 需要 JUnit 4 rules，已补仅测试依赖。
- IntelliJ 测试 ClassLoader 无 CodeSource，fixture 改用构建注入的 classpath；目标退出后的 connector.close 可能抛 IOException，修正了测试清理预期。
- Windows 沙箱会锁住插件 JAR，打包前须关闭本任务沙箱；早期为释放锁的清理不算正常项目关闭验收，后续已完成的 0.1.2 离线关闭另行记录。
- 验证器扫描用户历史插件缓存耗时较长，现使用 `build/plugin-verifier-home` 隔离缓存，未删除用户旧缓存。
- 0.1.1 TLS 接入 Gradle 初跑出现 `CodeSource.getLocation()==null`，随后由构建显式注入 `beacon.core.classes` 并增加空值说明；当时复跑 24 项全部通过。独立 TLS 首轮结果没有被冒充成 Gradle 成功；0.1.4 的 29 项结果另见上表。
- 2026-09-23 23:17 与 23:27 两次通过 Ultimate 沙箱 UI 请求退出时，IDE 自身均记录 `FlushQueue - Job was cancelled`，`runIde` 返回 exit 7；没有记为成功的 GUI/项目关闭验收。最小摘录在 `build/reports/ide-gui-blocker.txt`。日常 IDEA 未关闭。
- 2026-09-24 早期为释放 JAR 锁停止第一次 Community 沙箱；再次启动记录 VFS 非正常关闭后自动恢复，该清理不算正常关闭验收。随后用户处理系统提示，0.1.2 完成部分 GUI 流程、正常离线项目关闭和沙箱退出；事实集中见 [GUI 记录](gui-validation.md)，用户日常 IDEA 未关闭。

## 下一步

继续重复连接/断开矩阵、通知压力与永久阻塞请求下关闭、附加/匿名候选源码、更多主题/缩放、完整键盘和远程认证/PasswordSafe 界面验证。产品开发优先运行配置关联、复杂结果树/表与追踪使用体验验证。完成范围以 [GUI 记录](gui-validation.md) 为准，不沿用旧包通过结论。

随后补双向 TLS、证书主机名、WAN/RMI 第二端口、大 MBean/通知压力、长时资源及 IDEA/JDK/OS 矩阵。显示上限不能限制 RMI 反序列化巨大对象，当前只连接可信目标。SSH、Jolokia、JFR、虚拟线程采集、运行配置关联和跨重启恢复留在 [路线](decisions.md)，没有用占位按钮伪装完成。
