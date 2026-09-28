# GUI 验证记录：0.1.2—0.14.0

最近日期：**2026-09-28**，时区 Asia/Shanghai（UTC+08:00）。这是部分真实界面流程的观察记录，**不代表全部 GUI 验收通过**。自动测试、安装包及兼容性检查另见 [验证与接续状态](validation.md)。

## 0.14.0：统一时间区间与事件聚焦

最终包 **16:41:42** 构建，**16:42:28.311—16:47:09.116** 在独立官方 IC 2025.1.3 / JBR 21 / Windows 运行；1388×974、125%。ZIP 内与实际加载 JAR 哈希一致，runIde exit 0、会话 ERROR 0，详情见 validation。以下均来自布局修正后的最终包；分析全程未连接业务目标。

- 从原生文件选择器打开自有双阶段 range.jfr，[FULL](../build/reports/ui-0.14.0/full.png) 为 321/321 events、133,076 bytes、EOF。[Time range 对话框](../build/reports/ui-0.14.0/dialog.png)使用 UTC 起点和相对秒数；键盘 Tab 切换输入。应用 B 阶段 `[0.5902413, 0.98413470)` 后，[库存](../build/reports/ui-0.14.0/range.png)为 196/321，allocation 171、ExecutionSample 13、GC 两类各 2。
- 切[采样栈](../build/reports/ui-0.14.0/stacks.png)为 13 represented samples；切 [GC](../build/reports/ui-0.14.0/memory.png)为 4 events / 17 allocation classes，顶部 RANGE 一致。双轨标签完整，表格可见两行并可滚动；不是全部小窗口验收。
- [选 GC #4](../build/reports/ui-0.14.0/gc-select.png)读到 426,200 ns；按 Focus event ±100 ms 后范围为 `[08:29:32.503833200Z, 08:29:32.704259400Z)`，[结果](../build/reports/ui-0.14.0/gc-focus.png)为 106/321、2 GC events，选择清除、表内完整时长仍 0.426 ms。Full recording [恢复](../build/reports/ui-0.14.0/restored-memory.png)321/321、10 GC events / 18 classes。
- [完整等待页](../build/reports/ui-0.14.0/waits-full.png)13 events / 5 hotspots。All filtered events 后双击 main 的 Monitor entry，[详情](../build/reports/ui-0.14.0/wait-event.png)为 120,518,100 ns、历史 previousOwner=beacon-range-owner、两帧原栈。Focus 后[视图](../build/reports/ui-0.14.0/wait-focus.png)为 16/321、5 waits；原 Monitor entry 仍为 120.518100 ms。[Coverage](../build/reports/ui-0.14.0/coverage.png)显示相同 UTC `[08:29:32.124861800Z, 08:29:32.445379900Z)`、完整时长语义及计数。
- 切原生 [Light](../build/reports/ui-0.14.0/light.png)，颜色/字体随主题更新。重新编辑 From=-1 后 Apply 被拒绝，[原生错误提示](../build/reports/ui-0.14.0/invalid-range.png)可见且旧范围保留；Cancel 后 Full recording [恢复 321/321 与 13 waits](../build/reports/ui-0.14.0/restored.png)。展开 [capture controls](../build/reports/ui-0.14.0/capture-controls.png)仍能读未连接状态，没有隐式启动录制。

前一候选工具栏挤压 GC 标签与表格，最终版已合并操作区并给时间线设置最小高度；截图只保存最终包。当前尺寸仍需滚动长表、深栈和长报告。未逐项 GUI 复测：所有非法输入、B 范围等待/分配详情、已应用筛选保留、外部改文件、迟到响应、空/坏文件、复制回读、源码导航、远程录制按钮、IU GUI、长时/极限文件；其中若干由核心/组件测试覆盖，不能称为 GUI 通过。仅关闭本项目自有沙箱。

## 0.13.0：等待热点、事件与源码

最终包 **15:47:10** 构建；**15:48:00.006—15:53:41.040** 运行于独立 IC 2025.1.3 / JBR 21 / Windows，1388×974、125%。实际加载 JAR 与 ZIP 内一致，runIde exit 0，ERROR 级日志 0；哈希及日志见 validation。下面均使用此最终会话截图，不采用前两次列宽候选截图。

- 打开独立 fixture 的 waits.jfr，[库存](../build/reports/ui-0.13.0/inventory.png)为 15 events / 119,319 bytes / EOF；3 个 custom 探针只在库存中显示，等待分析严格只取标准 12 events。[Light 热点](../build/reports/ui-0.13.0/light.png)为 8 组，Sum/Max 的 ms 单位与计数表头可读。
- 输入 beacon-wait，再 Enter 应用，[筛选](../build/reports/ui-0.13.0/filter.png)为 5 events / 5 hotspots。点击 Sum 列后[升序排序](../build/reports/ui-0.13.0/sorted.png)；选 142.229700 ms 的 Gate 组并按 Enter，下钻到 [beacon-wait-entrant 单事件](../build/reports/ui-0.13.0/drill.png)。排序后的 model/view 映射正确。
- 双击事件打开[精确证据与栈](../build/reports/ui-0.13.0/event.png)：142,229,700 ns、实际起止、historical previousOwner=beacon-wait-owner，5 个 leaf-first 帧。选择 enterGate:48 后 Find source candidate，出现[类/descriptor/路径确认](../build/reports/ui-0.13.0/source-confirm.png)，确认后实际打开 [WaitRecordingFixture.java 第 48 行](../build/reports/ui-0.13.0/source-editor.png)。项目使用 Corretto 21 SDK 和当前 fixture 源码复制件，不保证业务录制源版本/loader 一致。
- 切 IDEA 原生 [Dark](../build/reports/ui-0.13.0/dark.png)，主题与字体更新，无固定白分隔；[Coverage](../build/reports/ui-0.13.0/coverage.png)保留 active query/kind、当前文件窗口和各类遗漏统计。长报告可以滚动，不把未显示部分当作已逐行 GUI 验收。
- 换成只有一个 custom 事件的合法 no-sampling.jfr，查询重置、新文件窗口更新，热点/事件清空，显示[明确无匹配状态](../build/reports/ui-0.13.0/empty.png)。再开自有 67 字节 invalid.jfr，显示 [IO 文件错误](../build/reports/ui-0.13.0/invalid.png)，等待页[清空并禁用报告/下钻/Inspect](../build/reports/ui-0.13.0/cleared.png)。原文件均未修改。

候选修正是表格首轮默认宽度导致 kind/时长表头截断，随后调整列宽和简短 Sum 单位标题，再修正 Events 计数列；最终包重新构建、125 测试、IC/IU Verifier，并走通以上流程。当前尺寸直接可见约 4 行；部分名称与 Monitor entry 单元格仍可能省略，使用原生展开、调整列宽或详情读取。没有宣称所有尺寸/缩放都完整展示全文。

未实操：完整 kind/搜索无匹配矩阵、剪贴板回读、全部对话框暗色/键盘/无障碍组合、限额大文件 GUI、IU GUI、虚拟线程其他 JDK 事件覆盖、旧远程录制全部按钮与长时间资源验收。缺栈、取消、迟到结果等按核心/组件测试层级报告。正常关闭的是本项目自有测试沙箱，未关闭日常 IDE。

## 0.12.0：GC 与分配压力

最终包见 validation（14:44:36 构建）；**14:45:26.505—14:52:41.318** 运行于独立官方 IC 2025.1.3 / JBR 21 / Windows，窗口 1388×974、125%。包内与加载 JAR 哈希一致，runIde exit 0、会话 ERROR 0。以下截图均来自此最终会话，不使用较早候选截图。

- 打开脚本真实 `memory.jfr`（118,545 bytes / 460 events），[Dark GC 双轨](../build/reports/ui-0.12.0/gc-dark.png)保留 16 cycles / 16 pauses。点击末尾周期标记后定位 [GC #16 / SerialOld / System.gc()](../build/reports/ui-0.12.0/gc-select.png)，详情为 7,069,500 ns。聚焦详情再 Ctrl+End 可读[暂停字段](../build/reports/ui-0.12.0/gc-detail.png)；此 SerialGC 样本周期和暂停相同，不据此外推其他收集器。
- [分配页](../build/reports/ui-0.12.0/allocation-dark.png)显示 7 类、精确字节权重和占比条。输入 java.lang.String 后仅保留该行，份额仍 [0.245%](../build/reports/ui-0.12.0/filter.png)，[选中详情](../build/reports/ui-0.12.0/allocation-selection.png)为 3 samples / 710,312 weight，窗口可横向/纵向滚动。
- 切换 IDEA 原生 [Light](../build/reports/ui-0.12.0/light.png)，字体/背景更新。搜索不存在名称，[旧详情清除且复制所选按钮禁用](../build/reports/ui-0.12.0/no-match.png)。[Coverage](../build/reports/ui-0.12.0/coverage.png)继续呈现全文件统计与实际事件窗口，不因搜索改变。
- 重新打开仅含一个自定义事件的合法 no-sampling.jfr，GC/分配均清空，显示[无匹配事件与当前文件窗口](../build/reports/ui-0.12.0/empty.png)。打开自有无效测试文件时显示 [IO 文件错误](../build/reports/ui-0.12.0/invalid.png)，[GC 页面清空、复制禁用](../build/reports/ui-0.12.0/cleared.png)。

候选修正：第一轮 125% GUI 中时间线/详情挤掉 GC 表格；最终版改成原生 OnePixelSplitter 与紧凑双轨。此窗口直接可见约 2 行 GC 表格，更多行使用滚动；详情长文本仍需滚动，未宣称已覆盖所有窄窗口/缩放。排序和 missing/zero 由组件测试覆盖，本次未人工遍历所有排序、复制、键盘组合，也未复测旧录制全流程或 IU GUI。

## 0.11.0：采样调用树与火焰图

2026-09-28，最终包 **13:14:31**，SHA-256 `9e17d7e4059cc8dfefb83172104bae8b384366cf185cf1e9688f8d112cf6023e`。IC-251.26927.53/JBR 21/Windows，1388×974、125%。最终沙箱加载 JAR 与 ZIP 内一致，加载/退出日志和校验见 validation。所有下述文件来自本项目的独立测试进程，GUI 分析全程未连接业务目标。

- 最终包 **13:15:16.938** 加载。从 Open local .jfr 打开 `build/examples/jfr-20260928-125558-062/capture.jfr`：360,692 bytes、4,637 events、EOF，[库存](../build/reports/ui-0.11.0/inventory.png)。Sampled stacks 默认 Java=4，[Light](../build/reports/ui-0.11.0/light.png)，切换原生 [Dark](../build/reports/ui-0.11.0/dark.png) 后颜色与字体更新，无固定白分隔。深路径使用纵向滚动，当前尺寸约能直接看到 3 层；未宣称所有窄窗口均可用。
- 切 NativeMethodSample 再 Apply filters，树与图变为 **388** 样本，[native 图](../build/reports/ui-0.11.0/native.png)。选择 98 样本路径显示 25.26%，[Zoom 后](../build/reports/ui-0.11.0/native-zoom.png) 分母不变。向下滚动后 Reset 恢复 388 根节点，[复位](../build/reports/ui-0.11.0/reset.png)。
- 切回 ExecutionSample，选择 `beacon-fixture-cpu-pulse · Java #33 / JFR #33` 再 Apply，**3** 样本，[线程筛选](../build/reports/ui-0.11.0/filter.png)。输入 cpuPulse 后仅高亮，滚动找到 `cpuPulse:243`，[选中帧](../build/reports/ui-0.11.0/highlight.png)显示 3 inclusive / 0 self；此行实际在 await 附近，不能从方法名或样本比例宣称循环占 CPU 时间。Coverage 保留 kind、thread、全 kind 缺失/遗漏与时窗，[范围](../build/reports/ui-0.11.0/coverage.png)。
- 验收项目配置 Corretto 21 SDK，源码为当前 fixture 的复制件。Find source candidate 检查类、`(Ljava/util/concurrent/CountDownLatch;)V`、方法所属行后出现[确认](../build/reports/ui-0.11.0/source-confirm.png)，确认后确实打开 [DemoApplication.java 第 243 行](../build/reports/ui-0.11.0/source-editor.png)。版本/loader 未核验提示保留。较早候选沙箱没配项目 SDK 时明确未匹配，没有猜测导航；不将候选失败当最终成功证据。
- Call tree 显示与图相同 inclusive/self 路径；鼠标聚焦根后按右方向键选择子路径，详情随之更新，[键盘证据](../build/reports/ui-0.11.0/keyboard.png)。这不是全套屏幕阅读器/快捷键验收。
- 自有 `StackRecordingFixture` 产生 **111,689 bytes** 的 no-sampling.jfr，只有一个 beacon.StackConversionTest 自定义事件；切图显示 **0 represented samples / n/a share** 与明确[无采样状态](../build/reports/ui-0.11.0/empty.png)，不继承旧树。不把自定义事件当 CPU 采样。
- 故意损坏的本地 invalid.jfr 显示 [I/O/格式失败](../build/reports/ui-0.11.0/invalid.png)，旧图和操作均[清除](../build/reports/ui-0.11.0/cleared.png)。原文件未修改。**13:22:54.437** 正常退出沙箱，runIde exit 0，最终会话 ERROR 级日志 0；平台 WARN（主题、索引等）保留。

候选中发现并修复：长状态/详情挤掉图表；从深层滚动后 Zoom 留在旧滚动位置。最终包增加可收起 Capture controls、详情滚动区域和回根行为并用以上新截图验收。未将 13:00/13:06 候选包截图写成最终包证据。

未实操：本轮重新录制的所有 GUI 按钮、连接状态下收起/展开控制的完整矩阵、复制内容的剪贴板回读、无匹配事件搜索、超大/限额文件 GUI、Ultimate GUI、窄窗口/其他缩放、无障碍全矩阵、源版本/loader 冲突、完整 attached-source 成功流程。对应组件/核心测试仅在其层级报告。

## 0.10.0：Flight Recorder 与本地事件库存

2026-09-28，Windows / IC-251.26927.53 / JBR 21 / 1388×974 / 125%。最终 ZIP 为 **12:17:26** 生成，SHA-256 `274ecd937767def4146ef7053d4ce5822108a111532530cc4afa6b9bc4990cb7`；最终沙箱 JAR 与 ZIP 内相同，核对见 validation。

- 最终包 **12:18:25.984** 加载。启动最长 300 秒的自有本地 fixture **PID 7984**，启动时间 **12:18:10.843**，使用自有 `jcmd 7984 ManagementAgent.start_local` 初始化本地端点，没有远程端口。GUI 选择准确 PID，Read-only 默认开启，Check / refresh 报告 default/profile；随后显式关闭只读并打开 [录制确认](../build/reports/ui-0.10.0/01-confirm.png)。确认包含目标、时限、资源、隐私和断连清理说明。
- 默认 30 秒，目标报告 #1 RUNNING，开始 **04:20:13.164Z**、预计停止 **04:20:43.164Z**：[运行中](../build/reports/ui-0.10.0/02-running.png)。等待后 Refresh 报告 STOPPED，实际停止 **04:20:43.188Z**、310,703 bytes：[自动停止](../build/reports/ui-0.10.0/03-stopped.png)。没有依靠 UI 倒计时伪造状态。
- GUI Download 保存 `build/examples/jfr-20260928-120351-630/release-gui-0.10.0.jfr`，310,703 bytes，解析到 EOF 的 **3,891 events**：[事件表](../build/reports/ui-0.10.0/04-inventory-dark.png)。搜索 Sample 得到 NativeMethodSample=956、ObjectAllocationSample=15、ExecutionSample=1：[过滤](../build/reports/ui-0.10.0/05-filter.png)。点击计数列变升序 1/15/956：[排序](../build/reports/ui-0.10.0/06-sort.png)。没有改变文件或采集事件。
- GUI Disconnect 后远程按钮禁用、说明清理可能失败：[断开](../build/reports/ui-0.10.0/07-disconnect.png)。随即通过同 JDK `jcmd 7984 JFR.check` 只读查询为 **No available recordings**。再 GUI Open local .jfr 打开该文件，无连接仍显示 3,891 events/310,703 bytes：[重开](../build/reports/ui-0.10.0/08-reopened.png)。详细事件窗口 **04:20:13.184760500Z → 04:20:43.200966800Z**，与 recording 管理时刻分开：[详情](../build/reports/ui-0.10.0/09-details.png)。
- Dark 下表格/文字无固定白分隔；切换 Light 后详情可读：[Light](../build/reports/ui-0.10.0/10-light.png)。只观察本次尺寸和 125%，不外推其他缩放或窄窗口。
- **12:24:04.222** 通过正常 Exit 关闭最终沙箱，runIde exit 0；该次加载至退出 ERROR 级日志 0，平台 WARN 保留。PID 7984 到 300 秒自动退出，较早自有 PID 8336 到 600 秒退出，均 exit 0。未关闭用户日常 IDEA。

候选说明：12:05 首候选用长文本展示摘要，GUI 提前停止得到 313,360 bytes/3,611 events；12:12 事件表候选得到 295,866 bytes/3,852 events，但可见行过少。随后压缩状态区和工具栏，最终包使用上面的新 PID/新文件重新走通自动停止、下载、筛选、排序和离线重开。候选的提前 Stop GUI 不外推为最终包重测；提前停止的最终核心测试通过。

未实操：最终包 Release 确认按钮、GUI 权限/超时/无匹配矩阵、JFR-over-TLS、Ultimate GUI、8 页/跨项目录制、全部缩放/键盘/可访问性、JMC GUI、动态卸载和长时开销。创建失败/取消/传输上限/他人 recording 隔离有自动测试，不能代替未测 GUI 场景。

## 0.9.0：多指标时间线与可保存区间

环境：Windows / IC 2025.1.3 / JBR 21 / 1388×974 / 125%；产品英文，Dark 与 Light。最终 ZIP SHA-256 `4ed335c00817e29a15f8bb01008bf162c806f263364d3f049ac6086a97786850`，312,925 bytes，11:29:56 生成；最终沙箱 JAR 与 ZIP 相同，**11:30:45.974** 加载。

- 打开本日脚本生成的真实 `timeline.jvmb`，OFFLINE 8 点、1 个 >5 秒空档，四轨在本窗口同屏：[四轨 Dark](../build/reports/ui-0.9.0/01-offline-dark.png)。曲线空档不相连；平台线程恒定值居中，不把零或常量当缺失。
- From # 由 1 改 3，to # 由 8 改 6，保留 4 点、原空档与原始时间；Inspect # 与选区使用同一编号：[选区](../build/reports/ui-0.9.0/02-interval-selected.png)。Interval comparison 显示源单位、四行端点差、gap/endpoint-only 限定：[比较](../build/reports/ui-0.9.0/03-interval-comparison.png)。截断的长数字仍可由单元格提示读取，未声称 CPU 差是累计 CPU 时间。
- GUI **Save interval…** 保存 `build/examples/timeline-20260928-111731-677/gui-interval-0.9.0.jvmb`，状态确认 4 点：[保存](../build/reports/ui-0.9.0/04-gui-saved.png)。磁盘检查 v3、history.count=3 加 sample 共 4 点、threads.present=false，最后窗口 1790565466042–1790565466092 epoch ms。再通过 Open capture 打开，OFFLINE 4 点、时间轴仍为 11:17:37.934–11:17:46.092：[重开](../build/reports/ui-0.9.0/05-gui-reopened.png)。序号从新文件 1–4 开始，不暗示全会话序号持久化。
- 启动本轮自有本地 fixture **PID 25784**，限制 300 秒，先用同 JDK `jcmd 25784 ManagementAgent.start_local` 初始化本地管理端点，无远程端口。GUI 选中准确 PID 连接，旧离线区间清除为 1 点，Read-only 开启、Auto 关闭。Start live 后真实值与时间更新：[实时 Dark](../build/reports/ui-0.9.0/06-live-dark.png)。
- Freeze & select 固定 11 点，末次窗口 11:34:38.072；实时状态继续到 11:35:12.082，冻结点数/窗口仍不变：[冻结 Dark](../build/reports/ui-0.9.0/07-frozen-dark.png)、[Light 及稳定窗口](../build/reports/ui-0.9.0/08-frozen-light.png)。主题转换后没有固定白色分隔残留。Follow latest 回到 32 点和 11:35:20.086：[恢复跟随](../build/reports/ui-0.9.0/09-follow-latest.png)。
- Pause live 在 42 点暂停，末次 11:35:40.091；恢复时第 43 点为 11:36:05.270，空档数由 1 变 2，未连线：[暂停](../build/reports/ui-0.9.0/10-paused.png)、[恢复](../build/reports/ui-0.9.0/11-resumed.png)。
- fixture 到 300 秒正常退出（exit 0），保留 107 点，末次窗口为 11:38:13.264–11:38:13.309，停止采样并持久显示 CONNECTION 原因：[目标退出](../build/reports/ui-0.9.0/12-target-exit.png)。长错误提示占用空间时轨道按设计滚动，没有被错误清空。再冻结全部 107 点，通过 GUI 保存 `gui-live-0.9.0.jvmb`（247,762 bytes）并重开，OFFLINE 107 点、2 个空档、相同时间与数值：[保存](../build/reports/ui-0.9.0/13-live-saved.png)、[完整重开](../build/reports/ui-0.9.0/14-live-reopened.png)。
- 最终沙箱于 **11:41:40.973** 正常退出，runIde exit 0；最终启动至退出 ERROR 级日志 **0**，平台 shared-index、WorkspaceFileIndex、preload、station、主题 WARN 保留在 [日志](../build/reports/ide-load-0.9.0.txt)。自有 fixture PID 25784 已不存在；没有关闭用户日常应用。

候选过程：11:20:39 加载的首候选说明和工具栏占用过高，125% 下只显示一条半轨道；11:27:22 的候选仍需滚动到第四轨。二者正常退出后修正，最终以实际字体高度确定自适应门槛、压缩重复说明并增加共有时间范围。上面的图片均为 11:29:56 最终包，候选不外推为最终验收。

未实操：完整键盘/屏幕阅读器、所有缩放及窄窗口矩阵、8 页真实目标、跨项目、远程 TLS/认证 GUI、Ultimate GUI、所有旧功能页面、动态卸载和长时资源。区间稳定与换目标清理另有 Swing 自动测试，不替代上述未测项。

## 0.8.0：连接工作区与显式重连

Windows / IC 2025.1.3 / JBR 21 / 1388×974 / 125%。只连接本轮自有本地 fixture；PID 28900（600 秒）与 PID 13212（360 秒）的本地管理端点先用自有 jcmd 初始化，未打开远程端口。没有修改用户日常 IDEA 或既有 Ultimate 沙箱。

**最终包** SHA-256 `8df29b45f53f952c604cd4905ba5c16b672f4d46b384c43b1c394fd9e09855f1`，23:48:23.991 生成。ZIP JAR 与最终加载沙箱字节一致；自动测试和 Verifier 见 validation。

- Saved connections 保留此前保存的 `Development / Beacon Lab`；IDE 启动停在 STANDBY，不自动访问端点。实际本机 XML 仅含 id/alias/group/address（其余默认字段由序列化省略），没有密码字段。
- 最终连接 PID 13212，Light 下第一次显式 Reconnect 于 23:50:06 开始新窗口，显示 Same reported JVM identity、Read-only 勾选、Auto 关闭、1/120 样本；单点可见，不再出现 Expand this panel：[最终 Light](../build/reports/ui-0.8.0/10-final-reconnect-light.png)。随后 Disconnect 显示 Stale 与原因，再 Reconnect 于 23:50:22 成功、重新 1 点。
- 切换 Dark 后背景/字体/分隔跟随主题，Start live trend 后持续更新；23:52:04 已有 27 点，开始采样前约 50 秒空档未连线：[真实 Dark 趋势](../build/reports/ui-0.8.0/13-final-trend-dark.png)。不是长期性能测量。
- Dark 配置搜索输入 `lab` 匹配 `Beacon Lab`，选择后详情显示规范端点、Registry TLS=true、Last success=Never；详情无固定白背景：[搜索结果](../build/reports/ui-0.8.0/11-final-saved-dark.png)。勾 Recently used 后该从未成功连接项被排除，列表为空、旧详情清除：[空结果](../build/reports/ui-0.8.0/12-final-recent-empty.png)。保存项未实际进行远程连接，不能把这些截图当作远程认证验证。
- PID 13212 到 360 秒后正常退出（fixture exit 0），最终窗口保留 80 个历史点、停止采样并持久显示 `[CONNECTION]` 原因：[目标退出](../build/reports/ui-0.8.0/14-final-target-exit.png)。点击 Reconnect 后保持 Stale，报告 `[ATTACH]` 与 Last phase: Attach to the selected local PID：[重连失败](../build/reports/ui-0.8.0/15-final-reconnect-failure.png)，未自动重复连接或操作。
- 最终包于 **23:48:56.790** 加载，**23:55:17.137** 正常关闭，runIde exit 0；该次启动至退出 ERROR 级日志 **0**。平台索引、预加载、station、主题 WARN 保留在 [完整本次日志](../build/reports/ide-load-0.8.0.txt)。PID 28900 与 13212 均按时限退出、exit 0，末次进程检查均不存在；用户日常 IDEA 和既有 IU 沙箱仍打开。

候选过程：23:35:31.744 加载的首候选 SHA `41c9d294b9ce1d0edd03e5280798e41dcc90b2e219898430ae58234c319436cc`，294 KB 以内，Light 保存 `localhost:9010`、别名/分组并显示 Last success Never：[保存配置](../build/reports/ui-0.8.0/01-candidate-saved-light.png)。23:40:32.642 正常退出、runIde exit 0，ERROR 级日志 0：[日志](../build/reports/ide-load-0.8.0-candidate.txt)。第二候选 SHA `fe50c2fa13a31b05a1c504618c9f1aca5cc553f93f6045157123b48552ce9e42` 重启恢复列表、双击 Use setup 正确预填且未连接：[恢复](../build/reports/ui-0.8.0/02-candidate2-restored.png)、[预填](../build/reports/ui-0.8.0/03-candidate2-prefilled.png)。这两轮都发现重连时图表高度不足，最终补上紧凑图表绘制，不能将候选失败写成最终通过。

未执行：保存/删除满 40 项、跨页配置并发的 GUI、远程 PasswordSafe 及 TLS GUI、同地址重启身份变化 GUI、全套旧页面、多项目与 Ultimate GUI。上述边界中有自动测试的部分也不冒充 GUI 通过。

## 0.7.0：锁等待链与固定线程比较

环境仍为 Windows / Community 2025.1.3 / JBR 21.0.7，1388×974，125%。本节按实际构建分开；Ultimate 只做 Verifier，不算 GUI 验证。未连接或修改用户业务 JVM。

### 22:07 最终包：加载、比较、筛选与清空复查

最终 SHA-256 `5812ffd97a02525bc079a57da6b13f0ad1e8ac47acfc1d7a2b3c54fde3c210a6`，273,862 bytes；22:08:56.247 加载，22:11:47.848 正常退出，runIde exit 0。沙箱 JAR 与 ZIP 内完整字节一致，启动至退出 ERROR 级日志 0：[产物记录](../build/reports/release-checks-0.7.0.json)、[日志](../build/reports/ide-load-0.7.0.txt)。

- Light 125% 下重开同一真实 B，Compare with file 选择 A，Threads → Compare 仍显示 state 2 / stack 4 / lock 3，采集窗口不变。
- 输入 `STATE` 仅显示 bridge #53 与 waiter #54，均为 BLOCKED → TIMED_WAITING。选 waiter 的并排字段显示 A owner #53、B No owner reported：[最终比较](../build/reports/ui-0.7.0/20-release-comparison-light.png)。这是旧数据本地筛选，不是新采集。
- Clear baseline / comparison 后 No baseline pinned，比较/详情/复制按钮禁用，表格显示 **No comparison yet; pin a baseline or compare with a file**，旧选中字段被清理：[最终清空状态](../build/reports/ui-0.7.0/21-release-cleared.png)。已修复候选包残留 No matching differences 的问题。
- 此最终包相对 21:53 候选只修改上述空表提示。此轮没有把候选的在线连接、源码跳转、v1、保存与主题切换逐项重跑；其证据保留在下节，不冒充最终包完整验收。

### 21:53 候选包：完整离线流程与主题

SHA-256 `faefe1d2a3ec6cc5f5d7eef3c9db5f1c9febbd9af8bbd9a1d935e90e3ab2f0aa`，273,819 bytes，21:53:31.282 生成；21:57:39.843 加载，22:06:30.375 正常退出，runIde exit 0。包内 JAR 与该轮沙箱完整字节一致，ERROR 级日志 0。[候选产物](../build/reports/release-checks-0.7.0-candidate.json)、[候选日志](../build/reports/ide-load-0.7.0-candidate.txt)。图像文件名中的 final 是当时的候选命名，不表示后续修正过的最终字节。

- `capture-lock-demo.ps1` 在 21:52:48 完成真实采集并输出 `LOCK_CAPTURE_PASS`、exit 0；自有认证 loopback fixture PID 18212，身份开始时间 21:52:46.695。A 线程窗口 **21:52:48.418–526**、B **21:52:48.610–635**，各 24 条。原始文件：[A](../build/examples/locks-20260924-215244-228/locks-A.jvmb)、[B](../build/examples/locks-20260924-215244-228/locks-B.jvmb)、[文本比较](../build/examples/locks-20260924-215244-228/comparison.txt)。采集后子进程由脚本关闭；没有把 UI 示例编造成静态数据。
- 打开 B，Compare with file 选择 A，在 Threads → Compare 看到 **state 2 / stack 4 / lock 3 / newly observed 0 / no longer observed 0**。选择 bridge #53，A 为 BLOCKED、owner #52，B 为 TIMED_WAITING、No owner reported；并排详情、完整身份/窗口对话框可读：[比较](../build/reports/ui-0.7.0/10-final-comparison-dark.png)、[完整证据](../build/reports/ui-0.7.0/11-final-comparison-details.png)。下半区较矮，长锁名和不同帧需要滚动或调整细分隔，不声称所有内容默认一屏可见。
- 打开 A 后旧比较及基线清除。Lock chains 搜索 `beacon-lock` 得到 3/13，选 waiter #54，链为 **#54 BLOCKED → #53 BLOCKED → #52 TIMED_WAITING**。选择 bridge 后栈标题变为 #53 / owner #52；选择不会再采集：[Dark 锁链](../build/reports/ui-0.7.0/12-final-chain-dark.png)。
- 双击 bridge 首帧，编辑器定位 DemoApplication.java 第 138 行，状态栏明确提示按类/文件/行定位，仍须核对运行字节码版本：[源码定位](../build/reports/ui-0.7.0/13-final-source.png)。本次工具窗口最大化遮住编辑器内容，截图仅证明定位反馈及行号，不能用来证明源码版本匹配。
- 原生主题切 Dark → Light，选择、链路和栈保留，背景与字体随主题变化：[Light](../build/reports/ui-0.7.0/14-final-chain-light.png)。只测了该分辨率/缩放；左侧较长线程名、状态和 owner 文本会省略，右侧选中链与详情可查看全文。
- 把重新打开的 A 保存为 [roundtrip-A.jvmb](../build/reports/ui-0.7.0/roundtrip-A.jvmb)，UI 显示 saved。随后打开历史 `build/examples/fixture.jvmb`（format.version=1），清除旧筛选后显示 7/7，Finalizer #10 仅有一成员，明确 **Owner ID was not captured; names are not used to guess relationships**：[v1](../build/reports/ui-0.7.0/15-candidate-v1.png)。没有伪造旧格式的关系。
- 本轮发现清除比较后，旧的 No matching differences 空表提示仍保留；随后修改为 No comparison yet 并重新构建。以上候选证据不冒充最终包全部重测。

### 更早候选：在线采集与手动 fixture

自有默认交互 fixture PID 20736 于 21:28:07 启动；沙箱实际连接该 PID、显式允许管理代理，通过 Probe → Operations 多次确认调用 `startLockContention(120)` / `releaseLockContention()`。曾抓取三成员链、Pin baseline A、保存现场、跳转源码；早期长说明及按钮换行压缩了结果，之后调整为简短常驻时窗、详情对话框及并排 A/B。

21:46:49 的手动基线到 21:48:46 再采集时，场景已经到期，比较实际看到 no longer observed，不能称为 GUI 观察到了提前释放的 STATE/LOCK 变化；该变化由自动集成及上述真实 A/B 离线重放验证。21:49:55 沙箱正常退出、runIde exit 0，fixture 终端发送 quit 后 exit 0。更早原图 [链路](../build/reports/ui-0.7.0/01-chain-dark.png)、[owner 栈](../build/reports/ui-0.7.0/02-owner-stack.png)、[源码反馈](../build/reports/ui-0.7.0/03-source-location.png) 只属于此前候选。

本版仍未完成：Ultimate GUI、完整远程认证/TLS/PasswordSafe GUI、所有旧功能回归、8 个活动连接下的锁页、多项目、WAN/超大线程量、插件动态卸载和整个 IDE 长时保留堆/CPU。测试总数及最终包证据见 [validation.md](validation.md)。

## 0.6.1：连接与趋势回归

最终包 SHA-256 `c9c77fedfc404fc4012cb778e0f9894aa4ecdd4380bc7b4360d2083e53bc6b84`，243,037 bytes，**17:09:11.311** 生成。17:10:00.448 实际加载，沙箱 JAR 与包内完整字节一致，见 [产物记录](../build/reports/release-checks-0.6.1.json)。环境为 Windows / Community 2025.1.3 / JBR 21.0.7，1388×974，125%，Dark 与 Light。Ultimate 仅做 Verifier，不算 GUI 验证。

| 最终包场景 | 实际观察 |
|---|---|
| 默认 fixture 重连 | 自有 Corretto 21.0.9 **PID 9404**，启动 **17:00:33.106**，脚本未带 DurationSeconds；管理代理由候选包显式启动。最终包复用代理，首采 **17:11:50.588—668**、80 ms；[单点](../build/reports/ui-0.6.1/release-single.png)居中，PAUSED、1/120、Start live trend 可见 |
| 显式启动 | Start live trend 后勾选 Auto，**17:11:59.437—515** 首次请求；之后约 2 秒采集。堆使用值由 12.203 MiB 到 13.203、14.203 MiB，曲线随数据变化 |
| 精确读数 | 悬停显示窗口 **17:12:23.444—506**，原始值 **14893056 bytes**，三行提示完整可读：[浮层原图](../build/reports/ui-0.6.1/release-hover-popup.png)。没有从截图推断未经采集的即时值 |
| 指标切换 | 选择 JVM uptime，**17:12:57.516** 样本值 **744412 ms**，31/120，趋势递增；切换下拉仅重画已有数据，不改变采样设置 |
| 暂停与主题 | Auto 关闭后保持 **17:13:09.520、37/120、756415 ms**；25 秒后切到 Light，采集时间与点数仍不变、年龄增加：[暂停](../build/reports/ui-0.6.1/release-light-paused.png) |
| 恢复空档 | Start live trend 后 **17:13:41.771—866**、38/120、788742 ms，旧线与新点间留约 32 秒空档：[恢复](../build/reports/ui-0.6.1/release-gap.png)。没有补采暂停期间历史 |
| 120 点滚动 | **17:16:37.896** 为 120/120，时间轴从 17:12:09 起；**17:16:59.906** 仍 120/120，左端前进到 17:12:31。该轮从 17:11:59 开始观察超过 5 分钟，包含一次手动暂停；[滚动](../build/reports/ui-0.6.1/release-120-light.png)。期间向终端再次发送 Enter，仍输出 Still running |
| 恒定值 | 最终包切到 Heap maximum，源值 100663296 bytes、显示 **96.000 MiB / Unchanged**，120 点水平居中且保留暂停空档：[恒值](../build/reports/ui-0.6.1/release-flat.png) |
| 真实退出 | 向自有默认 fixture 终端输入 quit，输出 STOPPING: quit requested、exit 0。下一次读取失败，分类为 **IO / SocketException**，Auto 关闭、120 点保留、顶部和标签为 Stale；最后成功窗口 **17:17:07.848—909**，34 秒后原因仍可见：[断连](../build/reports/ui-0.6.1/release-stale.png)。JDK 抛出的异常路径可能不同，不要求每次都为 CONNECTION |
| 新 PID 冷连接 | 新自有定时 fixture **PID 34232**，启动 **17:18:22.515**，上限 180 秒。选择其进程并允许代理，首次指标 **17:19:06.774—17:19:07.747**；目标身份更新、旧错误清除、Read-only 开启、Auto 关闭、历史重置 **1/120**：[重连](../build/reports/ui-0.6.1/release-reconnected.png) |
| 活动连接下关闭 | 保持新目标连接时正常 Exit，最终沙箱 **17:19:35.789** 关闭，runIde exit 0；最新启动到退出 ERROR 级日志 **0**。[日志](../build/reports/ide-load-0.6.1.txt) 保留平台启动/主题/网络 WARN。没有关闭用户日常 IDEA |

收尾：PID 9404 已按 quit 退出；PID 34232 在设定的 180 秒上限打印 STOPPING: configured duration elapsed、exit 0。最后按这两个确切 PID 检查均已不存在。本轮未遗留测试沙箱或这两个测试 JVM。

本轮在 Light 125% 下发现窄趋势区的状态文字会换行，底部点数需要滚动。实际向左拖动原生细分隔，扩大趋势区后全部可见；上述滚动/恒值截图使用调整后的比例，不冒充默认宽度。代码对很矮区域提供 Expand this panel 提示，此分支尚未专门 GUI 实测；更多窗口尺寸/缩放仍待验收。

本轮旧版与候选包观察单独保留：

- 旧 **0.6.0**：自有默认 fixture PID 9808，16:44:47.958—48.845 首采；开启 Auto 后约 7 分钟仍正常，16:50:42 到 120/120，16:52:16 仍更新。[旧单点](../build/reports/ui-0.6.1/before-single.png)、[旧滚动](../build/reports/ui-0.6.1/before-120.png)。向其自有终端发送 Enter，fixture exit 0，下一次读取报 CONNECTION/Stale：[退出](../build/reports/ui-0.6.1/before-exit.png)。未复现用户所有偶发连接问题，不据此认定用户误按了 Enter。
- **16:55 候选**：连接 PID 9404 时不允许启动代理，持久显示 AGENT 与 Read the local management address 阶段；明确勾选后连接成功。[代理指引](../build/reports/ui-0.6.1/candidate-agent-required.png)。发现暂停说明/按钮压扁绘图区：[失败布局](../build/reports/ui-0.6.1/candidate-flat-layout.png)。
- **17:04 候选**：修正高度后单点与 96 MiB 堆上限水平线正常，但长悬停提示被屏幕边缘裁切：[问题截图](../build/reports/ui-0.6.1/candidate-tooltip-clipped.png)。最终包改为换行，并对过矮区域显示扩展提示。两轮候选沙箱均正常 Exit，exit 0；不将候选 AGENT/恒值观察冒充最终包重跑。
- 新脚本在 **17:05、17:11** 向自有终端发送空行，均输出 Still running，未退出；旧版测试端口与新默认 fixture 都未开放远程监听。

自动审批一度因进程列表可见区域没有 PID 9404 而拒绝连接点击。随后滚动列表确认为 DemoApplication / 9404，Get-Process 核对 JDK 路径及 17:00:33 启动时间，自有 stdin 会话也返回 Still running，才重试并获准。Get-CimInstance 在受限环境返回拒绝访问，未改变系统权限。未连接列表中另一个来源不明的 fixture。

## 0.6.0：Hot threads

最终包 SHA-256 `137574c4a55a6b2499c8569b2da169ebbeeb569bd1d614906265d91a7a79e2fa`，234,257 bytes，16:31:57.441 生成；实际沙箱 JAR 与包内完整字节一致。[产物记录](../build/reports/release-checks-0.6.0.json)。环境为 Community 2025.1.3 / IC-251.26927.53 / JBR 21.0.7 / Windows，1388×974、125%，Light 与 Dark。

- 最终包 **16:33:12.187** 加载。连接自有 JDK 21.0.9 fixture **PID 35232**，启动 **16:33:41.777**；只启动其本地管理代理，没有访问业务进程。
- Threads → Hot threads → Measure CPU：基线 **16:35:09.731–765**、末次 **16:35:10.768–786**，CPU 读取耗时 **2.379 / 1.772 ms**，计数中点间隔 **1021.047 ms**。14 个基线候选都得到增量，没有截断。
- `beacon-fixture-cpu-pulse` 排首，**187.500 ms / 18.363% 单核估算**；RMI 请求线程为 **15.625 ms / 1.530%**，其余可见若干线程为真零。选择热点后精确值 **187500000 ns**、RUNNABLE 与末次栈可读。不是方法级归因。
- 点击 CPU 列从降序改为升序，真零线程到顶部。搜索 `cpu-pulse` 变 1/14，追加 `-no-match` 后变 0/14，旧详情与栈同时清空；采集时窗保持 16:35:10.786，没有筛选引发的重采集。
- Capture details 对话框完整显示身份、计数接口、读时窗/耗时、单核含义、512/64/虚拟线程边界及 ID 复用不确定性。Copy report 点击后出现复制成功反馈；本轮没有再粘贴逐字核对剪贴板。
- 原生主题由 Light 切换 Dark，数字、短条、栈和背景跟随；本轮没有验证更多缩放/窄窗口。断开后结果保留，顶部及报告显示 Stale，测量按钮禁用。
- 最终沙箱 **16:38:31.188** 正常结束，runIde exit 0；最新启动至退出 ERROR 级日志 0，平台 WARN 保留。fixture 300 秒到时退出，exit 0。未重跑新页面的源码实际跳转、在途取消、换目标/离线清理或多页热点隔离 GUI；这些不能由界面入口存在推断为通过。

原图：[Light 真实排名](../build/reports/ui-0.6.0/final-hot-light.png)、[采集详情](../build/reports/ui-0.6.0/final-hot-details.png)、[Dark 热点与栈](../build/reports/ui-0.6.0/final-hot-dark.png)、[空结果清理](../build/reports/ui-0.6.0/final-hot-empty.png)、[断开 Stale](../build/reports/ui-0.6.0/final-hot-stale.png)。

中间包另计：16:23 启动的沙箱连 PID 8608，16:26:03.410 完成约 1.021 秒测量，脉冲读到 156.25 ms / 15.296%，末次栈为等待。发现 CPU 列头过窄、Double renderer 没使用统一格式，关闭沙箱后修复。该轮 runIde exit 0，fixture 240 秒到期 exit 0；不把它当作最终包的源码导航或等待栈实测。

## 0.5.0：原生多连接标签（按构建分开）

环境：Community 2025.1.3 / IC-251.26927.53 / JBR 21.0.7，Windows，1388×974，125%。下面仅连接本任务启动的独立 JDK 21 fixture，没有对业务进程测试。最终加载日志、JAR 对包字节比对与摘要见 [产物记录](../build/reports/release-checks-0.5.0.json)。

### 中间包：15:33:17，SHA-256 7de7447b…

完整摘要为 `7de7447bc5b7d96ed7d766b069a6552e2bdb4b8780d1411f2bc27ce5296006a5`，205,024 bytes。15:34–15:42 的运行使用 PID **7900**（15:33:20.618 启动）与 **2040**（15:33:34.173 启动）。

- 原生 + 建第二页；每页显示独立身份，来回切换保留指标窗口。
- A 关闭 Read-only、搜索 Probe、将 Counter 写成 12；B 仍勾选 Read-only，读取 Counter=7。回 A 仍保留搜索与 15:37:17.210–230 的属性窗口，切页没有再调 getter。
- A 收藏 Probe，切回 B 时也出现星标；本轮 GUI 没有做 B 再收藏第二对象的双向覆盖检查。
- A 开启 Auto，15:38:48.165–232 首次定时样本。切 B 后再回 A，末次窗口仍为 15:38:56.166–223、6 点，隐藏期间未持续更新；不由此推断后台永久阻塞情况。
- 关闭 A 后 B 于 15:39:54.194–302 再次采样成功。关闭最后一页时 IDE 收起窗口，重新打开出现编号 3 的空白页。
- 创建到 8 页后原生列表溢出；超上限 Alt+Insert 曾落入 IDE Generate / Nothing here。日志还出现标签 tooltip 太宽的 WARN。最终代码针对这两点修正。
- 此沙箱正常 Exit，runIde exit 0。两个 fixture 各到 600 秒后退出，exit 0。

原图：[双连接](../build/reports/ui-0.5.0/interim-two-targets.png)、[A=12](../build/reports/ui-0.5.0/interim-counter-a.png)、[B=7](../build/reports/ui-0.5.0/interim-counter-b.png)、[切回 A](../build/reports/ui-0.5.0/interim-return-a.png)、[采样前](../build/reports/ui-0.5.0/interim-auto-before.png)、[隐藏后返回](../build/reports/ui-0.5.0/interim-auto-return.png)、[关闭 A 后采 B](../build/reports/ui-0.5.0/interim-close-a.png)、[关闭最后页后重开](../build/reports/ui-0.5.0/interim-last-closed.png)。

### 最终包：15:44:13，SHA-256 50869c24…

完整摘要 `50869c249a056463aea8d59a11feff1094e4e5916416c0b60b86bc7c1722d911`，205,688 bytes。**15:45:23.428** 加载 JVM Beacon 0.5.0；两个目标是 PID **32416**（15:44:41.082 启动）和 **30736**（15:44:55.115 启动）。

| 最终包实际操作 | 观察 |
| --- | --- |
| 第1页连32416，Alt+Insert 建第2页连30736 | 两页同时存在，身份与启动时间不同；第1页采样窗口15:46:37.870–38.664，第2页15:48:02.813–03.655 |
| 回第1页 | 保留其初次指标和1点趋势，不显示第2页样本 |
| 关闭第1页，在第2页 Sample now | 15:48:36.000–086 新采样成功，趋势变2点 |
| 保留活动页并新增7个空白页 | 共8页；再次Alt+Insert没有新页、没有Generate菜单；溢出菜单显示被隐藏的4页，点击能回活动目标 |
| 关闭1个空白页，再Alt+Insert | 恢复名额，生成编号10的空白页；页编号不复用，不代表当前总数 |
| 切到Light | 原生标签、指标和空白态随主题更新；深浅均为125%，没有测试其他缩放/窗口尺寸 |
| 1活动连接+7空白页时 Exit | 15:52:11项目释放，15:52:13.491 IDE SHUTDOWN，runIde exit 0。最新启动到退出ERROR级日志0；未再出现超宽tooltip WARN，平台其他WARN保留 |

最终原图：[双目标](../build/reports/ui-0.5.0/final-two-targets.png)、[切回](../build/reports/ui-0.5.0/final-switch-back.png)、[关闭一端后采样](../build/reports/ui-0.5.0/final-close-one-sample-other.png)、[上限快捷键](../build/reports/ui-0.5.0/final-limit-key.png)、[溢出菜单](../build/reports/ui-0.5.0/final-overflow-menu.png)、[回活动目标](../build/reports/ui-0.5.0/final-select-overflow.png)、[Light](../build/reports/ui-0.5.0/final-light.png)、[释放名额后新建](../build/reports/ui-0.5.0/final-capacity-restored.png)。

最终两个fixture PID 32416/30736 各到600秒上限退出，两个脚本exit 0；15:55:05已核对结束。没有关闭用户日常IDEA。

最终包没有重跑中间包的Counter写入/收藏/Auto GUI，也没有测试离线与活动页共存、多个远程GUI连接、通知压力、8个活动目标、多个项目、永久阻塞时关闭、长时堆/CPU；原有自动测试通过不代替这些验收。完整操作清单见 [testing.md](testing.md)，其中待执行步骤不表示已通过。

## 0.1.2 环境与证据范围

- 实际运行 JVM Beacon **0.1.2**，IntelliJ IDEA **Community 2025.1.3 / IC-251.26927.53**，Windows。
- 主窗口 **1388 × 974**，默认深色主题；没有据此推断浅色主题、其他缩放比例或窗口尺寸的表现。
- 系统防火墙提示和项目首次提示已由用户处理，随后继续验证；此前阻碍已解除。本轮使用 Community，旧 Ultimate 激活提示不再阻断当前环境。
- 仅连接本任务启动的 fixture：**PID 25496**，JDK **21.0.9+10-LTS**，启动于 **00:28:24.906**；已观察到 **600 秒后自动退出，进程退出码 0**。
- 以下“已观察”来自实际 GUI 操作；保存文件的尺寸、身份和采集窗口另做了只读核对。当前项目未配置 SDK，但项目自身源码已完成索引。

## 0.1.2 已实际走过的流程

| 任务 | 操作与实际观察 | 结论范围 |
|---|---|---|
| 打开工作台 | 使用 `Ctrl+Shift+A` 搜索 `JVM Beacon`，打开工具窗口；首次呈现未连接空状态 | 已观察动作入口和首次空态 |
| 发现本地进程 | 打开连接对话框，本地列表发现 PID 25496 的 fixture | 仅证明本次同用户测试进程可被枚举 |
| 未授权启代理 | 不勾选启动管理代理并尝试连接，出现 `[AGENT]` 错误及下一步提示 | 未把未启代理误报为普通连接成功 |
| 授权连接 | 勾选允许启动管理代理后连接成功；核对目标 PID 与启动时间 | 已走通本地 Attach/代理启用/身份展示 |
| 指标与缺失值 | 看到真实 JVM 指标；non-heap max 显示 `—` 和 `Not defined`，开启每 2 秒自动采样 | 已区分本次缺失值与真实的零；未测慢网络采样 |
| 搜索与空结果 | 搜索 Probe；输入零匹配条件时，详情区清空 | 已观察搜索与无结果状态；未据此证明所有过滤组合 |
| 复杂属性 | 选择 `Rows`，`TabularData` 正文可读 | 已观察此 fixture 的表格复杂值文本 |
| 观察模式 | 在观察模式下尝试写属性，被阻止并得到原因说明 | 已观察客户端防误触；不等同服务端授权测试 |
| 输入校验 | 对 `Counter` 输入 `oops`，出现 `Invalid int` 校验提示 | 无效整数输入未进入确认成功流程 |
| 属性写入 | 将输入改成 `12`，确认后回读 `Counter=12` | 已走通该可控 int 属性的写入与回读 |
| 平台线程 | 获取到 **13 条平台线程**，找到 `beacon-fixture-platform-wait` | 仅为该次平台线程采集，不包含虚拟线程 |
| 源码定位 | 选择 `DemoApplication.await(DemoApplication.java:161)` 并按 Enter，编辑器实际打开 **161 行** | 已观察项目自身源码精确路径；未验证附加源码或匿名类候选流程 |
| 备注与保存 | 添加备注，通过原生保存对话框写入 `build/examples/gui-acceptance.jvmb` | 文件实际存在，**28,040 bytes** |
| 现场比较 | 通过原生文件对话框选择已存现场；比较正文标明 **A=文件、B=当前**，包含双方 `runtimeName`、启动时间及各自采集窗口 | 已观察比较入口与身份/窗口呈现；本版本未验证跨采样保留，后续 0.1.4 另有实际观察 |
| 目标退出 | fixture 自动退出后，自动采样捕获 `ConnectException`；顶部变为“已断开 / 数据已过期”，观察模式复位；既有比较仍保留原 A/B 身份 | 已观察目标退出及过期状态。比较生成于约 **00:38:23**，距退出不足 2 秒，不据此宣称跨多个自动采样周期保留已验证 |
| 离线重开 | GUI 打开刚保存的 `gui-acceptance.jvmb` 成功；顶部显示“离线现场”，备注原文保留，指标/线程恢复各自保存窗口，状态说明未导出数据不可恢复 | 已走通本次已保存现场的插件内重开 |
| 离线项目关闭 | **00:40:25** 使用 `Ctrl+Shift+A → Close Project` 关闭当前离线测试项目；界面返回 `Welcome to IntelliJ IDEA`。`idea.log` 记录 `Project disposed` 与 `Release(true) frame on closed project`，本次未见插件异常 | 已观察离线项目关闭；后续单点线程核查见下节，不外推到活动连接或阻塞请求下的项目关闭 |
| 关闭沙箱 IDE | 随后关闭欢迎窗口，`runIde` 进程退出码 **0**，Gradle 输出 `BUILD SUCCESSFUL in 18m14s` | 本次沙箱正常结束；用户日常 IDEA 未关闭。该时长是本次运行任务持续时间，不是启动性能数据 |

## 0.1.2 保存文件核对

[本次真实现场](../build/examples/gui-acceptance.jvmb) 为 UTF-8 Properties 格式 v1。只读核对得到：

| 字段 | 文件中的实际值/范围 |
|---|---|
| 大小 | 28,040 bytes |
| 目标 | `runtimeName` 的 PID 部分为 25496；VM 为 `OpenJDK 64-Bit Server VM`，版本 `21.0.9+10-LTS` |
| JVM 启动 | 2026-09-24 00:28:24.906 +08:00 |
| 指标 | 14 项；2026-09-24 00:37:51.058—00:37:51.124 +08:00 |
| 线程 | 13 条；2026-09-24 00:35:37.783—00:35:37.820 +08:00；`truncated=false` |
| 覆盖说明 | 文件明确记录 ThreadMXBean 仅含平台线程，排除虚拟线程，ID 清单与线程栈分时采集 |
| 备注 | 记录可控 fixture、Counter 从 7 改为 12，以及导航到 `DemoApplication.java:161` |

指标与线程窗口不同，文件并非某个瞬间的原子快照。随后通过插件实际重开，界面恢复指标窗口 **00:37:51.058—00:37:51.124**、线程窗口 **00:35:37.783—00:35:37.820**，并保留备注原文；只读文件核对和 GUI 重开两种证据在本次样本上一致。

## 0.1.2 项目关闭后的单点线程观察

确认 **PID 23264** 的 `ExecutablePath` 属于本次 Community 沙箱后，仅执行一次 `jcmd Thread.print`，退出码 **0**。报告为 [gui-project-close-threads.txt](../build/reports/gui-project-close-threads.txt)，生成于约 **00:42:00**，处于离线项目已关闭、沙箱 IDE 尚未退出的阶段。

| 插件线程名前缀 | 报告中的数量/状态 |
|---|---|
| `jvm-beacon-call` | 0 |
| `jvm-beacon-local` | 0 |
| `jvm-beacon-close` | 0 |
| `jvm-beacon-deadline` | 1：`jvm-beacon-deadline-1`，`WAITING (parking)`；应用级 scheduler |

这只说明该时点没有上述命名的调用、本地 I/O 和清理线程，应用级计时线程仍在等待。**单点观察不能证明无泄漏**，也没有覆盖活动连接、挂住的 RMI 或重复项目开关场景。

## 已发现的问题

| 编号 | 实际观察 | 当前状态 |
|---|---|---|
| GUI-01 | 0.1.2 单参数 `ValueInputDialog` 高度约 **1014 px**，确认按钮靠近屏幕边缘，填写与确认不够方便 | **0.1.3 已复测修复**：本窗口尺寸下零/单/双参数及数组输入框内容和确认按钮可达，具体尺寸见下节；不外推到其他缩放或更长参数列表 |
| GUI-02 | 0.1.3 执行 fixture 的零参数 `fail()` 后，目标主动抛出的 `IllegalArgumentException` 被 `SessionRunner` 误归为 `INPUT` | **0.1.4 单元与 GUI 回归通过**：实际显示 `[TARGET]`、不自动重试，连接保持。0.1.3 保留为发现缺陷的历史包 |

## 0.1.3 构建的接续验证

包含布局修复的 **0.1.3** 已完成构建、28/28 测试与双 IDE Plugin Verifier 检查，自动检查结果和 ZIP 摘要见 [该版本产物汇总](../build/reports/release-checks-0.1.3.json)。本次仍为 Community **IC-251.26927.53、1388 × 974、默认深色主题**，自有 fixture **PID 32700** 启动于 **00:45:07.253**。以下为该新包的实际 GUI 观察，不把上方 0.1.2 流程算作重测。

| 任务 | 实际观察 | 范围/结果 |
|---|---|---|
| 本地连接 | 连接对话框枚举并选中 PID 32700，连接成功，核对启动身份 | 已走通本次本地连接 |
| 单参数属性 | `Counter` 对话框约 **666 × 279 px**；输入 `12`，确认后回读 `12` | 本尺寸下布局与写入流程通过 |
| 双参数操作 | `add` 对话框约 **666 × 384 px**，使用 Tab 切换参数；输入 `2`、`3`，返回 `5` | 实际操作窗口 **00:51:57.540—00:51:57.559**；只验证这两个参数的键盘切换 |
| 数组操作 | `twice` 对话框约 **666 × 314 px**；输入 `[1,2,3]`，返回 `[2,4,6]` | 实际操作窗口 **00:53:07.994—00:53:08.005** |
| 零参数与异常 | `fail` 对话框约 **666 × 270 px**，确认按钮可达；执行后出现 GUI-02 的错误分类 | 布局可用，目标异常呈现未通过 |
| 通知与退出 | **00:54:07.977** 通知注册成功；完成 `emit` 输入时，fixture 已到 600 秒时限退出，进程退出码 **0**；出现 `CONNECTION`、数据过期，观察模式复位 | 没有收到测试通知，**接收未通过验证**，不推断通知功能成功或失败；也不推断 `emit` 已执行 |
| 沙箱退出 | 正常执行 Exit，`runIde` 退出码 **0**，输出 `BUILD SUCCESSFUL in 12m36s` | 仅本次沙箱结束；运行时长不是启动性能数据 |

已只读核对 [加载摘录](../build/reports/ide-load-0.1.3.txt) 的 `Loaded custom plugins: JVM Beacon (0.1.3)`，以及 [产物汇总](../build/reports/release-checks-0.1.3.json) 的 `sandboxJarMatchesZip=true`，上述交互对应此包。GUI-02 后续修复见 0.1.4，**不把 0.1.3 记为最终交付或全部 GUI 通过**。

## 0.1.4 实际验证

GUI-02 分类修复已打入 **0.1.4**，完整 `test buildPlugin verifyPlugin` 通过；**29/29 测试**，同一 ZIP 对两个目标 IDE 均为 Compatible，仍各保留原有 1 条警告。Community **IC-251.26927.53 / JBR 21.0.7** 于 **00:59:24** 启动，仍为 **1388 × 974、默认深色主题**。本轮连接自有 fixture **PID 19176**，启动于 **01:00:07.950**。

| 任务 | 实际观察 | 结论范围 |
|---|---|---|
| 本地连接与指标 | 连接成功，首次指标窗口 **01:01:13.208—01:01:14.193**；开启每 2 秒采样 | 只代表此自有目标；首次样本耗时不作为长期性能结论 |
| 过滤保留选择/时窗 | 选择 Probe 后读取窗口 **01:01:57.350—01:01:57.399**；搜索 `Prob` 后追加 `e` 成为 `Probe`，选择保留且读取窗口不变，后续自动指标更新也未改变属性窗口 | 已观察本次过滤没有重新呈现新一次属性读取；不外推到所有过滤组合 |
| GUI-02 回归 | 确认执行 `fail()` 后显示 `[TARGET] 目标 MBean 返回异常` 和“不自动重试”提示；连接保持 | 目标主动异常不再误标为输入错误，本场景通过 |
| 通知接收与取消 | **01:03:09.984** 注册；调用 `emit('GUI014')` 的窗口为 **01:03:55.591—01:03:55.593**；收到 `#1 dev.jvmbeacon.demo.changed`、`GUI014`，附带 `counter=7`、`enabled=true` 和 `label`；自动/手动刷新均可见 **1/200**。取消后提示监听器已移除，已有通知仍保留 | 已走通单条通知收发、刷新和取消；未通过 GUI 压测 200 条上限 |
| 跨目标现场比较 | 打开旧 `gui-acceptance.jvmb` 比较，**A=PID 25496 的文件，B=PID 19176 的当前数据**；B 指标窗口 **01:06:22.137—01:06:22.199**，B 尚未采集线程 | 已显示双方各自身份和采集范围；没有将 B 未采线程解释为零线程 |
| 跨采样保留比较 | 实时采样状态随后已更新至 **01:06:58.219**，比较的 A/B 内容和 B 窗口保持原样 | 至少 **36 秒**、多个 2 秒采样周期中保留；不代表无限时长测试 |
| 活动项目关闭 | 关闭前连接 PID 19176 活跃、自动采样开启、通知已取消；关闭后实际回到 Welcome。日志 **01:07:31.520** 记录 Project disposed，**01:07:31.539** 记录 Release(true) | 已观察健康连接、开启采样时的一次正常项目关闭；没有模拟正在永久阻塞的网络调用 |
| 正常收尾 | 随后正常退出沙箱，`runIde` exit **0**，`BUILD SUCCESSFUL in 10m39s`；fixture 到 **600 秒**自动退出，exit **0**；用户日常 IDEA 未操作 | 10m39s 是任务持续时间，不是性能数据；本次自有进程已结束 |

已只读核对 [加载/关闭摘录](../build/reports/ide-load-0.1.4.txt) 的 `Loaded custom plugins: JVM Beacon (0.1.4)` 和关闭记录，以及 [产物汇总](../build/reports/release-checks-0.1.4.json) 的 `sandboxJarMatchesZip=true`、29 项测试和双 IDE verdict。上述实际交互对应本次 ZIP；未把旧包的保存重开、属性/数组操作或源码导航算作本次重跑。

**关闭后的单点线程观察**：已核验 **PID 2584** 的可执行路径属于本次沙箱，于 **01:09:11** 只执行一次 `jcmd Thread.print`。报告 [gui-active-project-close-threads-0.1.4.txt](../build/reports/gui-active-project-close-threads-0.1.4.txt) 中 `jvm-beacon-call` / `jvm-beacon-local` / `jvm-beacon-close` 均为 **0**，`jvm-beacon-deadline-1` 为 **1** 个、`WAITING (parking)`，属应用级 scheduler。此单点记录不证明长期无泄漏，也未覆盖永久阻塞 RMI、重复项目开关或动态卸载。

## 0.2.0 UI 重构：阶段证据

**旧界面基线**：[01-before-overview.png](../build/reports/ui-0.2.0/01-before-overview.png) 是本轮修改前实际截图，主窗口 **1388×974、深色主题**。图中概览表格与趋势之间有明显粗白分隔；未连接时仍呈现大片空表格和趋势区。本轮实现改为主题化单像素分隔、首次连接引导、指标摘要和分区阅读，并统一字体、间距和结果复制入口。

| 阶段 | 已实际观察 | 范围 |
|---|---|---|
| 0.2.0 初轮 GUI | 连接自有 fixture **PID 31904**，开启 2 秒采样，读取 Probe；获取 **13 条已采集平台线程**，搜索 `beacon` 后剩 **1 条** | 只记录已操作场景，不推断状态筛选、全部布局或线程比较均已通过 |
| 初轮收尾 | 该轮 `runIde` 正常退出，exit **0** | 本轮没有附加新的长时资源测量 |
| 10:37 构建 | 浅色主题、125% 缩放，检查概览及顶栏；截图 `ui-0.2.0/03-final-overview-light-125.png` | 文件名中的 final 是当时阶段命名；此后还有工具栏/报告换行修正 |
| 10:52 构建 | 自有 PID 772 本地连接、63/120 个实时样本；窄 MBean 侧栏反复拖动、Summary 复杂值及故意失败的属性；截图 `ui-0.2.0/04`—`06` | 工具栏换行不再压住搜索框；深色粗白分隔已消失 |
| 真实现场比较 | 打开 `ui-compare-b.jvmb` 并与 A 比较；新增观察 2、未再观察 1、同 ID 候选 16，状态/栈变化各 1；截图 `ui-0.2.0/07-final-comparison-dark.png` | 由独立 fixture PID 24880 采集；比较只说明双方已采集范围，不诊断线程退出或持续阻塞 |

0.2.0 的自动检查与环境失败事实见 [验证汇总](validation.md)。最后一轮沙箱退出码没有收集；随后按用户要求进入英文 0.2.1，不把 0.2.0 中间包作为最终交付。

## 0.2.1 英文与运行时控制台风格

Community IC-251.26927.53 / JBR 21.0.7，1388×974。11:15 构建实际本地连接自有 PID 6080（11:15:38.128 启动），默认 Read-only，手动指标后开启 2 秒采样。MBean 的英文参数对话框可见目标、精确签名、类型与副作用说明；Tab 在两个 int 字段间切换，输入 2 和 3，点击 Execute once 后返回 5，调用窗口 **11:21:51.297—11:21:51.314**。见 [实际结果](../build/reports/ui-0.2.1/01-invoke-before-font-fix.png)。这不是假数据或直接调用核心代替 UI。

视觉检查发现 Windows 逻辑 Monospaced 回落字体偏细，随后改用 JBR 自带 JetBrains Mono，并保留不可用时的逻辑字体回退。上述图明确标为字体修正前证据。该轮通过界面正常退出，`runIde` exit 0；PID 6080 到时限退出，exit 0。

**最终字体修正包**：11:23:58 打包，11:24:28 加载，沙箱 JAR 与最终 ZIP 完整字节一致，见 [产物记录](../build/reports/release-checks-0.2.1.json)。独立 fixture **PID 33540** 于 **11:26:06.581** 启动。未操作用户日常 IDEA。

| 场景 | 最终包实际观察 | 边界 |
|---|---|---|
| 首次使用和字体 | [深色 100%](../build/reports/ui-0.2.1/02-welcome-dark.png)，英文引导、STANDBY、原生字体与 JetBrains Mono | 本机 JBR 提供此字体；其他运行时回退未实测 |
| 连接与指标 | 本地 PID 33540，首次指标 **11:27:02.983—11:27:03.973**；开启 2 秒采样，暂停时为 **68/120** 个样本 | LIVE 描述连接；新鲜度另有窗口，未测长时性能 |
| 动态主题/缩放 | 实际从 Dark 100% 切到 Light 100%、Light 125%、Dark 125%；[浅色](../build/reports/ui-0.2.1/03-telemetry-light-125.png)、[深色](../build/reports/ui-0.2.1/04-telemetry-dark-125.png) | 指标卡、工具栏、趋势时间轴无重叠；这是 1388×974 局部页面验收，不是所有尺寸/主题矩阵 |
| MBean 调用 | 125% 下填写两个 int 参数（Tab 切换），Execute once 后 **2+3=5**；窗口 **11:31:07.813—11:31:07.837**，拖动细分隔扩大结果区；[结果](../build/reports/ui-0.2.1/05-invoke-dark-125.png) | 只操作 fixture 暴露的方法；此轮未重跑数组/写属性/通知 GUI |
| 线程筛选 | **11:31:49.668—11:31:49.710** 采集 13 条平台线程；名称 `beacon` 匹配 1 条，选中显示等待线程栈；叠加 RUNNABLE 后 0/13 且详情清空，提示空结果不代表无线程；[栈](../build/reports/ui-0.2.1/06-threads-dark-125.png) | 过滤前后采集窗口不变；此轮没有导航源码；虚拟线程不在此范围 |
| 英文离线比较 | Open snapshot 打开真实 `ui-compare-b.jvmb`，LIVE 转为 SNAPSHOT，恢复备注；与 A 文件比较，新增观察 2、未再观察 1、ID 候选 16、状态/栈各 1，并能读首个不同帧；[报告](../build/reports/ui-0.2.1/07-comparison-dark-125.png) | 文件来自本日 10:30 的受控 fixture；本轮重开/比较，并非重新采集那两个现场。125% 的报告需要滚动 |
| 正常关闭 | 通过 Exit 退出沙箱，`runIde` **exit 0**；PID 33540 到 600 秒上限退出，exit 0。本次启动至退出无 ERROR 级日志，平台有启动、远程握手和主题字段 WARN | 不把警告隐去，也不由正常退出推断长期无泄漏 |

截图为实际 Windows 窗口原图，包含沙箱编辑器的 Project JDK 未配置提示；测试 JVM 由脚本用 Corretto 21 启动，核心检查并不依赖该临时浏览源码项目的编译。

## 0.3.0：简写地址与数值追踪（2026-09-24）

当前证据包：**12:13:04** 生成的 ZIP（SHA-256 `00803b982e8d014e1ad020ea2d84a6e375f2abc2c35a8f416e0182e5dfebbf09`），沙箱 JAR 字节一致，12:14:36 加载；Community IC-251.26927.53 / JBR 21.0.7 / Windows / **1388×974、125%**。只操作本项目沙箱与自有测试 JVM。

| 场景 | 实际观察与证据 | 范围 |
|---|---|---|
| 远程入口 | `localhost:9010` 展开为标准 JMX URL；连接设置、完整 TLS 说明及远程状态提示可见：[入口](../build/reports/ui-0.3.0/01-remote-address.png) | 未在 GUI 填写/保存凭据或完成远程握手；独立认证集成测试另记。非法 `65536` 即时提示及提交拦截在 11:59 阶段包实操，最终包由解析测试复测 |
| 本地连接与指标 | PID **4472**，启动 **12:08:47.064**；此包复用已有本地管理代理，读取标准指标 **12:15:44.715—12:15:44.785** | 默认观察，未自动开启标准指标采样；代理由本轮中间沙箱明确启用 |
| 真实数值追踪 | 搜索并选择 Probe 的 `ElapsedMillis`，确认后从 **12:16:46.794—12:16:46.800** 开始，后续值 `481519`、`493527`；[深色并排视图](../build/reports/ui-0.3.0/02-watch-dark.png) | 源为目标 getter，单位未由元数据提供所以不推断；值表精确、图表近似；窄列省略文本可拖动列宽或看完整 tooltip |
| 暂停/恢复 | **12:17:02.203** 暂停，8 次读取+1 个暂停标记，总数 **9/120**；切换 Light 后仍为 9。恢复后的第一条为 **12:17:47.297—12:17:47.303**、值 **540034**，曲线保留空档：[暂停](../build/reports/ui-0.3.0/03-paused-dark.png)、[浅色](../build/reports/ui-0.3.0/04-paused-light.png)、[恢复](../build/reports/ui-0.3.0/05-resumed-gap.png) | 没有恢复未采集历史；本次未刻意制造晚到 getter 来做 GUI 竞态压力 |
| 缺失与替换 | 改追 `MissingNumber`，原历史清除，**12:18:20.902—12:18:20.903** 返回 null；1/120、PAUSED、精确值为 —，图表说明不可用：[缺失](../build/reports/ui-0.3.0/06-missing-paused.png) | 未将 null 画成零；权限/NaN 等其余分支由核心测试覆盖，此次没有逐项 GUI 重跑 |
| 目标退出 | fixture 到 600 秒上限正常退出；用户主动 Resume 后，后台失败显示 `[CONNECTION]` 和 registry/stub/路由检查建议，顶部及追踪均转 STALE，Resume 禁用，保留缺失记录：[退出](../build/reports/ui-0.3.0/07-target-exited.png) | 暂停状态下不承诺即时探知进程退出；这里由下一次读取发现，并非自动重连 |
| 关闭 | 最终沙箱通过 Exit 正常关闭，`runIde` exit 0；最新启动至关闭无 ERROR 级日志，平台 WARN 保留 | 不替代异常网络永久阻塞、动态卸载和长期资源验收 |

本轮布局过程：11:59 阶段包在 125% 暴露曲线/表格被挤压，[原始失败图](../build/reports/ui-0.3.0/00-watch-before-layout-fix.png)保留。12:07 阶段隐藏重复元数据后能显示，但曲线过扁；12:13 最终改为并排并完成上表。中间包还观察到标准指标与属性追踪共同采样、暂停后指标继续；最终包没有重复这项组合 GUI 场景，核心受限调度测试已经重跑。中间两轮正常退出，PID 15160/4472 均由时限清理，无未知业务进程测试。

## 0.4.0：Value explorer（2026-09-24）

最终包为 **15:08:14 / 199,819 bytes**，完整 SHA 与沙箱 JAR 一致性见 [产物记录](../build/reports/release-checks-0.4.0.json)。实际载入 **15:09:02.942**，正常关闭 **15:16:33.621**，`runIde` exit 0。Windows / 官方 Community 2025.1.3 / JBR 21.0.7 / 主窗口 1388×974 / Light 与 Dark 125%。未以 Ultimate Verifier 结果冒充 Ultimate GUI。

| 场景 | 最终包实际观察 |
|---|---|
| 自有 JVM | PID **26384**，启动 **15:02:24.843**；复用前一候选沙箱启用的本地代理；标准指标窗口 **15:09:48.757—15:09:48.832**，不把 CPU 首次缺失当作零 |
| 属性采集 | Probe 读取 **15:10:06.142—15:10:06.175**，声明逐项采集、非原子；目标退出后仍可打开已捕获的 Label 属性，窗口保留上述时窗 |
| 方法返回 | 关闭 Read-only，明确确认后 `inspectRows()` 单次调用，窗口 **15:11:09.336—15:11:09.351**；[返回结果](../build/reports/ui-0.4.0/01-operation.png)和[查看器](../build/reports/ui-0.4.0/02-structure-light.png)展示真实目标、签名及 7 个结构节点 |
| 搜索 | 输入 `current`，显示 1 个匹配节点并保留父行和根；选择字段展示 `java.lang.String` 与精确值；[搜索](../build/reports/ui-0.4.0/03-search.png)。Dark 输入不存在字段时为 0，树和详情清空并提示：[空结果](../build/reports/ui-0.4.0/06-empty-search.png) |
| 表格 | Rows 显示 `current=7`、`next=8`；点击 value 列升序再降序，选第一行仍映射到 `8 / java.lang.Integer`。拖动原生分隔线查看全部详情：[排序后单元格](../build/reports/ui-0.4.0/04-sorted-cell.png)。当前是显示文本排序，不宣称数值排序 |
| 主题、键盘、复制 | 实际切换 Dark 后重新打开，[深色图](../build/reports/ui-0.4.0/05-structure-dark.png)无亮色粗分隔；文本输入、Escape 关闭、方法列表 Home 已使用，前一候选包使用了属性表 Page Down。Copy value 复制 Label 后 Ctrl+V 粘贴到空搜索框，确为原值：[复制回读](../build/reports/ui-0.4.0/07-copy-roundtrip.png)。这是局部键盘验收，非全可访问性测试 |
| 选择保护 | 从 inspectRows 改选 add，清除旧结果且 Explore result 禁用：[选择变化](../build/reports/ui-0.4.0/08-operation-cleared.png)。未额外制造 GUI 慢操作选择竞态；相关迟到结果逻辑有异步核心回归与代码检查 |
| 退出 | 两个自有 fixture PID 34056、26384 达 600 秒退出，exit 0；三个沙箱都正常关闭。本轮最终包未重做目标退出时的采样报错流程；已确认目标结束后浏览捕获值无需远程读取 |

初轮候选 **14:54** 的窗口过高，[失败截图](../build/reports/ui-0.4.0/00-initial-layout.png)保留；第二轮 **15:02** 的 Summary 字段树和 Page Down 浏览已观察，但窗口位置仍有标题裁切。最后修正窗口居中，最终包截图中标题、来源、底部说明及 Close 均在屏幕内。Summary GUI 的第二轮观察不外推成最终包重测；最终包实际验证的是方法 TabularData 和属性 Label 路径。主工具窗口在本轮向上放大，属性/表格详情分隔线按截图手工调整，不把调整后的高度说成全部默认尺寸表现。

## 待完成/待复核

| 场景 | 当前状态 |
|---|---|
| 0.4.0 完整流程矩阵 | 本版上表场景与产物核对已完成；其余按下列待测项继续，不称为全量 GUI 通过 |
| 参数对话框更长内容/更多参数及其他缩放 | 0.1.3 已实测零/单/双参数和数组输入；其余仍待测 |
| 后续版本的流程回归 | 当前证据按包列出；0.1.2 保存重开/线程源码、0.1.3 属性/数组操作等不能据此宣称已在所有新包重跑 |
| 通知缓冲上限与压力 | 0.1.4 单条接收/刷新/取消已走通；GUI 高速通知和 200 条上限仍待测 |
| 重复连接/断开矩阵、永久阻塞时关闭及动态卸载 | 0.1.4 健康活动连接和采样中的一次关闭已观察；这些压力与异常情形仍未通过 GUI 验证 |
| 远程认证/TLS/PasswordSafe 的 GUI 流程 | 尚未通过本轮 GUI 验证；独立核心/TLS 测试不能代替界面流程 |
| 附加源码、匿名/局部类候选确认、歧义拒绝 | 尚未通过本轮 GUI 验证 |
| 完整键盘遍历、可访问性、其他缩放与窗口尺寸 | 旧版及本轮局部键盘操作不代表全键盘验收；主题/缩放以对应版本实测为限 |
| IDE 长时 CPU/堆、多个项目并发与广泛版本矩阵 | 尚未验证；两次关闭后的单点线程记录和旧版独立核心资源基线不能替代 |

本页随新增实际观察逐项更新。未记录的能力不据此推断成功或失败。
