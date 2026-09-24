# 手动测试指南

适用开发版本：0.8.0。建议环境：Windows、IDEA 2025.1.3、完整 JDK 21。这里只说明**怎么测试**；实际通过/失败/未测范围见 [validation.md](validation.md) 和 [gui-validation.md](gui-validation.md)。不要在未知业务进程上验证写入、方法、压力或退出。

## 准备：约 2 分钟

1. 按 [README 安装步骤](../README.md#安装与开始) 安装 `build/distributions/jvm-beacon-0.8.0.zip`，重启 IDE；在 Plugins 中确认显示 0.8.0。
2. 打开 **View → Tool Windows → JVM Beacon**。工具窗口太矮时向上拖顶部边缘；结果区的细分隔线也可以调整。插件自有界面应为英文。
3. 在 PowerShell 运行以下命令，替换路径。这个终端要保持运行，看到 `PID=…` 和 `READY` 才开始连接。

```powershell
Set-Location 'D:\IdeaProjects\JVM-Beacon'
$env:JAVA_HOME = 'C:\Users\lenovo\.jdks\corretto-21.0.9'
.\scripts\run-fixture.ps1
```

默认模式在终端输入 `quit` 后按 Enter 正常结束；空行被忽略。若使用 `-DurationSeconds 600`，会在 10 分钟后结束，Enter 不控制退出。每次启动是独立 JVM，Counter 重新从 7 开始。该脚本没有远程监听端口；它用于 Local JVM 测试。

## 连接工作区与显式重连（0.8.0）

以下是可重复执行的验收步骤，实际执行范围另见验证记录。

1. Connect JVM… → Remote JMX，填自己的测试端点、Alias `Beacon Lab`、Group `Development`，点 **Save setup**。无需连接就能保存；若只验证配置，可保留默认 `localhost:9010`，不点 Connect、不修改 TLS、不输入密码。Saved connections 应出现 `Development / Beacon Lab`，选择后显示规范 URL、Registry TLS、Last success: Never。
2. 搜索 `lab` 应匹配，输入不匹配文字应显示空结果并清空详情。勾 Recently used 时，未连接成功的配置不在列表；取消过滤后恢复。选中并按 **Use setup…**，应返回预填表单，密码为空、不自动连接。
3. 修改别名并 Save setup 应更新原项；Save as new 应增加一项。Forget setup 确认后只删除元数据，保留活动连接及 PasswordSafe 凭据。可在两个连接页同时打开编辑，验证删除/改端点后旧连接成功不恢复被删项或覆盖新端点；40 项上限与最近 10 项由自动测试覆盖。
4. 正常退出测试 IDE 后重新启动，Saved connections 应保留配置，**不会自动连接**。元数据仅在 IDE config 的 `options/jvmBeaconConnections.xml`，项目文件中没有密码或连接配置；PasswordSafe 单独管理凭据。
5. 启动本地 fixture，只连接它打印的 PID。先开始 Auto · 2 s，待趋势有多个点，点击顶部 **↻ Reconnect**。应显示 RECONNECTED / Same reported JVM identity，Read-only 勾选、Auto 关闭、趋势从一个新点开始。再执行 Disconnect → Reconnect，重复三次；不应增加标签页、订阅或后台采样，UI 仍可响应。
6. 本地重连不沿用启动管理代理的许可。如果目标端点不可用，需要重新打开 Local processes 表单，由操作者决定是否再次允许。fixture 退出后点击 Reconnect 应显示失败阶段且保持 Stale，不能显示 LIVE。打开离线现场后重连按钮禁用，不能误连该文件的 PID。
7. 远程认证目标测试时，按现有认证/TLS 步骤连接自己的 loopback fixture。记住密码后显式重连应后台读取 PasswordSafe；无已存密码应返回预填表单。用户名/端点是凭据作用域；改动后不能沿用原密码。超时写操作不会被重连重放。
8. 关闭并重启自己的远程 fixture、复用测试端点，显式连接应报告 TARGET CHANGED；没有完整身份时应报告 IDENTITY UNVERIFIED。这不代表已认证服务器身份。此同端点重启的 GUI 场景尚未实测；自动集成实测相同子 JVM 重连与另一个子 JVM 的身份差异。

## 锁链与结构化线程比较（0.7.0）

**快速离线流程**：运行 `./scripts/capture-lock-demo.ps1 -JdkHome 'C:\Users\lenovo\.jdks\corretto-21.0.9'`，看到 `LOCK_CAPTURE_PASS` 后按输出目录打开 B、Compare with file 选 A。在 Threads → Compare 搜索 `beacon-lock`，检查 waiter/bridge 的 STATE、LOCK、STACK 变化和左右 A/B 详情；Comparison details 提供完整身份、时窗与比较限制。打开 A 后在 Lock chains 搜索这些线程，选 waiter 验证三段链。脚本自身使用独立认证 loopback 子 JVM、30 秒有界竞争，finally 关闭；无需手动启动应用或修改业务进程。每次新目录，不覆盖之前现场。

下面是手动操作测试，适合同时检查连接、MBean 参数确认与实时采集。每次竞争最多 120 秒；若来不及完成，使用上面的快速离线流程验证结果阅读，重新启动竞争验证实时操作。

1. 按准备步骤启动本项目 fixture 并连接其输出的 PID。在 MBeans 搜索 `dev.jvmbeacon.demo`，选择 Probe → Operations。取消 Read-only，调用 `startLockContention(int)`，输入 `120`，核对本 fixture 身份后确认。操作只创建三个有界平台线程，不制造永久死锁；如果测试超过 120 秒，重新调用并重新采集/固定基线。
2. **Threads → Lock chains → Capture threads**，搜索 `beacon-lock`。点 `beacon-lock-waiter`：右侧应为 waiter → bridge → owner 三段，前两个 BLOCKED，末个 TIMED_WAITING。点 bridge 或 owner，栈应随之变化；选择 `DemoApplication` 帧，可通过 Go to source / Enter / 双击验证源码定位。源码需与本次编译的 fixture 一致，不应宣称二进制版本已自动核对。
3. **Threads → Compare → Pin baseline A**。到 **Snapshots** 保存 `locks-A.jvmb`，备注写入观察目的。回 MBeans 调用 `releaseLockContention()` 并确认；在 120 秒总时限内回 **Threads → Compare → Capture threads → Compare A → current B**。
4. 搜索 `beacon-lock`，waiter/bridge 应从 BLOCKED 变为 TIMED_WAITING，包含 STATE、LOCK（也可能 STACK）；点行查看 A/B owner ID 和首个不同帧。按列排序，清空/更换搜索，复制比较。新采集不会改动当前固定报告；重新点击 Compare 才重算。如果线程已到期不再出现，报告只能写 No longer observed，不能当成持续阻塞或退出原因。
5. 保存 `locks-B.jvmb`。打开 A（成功后切到 Offline），进 Lock chains 验证原三段链与时窗仍在。打开 B，再 **Snapshots → Compare with file…** 选 A，查看 **Threads → Compare** 的结构表。离线选择/筛选不应重新连接。
6. 打开旧 v1 文件（例如历史 `build/examples/fixture.jvmb`，可查看其 `format.version`）：owner ID 显示未采集，不允许通过同名线程猜链；新 v2 文件用 0.6.x 打开会报不支持，此为预期。旧文件重新保存为 v2 仍保留 unknown，不会补造 owner ID。
7. Clear baseline / comparison 后报告清空；换 JVM 或打开另一个离线现场后旧基线/比较清空。关闭本连接标签后再次开页，旧状态不应泄漏。空结果、无线程现场和不同目标比较应有说明，无数据不显示为零变化。

此功能沿用平台线程采集：不启用 contention monitoring、不查询每个 owner、不自动重试操作。单次记录上限 512、每条链/栈 64；链图是非原子观察，不等同于 JVM 死锁查询。未知 owner、重复名称、观察到的环、超长链、旧格式和非法字段由自动测试覆盖，不能从 GUI 普通三段链推定全部通过。

## 其他单连接验收：约 10 分钟

按顺序执行。MBean 都选 `dev.jvmbeacon.demo:type=Probe,name=Workbench`，搜索 `dev.jvmbeacon.demo` 即可找到。

| 操作 | 预期结果 |
| --- | --- |
| Connect JVM… → Local processes → 选择输出 PID → 勾选允许启动本地管理代理 → Connect | 页标题变成该目标；顶部显示身份/启动时间、LIVE、Read-only。若列表找不到，可手填这个已知 PID。 |
| Telemetry → Sample now；再启用 Auto · 2 s | 显示真实采集窗口和指标，窗口时间前进、趋势逐步增加；不能用 LIVE 判断数值新鲜。 |
| MBeans → 搜索 Probe → 选择 Summary / Rows → Explore value… | CompositeData 结构及类型可读；Rows 初始 current=7、next=8。Structure 搜索只筛已捕获节点，Rows 按显示文本排序。 |
| 查看 Forbidden / Broken | 显示权限拒绝/目标异常，不是 0 或 null；其他成功属性仍可读。 |
| 关闭 Read-only → Counter → Edit attribute… → 12 → 确认目标 | 刷新后 Counter=12。确认提示有目标和影响；未关闭 Read-only 时写入被阻止。 |
| Operations → add，参数 2、3 → Execute once | 返回 5。twice 的参数 `[1,2,3]` 返回 `[2,4,6]`；inspectRows() 返回 12、13，Explore result… 可查看表格。 |
| Operations → fail → Execute once | 返回目标方法失败；连接仍可用于 Sample now；不自动再次执行。 |
| Attributes → ElapsedMillis → Watch attribute… → Start tracking | Watch 出现递增的精确值、窗口、趋势；Pause 后暂停、Resume 后继续，不补历史。 |
| Notifications → Subscribe → Operations → emit 输入任意测试文本 → Refresh 通知 | 看到该通知；取消订阅后不继续接收。通知从订阅时开始，最多保留 200 条。 |
| Threads → Snapshot → Capture threads → 搜索 beacon | 找到平台测试线程，选择后可读栈；不把 fixture 的虚拟线程算进覆盖。源码导航须在项目包含匹配源码时另测。 |
| Snapshots → 写备注 → Save snapshot… → 选本地 .jvmb | 保存成功；包含这次指标、平台线程、备注与窗口。不包含 MBean、通知、追踪历史或凭据。 |
| 再次采线程 → Compare with file… → 选择刚保存的文件 | 显示双方身份和窗口；比较结果不会被后续实时采样覆盖。栈未变不证明持续阻塞。 |
| + 新建页 → Snapshots → Open snapshot… → 选择保存文件 | 新页显示 Snapshot、相同身份/备注；原活动页仍可连接。离线页的实时操作不可用。 |
| 回活动页；终端输入 quit 后按 Enter 结束目标 → Sample now | 显示连接失效原因、已有数据保留且标为 Stale；不会偷偷连接另一个同名进程。 |

## 连接与趋势回归（0.6.1）

按上方默认命令启动，保持终端打开，使用刚输出的 PID；不要连接同名但来源不明的进程。

1. 连接后 Auto 默认关闭，图中央应有一个点和 One point（较高窗口为 One valid point）提示，底部 PAUSED、1/120 及最后采集时间。仅切换下拉指标不会采样。
2. 点击 **Start live trend**，应勾选 Auto，首次立即请求，此后约 2 秒一次。选 **JVM uptime (ms)**，时间/点数和值应递增；恒定的线程数或 heap maximum 应显示水平居中线和 Unchanged，不是零。
3. 暂停 Auto 6 秒再开启，保留过去数据且跨间断不连线。悬停点查看窗口和精确值；页面中的单点/曲线不应贴着底边。切到其他连接页也不能补回隐藏期间历史。较窄的趋势区状态文字会换行，可滚动说明或向左拖动表格/图表之间的细分隔线，查看完整点数。
4. 保持至少 5 分钟，计数最多 120，达到上限后最早样本滚动移除，最新时间应继续推进。期间按终端 Enter 应提示 Still running 并继续采样。
5. 终端输入 `quit` 后 Enter；下一次采样应失败、显示 Stale 和持久原因，自动采样关闭。重启 fixture 使用新 PID，连接后从 1 点开始；不得沿用上个进程的时间序列。
6. 若未连接成功，记录 CONNECTING 的最后阶段、耗时、底部错误分类及终端是否仍在运行。连接上限 20 秒，其他请求 8 秒；Stop waiting 后迟到结果不得恢复界面。

工作线程池占满时已有连接应保留、自动采样暂停并提示，空闲后手动恢复。此场景由有界阻塞单元测试验证，不要用未知业务 JVM 人为造挂起。实际执行过的 GUI 项目另见验证记录。

## Hot threads 验收

另启动带有上限的测试进程，立即在新连接页连接它：

```powershell
.\scripts\run-fixture.ps1 -JdkHome 'C:\Users\lenovo\.jdks\corretto-21.0.9' -DurationSeconds 120 -CpuDemo
```

默认 fixture 不制造 CPU 热点。`-CpuDemo` 要求显式的正数时限，每轮计算约 40 ms / 等待至少 160 ms，脉冲最多运行 120 秒；定时到期后整个进程退出，不需要结束未知 PID。

| 操作 | 预期结果 |
| --- | --- |
| Threads → Hot threads → Measure CPU · 1 s | 等待约 1 秒加远程耗时，显示两次批量计数的结果。`beacon-fixture-cpu-pulse` 有非零 CPU 增量；数值不保证恰好 20%。 |
| 选热点行 | 右侧显示精确纳秒值、末次状态/锁和栈。脉冲大部分时间等待，末次栈可能为 park/await；不能据此归因到方法。 |
| 点击 CPU / % 列标题 | 按数值大小升降序，0 是有效测量，无法取得的值显示 — 并说明原因。 |
| 搜索 cpu-pulse，再输入不存在的名称 | 先剩热点行，再显示空结果；右侧详情及栈清空，采集时间不变。筛选不重新调用目标。 |
| Capture details… / Copy report | 窗口包含身份、基线/末次时窗、计数读耗时、真实间隔、覆盖/截断；复制全部候选含被过滤的行，不自动脱敏。 |
| 测量期间 Disconnect / Stop waiting | 立即停止 UI 等待，迟到结果不能更新当前页；不宣称底层请求一定停止。 |
| 正常捕获后 Disconnect，再连接另一自有 PID | 断开保留 Stale；新目标成功连接后清除旧 Hot threads 结果。其他连接页保持独立。 |

CPU 监控未开启及 observer 服务端权限拒绝由独立自动集成测试验证；插件不会改变设置。JDK/供应商不提供批量接口时返回不支持，不退化成数百个逐线程远程请求。线程 ID 复用和两次检查之间短暂关闭/重开监控仍有不确定性。仅平台线程，不包含虚拟线程；此测量不写入 `.jvmb`。源码跳转还需打开有对应项目/附加源码的 IDE，并自行核对版本。

## 双连接验收

分别在两个 PowerShell 终端启动未修改的 fixture，记录 PID A、PID B。可使用同一脚本；不要把两个页都连成同一个 PID。

1. 第一页连 A。用标题栏 **+** 或焦点位于工作台时 **Alt+Insert** 新建第二页，连接 B。两个标签应显示不同身份，点击切换不触发重新连接。
2. A 搜索 Probe、关闭 Read-only、将 Counter 写成 12。切到 B：Read-only 仍开启，读取 Counter 为 7。回 A：搜索、选择和已读结果仍保留。
3. A 开启 Auto · 2 s，记录采集窗口；切 B 至少 6 秒再回 A。隐藏期间不新增定时采样（离开前已发出的一次请求仍可能完成）。回 A 后继续后续采样；间隔超过 5 秒的趋势不连接折线。
4. 在 A 收藏 Probe，回 B 的 Favorites 中也应能找到；在 B 收藏另一个 MBean，回 A 应同时保留两项，不被另一页旧缓存覆盖。
5. 在 A 订阅 Probe 并调用 emit；B 未订阅时不会出现 A 的通知。订阅 B 后分别 emit，各自缓冲不串台。
6. 结束 A 测试终端中的 JVM，回 A 手动采样，应变 Stale；B 的 Sample now 和线程捕获仍成功。关闭 A 标签后，再次采样 B，仍成功。
7. 将 B 的现场保存。新建第三页打开这个文件，保持 B 活动页共存；关闭离线页不能断开 B。
8. 关闭所有页，IDEA 会收起工具窗口；再次打开应只剩一个新的空白工作台，能再连 B。关闭 IDE 项目会释放其所有页；不应自动新建连接或恢复写操作。

关闭页会丢弃未保存的捕获和备注。不要用未知业务目标测试“执行中关闭”；自动测试用受控阻塞任务验证迟到连接清理与另一页继续执行。标签页最多 8 个；第 8 个后 + 禁用，关闭一个后可再新增。要验证上限，空白页即可，无需启动 8 个 JVM。

## 远程、认证与 TLS

对你有权限的远程测试目标，在新页选 **Remote JMX**，输入 `hostname:port` 或完整 JMX URL，并填写目标配置的用户名/密码。IPv6 使用 `[::1]:9010`。先核对表单中的完整端点预览，再按目标配置选择 registry TLS；不要为连接方便而关闭服务端认证或证书校验。

本项目的交互脚本不提供远程服务。可运行下面的自动测试验证 **认证 loopback、hostname:port、权限拒绝及临时证书 TLS**；测试自己启停 JVM，不修改 IDEA/JBR 信任库。这不能替代真实远程网络、服务器证书及 PasswordSafe GUI 的验收。

```powershell
$env:JAVA_HOME = 'C:\Users\lenovo\.jdks\corretto-21.0.9'
$beaconIde = 'D:\IdeaProjects\JVM-Beacon\.intellijPlatform\ides\community-2025.1.3'
.\gradlew.bat test buildPlugin "-PlocalIdePath=$beaconIde"
.\gradlew.bat verifyPlugin "-PlocalIdePath=$beaconIde"
```

结果：`build/reports/tests/test/index.html` 为测试报告，`build/distributions/` 为安装包，`build/reports/pluginVerifier/` 为兼容性报告。无 IDE 时可运行 `./scripts/smoke-core.ps1 -JdkHome '<JDK21目录>'`，应输出 `CORE_SMOKE_PASS`；它不验证 GUI。

## 常见问题与复现记录

| 现象 | 检查与下一步 |
| --- | --- |
| 脚本说找不到 javac / 编译失败 | JAVA_HOME 应指向完整 JDK 21 根目录，不是 bin，也不是只有 java 的 JRE。 |
| 本地列表找不到 / Attach 拒绝 | 核对 fixture 仍运行、PID、同用户权限与目标 Attach 设置；可手填自己刚启动的 PID。不要尝试未知进程。 |
| 远程连接失败 | 核对 endpoint、registry 与 server RMI 两个端口、stub 主机名、认证、证书信任及 registry TLS 配置；简写不会自动配置这些。 |
| 没有 +，或只有 Switch JVM | 在 Plugins 中确认加载 0.6.1 并重启；+ 在 JVM Beacon 工具窗口标题栏，窗口窄时留意折叠菜单。 |
| 切走后曲线没更新 | 本版只轮询可见页，这是采集范围；隐藏不代表断开。启用 Auto 或 Watch 后回到该页继续。 |
| 操作超时 | 结果可能已生效。先核对目标状态，不重复点击执行；取消仅停止等待。 |
| 打包提示 JAR 被占用 | 只关闭本项目 runIde 开发沙箱，然后重试；无需关闭日常 IDEA。 |

反馈问题时记录插件/IDE/JDK 版本、Local/Remote、页编号、目标 PID/启动时间、操作顺序、预期/实际、窗口内错误分类和采集时间。截图或日志分享前移除凭据及敏感目标数据，不附密码或完整业务现场。
