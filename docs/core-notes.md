# 核心采集与独立 JVM 验证

记录日期：2026-09-23。本页仅记录纯 Java 核心；IDE 加载、交互和兼容性结果见 `validation.md`。

## 实际运行

本页保留首轮检查经过。最终普通用户权限下脚本已观察到 fixture 自动发现成功，且统一 Gradle 20 项测试通过；最新复跑数值和范围以 [validation.md](validation.md) 为准。

环境：Windows 11 10.0，Amazon Corretto / OpenJDK 64-Bit Server VM 21.0.9，16 个逻辑处理器。客户端与测试目标均由本任务启动；目标 `-Xms32m -Xmx96m`。没有连接未知业务 JVM。核心使用 Java 21 编译，无第三方运行时依赖。

`CoreSmokeMain` 已通过真实本地 Attach、管理代理显式启用、指标、平台线程、认证远程 JMX、属性写入、操作、通知及现场重开。它是独立程序，不代表插件加载成功。

| 测量/检查 | 观察 |
| --- | --- |
| 本地指标采样，预热 10 次、连续 100 次 | median 46.379 ms；p95 51.308 ms；max 56.370 ms |
| 上述 100 次调用线程 CPU 时间 | 共 218.750 ms；仅调用线程，未包含 RMI 线程、目标 JVM 或 IDEA |
| 线程快照 | 15 条平台线程；自定义平台等待线程可见；自定义虚拟等待线程不可见，符合覆盖声明 |
| 快照 | 31,511 bytes，UTF-8 Properties v1，写入后重开身份一致 |
| 通知 | 发送 260 条，最终只保留最近 200 条，最后序号 260 |
| 20 轮连接/订阅/采样/取消订阅/关闭 | 与预热后基线相比，客户端 JMX/RMI 线程分组计数未增加；两次均 GC Daemon 1、RenewClean 2、Scheduler 1；未遗留通知获取线程 |

上述时延来自本机空闲 fixture、连续读取，是开发基线，不是负载下性能承诺。建议首版预算：默认采样周期不少于 2 秒、禁止重叠采样；健康 loopback 单次采样 p95 目标 <250 ms。尚未完成长时运行、目标大规模 MBeans、慢 WAN、高丢包或整个 IDE 内存/CPU 开销测量。JDK 自身 RMI GC/Scheduler 线程可能在连接关闭后保留，因此不能把 JVM 总线程数立刻回到启动值作为唯一泄漏标准。

首次 JUnit 运行：14 个测试中 13 通过、1 在 `VirtualMachine.list()` 未枚举出已知子进程时失败。手动 PID Attach 的独立冒烟已经通过。测试随后移除“枚举成功才允许继续 Attach”的前提，发现能力单独由 `CoreSmokeMain` 报告。当前 Windows 沙箱中的枚举缺失原因尚未完全定位，UI 必须提供手填 PID，不能声称本地自动发现已在该环境验证成功。

修复后的独立 JUnit 完整运行：**14/14 通过、0 跳过，57.980 秒**（Jupiter 5.10.0；离线可用 Platform Launcher 1.8.2 启动）。期间还修复了目标退出后 `close()` 可抛 IOException 的测试清理预期。IDE 测试类加载器可能不提供 CodeSource，fixture 引导已支持 `beacon.fixture.classes` 显式 classpath。最终统一版本的 Gradle 检查结果由 `validation.md` 记录。

## 测试程序与运行

- Fixture 主类：`dev.jvmbeacon.fixture.DemoApplication`。默认仅本地管理，无远程监听；打印 PID，标准输入换行后结束。
- `--duration=600`：自动在 600 秒后清理退出，范围 1–600；`--timed` 等价默认 600 秒。平台和虚拟等待线程均有 10 分钟绝对上限。
- 第一个参数 `--remote`：仅在 `127.0.0.1` 建 RMI registry 和 connector endpoint，要求标准输入第一行提供至少 8 字符密码。用户 `operator` 可操作、`observer` 经服务端 `MBeanServerForwarder` 强制拒写/操作。密码不进入命令行、不打印。远程 URL 和 PID 会打印；该 fixture 仅用于本机，未启 TLS，不能用作部署模板。
- Fixture MBean：`dev.jvmbeacon.demo:type=Probe,name=Workbench`；提供可写 int/String/boolean、int[] 参数/返回值、CompositeData、TabularData、拒绝权限的 getter、异常 getter/操作、最多 3 秒的 slow 操作和最多 400 条的 notification burst。没有无限 CPU 压力或永久死锁。
- JUnit：`TypeCodecTest`、`SnapshotStoreTest`、`JmxClientIntegrationTest`。独立子进程启动等待上限 15 秒，结束等待 5 秒，超时强制清理；单个集成测试上限 60 秒。
- 无 JUnit 冒烟/度量入口：`dev.jvmbeacon.core.CoreSmokeMain`，classpath 包含编译后的 core 与测试 fixture。输出 `CORE_SMOKE_PASS` 才算成功，并在 `build/core-check/real-fixture.beacon` 写真实样本。常规 Gradle 路径见项目 README。

## 有意保留的边界

- 所有 `JmxClient` 网络、Attach 和 `close()` 都是阻塞 API，调用方必须用有界后台执行器。取消 Future 不保证取消 Attach、RMI 或服务端操作；超时修改不能重试。`sample()` 与 `readAttributes()` 将连接级 IOException 向外传播，能力缺失、权限或 getter 自身异常则保留为各项缺失，不冒充零。
- 本地启动 management agent 需要明确允许；Attach 始终 finally detach。进程列表只显示可识别的主类首 token 和 PID，拒绝显示命令行参数、jar 路径或启动器参数。
- 远程只接 JMX/RMI；registry TLS 开关只控制 registry socket，connector 的 TLS 由服务器 stub/证书配置决定。没有关闭校验、自动降级或全局覆盖 TLS/RMI 属性。证书链、双向 TLS、远端防火墙/NAT、多厂商 JVM 尚未实测。
- OpenJDK 21 的连接检查周期设为 0，通知批次 200、长轮询等待 1 秒。它们是 JDK provider 环境属性，不是跨 provider 的强制网络超时。JDK 在 `NoSuchObjectException` 等特定情况仍可能有内部恢复；普通 IOException/超时不会由本插件重试写操作。认证数组连接完成即清除，不能依赖 provider 内部保留凭据恢复；重新连接由用户发起并重新提供凭据。
- 名称最多展示 10,000；属性每 MBean 最多 1,000；线程最多 512 个 ID、每线程 64 帧；每连接最多 32 个通知订阅，通知保留 200 条。ValueFormatter 最多 6 层、每容器 100 项、约 32 Ki 字符，不调用未知对象的 `toString()`。
- **这些是显示/保留数量限制，无法限制 JMX/RMI 在反序列化阶段收到的对象体积。** 当前每个可读属性仍逐项读取原始返回值，巨大数组/复杂 MBean 值可能先进入内存。未来应实现按需属性读取、进程隔离传输及可配置序列化过滤；首版只连接信任的目标，不承诺恶意 JMX 服务端安全隔离。
- ThreadMXBean 只覆盖平台线程；ID 清单与栈非原子采集，线程可在中途退出。死锁只报告该次可检测的监视器/ownable-synchronizer 环，不代表排除了虚拟线程、活锁、饥饿或一般阻塞。
- 快照为最多 5 MiB 的普通文本，禁止 Java 对象反序列化；校验版本、重复键、计数、时窗、有限数字，限制 BigDecimal precision 与 scale 防止极端指数导致比较内存膨胀。快照不含凭据/连接 URL/命令行/系统属性/任意 MBean 值/通知；线程名、类名、锁名和用户备注可能敏感，必须在分享前自行审阅。该范围不是自动完全脱敏。
- 只用 runtimeName 和 JVM startTime 做尽力身份匹配；同标签不同 startTime 可能为重启或 PID 复用；不同实例不比较线程 ID 或累计指标差值。保存的是分别采集的窗口，不是恢复未采集历史。

## 核查来源（2026-09-23）

- [Java 21 JMX RMI package](https://docs.oracle.com/en/java/javase/21/docs/api/java.management.rmi/javax/management/remote/rmi/package-summary.html)：官方 API；核验 registry/JNDI 与 server stub 两层地址、服务端 socket factory 及序列化边界。
- [OpenJDK 21 RMIConnector 源码](https://github.com/openjdk/jdk21u/blob/master/src/java.management.rmi/share/classes/javax/management/remote/rmi/RMIConnector.java)：官方仓库源码阅读；核验连接检查、通知和异常恢复行为。只据此设计调用策略，未复制实现代码。
- 本机 Corretto 21.0.9 `lib/src.zip` 内 `java.management/com/sun/jmx/remote/util/EnvHelp.java`：本地源码核验 `jmx.remote.x.client.connection.check.period` 默认 60,000 ms、接受最小值 0。网页同文件未能成功抓取，没有将抓取失败记为已访问成功。

JDK 文档是标准能力说明，源码是具体 JDK 实现证据，表格是本环境实测；三者均不等价于所有操作系统、JDK 厂商或 IDEA 版本兼容。
