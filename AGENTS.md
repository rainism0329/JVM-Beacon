# JVM Beacon

IDEA 原生 JMX 诊断插件；当前开发版本 0.10.0（2026-09-28）。用户已授权创建并推送到现有账号 rainism0329 的私有 GitHub 仓库 JVM-Beacon；不公开仓库、不发布 Marketplace 或 GitHub Release。

- Java 21、IntelliJ Platform Gradle Plugin 2.x、Swing；纯 JDK 核心放在 `dev.jvmbeacon.core`，IDE 适配/UI 放在 `dev.jvmbeacon.ui`。
- UI 复用 `BeaconUi`：OnePixelSplitter、动态主题色、DPI 间距和字体随主题更新；不恢复标准 Swing 粗分隔或固定亮色背景。
- 产品界面、插件自有提示和诊断报告统一英文；保留目标返回的原始文本。与用户交流和项目文档可以中文。
- 视觉目标是酷、极客、专业的运行时控制台：原生主题与字体、技术数据等宽、克制的信号色和清晰层级；不能用假数据、装饰性按钮或无依据的健康指示换取观感。`LIVE` 仅表示连接，数据新鲜度由采集窗口说明。
- 验证目标为官方完整 IDEA Community 和本机 Ultimate 2025.1.3（IC/IU-251.26927.53）/ JBR 21 / Windows；编译、Verifier、加载日志与 GUI 验收分别报告，不将描述符范围视为完整兼容性实测。
- Windows 构建：将 `JAVA_HOME` 指向 JDK 21，执行 `./gradlew.bat test buildPlugin -PlocalIdePath="<IDEA目录>"`。本机 Community 路径为 `D:\IdeaProjects\JVM-Beacon\.intellijPlatform\ides\community-2025.1.3`。官方检查 `verifyPlugin` 可用 `-PadditionalVerificationIdePath="<第二IDE目录>"` 对同一包追加目标。
- 开发 ID 为 `dev.jvmbeacon`。验证器缓存隔离在 build；Windows 打包前关闭本项目的 runIde 沙箱以释放 JAR 文件锁。不要关闭用户日常 IDEA。
- 无 IDE 的核心复测：`./scripts/smoke-core.ps1 -JdkHome "<JDK21目录>"`；交互 fixture：`./scripts/run-fixture.ps1`；短时资源观察：`./scripts/soak-core.ps1`（默认 180 秒，上限 600 秒）。这不等于整体 IDE 长时性能验收。
- 锁现场快速复测：`./scripts/capture-lock-demo.ps1 -JdkHome "<JDK21目录>"`，创建并关闭自有认证 loopback fixture，生成真实 A/B 和比较文本到新时间戳目录，成功输出 LOCK_CAPTURE_PASS；不连接已有进程。
- TLS 集成测试使用临时 keytool 材料和独立子 JVM，禁止修改用户/IDE 信任库；详见 `docs/tls-validation.md`。
- 禁止 EDT 上 Attach、JMX、文件读写或 PasswordSafe I/O。远程请求限流、超时丢弃迟到结果；取消不等于底层调用已停止。
- 连接阶段可等 20 秒，普通请求仍 8 秒；阶段状态按请求隔离，迟到进度不能污染新连接。CAPACITY 不代表连接已失效：保留会话、暂停自动采样、明确提示。Stale 原因持久可见。TrendSeries 只由已有证据计算，120 点、单点/恒定值居中，缺失/超过 5 秒/时间倒退不连线；原值与显示单位分开。默认交互 fixture 输入 quit 退出，忽略空行，定时模式独立。
- 网络池 4 线程、本地 I/O 池 2 线程，分别无队列；16 个连接许可覆盖连接中、活动及清理中，关闭入队前去重、结束后归还，释放插件时不得丢弃已排队清理。
- 连接使用原生 Content 标签，每项目最多 8 页；每页独立 BeaconPanel/SessionRunner，关闭由 Content disposer 清理。只对可见页自动轮询，隐藏页保留有界通知；收藏修改先读应用最新集合，避免跨页覆盖。不能在项目/内容管理器释放时重建空页。测试步骤集中在 `docs/testing.md`，README 保留入口与快速流程。
- 默认观察模式；属性写入和操作要明确确认，超时后不能自动重试。凭据只进 PasswordSafe，不进日志/导出/项目配置。
- ConnectionWorkspace 仅保存 40 个远程配置元数据到应用本机非漫游设置，最近成功最多 10 个；不存密码/本地 PID。删除或端点修改胜过迟到成功，跨页改名不覆盖其他配置。显式重连清采集历史、恢复只读/暂停采样、不沿用 agent-start 许可；密码后台读取，迟到秘密须清除。身份比较只表示报告值相同/变化/不完整，不保证目标认证。
- 错误分类先识别 MBean 异常包装；目标代码抛出的参数/权限/I/O 异常不能误报成客户端校验失败或连接失效。
- 所有采集和显示必须标明时窗、来源、缺失/截断/能力边界；ThreadMXBean 只表示平台线程。
- Hot threads 仅显式测量两次批量 CPU 计数，中间等待 1 秒，沿用全局执行器/截止时间；不自动启用监控或逐线程网络回退。512 个基线候选、64 帧末次栈、每页一个报告。百分比相对于单核、保留读取时延；末次栈不能用于 CPU 方法归因。新目标/离线打开清除报告，暂不纳入 .jvmb。CPU fixture 仅显式 -CpuDemo 且必须有时限，脉冲上限 120 秒。
- 复杂值在后台转换成不可变 `StructuredValue`，UI 不持有远程对象图。每值最多 512 节点/32768 字符/深度 6/每层 100 子项，属性批次共享 4096 节点/262144 字符预算；展开和搜索不重新调用 getter。方法结果随选择变化失效。
- 远程简写仅在本地规范化为 JMX/RMI URL，IPv6 要求方括号，PasswordSafe 使用规范端点+用户名；不做 DNS 猜测或 TLS 降级。单属性追踪复用现有采样调度、最多 120 点，暂停/清空/换目标丢弃迟到值，失败暂停，精确值与近似图表分开；追踪不进入现有现场导出。
- 线程筛选只处理已有快照；线程比较须先核对目标身份和采集有效性，同 ID 仅候选，不把缺失或未变的栈诊断为线程退出/阻塞。
- Lock chains 仅用 owner ID 连边，最多 512 记录/64 成员一条路径；null=未采集，-1=未报告 owner，缺失 owner 不猜名称、不宣称退出，观察环不等同于独立死锁查询。现场写 v3、读 v1/v2/v3；v1 owner ID 保持 null。每页一个线程基线和固定比较，清空/换目标按 generation 丢弃迟到结果；比较报告本身不进入现场。fixture 锁竞争仅显式 MBean 操作，单组 3 线程、总时限 1–120 秒，可提前释放/重复启动清理。
- Timeline 复用现有采样；每页 120 点，冻结最多额外固定 120 点，按采集顺序选区间，换目标/离线打开清冻结视图。v3 保存最多 120 个指标样本，5 MiB 文件上限；保存区间仅指标/身份/备注，普通快照可另带线程。旧文件不补历史。GC 是累计近似采集时间，不当暂停；缺失、单位变化、时窗异常和计数回退抑制相应差值。`scripts/capture-timeline-demo.ps1` 生成真实 8 点/4 点现场，含主动采样空档，自动清理自有认证 fixture。
- 搜索过滤不得因恢复选择重复远程读取；无选择时清详情，比较结果不得被实时采样覆盖。源码定位保留合法 `$`、经附加源码导航；匿名/局部类外层候选须明确确认，不能宣称版本匹配。
- JFR 每连接仅管理自有 recording；启动前设 5–120 s duration/maxAge、32 MiB disk retention、dumpOnExit=false，不指定目标文件。创建/配置/启动不自动重试，失败保留已知 ID；断连先非阻塞 cancel 再借连接许可清理，不能承诺网络失败后已释放。仅 STOPPED 可流下载，64 KiB block / 64 MiB / 45 s 循环预算、60 s UI 截止；不覆盖文件、不使用 copyTo 或 recording ID 0。局部 JFR 操作失败需刷新状态。RecordingInfo duration/maxAge 单位为秒。离线库存限制 64 MiB/200k events/256 types/5 s 扫描，JDK 单次解析不是硬资源隔离；不保留字段/栈，不把事件数当 CPU 占比或完整覆盖，不自动脱敏。`scripts/capture-jfr-demo.ps1` 生成并清理自有认证目标的真实录制。
- 自动测试仅连接自己启动、finally 清理的 fixture；远程 fixture 仅 loopback 且认证，不测试未知业务进程。
- 调研与决策见 `docs/research.md`、`docs/decisions.md`；自动检查汇总见 `docs/validation.md`，具体 GUI 证据按版本集中在 `docs/gui-validation.md`，不得从旧构建外推新包通过；使用方法见 `README.md`。
- 不搬运竞品代码或资源；修改后运行相关测试、构建，并据实记录未验证项。
