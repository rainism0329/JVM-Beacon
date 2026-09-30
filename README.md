# JVM Beacon

IDEA 原生的 JMX 管理与 JVM 运行时诊断工作台。当前候选版本为 **1.0.0-rc.2**（2026-09-30）：核心流程无需云账号、外部 AI 或上传运行数据。源码仓库保持私有，准备按免费闭源方式由 **Philip Zhang / PhilZ Dev** 分发；未上传 Marketplace 或创建 GitHub Release。开发名称不代表已完成商标核查。

这一轮冻结功能范围，完成凭据取消/拒绝/超时清理、首用与故障处理说明、安装包核对和现有流程验收。已有按需 MBean 读取/写入/方法调用、复杂值/通知/追踪、JFR 统一区间与火焰图/GC/分配/等待分析、录制下载、Signal timeline、线程诊断、现场保存比较、多连接标签和 `hostname:port`。界面为英文、随 IDEA 主题变化。

**[English user guide](docs/user-guide.md)** · **[首发范围与验收](docs/release-candidate.md)** · [更新记录](CHANGELOG.md) · [自动检查](docs/validation.md) · [GUI 证据](docs/gui-validation.md)

**[Marketplace 发布材料](docs/marketplace/README.md)** 包括原创浅/深色 Logo、英文商店介绍、真实产品截图、快速开始、更新说明、FAQ、免费闭源 EULA 与隐私说明。材料检查用 `./scripts/verify-marketplace.ps1 -Ready`；打包用 `./scripts/package-marketplace.ps1`。两者不上传或发布。

第一次测试可按下方顺序操作：**安装 → 启动测试 JVM → 单连接流程 → 双连接标签页**。完整验收清单、预期结果和排错见 [测试指南](docs/testing.md)。文档中的待执行步骤不代表已经验收通过。

源码仓库保留构建脚本、测试程序与文档；`build/`、IDE 沙箱、凭据/证书及诊断现场不进入 Git。文档中指向 `build/` 的安装包、截图和原始证据属于本地验证产物，新克隆需按下方命令生成，不能把 GitHub 上缺少这些附件理解为已发布安装包。

## 安装与开始

首次试用建议使用 **IntelliJ IDEA Community 2025.1.3（IC-251.26927.53）/ JBR 21 / Windows**；各包的 GUI 实测逐版记录在验收文档。相同 build 的 Ultimate 通过官方兼容检查，GUI 待补验；rc.1 的加载记录不冒称 rc.2 GUI 通过。Community 使用官方完整发行包；被监控测试程序使用 JDK 21。描述符范围和 Verifier 结果不等于全部环境实测。

1. 在 IDEA 的 **Settings → Plugins → 齿轮 → Install Plugin from Disk…** 选择 [jvm-beacon-1.0.0-rc.2.zip](build/distributions/jvm-beacon-1.0.0-rc.2.zip)，不解压，按 IDE 提示重新启动；该包的实际验证状态见上方记录。
2. 打开项目，通过 **View → Tool Windows → JVM Beacon** 打开底部工具窗口。
3. 点击 **Connect JVM…**，选择当前用户可见的本地 Java 进程，或输入 PID。若该进程尚未开启本地管理端点，需要明确勾选 **Allow starting the local management agent if needed**；这会改变目标进程状态。
4. 连接成功后核对顶部的目标身份和启动时间。默认开启观察模式，自动采样关闭；可在“Telemetry”手动采样或开启每 2 秒采样。

本地可见不代表可 Attach；同用户权限、目标 JVM 配置、容器和操作系统限制均可能影响连接。第一次使用建议先运行下面的独立测试程序。

## 保存连接与重连

在 **Connect JVM… → Remote JMX** 输入 `hostname:port`，可设置 Alias / Group。**Save setup** 只保存配置；**Save as new** 创建副本。勾选 **Save connection setup in this IDE** 时，Connect 前会先保存配置，连接失败不会成为“最近成功”。

**Saved connections** 按分组/别名排列，可搜索别名、分组、端点和用户名；**Recently used** 只列出最近成功使用的最多 10 个已保存配置。选中后 **Use setup…** 返回可编辑表单，不立即连接或读取密码。保存上限 40 个；Forget setup 不关闭活动连接，也不删除 PasswordSafe 中的密码。

配置保存在 IDE 本机设置 `options/jvmBeaconConnections.xml`（禁用设置漫游），含地址、用户名、别名、分组及上次成功报告的 JVM 身份；这些是普通本地元数据，不是加密凭据。密码单独使用 PasswordSafe，不进入配置、项目或 `.jvmb`。不要把密钥填入别名、地址或备注。保存配置不包含本地 PID；任意不透明 JMX stub URL 只能取消保存后一次性连接。

连接后的顶部 **↻ Reconnect** 按钮重新访问本页端点；远程用户名非空时后台读取 PasswordSafe，未保存密码则返回预填表单。它会开始新的采集窗口，恢复只读并暂停采样，不重放属性写入、方法调用、通知订阅或启动管理代理的许可。相同/变化/不完整身份都有说明；这是比较目标报告的 runtime name、start time、VM name/version，不能证明服务器身份或排除 PID 复用。打开离线现场会清除该页重连入口。当前不自动重连，也不在 IDE 启动时连接。

## 用测试 JVM 走完真实流程

在项目目录的 PowerShell 中设置 JDK 21，然后运行测试程序。脚本及程序只服务于本机可控验证；结束时在程序终端输入 quit 后按 Enter。

```powershell
Set-Location 'D:\IdeaProjects\JVM-Beacon'
$env:JAVA_HOME = 'C:\Users\lenovo\.jdks\corretto-21.0.9'
.\scripts\run-fixture.ps1
```

将工作区及 JDK 路径替换为你自己的路径。这个脚本只提供 **Local JVM / PID** 测试，不会打开远程端口。也可传入 `-DurationSeconds 600`，使 fixture 最多运行 10 分钟后退出；定时模式不读取 Enter，以到时退出为准。独立复现核心流程与资源基线运行 `./scripts/smoke-core.ps1`；成功会输出 `CORE_SMOKE_PASS`。2026-09-23 核心验证生成的真实示例现场在 `build/examples/fixture.jvmb`，可用于离线重开。

运行 `./scripts/soak-core.ps1` 可进行默认 180 秒、每 2 秒一次的独立采集资源观察，生成 CSV 与报告；可用 `-DurationSeconds` 设为 20–600 秒。它使用自有认证 loopback JVM，并检查通知、现场读写和目标退出；测量的是独立采集进程，不代表整个 IDEA 的开销。[资源观察](docs/soak-validation.md) 保留 **1.0.0-rc.1 核心复测与最终包混合标签观察**及此前历史基线。真实 TLS 测试随 `test` 执行，边界见 [TLS 验证](docs/tls-validation.md)。

程序会输出 PID 和 `READY`。常规测试 MBean 为 `dev.jvmbeacon.demo:type=Probe,name=Workbench`；新增 `dev.jvmbeacon.demo:type=OnDemand,name=Workbench` 用于核对 getter 调用次数，具体见 [按需读取测试](docs/testing.md#mbean-按需读取0150)。

1. **连接与指标**：在插件里选择该 PID，允许启动本地管理代理并连接。查看 heap、CPU、平台线程和 GC 等指标；缺失或不支持的值显示说明，不当作零。
2. **搜索与复杂值**：进入“MBeans”，搜索 `dev.jvmbeacon.demo`，选择 Probe。选择 `Summary` 或 `Rows`，先点击 **Read value**（或在属性表按 Enter），再点击 **Explore…**：Structure 可用方向键展开、按字段/类型/值搜索，Rows 表格应显示 `current → 7`、`next → 8`（尚未修改 Counter 时）。选择单元格查看类型和精确值，点击列标题排序。可以收藏 MBean。`Forbidden` 和 `Broken` 是故意设计的权限拒绝与异常属性，不会显示成 null。
3. **属性与操作**：取消顶部“Read-only”，选择 `Counter`，点击“Edit…”并输入 `12`，核对目标后确认。操作 `add` 输入 `2`、`3`，结果应为 `5`；`twice` 输入 JSON 数组 `[1,2,3]`，结果应为 `[2,4,6]`。调用 `inspectRows()` 后点击 **Explore result…** 查看返回表格；已经修改 Counter 时应为 `12`、`13`。每次都单独确认，超时不自动重试。
4. **通知**：在“Notifications”页订阅当前 MBean，再调用 `emit`，输入一段测试文本。点击“Refresh”查看结果；最多保留最近 200 条，取消订阅会移除监听器。
5. **平台线程与源码**：获取平台线程快照后，按名称/ID 或状态筛选，例如搜索 `beacon`；计数是匹配数/已采集数，过滤不会重新查询目标。选择线程阅读栈，双击有文件和行号的栈帧或按 Enter，会按精确二进制类名和成员关系查找项目或已附加源码；多个候选不自动导航。匿名/局部类只能匹配外层候选行时需要确认。源码版本仍须核对；fixture 的虚拟线程不在此采集范围内。
6. **保存、重开与比较**：进入“Snapshots”，填写备注并保存 `.jvmb`。再采样或获取新线程快照后选择“Compare with file…”，固定结果不会被自动采样覆盖。双方身份匹配且都采过线程时，列出新增观察、未再观察及状态/栈/锁信息变化；不同目标、缺失或无效采集不输出线程变化计数。同 ID 只是匹配候选，不能据此证明持续阻塞、死锁或线程刚创建/结束。“Open snapshot…”成功后切换为离线阅读。
7. **断开与退出**：在测试程序终端输入 quit 后按 Enter 退出，再手动采样以观察断连提示。也可以用“Disconnect / Stop waiting”主动断开；已采集数据保留并标记为过期。

测试程序源码见 [DemoApplication.java](src/test/java/dev/jvmbeacon/fixture/DemoApplication.java)。它还提供 `fail`、有时间上限的 `slow`、有数量上限的 `burst`，供验证异常与有界缓冲。不要把这些验证操作应用于未知业务进程。

## Trend 不动或显示 Stale 时

连接后默认只采一个样本，**选择 Trend 下拉项只切换展示指标，不会启动采集**。点击图表下方 **Start live trend** 或勾选 **Auto · 2 s** 才持续采样；采样仍只在该连接页可见且无其他在途请求时进行。页脚显示 AUTO / PAUSED / DISCONNECTED / OFFLINE、最后采集时间和年龄、最多 120 点。点数与时间在增加但线为水平，表示该指标本轮值没有变化；试选 **JVM uptime (ms)** 来验证更新，空闲 fixture 的 heap/线程数可能长时间不变。

图表内存按 MiB、纳秒计数按 ms 展示，原始表格/悬停保留源单位与精确值。只有一个点时居中提示，需要更多样本才能有线；恒定值居中绘制。缺失、超过 5 秒的间隔、重复/倒退时间戳不连线；暂停历史不会补采。上下界留白，悬停查看实际样本窗口，不用图表最近点冒充当前数据。

**Stale 表示已断开，保留的是旧数据**。顶部保留断开原因；连接过程中会显示 Attach、管理代理、JMX/RMI、身份或初次采集阶段。连接等待上限 20 秒，普通请求仍为 8 秒；超时不等于目标已停止执行。网络池忙时保留已有连接并暂停自动采样，可在空闲后手动恢复，不自动重试写操作。

默认 `run-fixture.ps1` **不设定时退出**，0.6.1 起需在终端输入 `quit` 再 Enter 才退出，单按 Enter 被忽略；终端关闭/输入流关闭也会结束 fixture，并打印原因。旧脚本单按 Enter 就退出，容易导致下一次采样变 Stale。若使用 `-DurationSeconds` 则到期结束，CPU 演示线程自己的 120 秒上限不代表整个 JVM 一定同时结束。重新启动 fixture 后一定按新输出 PID 连接；首次还需允许启动本地管理代理。

## 多指标时间线与区间保存

1. 连接自有 fixture 后进入 **Timeline → Start live**，四条信号随现有 2 秒采样更新；**Pause live** 暂停采样。移动鼠标或使用 **Inspect #**，四条轨道共享游标，底部显示原始精确值和实际读取窗口。图表内存单位为 MiB，精确值仍为 bytes。
2. 点击 **Freeze & select**，冻结当时最近最多 120 个样本。冻结只固定视图，已开启的采样仍可继续。使用 **From # / to #** 选择样本序号，进入 **Interval comparison** 查看首尾值与差值。**Follow latest** 返回当前保留的历史；更早的样本不会补回。
3. 在 **Snapshots** 填写备注后返回 Timeline，选择 **Save interval…** 保存选中的指标区间、目标身份与备注；不附带可能属于其他时段的线程。**Snapshots → Save snapshot…** 则保存当前保留的全部指标历史和一份单独采集的线程快照。
4. **Open capture…** 打开 `.jvmb` 后关闭本页实时连接，以 OFFLINE 展示已保存数据。多样本文件自动进入 Timeline，仍可选择更小区间另存。旧 v1/v2 只有当时保存的单个指标点，不能恢复过去趋势；0.8.x 及更旧插件不能读取 v3。

GC 曲线是近似累计采集时间之和，不是暂停事件或暂停总占比。CPU 是目标 JVM 报告的近期全 CPU 负载，不代表恰好这两秒的平均值。端点差值不是速率或根因；缺失/单位变化/时窗无效会抑制比较，GC 计数回退也不输出差值。间隔超过 5 秒或时钟倒退断线，保留真实零与缺失区别。所有样本按采集顺序保留，读取并非原子操作；不覆盖虚拟线程。

快速生成一份真实且含采样空档的离线示例：

```powershell
.\scripts\capture-timeline-demo.ps1 -JdkHome 'C:\Users\lenovo\.jdks\corretto-21.0.9'
```

约 15 秒后输出 `TIMELINE_CAPTURE_PASS`；打开输出目录中的 `timeline.jvmb`（8 点）或 `interval.jvmb`（选出的 4 点）。脚本只启动自己的认证 loopback fixture，使用 observer 读取，并在结束时清理。完整手工流程与验收边界见 [时间线测试](docs/testing.md#多指标时间线与采集区间090)。每页历史 120 点，冻结最多额外保留 120 点，文件上限仍为 5 MiB；不自动写磁盘或上传。保存前检查备注等敏感文本。

## 排查锁等待与比较线程

先排查锁等待可用 **Threads → Lock chains → Capture threads**。左侧按名称、ID、状态或 owner 筛选；选中等待者后，右侧按 `waiter → owner → …` 展示链路，点任一成员阅读其栈，再选帧 **Go to source**。所有筛选/选择只读已采集的数据，不会重新调用目标。

在 **Threads → Compare** 点 **Pin baseline A**，释放竞争或等待业务变化后 **Capture threads → Compare A → current B**。结果是固定表格，包含状态 A/B、变化字段和首个不同栈帧，支持筛选、排序和复制。**Snapshots → Compare with file…** 同时生成此表格，文件作为 A、选择文件时的现场作为 B；后续实时采样不会覆盖它。

测试 fixture 已有 `startLockContention(int seconds)` / `releaseLockContention()` 两个 MBean 操作，seconds 为 1–120。只有明确调用才创建三个线程；到期自动释放并退出，提前释放后暂保留线程至截止时间以便比较，再次启动会先清理上一组。完整步骤见 [锁链与比较测试](docs/testing.md#锁链与结构化线程比较070)。只在自有 fixture 上测试。

想快速体验离线锁链/比较，可运行 `./scripts/capture-lock-demo.ps1 -JdkHome '<JDK21目录>'`。脚本自动启动一个认证 loopback 测试 JVM，采集真实竞争 A、主动释放后采集 B，校验变化后关闭该 JVM；结果保存在新建的 `build/examples/locks-时间戳/`。看到 `LOCK_CAPTURE_PASS` 后，在插件中打开 `locks-B.jvmb`，再 Compare with file 选择同目录 `locks-A.jvmb`。不需要抢在手动 fixture 的截止时间前操作。

锁边按 owner ID 建立，同名不连边；`No owner reported` 不等于没有锁，未采到 owner 不等于线程已退出。JVM 死锁查询与链路中观察到的环单独解释。最多处理 512 个平台线程、每栈 64 帧、每条链 64 个成员；不覆盖虚拟线程，不推断两次采样间持续阻塞。`.jvmb` v2/v3 保存 owner ID，读取 v1 时标为未采集；0.6.x 不能打开 v2。比较报告、Hot threads 和任意 MBean 属性追踪不随现场保存，可保存 A/B 两个现场后重新比较；栈仅持久化类/方法/文件/行，不含 module/class-loader 元数据。0.9.0 起普通 JVM 指标历史随 v3 现场保存。

## 测量 CPU 活跃线程

进入 **Threads → Hot threads → Measure CPU · 1 s**。插件读取两次批量线程 CPU 计数，中间等待 1 秒；表中按 CPU 增量排序，青绿短线表示相对于一个 CPU 核的近似占用。点击数值列标题排序、按名称/ID/状态筛选，选择线程查看精确纳秒增量和末次栈。选择栈帧后 **Go to source**、双击或 Enter 沿用源码匹配检查。

**Capture details…** 给出目标、两个实际采集窗口、计数读取耗时、估算间隔和覆盖范围；**Copy report** 复制全部已采集行和末次栈，不受搜索筛选影响，不自动脱敏。每页只保留最后一次测量；断开标为 Stale，换目标或打开离线现场时清除。此报告暂不包含在 `.jvmb` 中。

仅支持暴露 `com.sun.management.ThreadMXBean.getThreadCpuTime(long[])` 的目标；不支持、未开启、权限拒绝、计数不可得均有说明，插件不会自动启用 CPU 监控。最多选取 512 个基线平台线程、每个末次栈 64 帧；不覆盖虚拟线程和基线后新出现的线程。线程 ID 可能复用，名称相同也不能证明同一线程；计数降低或身份有变化时不计算增量。1 core = 100%，不是整机 CPU 百分比，远程时延会影响估算。**末次栈是另一次观察，不能据此认定哪个方法消耗了 CPU**；这还不是方法级 profiler。

安全复现热点（仅自有测试进程）：

```powershell
.\scripts\run-fixture.ps1 -JdkHome 'C:\Users\lenovo\.jdks\corretto-21.0.9' -DurationSeconds 120 -CpuDemo
```

看到 PID 后立即连接并测量，`beacon-fixture-cpu-pulse` 应有非零 CPU 增量。脉冲每轮计算约 40 ms、等待至少 160 ms，最多 120 秒，并随测试程序停止清理；实际 CPU 百分比不保证固定。详细步骤见 [Hot threads 验收](docs/testing.md#hot-threads-验收)。

## 多个连接：用标签页切换

工具窗口标题栏的 **+ / New connection tab** 新建独立工作台；焦点在 JVM Beacon 内时也可按 **Alt+Insert**。在新页点击 **Connect JVM…**，选择第二个本地 PID 或 Remote JMX 地址。点击原生标签即可切换，标题包含页编号与目标运行时名称；悬停显示身份和连接状态。**Replace JVM…** 只替换当前页的目标，要保留原连接请先新建页。

- 每页独立保留指标趋势、MBean 选择/搜索/结果、追踪、通知、线程、备注及现场比较。Read-only 默认开启，每页独立控制。MBean 收藏是应用级设置，切换回页时同步。
- **只有当前可见页会自动轮询**；隐藏页保留连接和已采集数据，已订阅通知仍进入其最多 200 条的缓冲。切回后继续后续采样，不能补回隐藏期间历史。手动请求已发出时仍可能在后台完成。
- 标签上的关闭按钮只断开并释放该页。未保存的现场和备注随页关闭丢弃；目标上已开始的操作可能继续，关闭不表示撤销。关闭最后一页时 IDEA 会收起工具窗口；再次打开时已有新的空白工作台。
- 每项目最多 8 页（含离线和空白页）；所有项目共同遵守 4 个网络工作线程、16 个连接许可。页不会跨 IDE 重启恢复，也不自动重连。离线现场可在新页中打开，与其他活动连接并存；当前不提供多目标联合图表。

**双 JVM 测试**：在两个 PowerShell 终端分别运行上面的 `run-fixture.ps1`，记录不同 PID 为 A、B。第一页连 A，再用 **+** 连 B。只在 A 关闭 Read-only 并将 `Counter` 改为 `12`；切到 B 后应仍为 Read-only，读取 `Counter` 应为 `7`。切回 A 应保留搜索和结果。关闭 A 标签后，在 B 点击 **Sample now** 仍应成功。详细的退出、追踪及离线共存检查见 [测试指南](docs/testing.md#双连接验收)。

## 远程连接与数据边界

“Remote JMX”接受 `hostname:port`（例如 `my-server:9010`）、IPv4、`[::1]:9010` 或完整的 `service:jmx:rmi:…` URL。输入框下方显示实际端点：`my-server:9010` 展开为 `service:jmx:rmi:///jndi/rmi://my-server:9010/jmxrmi`；自定义 registry binding 继续输入完整 URL。目标必须已启用远程 JMX，简写不自动配置目标、网络或 TLS。

用户名与密码单独填写。**RMI registry 使用 TLS** 默认开启；它仅控制 registry，JMX server 的 TLS 仍由服务端 stub 决定，应匹配服务端配置。证书信任使用运行 IDEA 的 JBR，不会自动关闭校验。远程连接还需要核对 stub 中的主机名、第二个 RMI 端口、网络路由及服务端授权。IPv6 地址解析已有测试；实际 IPv6 网络连接尚未验证。

凭据可显式从 PasswordSafe 载入，或在连接成功后保存；不写入项目配置、日志或现场文件。客户端观察模式只是防误触，不能替代服务端权限。读取属性也可能有开销或副作用。

简写与其等价标准完整 URL 使用同一个凭据键；不同端口和用户名隔离。不会将 DNS 别名、`localhost` 与 IP 地址自动合并。

## 追踪一个 MBean 数值

在 **MBeans → Attributes** 选择可读数值属性，点击 **Watch…**，核对目标及 getter 的潜在开销后 **Start tracking**。例如 fixture 的 `ElapsedMillis` 会递增，`Counter` 初始为 7。**Watch** 页呈现独立采集窗口、趋势和精确值表；可 Pause / Resume 或 Clear history。读取失败、null、NaN、不支持的返回类型会留下原因并暂停，不当作零。

一次追踪一个数值属性，最多 120 点（含暂停/失败间断标记），工作台可见且无在途请求时每 2 秒尝试采集；与标准指标共用调度。图表使用近似 double，数值表保留受支持值的精确文本，单位未提供时明确为未知。不能补采暂停期间的历史；取消不保证 getter 已停止。断开后保留 STALE，连接另一个 JVM 或离线打开现场会清除旧追踪。**追踪历史不包含在 `.jvmb` 文件中**。

指标时间窗口表示客户端读取的起止时间。MBean 读取和操作结果也显示后台实际采集窗口，多属性逐项读取并非原子快照。`ProcessCpuLoad` 的近期负载由目标 JVM 计算，其内部统计窗口不等于插件的 2 秒轮询间隔；GC 次数/时间、进程 CPU 时间为累计量。

v3 现场格式保存目标标识、最多 120 个保留的指标样本、一份平台线程快照和备注，并保留各自采集窗口与缺失信息；区间另存不带线程。它不保存任意 MBean 值、通知、连接 URL、凭据、命令行或已丢弃/未采集的历史。**目标标识、线程名、栈、锁信息和备注不自动脱敏**，分享前请自行审查。

## 阅读复杂 MBean 结果

属性先 **Read value**，再 **Explore…**；操作返回后 **Explore result…** 展示这次已采集的值、目标和实际读取/调用窗口。`CompositeData` 展开字段，数组显示下标，`TabularData` 显示索引字段并额外提供 Rows 表格；行号不代表跨采集的身份。搜索只影响 Structure，匹配节点的祖先用于保留上下文；Rows 在字段完整且名称无歧义时显示捕获的行；字段截断、失败或名称冲突时明确禁用 Rows，仍可用 Structure 阅读保留节点。Rows 按显示文本排序，不做数值大小推断。原始文本仍可在主工作台复制，树里的 **Copy value** 复制所选节点值（容器节点为摘要）。

工具窗口较矮时可向上拖动顶部边缘，表格与详情之间的细分隔线也可拖动。长文本可以滚动阅读，Rows 的列宽可以调整。此版查看器每次在当前屏幕居中打开，内部的分隔比例会保留。

后台转换后仅保留不可变展示模型，不将任意远程对象交给 EDT。每个值最多 512 节点、32768 个字符、深度 6、每个容器 100 子项；每对象最多保留最近 8 项读取，共最多 4096 节点和 262144 结构字符。截断、循环、不支持和读取失败分别说明；已淘汰的值需显式再读，未读取/失败/淘汰项禁用 Explore。展示限制不能限制 RMI 接收巨大对象的反序列化开销。嵌套表格可在 Structure 展开，Rows 仅用于顶层 `TabularData`，没有强行展开任意 Java 对象。

查看器的展开、排序、搜索、复制不重复读取目标。返回值不自动脱敏、不进入 `.jvmb`；再次选择操作会清除上次结果。整个读取/调用流程仍遵守超时、取消和不自动重试的约束。

## 当前能力与限制

- 本地 Attach、远程 JMX/RMI；连接身份、失败阶段提示及手动重连。暂不支持运行配置自动关联、SSH、Jolokia 或容器自动发现。
- ObjectName 搜索与应用级收藏；属性读取、复杂值文本/结构树/顶层表格、严格类型校验后的写入和精确签名操作调用。编辑支持基础标量、常用数值类型、`ObjectName`、基础类型数组和 `String[]`；不反射构造任意目标对象。
- 选中 MBean 时只读取元数据，属性值须显式点击 Read value；每对象保留最近 8 次读取，元数据和文本有上限；这些展示限制不能限制 RMI 接收巨大对象时的反序列化开销，当前仅连接可信目标。
- 实际指标与最多 120 点趋势，2 秒可选采样，无请求重叠；超过 5 秒的采样间隔不连接折线。JFR 提供录制、事件库存、采样调用树/火焰图；不提供健康分或自动根因判断。
- ThreadMXBean 平台线程快照、名称/ID/状态筛选：最多 512 条线程、每栈最多 64 帧，明确标识截断。现场线程比较只处理已采集范围，最多显示 200 条差异。缺失不等于零，栈未变化不等于持续阻塞；不覆盖虚拟线程。源码和运行字节码版本的一致性仍需用户确认。
- `.jvmb` 现场保存、离线重开与文本比较；文件上限 5 MiB。指标与线程可能在不同时间采集，不是原子快照，也不能恢复未采集历史。
- 每个视图最多一个在途任务；连接截止时间 20 秒，普通请求 8 秒。取消或超时不保证底层 Attach/RMI 已停止；迟到结果丢弃，修改结果可能未知。全局网络/Attach 池最多 4 个线程，本地文件与 PasswordSafe I/O 池最多 2 个线程，均无任务队列且彼此隔离；网络池耗尽不会占用离线任务的执行名额。16 个连接许可覆盖连接中、活动和关闭阶段，每个已接纳连接预留清理容量。

不同 IDEA、JDK、操作系统、真实 TLS 环境及长时间运行的验证范围请以 [验证记录](docs/validation.md) 为准，不能由编译或单元测试结果推断。

## 构建与验证

构建需要 **JDK 21**；IDE 运行时使用目标 IDEA 自带 **JBR 21**；首轮目标 JVM 使用 **JDK 21**。三者是不同的环境要求。

```powershell
$env:JAVA_HOME = 'C:\Users\lenovo\.jdks\corretto-21.0.9'
$beaconIde = 'D:\IdeaProjects\JVM-Beacon\.intellijPlatform\ides\community-2025.1.3'
.\gradlew.bat test buildPlugin "-PlocalIdePath=$beaconIde"
```

构建产物位于 `build/distributions/`，测试报告位于 `build/reports/tests/test/index.html`。首次构建可能需要下载 Gradle、构建插件和测试依赖；`localIdePath` 指向完整 IDEA 安装目录。上例使用本机官方 Community 包，也可将 `$beaconIde` 改为 `E:\JetBrains\IntelliJ IDEA 2025.1.3` 或自己的安装路径。

```powershell
# 官方兼容性检查
.\gradlew.bat verifyPlugin "-PlocalIdePath=$beaconIde"

# 对同一个安装包追加第二个 IDE 的兼容性检查
.\gradlew.bat verifyPlugin "-PlocalIdePath=$beaconIde" '-PadditionalVerificationIdePath=E:\JetBrains\IntelliJ IDEA 2025.1.3'

# 在独立开发沙箱中启动 IDEA
.\gradlew.bat runIde "-PlocalIdePath=$beaconIde"
```

`additionalVerificationIdePath` 为 `verifyPlugin` 增加第二个目标 IDE；构建和开发沙箱仍使用 `localIdePath`。

自动化测试仅连接自己启动且会清理的 fixture JVM；认证远程 fixture 仅监听 loopback。`verifyPlugin`、插件加载和交互验收的实际成功、失败与未验证项集中记录在 [docs/validation.md](docs/validation.md)。

开发入口：[AGENTS.md](AGENTS.md) · [调研与证据](docs/research.md) · [产品/工程决策及路线](docs/decisions.md) · [验证与接续状态](docs/validation.md)。

## JFR 录制与本地事件库存（0.10.0）

1. 连接本轮测试 JVM，进入 **Flight Recorder → Check / refresh**。发现 `default, profile` 后，取消顶部 Read-only，点击 **Record…**。确认目标、预设和 5–120 秒时限；默认 30 秒。录制会消耗目标 CPU、内存和磁盘。
2. 可点 **Stop…** 提前停止，或等时限后点 **Check / refresh**。只有目标报告 STOPPED 才允许 **Download .jfr…**。状态标有检查时间，不自动轮询，不把本地倒计时当远程事实。
3. 选择新的本地文件名。下载上限 64 MiB / 循环预算 45 秒，IDE 最多等待 60 秒；不覆盖已有文件。下载后显示可搜索/排序的事件表，**Inventory details** 保留时间范围、来源及截断说明。事件数不等于耗时、CPU 百分比或所有活动。
4. **Release…** 关闭本页自有录制并丢弃目标端保留数据。断连、换目标或关闭页也会请求清理，网络失败时不能保证已释放；目标端时限仍限制录制时长，但保留数据可能需要管理员清理。不会接管或关闭其他工具创建的录制。
5. 无连接时仍可 **Open local .jfr…**。深入分析可用 **Copy file path**，在另行安装的 JDK Mission Control 中 File → Open File 打开。插件不自动安装/启动外部软件；插件已有下述调用树、火焰图和事件分析；不输出自动根因结论。

`.jfr` 不并入 `.jvmb`，不会自动脱敏，可能含参数、属性、路径、线程/栈及应用数据。只打开可信来源文件；本地解析限制为 64 MiB、200,000 事件、256 个命名类型、5 秒扫描预算，JDK 单次解析可能超出这个软时间预算。32 MiB 目标保留设置不是总内存/磁盘或开销硬上限。当前实测目标为 Corretto 21.0.9；目标没有 JFR MXBean 时显示 UNAVAILABLE。

无需 IDE 的真实复现：

```powershell
.\scripts\capture-jfr-demo.ps1 -JdkHome 'C:\Users\lenovo\.jdks\corretto-21.0.9'
```

成功输出 `JFR_CAPTURE_PASS`，时间戳目录内有 `capture.jfr`、`inventory.txt`、`stacks.txt`、`evidence.txt`；脚本启动自有认证 loopback JVM，显式开启有时限的 CPU 脉冲，使用 profile 录制 8 秒，验证捕获到 cpuPulse 后下载、重读并清理，不连接业务进程。完整负向用例和预期结果见 [测试指南](docs/testing.md)。

## JFR 火焰图与调用树（0.11.0）

先运行上面的 `capture-jfr-demo.ps1`。无需保持目标运行，在 **Flight Recorder → Open local .jfr…** 打开输出的 `capture.jfr`，再选择 **Sampled stacks**：

1. 默认查看 `jdk.ExecutionSample`，**Flame graph** 从顶部根路径向下展开；宽度表示当前已表示样本数。`jdk.NativeMethodSample` 单独选择，不能把 native 等待栈的宽度解释成 CPU 时间。
2. 选择保留的 sampled thread（例如 `beacon-fixture-cpu-pulse`），点击 **Apply filters**。界面过滤只用本地有界副本，没有新的网络请求；**Highlight method / class…** 只高亮，不改变分母。
3. 单击帧查看 inclusive/self 样本数、方法描述符、记录内 class ID 和行号；**Zoom selected** 放大路径，**Reset zoom** 返回全图。占比始终相对当前筛选后已表示样本，缩放不更改分母。像素以下的小帧可从 **Call tree** 用方向键选择。
4. **Find source candidate…** 仅在类、方法描述符和方法所属行均匹配项目或附加源码时提供候选确认。JFR 没有提供 source filename，源码版本和 class loader 到依赖的映射未验证；匿名/隐藏类、缺行号、重复候选等情况不猜测导航。
5. **Coverage** 和图上方显示扫描部分状态、缺失栈、被预算省略的样本与截断数量。**Copy evidence** 复制范围说明和选中帧，不自动上传或导出整棵树。没有采样不代表没有活动。

每次已应用范围扫描最多保留 20,000 个采样、200,000 帧引用、128 帧/栈、256 个线程、8,192 个不同帧、2 Mi 字符帧元数据；每个名字/描述符上限 512 字符。每棵树 8,192 节点，超过时省略整条样本路径并计数。选择数量不等于完整录制数量；截断栈以 `[older frames not captured]` 显示，按文件遍历先到先保留。已有统一时间范围与 GC/等待事件附近观察；暂无分配火焰图、跨事件因果归因或完整 JMC 分析。

## JFR GC 与分配分析（0.12.0）

打开或下载 `.jfr` 后，切换 **Flight Recorder → GC & allocations**：

- **GC timeline**：紫色为 `GarbageCollection` 周期，青色为顶层 `GCPhasePause`；点击标记或用键盘选择表格行，下方显示纳秒精度与 UTC 时窗。Cycle 可能含并发工作，不是暂停时长。嵌套阶段不重复累加；小于 2 像素的事件显示最小标记，精确时长以详情为准。
- **Allocation pressure**：按 `ObjectAllocationSample.weight` 聚合到记录内 class ID + 类名；权重单位是 bytes，表示统计分配压力，不是单对象大小、精确分配量或存活内存。搜索不会改变占比分母；点击列头排序。复制所选证据会附带覆盖说明。
- **Coverage**：保留总数、无效/不支持、遗漏、扫描窗口和范围。最多保留 4,096 GC 事件、2,048 类，沿用同次 64 MiB/200k events/5 s 扫描；超限结果不保证代表性。无事件不能排除问题；当前未关联堆前后量、allocation stack 或跨事件因果。

快速生成真实演示文件（只启动独立测试 JVM，结束后自动退出）：

```powershell
.\scripts\capture-memory-demo.ps1 -JdkHome 'C:\Users\lenovo\.jdks\corretto-21.0.9'
```

成功输出 `MEMORY_RECORDING_PASS` 和 `build\examples\memory-时间戳\memory.jfr`，在 **Open local .jfr…** 选择该文件。测试堆上限 64 MiB、Serial GC、最多 256 MiB 累积分配/约 2 MiB 数组载荷保留，循环最多 3 秒；会在自有子进程中显式请求 GC，不连接已有业务进程。文件含额外测试事件，用于验证异常权重；产品不会将这些事件冒充标准 JDK 样本。更多手工步骤见测试指南。

## JFR 等待热点与事件（0.13.0）

打开录制后选择 **Flight Recorder → Wait analysis**：

1. 选择 All event kinds / Monitor entry / Object.wait / Park，可输入线程名、类名或记录中的方法名，再 **Apply filters**；筛选只在本地进行。
2. **Wait hotspots** 按类型 + 目标类 ID/名字 + 记录叶帧聚合。点击列头排序；选择热点后 **Show hotspot events**，或按 Enter/双击，下钻实际事件。**All filtered events** 返回当前过滤后的全部事件。
3. 在 **Events** 选择一行，**Inspect event…**（或 Enter/双击）显示精确纳秒时长、时窗、事件线程、历史 previousOwner/notifier 和叶帧在前的栈。选择有行号的帧可 **Find source candidate…**，仍需唯一匹配和确认，不能保证源码版本一致。
4. **Copy report** / **Copy event & coverage** 带上筛选、覆盖与边界；缺栈不丢弃已记录事件时长。Coverage 区分未记录/预算遗漏/截断栈与缺失事件。

总时长可在并发线程间重叠，不是 CPU 时间、锁持有时间或墙钟百分比。同类不等于同一个锁对象；本页不保留对象地址，也不据历史 owner 字段构造当前死锁图。Park 可能是正常空闲或协作等待。本轮 Corretto 21 测试的普通虚拟线程 park 未生成 ThreadPark 事件；虚拟线程标签只说明已有事件元数据，不保证完整覆盖。

生成独立可清理的真实测试文件：

```powershell
.\scripts\capture-waits-demo.ps1 -JdkHome 'C:\Users\lenovo\.jdks\corretto-21.0.9'
```

成功输出 `WAIT_RECORDING_PASS` 和文件路径。自有 JVM 堆上限 64 MiB，显式零阈值录制；制造约 140 ms 监视器竞争、120 ms 条件等待、160 ms 平台/虚拟线程 park，所有等待及 join 有上限，工作线程和 JVM 完成后退出；不连接业务进程。搜索 `beacon-wait` 定位自有负载。文件中额外的自定义测试探针不会被产品当成标准 wait 事件；一般 profile 录制的阈值可能隐藏短事件。

## 统一 JFR 时间区间（0.14.0）

1. **Flight Recorder → Open local .jfr…**。全局栏显示 FULL、匹配/已扫描事件数；这不是录制完整性的保证，PARTIAL 仍须看 Coverage。
2. **Time range…** 输入相对已扫描文件最早事件的秒数（最多 9 位小数），From 包含、Until 不包含；对话框给出 UTC 起点和最大结束偏移。点击 **Apply range** 后，所有分析页一起更新。各页已应用的 kind/线程/搜索保留，选择和缩放清除；未提交的筛选草稿不作为已应用筛选。
3. **GC & allocations** 选一个 GC，点击 **Focus event ±100 ms**；或在等待事件的 **Inspect event…** 中点击同名按钮。这样可直接查看该事件附近的采样栈、分配和其他等待。范围按整个已扫描文件窗口限制；可以继续用 Time range 调整。
4. **Full recording** 重扫并恢复整个已扫描范围。应用失败时继续显示旧范围，标记 Update failed；不会只更新其中一页。文件被替换或修改时重新 Open，不能把旧时间选择套在已变化的文件上。

瞬时事件按 [From, Until) 选入，持续事件只要与范围有正相交就纳入；GC/等待的完整时长和 sumOfPauses 等字段不按区间裁剪或摊分，因此合计可能超过选区长度。GC 图仅裁剪可见标记，精确值仍在详情。分配权重从原始事件重新汇总，搜索后的份额分母是当前范围内所有保留类权重。同一时段出现不等于因果关系。

每次应用都在后台读取本地文件，无目标调用、文件写入或上传。文件扫描仍受 64 MiB/200k events/5 秒软预算限制，扫描顺序不当作时间顺序；部分扫描可能漏掉所选区间。size/mtime/fileKey 检查能发现常规文件变化，不是内容哈希或认证。JFR 不随 .jvmb 导出，选区也不生成新 .jfr。

生成具有两段真实 CPU/分配/GC/等待负载的测试文件：

```powershell
.\scripts\capture-range-demo.ps1 -JdkHome 'C:\Users\lenovo\.jdks\corretto-21.0.9'
```

成功输出 RANGE_RECORDING_PASS 和 range.jfr 路径。独立 64 MiB SerialGC JVM、两段各最多 32 MiB 累积分配/约 1 MiB 数组载荷保留、各 180 ms CPU 循环、有限条件等待与监视器竞争；只在自有子进程显式 GC，finally 清理 owner 线程，运行完成退出。实际事件数量受 JDK/调度影响，不固定等于某次验收数字。
