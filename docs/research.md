# JVM Beacon：产品研究与证据

## 2026-09-24 补查：平台线程 CPU 活动

- **官方 API / JDK 21，查询日期 2026-09-24**：[ThreadMXBean](https://docs.oracle.com/en/java/javase/21/docs/api/java.management/java/lang/management/ThreadMXBean.html) 明确平台线程范围、不包含虚拟线程；线程 ID 在终止后可复用，CPU 监控可能未开启，启用可能有成本。[com.sun.management 扩展](https://docs.oracle.com/en/java/javase/21/docs/api/jdk.management/com/sun/management/ThreadMXBean.html) 提供批量 CPU 读取，纳秒单位不等于纳秒精度；未开启/不存在等情况可返回 -1，支持依赖实现。
- **本项目判断**：在 IDEA 内把两次真实计数排名与末次栈并置，能减少筛选目标线程的步骤；并非方法级 profiler，末次栈不能归因 CPU。客户端读耗时影响百分比，ID/同名匹配仍只作候选。不自动开启监控、不回退大量逐线程远程调用。
- **实际测试与限制**：受控 JDK 21 的认证 loopback、权限拒绝、未启用及真实 CPU 脉冲测试和 GUI 证据见验证文档。未实测其他 JVM 供应商、WAN、高线程量远程环境；文档声明不替代兼容实测。未复用竞品代码或新增依赖。

查询日期：**2026-09-23（Asia/Shanghai）**。本文件记录首版决策依据，不是完整市场清单。下列竞品均为**文档/API/仓库研究，未安装实测**；Marketplace 的兼容范围是发布者声明，不代表本项目验证。`官方`指产品文档或发布记录，`个案`指 issue 作者报告，`判断`指本项目推断。实现与实测结果另见 [决策](decisions.md)、[验证](validation.md)。

## 决策结论

首版的价值是把“选对 JVM → 找到一个 MBean → 看懂数据/有意识地修改 → 保存带上下文的现场”放进 IDEA，附带足够解释现场的平台线程与基础指标。优先级高于再做一个 CPU profiler、堆分析器或外部工具启动器。

- **已经很好**：IDEA Live Charts 的运行入口、Spring Beans/映射到源码；JConsole 的通用管理和通知；JProfiler 的 Open MBean 结构阅读、类型边界、选择性 MBean 快照；YourKit 的线程证据与历史保留；Hawtio 的按组件显示相关面板。它们应是基线，不能宣传为 Beacon 独有。
- **有证据的摩擦**：JMX 连接涉及本地权限、注册表与 RMI 实际端点、证书和服务端授权；JConsole 官方说明 ObjectName 属性顺序可能造成意外树形分组；Arthas 作者/贡献者明确记录过 IDE/浏览器切换、多实例和输出检索步骤；IDE 插件版本、线程模型、生命周期变化确实需要持续维护。具体证据见下文，不能由个案推算发生率。
- **仍是假设**：通用 MBean 管理在 IDEA 中的未满足需求规模；“搜索＋收藏＋现场”是否足以让用户切换；新手能否理解平台线程覆盖范围；跨重启关联是否比手动选进程更重要。没有用户访谈、留存或竞品任务计时数据。
- **切换理由（待验证）**：日常轻量排查不必离开项目，按准确对象身份搜索，错误给下一步，快照保留时间/缺失项而不伪装完整历史。重度 CPU、堆和 JFR 分析继续借助成熟工具。用户不必放弃已有工具。

## 检索方法与小型插件

实际访问 Marketplace 页面及搜索；网页文本抽取经常只得到标题，内置浏览器读取两次超时。随后用 Python `urllib` 保持默认 HTTPS 校验，读取官方 JSON API：`/api/searchPlugins?search=<词>&max=20`、`/api/plugins/<id>`、`/api/plugins/<id>/updates?size=1`。PowerShell/curl 的本机 Schannel 初始化失败；没有以关闭证书校验绕过。

查询 `JMX` 返回 4 项、`JConsole` 0 项、`MBean` 1 项、`JMX Console` 1 项；`Java monitor` 返回 43 项并读取前 20 项，由此发现下面的小工具。一次 `JVM` 查询连接被服务器关闭。搜索索引/关键词/截取范围有限，**这不能证明不存在通用 IDEA JMX 控制台**。其中 JMX support #10872 是 TeamCity Server/Agent 指标暴露插件，排除为 IDEA 直接竞品。来源：[JMX API](https://plugins.jetbrains.com/api/searchPlugins?search=JMX&max=20)、[Java monitor API](https://plugins.jetbrains.com/api/searchPlugins?search=Java%20monitor&max=20)、[JMX support](https://plugins.jetbrains.com/plugin/10872-jmx-support)。

以下版本和 build 范围来自当日最新返回的发布记录；日期按 API UTC 毫秒转日期。

| 对象与证据入口 | 当日版本 / 更新时间 / 声明 build | 用户任务路径与可借鉴点 | 边界 |
|---|---|---|---|
| [Drozd](https://plugins.jetbrains.com/plugin/7527-drozd/versions/stable/16830) | 0.1 / 2014-08-06 / 133.0–201.0 | 配置 SSH 环境 → 自动发现远端 JVM → 用选定系统属性匹配 JVM；可配置轮询间隔/超时、环境别名。跨重启关联早已有先例。 | 历史管理插件；未核验当前 IDE 可运行。无许可证/源码链接，不复用。 |
| [JDK VisualGC](https://plugins.jetbrains.com/plugin/14557-jdk-visualgc/versions/stable/697775) | 2024.3.0 / 2025-03-14 / 243.* | Tool Window 看 GC；作者说明本地/远端 HotSpot、G1/ZGC，部分能力付费；运行并监控缩短步骤。 | 介绍声明目标至 JDK 20、JBR 17，不能推成新 JDK 支持；2022.2 指南要求 `jdk.internal.jvmstat` exports，提示内部 API 维护成本。 |
| [Memory Leak Detector](https://plugins.jetbrains.com/plugin/30931-memory-leak-detector/versions/stable/1056174) | 1.0.2 / 2026-05-25 / 233.0–263.* | 配远程 JMX → Memory Monitor → 趋势、200 点历史、GC 确认、增长提示。 | 发布说明中的 2026.3 是声明范围；本次未实测。文档的无认证/无 TLS 示例不用于 Beacon。增长不能直接证明泄漏；导出标为 coming soon。 |
| [Java Insight](https://plugins.jetbrains.com/plugin/29866-java-insight/versions/stable/1017201) | 1.0.5 / 2026-04-11 / 231.0+ | 调用链图、参数录制、远程运行时分析、持久化，另含热更新/Groovy。 | 属于更强侵入式诊断邻接方向；没有把描述中的“强大”当性能证据，未确认通用 MBean 浏览。 |
| [DevTomcat](https://plugins.jetbrains.com/plugin/30721-devtomcat/versions/stable/1171511) | 1.4.3 / 2026-09-15 / 251.0–262.* | 管理 Tomcat 运行配置/部署 → Services 状态与诊断；声明按需启用且将 JMX 与 RMI registry 绑定 loopback，错误按阶段给建议。 | 组件专用管理体验比空泛“健康分”更有用；不等于通用 JMX 控制台。GPL-3.0，不搬代码。 |

## 相邻能力：应协作而不是重复

| 对象 | 已核验路径 | 对 Beacon 的影响 |
|---|---|---|
| [IDEA Profiler](https://www.jetbrains.com/help/idea/profiler-intro.html)、[Live Charts](https://www.jetbrains.com/help/idea/cpu-and-memory-live-charts.html)（官方 2026.2 Help） | 已启动的应用自动出现 CPU/heap 图；IDE 外进程可从 Profiler 右键打开 Live Charts，含线程与非堆数据；悬停有时点值、可选时间范围。深度分析含 CPU/分配、线程转储、内存快照。 | 原生图表已成熟。Beacon 第一屏为通用管理与证据，不凭相同图表竞争。不能把 2026.2 文档全部能力说成本机 2025.1 已实测。 |
| [Spring Boot](https://www.jetbrains.com/help/idea/spring-boot.html)（官方 2026.2 Help） | 添加 Actuator → Run/Services → Beans/Health/Mappings/Environment；双击 Bean/映射跳声明；Environment 有版本相关脱敏和显式显示值流程。 | “从运行时数据回源码”与敏感数据提示值得保留；Spring Bean 不等于 JMX MBean，不混淆两套模型。 |
| [VisualVM Launcher](https://plugins.jetbrains.com/plugin/7115-visualvm-launcher/versions/stable/478330)（1.23.1-IJ2023.3，2024-01-31，233.0+） | [VisualVM 官方 IDE 集成](https://visualvm.github.io/idesupport.html)确认可与应用一起启动 VisualVM；[仓库变更记录](https://github.com/krasa/VisualVMLauncher/blob/master/CHANGELOG.md)记录 GoToSource 支持。 | 外部工具不一定与源码脱节，不能声称 Beacon 首创源码导航。Launcher 不是内嵌管理台。Marketplace 1.23.1 与仓库 changelog 标题 1.23.0 不一致，保留区别。 |
| [JVMs Manager](https://plugins.jetbrains.com/plugin/19464-jvms-manager/versions/stable/829590)（2.4.0，2025-08-15，252.0+） | View → Tool Windows → JVMs；树表找进程/子进程，看命令行、属性、uptime，再获取线程转储/类加载器/堆信息；支持打开 Thread Dump Analyzer。见[仓库](https://github.com/marcelkliemannel/intellij-jvms-manager-plugin)。 | 进程入口和诊断命令已被覆盖。Beacon 不默认导出其展示的全量系统属性/环境变量。 |
| [Arthas IDEA](https://plugins.jetbrains.com/plugin/13581-arthas-idea/versions/stable/1110225)（2.52，2026-07-17，222.3345.118+） | [仓库](https://github.com/WangJi92/arthas-idea-plugin)从编辑器方法构造 watch/trace/ognl 等；2.52 增加源码行探针命令。不能只读旧 README 而称它“仅复制命令”：[#154](https://github.com/WangJi92/arthas-idea-plugin/issues/154)已描述 tunnel-server 场景在 IDEA 内执行、多实例监听、栈跳源码和结果检索。 | 学习源码上下文减少手填；首版不承担 Arthas 的 agent、表达式执行、热替换和集群运维面。 |

## 专业标杆：按任务，而不是按功能数量

**JConsole（JDK 25 文档，官方）**：本地选同用户进程，Attach 可以启用管理 agent；远程输入 host:port 或 service URL 及认证，可用 SSL。MBeans 树 → 属性/操作/通知；订阅后按通知元数据阅读；线程过滤与锁环检测、图表 CSV 导出、连接图标断开/重连，已形成完整日常路径。文档直接指出 ObjectName key 顺序造成分组差异；Beacon 应按 canonical name 建索引，避免必须理解树层级才能搜到对象。文档有历史 GC/界面内容，不能据截图年代推断新版本实现。来源：[Using JConsole](https://docs.oracle.com/en/java/javase/25/management/using-jconsole.html)。

**VisualVM 2.2.2（2026-09-08，官方）**：IDE 启动集成、外部进程浏览、采样/堆/线程是成熟组合；源码可回 IDE。当前 release 明确运行软件范围 JDK 8–26；修复 heap comparison NPE 等说明复杂离线分析持续维护。此范围是 VisualVM 的，不能转嫁给 Beacon。来源：[发布记录](https://visualvm.github.io/relnotes.html)、[IDE 集成](https://visualvm.github.io/idesupport.html)、[排障](https://visualvm.github.io/troubleshooting.html)。

**JProfiler 16.2.1（2026-08-26，官方）**：agent 在目标内访问 MBean server，无需依赖远程 JMX 开放；树表递归展示 array/composite/tabular。简单类型及数组可编辑，composite/tabular 不编辑，操作结果复用同一种结构视图。选择过滤范围后拍 MBean snapshot，可加标签，保存进会话供离线查看。其复杂结果和“只收所选对象”是本项目的质量标杆；全面支持任意 Java 参数不是必要目标。来源：[MBean browser](https://www.ej-technologies.com/resources/jprofiler/help/doc/main/mbean.html)、[变更记录](https://www.ej-technologies.com/jprofiler/changelog)、[快照比较](https://www.ej-technologies.com/resources/jprofiler/help/doc/main/compare.html)。

**YourKit 2026.9（2026-09-15，官方）**：死锁视图展示线程、锁拥有者、锁对象及持续时间；可展开/复制栈。2025.3 已加入保存死锁到快照及 Frozen threads，并明确栈未变化的线程也可能只是在等待。agent telemetry 默认环形保留约 1 小时、采样周期可调；这是预先采集的历史，绝不是连接后恢复过去。来源：[2026.9 发布](https://www.yourkit.com/changes/2026.9/)、[死锁](https://www.yourkit.com/docs/java-profiler/latest/help/deadlocks.jsp)、[采集选项](https://www.yourkit.com/docs/java-profiler/latest/help/agent-startup-options.jsp)、[2025.3 说明](https://www.yourkit.com/changes/2025.3/)。

**JMC/JFR（官方仓库与发布计划）**：JMC 包括 JMX Console、JFR 文件可视化/自动分析，核心解析库可独立运行；因此后续优先集成文件交接或核心库，不自研全部分析器。查询时发布计划将 JMC 10.0.0 源码发布排在 2026-10-07，属于计划，不能称已发布；本轮没有选择 JMC 依赖或实测特定发行商二进制。来源：[OpenJDK 仓库](https://github.com/openjdk/jmc)、[发布计划](https://wiki.openjdk.org/spaces/flyingpdf/pdfpageexport.action?pageId=84672599)。

**Hawtio/Jolokia（官方滚动文档，版本限定 v3+/v4 架构及当前 5.x 入门说明，未安装）**：Jolokia 将 list/read/write/exec/search 等管理动作映射为 HTTP/JSON；Hawtio 在此之上按对象提供 JMX、Camel、Runtime 等视图，用户选择树对象时只显示相关能力。需要部署/连接 Jolokia agent 与其授权，不能拿 HTTP URL 当现有 RMI 的即插即用替代。工作区可限制加载域来降低目标负担。Beacon 后续可借鉴能力驱动的组件面板；首版标准 JMX 避免额外服务端部署。来源：[Hawtio 入门](https://hawt.io/docs/get-started.html)、[架构](https://hawt.io/docs/developers/architecture.html)、[插件](https://hawt.io/docs/plugins.html)、[限制 workspace](https://hawt.io/docs/configuration)、[Jolokia 协议](https://jolokia.org/reference/html/manual/jolokia_protocol.html)。滚动页面相互出现不同主版本用语，未将其等同于确定 release 号。

## 痛点证据及适用范围

| 证据 | 可以支持的结论 | 不可以支持的结论 |
|---|---|---|
| [Oracle JMX 管理文档（25）](https://docs.oracle.com/en/java/javase/25/management/monitoring-and-management-using-jmx-technology.html)，官方 | 本地权限、认证/access file、SSL/truststore、RMI 配置是连接的真实条件；前端应按阶段解释和给建议。 | 自动关闭认证/TLS会成为合理排障方案；“读取”绝对无副作用。 |
| [Arthas #154](https://github.com/WangJi92/arthas-idea-plugin/issues/154)，2024-09 的已关闭扩展说明/个案 | tunnel 场景中切换浏览器、多实例执行、搜索输出有步骤成本；已有集成尝试解决。 | 当前 Arthas IDEA 仍只能复制命令；所有用户都需要多实例。 |
| [JVMs Manager changelog](https://github.com/marcelkliemannel/intellij-jvms-manager-plugin/blob/main/CHANGELOG.md)，官方发布记录 | 2.3.0 修复 EDT/已释放容器，2.4.0 修复新 IDEA 线程分析入口；需验证 EDT、dispose 与升级。 | 当前版本仍有相同冻结/释放错误。 |
| [VisualVM Launcher issues](https://github.com/krasa/VisualVMLauncher/issues)，2022–2023 报告 | 存在权限、无法找到待打开目标、Run/Debug 标题等历史个案，可作为测试输入。 | 这些问题在最新版可复现或很普遍；尚未阅读完整复现细节的不定根因。 |
| [IJPL-232215](https://youtrack.jetbrains.com/projects/IJPL/issues/IJPL-232215/Enabled-WSL2-Mirrored-Networking-causes-JMX-port-conflict-due-to-port-forwarding)，跟踪器显示 Fixed / Available 2026.2 | WSL mirrored 端口空间是需要专门验证的边界；应记录已修复状态。 | 2026.2 仍存在该缺陷。 |
| [IDEA-380578](https://youtrack.jetbrains.com/projects/IDEA/issues/IDEA-380578/Spring-actuator-unreachable-from-docker-run-targets)，用户 Docker Compose 报告，Spring plugin 252.26830.84 | JMX registry 端口 TCP 可达不代表后续 RMI 地址可达。 | 所有容器/新版本 IDEA 失败；单一报告证明已定位普遍根因。 |
| [JProfiler 16.2.1 release](https://www.ej-technologies.com/jprofiler/changelog)，官方 | 已修复单一 JMX telemetry 错误停止后续记录等问题；提示应隔离采集项失败。 | 最新 JProfiler 仍有相同缺陷。 |

## 技术与产品边界

- `ThreadMXBean` 在 JDK 25 明确只覆盖平台线程；`getAllThreadIds` 不含虚拟线程，锁环检测不发现包含虚拟线程的循环。`null`/`-1` 可表示线程结束、不存在、虚拟线程或能力未启用；不能显示成零。锁环检查可能昂贵，不能高频无限轮询。来源：[JDK 25 API](https://docs.oracle.com/en/java/javase/25/docs/api/java.management/java/lang/management/ThreadMXBean.html)。后续虚拟线程需单独验证 JFR / `jcmd Thread.dump_to_file` 路径。
- 从一个 MBean 的类型元数据构造编辑器：首版明确接受 primitive/wrapper、String 等白名单；未知类只读解释，不反射调用任意字符串构造器。复合结果递归展示并有深度/项数/文本上限。写入/操作显示目标、签名和参数；超时意味着结果未知，禁止自动重试。（产品/工程判断，参考 JProfiler 类型边界。）
- 只采实际需要的标准指标和当前选中属性，显示采集时间、来源和单位；snapshot 写采集窗口/进程启动标识/缺失项/截断/备注。默认不采系统属性、环境变量、全量业务 MBean 值。泛化“脱敏”不能保证业务字符串无敏感信息。（产品判断。）
- 断线后保留最近结果但明显标陈旧；手动重连核验进程身份；进程 PID 可复用，首版不自动按旧 PID 恢复写权限。跨重启关联留作独立功能验证。（产品判断，Drozd 已说明此任务有先例。）
- 远程网络与 Attach 必须在后台，有并发/排队/采样历史上限、迟到结果隔离、项目关闭释放。取消 UI 等待不等于底层 RMI 已退出；针对慢服务单独做故障实验。安全存储、平台版本、构建方案见 [技术决策](decisions.md)，不把插件、IDE runtime、被测 JVM 三种版本混写。

## 首版与后续路线（研究建议，不冒充已完成）

1. 首版：可安装 Tool Window，本地 JVM/远程 URL 连接，目标身份与状态；指标、MBean 搜索/标量校验/复杂结果/显式操作/通知，平台线程现场，保存/重开。真实独立 fixture 覆盖失败、权限、断线、重复连接和资源上限；若本轮范围不足，保留明确限制。
2. 下一阶段：收藏/监视单个数值与跨现场比较；证书配置体验、重连策略；可准确匹配的源码导航；IDE Run 配置身份关联。用任务测试检验“找并修改一个日志级别”“保存并解释一次卡顿现场”的步骤与理解率。
3. 有需求证据再做：JFR 文件交接/分析、虚拟线程覆盖、SSH/Jolokia/容器、多实例、框架专属视图、Arthas 深度诊断。优先复用成熟引擎，逐项评估目标负担和部署权限。AI 不是首版依赖，不上传现场。

## 许可证与复用决定

本轮只借鉴任务设计，**没有复制竞品代码、图标、截图或界面资源**。源码引用前还需核对具体文件及第三方依赖，Marketplace 许可证字段不能替代逐文件检查。

| 对象 | 本轮核验结果 / 入口 | 决定 |
|---|---|---|
| JVMs Manager / VisualVM Launcher / Arthas IDEA | 仓库标 Apache-2.0：[JVMs](https://github.com/marcelkliemannel/intellij-jvms-manager-plugin)、[Launcher](https://github.com/krasa/VisualVMLauncher)、[Arthas](https://github.com/WangJi92/arthas-idea-plugin) | 首版不引入代码。 |
| JDK VisualGC / Java Insight / Memory Leak Detector | Marketplace `urls.licenseUrl` 分别为 Apache-2.0 / Apache-2.0 / MIT；源码页部分无法读取：[VisualGC](https://github.com/beansoft/visualgc_java8/tree/master/visualgc_idea)、[Java Insight](https://github.com/NingaSekiro/plugin)、[Memory](https://github.com/pwang313-canada/intellij-plugin/tree/main/memory-leak-detector) | 仅记录发布者声明；未完成文件级核验，不复用。 |
| Drozd | Marketplace 未提供许可证/源码 | 不复用。 |
| DevTomcat | Marketplace GPL-3.0 链接；[仓库](https://github.com/Gezu-t/devtomcat) | 不复用。 |
| VisualVM / JConsole(OpenJDK) | VisualVM release 页 GPLv2 + Classpath Exception；JConsole 本轮仅读 Oracle 文档，未审核 OpenJDK 文件许可 | 不搬 UI 或实现。 |
| JMC | [仓库](https://github.com/openjdk/jmc)声明 UPL 1.0 或 BSD-style 可选 | 后续引入核心库前审核所选发行物、依赖和 notices。 |
| Hawtio / Jolokia | 仓库 Apache-2.0：[Hawtio](https://github.com/hawtio/hawtio)、[Jolokia](https://github.com/jolokia/jolokia) | 仅借鉴，首版不分发。 |
| JProfiler / YourKit | 商业产品，本轮只读公开官方文档 | 不复制代码/资源、不分发二进制或购买服务。 |

`JVM Beacon` 仍为开发名，未做商标、Marketplace 名称、plugin ID 或发布者身份核查。

## 2026-09-24 路线复核：锁、JFR 与虚拟线程

以下为本日实际访问的 **JDK 21 官方文档声明**，用于核对后续技术可行性，不代表 Beacon 已实现或完成目标实测。

- [ThreadMXBean](https://docs.oracle.com/en/java/javase/21/docs/api/java.management/java/lang/management/ThreadMXBean.html)：只管理平台线程，可查询 monitor/ownable synchronizer 死锁；查询可能昂贵，且不覆盖包含虚拟线程的环。后续等待链必须呈现采集范围与未知 owner，不能将普通 WAITING 状态当作死锁。
- [FlightRecorderMXBean](https://docs.oracle.com/en/java/javase/21/docs/api/jdk.management.jfr/jdk/management/jfr/FlightRecorderMXBean.html)：提供配置、创建、启动/停止、记录流读取与关闭；duration 可限制时间，maxAge/maxSize 的保留选项依赖 disk=true，默认可能不设上限。destination 写在被监控 JVM 所在机器，不能冒充已下载到 IDE。Beacon 拟先做有界录制和流式下载闭环，再做事件分析；服务端权限、连接中断与资源清理仍需实验。
- [Virtual Threads](https://docs.oracle.com/en/java/javase/21/core/virtual-threads.html)：提供 JDK 21 虚拟线程的诊断路径说明；拟独立评估转储与 JFR，不将 JDK 21 的行为外推到所有后续版本。

本次访问 JEP 444 页面和 ThreadInfo 的 JDK 21 API 页面返回工具 Internal Error，未将它们计为成功读取的证据；平台线程边界以已成功读取的 ThreadMXBean 页为准。具体排期和本地代码缺口见 [决策末节](decisions.md#061-后路线复核2026-09-24)。

## 2026-09-24 补查：远程简写与单属性追踪

- **官方文档 / Java 21**：[JConsole 连接说明](https://docs.oracle.com/en/java/javase/21/management/using-jconsole.html)同时描述 `hostname:port` 与完整 JMX URL。简写转换为标准 `/jmxrmi` registry binding 是本次实现决策；自定义 binding 仍输入完整 URL。依据 [JMXServiceURL API](https://docs.oracle.com/en/java/javase/21/docs/api/java.management/javax/management/remote/JMXServiceURL.html)在本地解析 URL；不在 EDT 做 DNS，简写 IPv6 要求方括号。查询日期为本节日期，官方文档不替代测试。
- **产品判断**：用户明确要求直接填写 host/port，因此优先于新增连接协议。解析后的地址可见，等价标准 URL 共用凭据键，不通过改写地址改变认证、TLS 或目标授权。跨真实网络和 NAT 行为尚不能由 loopback 实测推断。
- **产品假设**：在刚找到的数值属性上直接启动追踪，能减少切出 IDEA 或反复刷新的步骤。本版只跟踪一个明确选择的 getter，记录精确值、独立窗口和间断；getter 开销未知，必须明确启动，失败暂停。操作成本改善尚未经过外部用户研究，不宣称优于所有成熟工具。
- **实际测试**：对应 0.3.0 的独立认证 JVM、数值边界和界面结果见 [验证记录](validation.md)；不把此页的设计依据当作已通过 GUI 的证据。

## 2026-09-24 补查：复杂返回值阅读

- **官方 API 声明 / JDK 21**：[CompositeData](https://docs.oracle.com/en/java/javase/21/docs/api/java.management/javax/management/openmbean/CompositeData.html) 提供命名字段；[TabularData](https://docs.oracle.com/en/java/javase/21/docs/api/java.management/javax/management/openmbean/TabularData.html) 的行是 CompositeData，索引由 TabularType 指定，不能以遍历序号宣称跨采集的行身份。两页均在本节日期实际访问。
- **官方 SDK / 页面于本节日期访问**：[DialogWrapper](https://plugins.jetbrains.com/docs/intellij/dialog-wrapper.html) 是平台原生对话框入口。实际编译与 GUI 目标仍为 251.26927.53，并非文档更新意味着扩展支持版本。
- **本项目选择**：0.4.0 在后台把结果转为不可变、有节点/字符总量限制的结构；主工作台保留文本预览，独立对话框承载结构树和顶层表格。展开/搜索不重读目标；不调用任意对象的 toString。对字段层级和长返回值更易读是产品假设，尚无外部可用性研究；本机实现与 GUI 证据单独记录在验证文档。未复用竞品代码或资源，未增加依赖。

## 锁等待链能力核验（2026-09-24；目标 Java 21）

- **官方 API 文档，已访问**：[ThreadMXBean (Java 21)](https://docs.oracle.com/en/java/javase/21/docs/api/java.management/java/lang/management/ThreadMXBean.html)：管理平台线程，线程 ID 只在其生命周期内唯一、可能复用；线程信息读取与死锁检测是不同操作。非原子采样和平台覆盖限制保留在产品中，不把 ThreadMXBean 当作虚拟线程全貌。
- **本机官方发行 JDK 源码，实际读取**：Corretto 21.0.9 的 lib/src.zip 中 java.management/java/lang/management/ThreadInfo.java，getLockOwnerId 的 Javadoc 明确 -1 表示没有正在等待的对象或对象未由任何线程持有。Java 21 在线 [ThreadInfo 页面](https://docs.oracle.com/en/java/javase/21/docs/api/java.management/java/lang/management/ThreadInfo.html#getLockOwnerId()) 本次访问失败（超时/工具错误），未把该页面标成成功读取。此处依据本机附带源码注释核验 API 语义，没有搬运源码实现。
- **本项目验证**：真实认证 loopback 子 JVM 观察到 waiter → bridge → owner 三段 ID 关系，释放后比较状态与 owner 变化；v2 重开后关系保留。用重复名称、未知 owner、缺失记录、观察环、超长路径、非法 ID 和 v1 文件做单元边界测试。具体执行结果与 GUI 独立记录在验证文档。
- **产品判断**：先呈现选中路径与 owner 栈，比全量节点图更适合 IDEA 有限空间；这是本项目假设，不宣称已完成外部可用性研究。无新增依赖、竞品代码或视觉资源复用。

## 连接配置持久化补查（2026-09-24）

- **官方 SDK 文档，实际访问**：[Persisting State of Components](https://plugins.jetbrains.com/docs/intellij/persisting-state-of-components.html)，页面日期 2026-04-20。应用 service 的 PersistentStateComponent 及 XML 存储适合有结构的配置；本项目用非漫游存储，未把连接端点写入项目。当前文档不代替 251 编译/Verifier/实际重启验证。
- **官方 SDK 文档，实际访问**：[Persisting Sensitive Data](https://plugins.jetbrains.com/docs/intellij/persisting-sensitive-data.html)，页面日期 2026-07-30。PasswordSafe get/set 可能阻塞，不能放 EDT；用户名等元数据与密码分别管理。文档还指出 2025.3 之前 Remote Development 后端存在明文存储边界；本项目实际目标仅 Windows 桌面 2025.1.3，不承诺该远程开发部署的凭据保护。
- **官方 CLI 文档，实际访问**：[gh repo create](https://cli.github.com/manual/gh_repo_create)，核对 private/source/remote/push。用户明确指定现有账号和私有仓库后，使用现有 gh keyring 登录，创建并推送 rainism0329/JVM-Beacon；未新建令牌、公开仓库或发布 Release。CLI 查询 isPrivate=true 属实际运行证据。
- **本项目判断**：远程命名配置、最近成功项和显式重连有望减少重复输入；没有外部用户量化研究。真实认证 JMX 重连、XML 序列化及迟到结果测试分别提供技术证据，不能据此声称所有网络拓扑或密码 GUI 均通过。

## 时间线语义补查（2026-09-28，0.9.0）

证据类型：本日实际访问的 JDK 21 官方 API；适用语义为被测 JDK 21，不代替其他 VM/版本实测。无代码或界面资源复用。

- [OperatingSystemMXBean.getProcessCpuLoad](https://docs.oracle.com/en/java/javase/21/docs/api/jdk.management/com/sun/management/OperatingSystemMXBean.html#getProcessCpuLoad())：近期进程 CPU 使用，范围 0–1（插件乘 100），包含 JVM 内部和应用线程；1 表示所有 CPU 全时用于该 JVM，负值表示不可用。官方未把时间窗固定为客户端采样周期。因此 UI 标近期报告负载，端点差是百分点，不按此计算累计 CPU 时间。
- [GarbageCollectorMXBean.getCollectionTime](https://docs.oracle.com/en/java/javase/21/docs/api/java.management/java/lang/management/GarbageCollectorMXBean.html#getCollectionTime())：近似累计采集经过时间，单位 ms，未知为 -1；即使次数增加，时间也可能因精度保持不变。插件现有采集按 collector 求和。因此曲线保留累计意义，不把其差值声称为暂停总时长、暂停百分比或根因。计数回退可能来自重置/collector 变化，仅作不确定性说明。

产品判断（推断，待用户验证）：四轨共享采集窗口和检查游标能减少反复切换单指标的步骤；区间保留可帮助复盘实际看到的变化。它不代替 JFR 的事件级证据，也不保证更高诊断准确率。

## JFR：2026-09-28 补查

证据类型为官方 API/产品文档，适用 JDK 21；没有复用实现代码或竞品资源。使用 JDK 已提供的 API，不引入 JMC 二进制依赖，不暗示其许可证适用于本项目发布。

- [FlightRecorderMXBean](https://docs.oracle.com/en/java/javase/21/docs/api/jdk.management.jfr/jdk/management/jfr/FlightRecorderMXBean.html)：先配置 duration/maxAge/disk/maxSize，再启动；远程 copyTo 写目标文件，故选 stopped recording 的 openStream/readStream/closeStream 下载。并行录制可能影响所采事件；权限由服务端决定。客户端取消不是服务端停止的证据。
- [RecordingInfo](https://docs.oracle.com/en/java/javase/21/docs/api/jdk.management.jfr/jdk/management/jfr/RecordingInfo.html)：duration/maxAge 为秒，开始/停止时间为 epoch ms。初次实测因误当毫秒失败，修正后验证 5 秒自动停止。
- [Oracle JDK Mission Control](https://www.oracle.com/java/technologies/jdk-mission-control.html)：专业 JFR 分析入口。此轮核验产品定位，不宣称实测 JMC GUI。种子 `https://docs.oracle.com/en/java/javase/21/jfapi/flight-recorder.html` 抓取失败，未将其作为已读依据。

项目判断：先提供可控录制和可带走的证据，再开发采样栈视图。可排序/搜索的事件库存有助于快速确认实际采到了哪些事件，不能替代专业分析。价值仍待外部用户验证。实际自有 JVM / GUI 证据分别见 validation 和 gui-validation，不由官方声明推导本插件已通过。

### 采样栈语义补查（2026-09-28，0.11.0）

- **官方 API，JDK 21，已访问**：[RecordedStackTrace](https://docs.oracle.com/en/java/javase/21/docs/api/jdk.jfr/jdk/jfr/consumer/RecordedStackTrace.html) 提供栈与截断标记；文档并未明确帧顺序，因此另用自有子 JVM 的已知递归调用验证 leaf-first，不能把顺序当作查到的文档声明。
- **官方 API，JDK 21，已访问**：[RecordedFrame](https://docs.oracle.com/en/java/javase/21/docs/api/jdk.jfr/jdk/jfr/consumer/RecordedFrame.html) 的行号/BCI 可以缺失，Java native 方法仍属于 Java frame；[RecordedMethod](https://docs.oracle.com/en/java/javase/21/docs/api/jdk.jfr/jdk/jfr/consumer/RecordedMethod.html) 给出所属类、描述符和 hidden 信息，不提供 SourceFile 属性。[RecordedThread](https://docs.oracle.com/en/java/javase/21/docs/api/jdk.jfr/jdk/jfr/consumer/RecordedThread.html) 的记录内 ID 与 Java thread ID 分开保留。
- **官方源码元数据，jdk21u master，访问日版本快照而非固定发行 tag**：[metadata.xml](https://raw.githubusercontent.com/openjdk/jdk21u/master/src/hotspot/share/jfr/metadata/metadata.xml) 两种采样事件均使用 sampledThread，不用 eventThread 推断采样线程。NativeMethodSample 描述在 native 中观察线程状态。该文件声明 GPLv2；仅核对数据定义，没有复制实现或资源。尝试抓取 recorder/stacktrace/jfrStackTrace.cpp 与 internal/consumer/StackTrace.java 失败，不作为已读证据。
- **实际测试，本机 Corretto 21.0.9、认证 loopback 自有 fixture**：8 秒 profile 文件含 ExecutionSample=4、NativeMethodSample=388。JDK jfr print 在 native 栈里读到 FileInputStream.readBytes、Net.accept，说明 native 栈不能直接解释为 CPU 执行耗时；范围限于本次文件。生产入口仅识别两个明确事件名，确定性转换测试的自定义事件不伪装成真实 CPU 采样。
- **产品/实现判断**：分开 event kind，宽度按表示样本计数，线程筛选只针对已保留线程；截断根、丢弃数量、扫描窗口明显可见。原生 Swing 自绘并配键盘调用树，无新依赖或竞品图标。减少切换工具的价值仍是待验证假设，暂不宣称替代完整 profiler。源码候选需精确类+descriptor+方法所属行，再让用户确认未核验版本/loader 关系。

## 0.12.0：GC / allocation 语义补查（2026-09-28）

适用基线 JDK 21，插件平台继续 IC/IU 2025.1.3，无新增第三方依赖或竞品资源。

- 官方定义：[OpenJDK jdk21u metadata.xml](https://raw.githubusercontent.com/openjdk/jdk21u/master/src/hotspot/share/jfr/metadata/metadata.xml)。GarbageCollection 的 duration 与 sumOfPauses / longestPause 分离；GCPhasePause 与多个嵌套 Level 事件独立。设计推断：仅绘顶层暂停，不将周期当暂停、不将嵌套事件重复求和。
- 同一官方 metadata 为 ObjectAllocationSample.weight 标注 bytes 与统计分配压力语义；TLAB、outside-TLAB 的 allocationSize 是另一些事件，不能直接混合。产品只做保留权重占比，不估计单对象大小、存活堆、速率或泄漏结论。[DataAmount JDK 21 API](https://docs.oracle.com/en/java/javase/21/docs/api/jdk.jfr/jdk/jfr/DataAmount.html) 用于校验单位注解。
- 实测证据：独立 Corretto 21.0.9 / SerialGC / 64 MiB 堆 fixture 生成录制，再由 RecordingFile 原字段独立累加与产品模型比较。具体最终次数和 GUI 证据见 validation。并未实测所有收集器/JDK。
- 访问失败：[JDK-8307488](https://bugs.openjdk.org/browse/JDK-8307488) 返回 403；gcTraceSend.cpp raw 路径 cache miss。未将它们当作已阅读证据。上述 metadata 属 GPLv2，仅核对事件协议，自行实现，没有复用其代码/资源；本项目开发许可证状态不因此改变。

用户价值假设：在同一 IDE 内从录制直接定位 GC 时刻和高权重类，减少工具切换；尚无正式用户研究证明效率提升。真实暂停字段和明确遗漏比泛化健康分更可核验，因此优先做此范围，堆关联、分配栈与锁事件继续排后。
