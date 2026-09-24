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
