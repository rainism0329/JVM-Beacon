# 独立采集进程的资源观察

入口：`scripts/soak-core.ps1`；实现：`src/test/java/dev/jvmbeacon/core/ResourceSoakMain.java`。它独立使用 `javac/java`，不启动 Gradle，不连接未知业务进程。

```powershell
.\scripts\soak-core.ps1 -JdkHome 'C:\Users\lenovo\.jdks\corretto-21.0.9'
# 默认实际采样 180 秒；可指定 20～600 秒，预热与收尾另计。
.\scripts\soak-core.ps1 -JdkHome 'C:\Users\lenovo\.jdks\corretto-21.0.9' -DurationSeconds 600
```

每次运行在 `build/reports/resource-soak/<时间>/` 写入 `summary.md`、`samples.csv` 与最后一次 `last-capture.jvmb`。原始报告不记录密码或远程 URL；现场仍包含自有目标标识、平台线程名和栈，不能视为匿名数据。

## 方法与解释范围

- 启动一个独立、认证且仅监听 loopback 的 JMX fixture。采集进程最大堆 128 MiB，目标最大堆 96 MiB；两个 JVM 都使用指定的 JDK。
- 预热 3 次采样、线程读取、订阅/发送/取消订阅，然后每秒观察一次相关线程，连续 5 次。正式窗口每 2 秒串行采样，不补发错过的周期。
- 正式窗口每 10 个样本订阅一次通知，5 个样本后取消；每 5 个样本读取平台线程、保存现场并重开验证。报告中的操作计数不含预热。
- 记录 `sample()` 本身的时延；额外线程、通知和磁盘操作不计入这个时延。CPU/堆/线程来自**独立采集进程**，包含装置自身记录开销，不含整个 IDEA，也不能直接当作插件整体开销。CPU 百分比以占满一个逻辑 CPU 为 100%；`-1` 表示当前 JVM 不提供该值。
- 堆 used 不强制 GC，仅表示采样瞬间的已用堆；报告的最大值是实际采样观察到的最大值，无法保证捕获两点之间的峰值。
- 采样后在连接仍打开时观察 5 次线程计数；然后真实终止自有 fixture，验证后续读取失败。关闭客户端后再观察 8 次相关线程。名称以 JMX / RMI / beacon 筛选，并归一化数字；`beacon-soak-*` 是装置线程。
- 单个后台任务 10 秒截止，关闭调用最多等待 5 秒；超时不保证底层调用停止。出现失败即停止新采样并执行 finally 清理；迟到连接尝试关闭。另有“请求时长 + 100 秒”的进程内看门狗，最终通过结束自有 fixture 与本次独立采集进程限制存活期。
- 客户端关闭是否返回、自有目标是否退出及采集 executor 是否终止均参与完整流程判定。线程计数没有硬编码的精确通过门槛；有限窗口内计数一致不证明无泄漏，变化也可能来自 RMI 缓存或定时线程生命周期。

本装置不能替代 IDEA 内多项目关闭、窗口销毁、迟到回调、长时 CPU/堆基线和 GUI 交互验证。完整项目验证入口见 [validation.md](validation.md)。

## 1.0.0-rc.1 首发候选观察（2026-09-28）

Windows 11 / Corretto 21.0.9 / 16 逻辑处理器，23:01 起执行默认 180 秒，`RESOURCE_SOAK_PASS`。核心采集路径之后没有修改，后续更改只涉及 JFR 筛选 UI。原始 [摘要](../build/reports/resource-soak/20260928-230131/summary.md) / [CSV](../build/reports/resource-soak/20260928-230131/samples.csv) / [运行日志](../build/reports/soak-1.0.0-rc.1.txt)。

| 观察项 | 实际值与解释 |
|---|---|
| 工作流 | 180.001 s / 90 个指标样本；线程采集与 save/load 各 18 次；订阅/周期移除各 9 次 |
| sample() 时延 | median 59.750 ms / p95 67.011 ms / max 72.662 ms；不含同周期额外操作 |
| 独立采集进程 CPU | 2,156.250 ms；平均 1.19791% 单核，包含装置序列化和文件读写 |
| 最大观察堆 used | 18,675,696 bytes；非强制 GC 后保留量 |
| 清理 | 自有目标已退出，采集 executor 已终止，close 调用已返回；目标退出后 close 返回 ConnectException，不是未结束的调用 |

另对最终包 IC GUI 做三次 `jcmd Thread.print` / `GC.heap_info`，不触发强制 GC，测量整个 IDE PID 30928：

| 时点 | plugin-owned 线程观察 | 整个 IDE heap used |
|---|---|---|
| 活动 JFR 分析页 + 第二空页 | 1 call worker + 1 deadline | 460,600 KiB |
| 8 混合页（一隐藏活动、一离线、六空页） | 1 deadline，call/local I/O worker 已空闲退出 | 457,068 KiB |
| Close All 后、重开工作台前 | 1 deadline，无 call/local I/O worker | 371,046 KiB |

原始 [两页线程](../build/reports/rc1/ic-final-two-tabs-threads.txt)、[八页线程](../build/reports/rc1/ic-final-eight-tabs-threads.txt)、[全部关闭后线程](../build/reports/rc1/ic-final-closed-tabs-threads.txt)；对应 `*-heap.txt` 位于同一目录。随后新建连接走完核心流程并离线重开，Close Project 返回欢迎页后又采一次 [线程](../build/reports/rc1/ic-final-project-closed-threads.txt)，同样仅保留应用级 deadline；IDE 正常退出。

这些有限观察没有显示按空标签增长的工作线程，也验证隐藏页停止轮询后可空闲回收。堆变化包含 IDE 索引、JIT、GC 和 UI，不可相减当插件占用；未测最坏八个满载 JFR 页、持续数小时、多项目反复打开关闭或每次 EDT 停顿。并行运行其他 IDE/构建，未建立空闲机器性能基线。[资源预算](decisions.md#生命周期与预算)仍是待测目标；原有小历史预算不能当成八页完整 JFR 分析的已证实内存上限。不把本次有限观察称为无泄漏证明。

## 0.14.1 审查期间核心复测（2026-09-28）

Windows 11 / Corretto 21.0.9 / 16 逻辑处理器；17:14 起执行，默认 180 秒，`RESOURCE_SOAK_PASS` / exit 0。这是本轮核心补丁后的独立进程观察，后续 UI 门控调整不改变此测量路径。构建与审查并行，未做空闲机器基线。

原始文件：[摘要](../build/reports/resource-soak/20260928-171357/summary.md)、[CSV](../build/reports/resource-soak/20260928-171357/samples.csv)、[现场](../build/reports/resource-soak/20260928-171357/last-capture.jvmb)。

| 观察项 | 实际值 |
|---|---|
| 采样与工作流 | 180.001 秒 / 90 次；线程与 save/load 各 18 次；订阅/周期取消各 9 次 |
| sample() 时延 | 中位数 63.428 ms / p95 83.462 ms / 最大 102.363 ms；不含同周期额外操作 |
| 采集进程 CPU | 2109.375 ms / 平均单逻辑核 1.17187%，包含测试装置 |
| 最大观测 heap used | 19,774,432 bytes；未强制 GC，不是保留堆或 IDEA 峰值 |
| 线程 | 三组有限窗口中均 3 个 RMI + 2 个装置线程，平台线程总数 12；不证明无泄漏 |
| 目标退出及清理 | 退出后读取 3.728 ms 返回 ConnectException；close 也返回 ConnectException，自有目标退出、collector executor 终止、close 调用完成 |

未执行整体 IDEA 长时/8 标签/最大 JFR 的内存或 EDT 基准，不能由上述数值宣称插件整体开销或泄漏自由。

## 0.6.1 核心复测（2026-09-24）

Windows 11 / Corretto 21.0.9 / 16 逻辑处理器；默认 180 秒，输出 `RESOURCE_SOAK_PASS`，exit 0。本次脚本修正了核心源文件清单遗漏新依赖的问题，改为收集整个纯 JDK 核心目录。与旧记录一样，只测独立采集进程。

原始文件：[摘要](../build/reports/resource-soak/20260924-165511/summary.md)、[CSV](../build/reports/resource-soak/20260924-165511/samples.csv)、[最后现场](../build/reports/resource-soak/20260924-165511/last-capture.jvmb)。

| 观察项 | 结果 |
|---|---|
| 正式采样 | 180.001 秒，90 个样本；18 次线程采集和 save/load，9 次订阅及周期取消 |
| sample() 时延 | 中位数 64.892 ms，p95 89.925 ms，最大 138.586 ms；额外线程、通知和文件操作不计入该值 |
| 采集进程 CPU | 累计 3484.375 ms，平均 1.93575% 的一个逻辑 CPU，包含装置自身开销 |
| 观察到的堆 used | 最大 20,883,352 bytes；无强制 GC，不是保留堆或整个 IDEA 的开销 |
| 线程观察 | 预热后 5 次、采样后 5 次、关闭后 8 次均为 3 个 RMI 与 2 个装置线程，进程平台线程总数 12；有限窗口不能证明无泄漏 |
| 真实退出和清理 | 目标退出后的读取 3.463 ms 返回 ConnectException；客户端关闭也以 ConnectException 返回。自有 fixture 已退出，采集 executor 已终止，关闭调用完成 |

该次运行与 IDEA 开发活动并行；没有空闲机器基线或完整 IDE 的堆分析。0.6.1 最后调整图表自适应高度没有修改测量涉及的核心代码。

## 历史执行记录（2026-09-23）

2026-09-23 在 Windows 11 10.0 / amd64、Amazon Corretto OpenJDK 21.0.9、16 个逻辑处理器上完成默认运行，输出 `RESOURCE_SOAK_PASS`，退出码 0。该标记只表示下面的独立验证流程完成。

原始文件：[摘要](../build/reports/resource-soak/20260923-231951/summary.md)、[样本 CSV](../build/reports/resource-soak/20260923-231951/samples.csv)、[最后现场](../build/reports/resource-soak/20260923-231951/last-capture.jvmb)。`build/` 属于本地构建产物，若已清理可重新运行脚本。

| 观察项 | 本次结果 |
|---|---|
| 正式采样窗口 | 180.015 秒，90 个指标样本 |
| 周期操作 | 18 次平台线程快照、18 次 save/load，9 次订阅与 9 次周期取消订阅；另有预热和最终清理 |
| `sample()` 时延 | 均值 63.358 ms（CSV 计算），中位数 61.908 ms，p95 76.628 ms，最大 86.802 ms |
| 采集进程 CPU | 窗口内累计 2718.750 ms，平均 1.51029% 的一个逻辑 CPU；包含装置记录、文件和序列化开销 |
| 观察到的堆 used | 最大 20,643,096 bytes；预热观察结束 14,680,064 bytes，采样结束 6,835,472 bytes；没有强制 GC，不能当作保留堆差值 |
| 相关线程 | 预热后 5 次、结束时 5 次、关闭后 8 次观察均为同一组：3 个 RMI 线程 + 2 个装置线程；这些窗口的平台线程总数为 12 |
| 真实目标退出 | 后续读取在 4.039 ms 内以 `ConnectException → ConnectException` 失败；未用模拟断连代替 |
| 清理 | 自有 fixture 已退出，采集 executor 已终止；客户端关闭调用已返回，返回的是目标退出后的 `ConnectException`，没有将关闭超时当作成功 |

RMI 的 GC / RenewClean / Scheduler 线程在关闭后的这 8 秒观察窗口仍然存在；本记录没有将其称为立即清零或“无泄漏”。装置工作线程在最后显式关闭 executor 后终止；整次独立进程随后退出。宿主机同期还有 IDEA 开发与验收活动，未建立空闲机器或完整 IDE 开销基线。

CSV 的 `snapshot_bytes=0` 表示该周期没有新保存现场，不表示写出了零字节文件；只有每 5 个样本保存一次。文件大小与通知数量都是实际观察值，通知来自异步接收，发送之后未必在同一采样点立即可见。

执行中最初两次受限沙箱编译在关闭编译器资源时失败，`-XDdev` 将原因定位为 JDK `lib/ct.sym` 的 `toRealPath` 访问被拒绝。经 `require_escalated` 自动批准后，同一源代码以普通用户权限编译并完成上述运行；没有修改 JDK 或关闭目标安全机制。
