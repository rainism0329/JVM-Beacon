# TLS 独立验证

2026-09-23，Windows 11，Amazon Corretto 21.0.9。这里验证真实 loopback JMX/RMI 的 TLS 连接及拒绝路径，不替代 IDEA UI、生产网络或所有 JDK 的兼容性验证。

## 结果

`TlsSmokeMain` 独立运行通过，输出 `TLS_SMOKE_PASS`。registry 与 connector endpoint 均绑定 `127.0.0.1`、均使用 TLS；每个客户端情形使用独立 JVM。

| 情形 | 实测结果 |
| --- | --- |
| 专用 truststore 信任临时自签证书，正确 operator 凭据 | 连接、真实指标、属性写入/读回、MBean 操作成功 |
| 同一受信链，observer 凭据 | 指标读取成功；属性写入与操作均被服务端拒绝 |
| 受信链，错误密码 | 连接失败，异常链为认证拒绝 |
| 受信链，不提供凭据 | 连接失败，异常链为认证拒绝 |
| 不受信证书（专用空 truststore） | TLS 失败，异常链含 SSLException；JmxClient 给出 TLS handshake failed 分类 |
| TLS registry 配置下误用明文 registry 客户端 | 连接失败；客户端没有回退连接成功 |

六种情形全部通过。父进程 `javax.net.ssl.trustStore` 保持不变；运行结束后确认 `build/tls-validation` 下无证书、私钥或子进程日志文件残留。此次没有发现需要修改 `JmxClient` 的 TLS bug。

测试使用 `keytool` 临时生成 RSA 2048、自签、有效期一天、SAN 为 `localhost` 和 `127.0.0.1` 的证书，并创建专用 PKCS12 truststore。证书、私钥及日志只存放于被 Git 忽略的 `build/tls-validation/run-*`，finally 校验目录边界后删除。store 密码通过进程环境传入 keytool；登录密码通过环境或 fixture stdin 传入，不进入命令行。没有修改 JDK cacerts、用户配置、IDE 参数或系统信任。

## 复现

以下为本次实际采用的独立命令，工作目录为项目根目录。JDK 路径可替换为本机 Java 21：

```powershell
New-Item -ItemType Directory -Force build/tls-check | Out-Null
$coreSources = Get-ChildItem src/main/java/dev/jvmbeacon/core -Filter '*.java' | Select-Object -ExpandProperty FullName
& 'C:\Users\lenovo\.jdks\corretto-21.0.9\bin\javac.exe' -encoding UTF-8 -d build/tls-check $coreSources src/test/java/dev/jvmbeacon/fixture/DemoApplication.java src/test/java/dev/jvmbeacon/core/FixtureProcess.java src/test/java/dev/jvmbeacon/core/TlsProbeMain.java src/test/java/dev/jvmbeacon/core/TlsSmokeMain.java
& 'C:\Users\lenovo\.jdks\corretto-21.0.9\bin\java.exe' -cp build/tls-check dev.jvmbeacon.core.TlsSmokeMain
```

另提供 `JmxTlsIntegrationTest` 供常规 Gradle 集成；2026-09-23 的 0.1.1 统一 Gradle 24 项测试全部通过，包括这个调用相同六情形流程的用例。2026-09-24 的 0.1.2 又随 28 项统一测试通过，最新证据见 [验证记录](validation.md)。首轮集成因 IDEA classloader 的 CodeSource location 为空而失败，已由构建注入 `beacon.core.classes`，保留独立运行的标准类路径回退。JUnit 用例上限 180 秒；每个 keytool 子进程上限 30 秒、每个 TLS 客户端上限 20 秒；finally 关闭 fixture，必要时终止本测试创建的子进程。

Fixture 新参数为 `--remote --tls`；它要求 `BEACON_TEST_KEYSTORE`、`BEACON_TEST_TRUSTSTORE`、`BEACON_TEST_STORE_PASSWORD` 三个专用测试环境变量。由 harness 自动传递即可，无需设置到用户环境。

## 适用边界与依据

- TLS registry 开关只控制 registry 的 socket。connector 的 TLS 能力由服务端 RMI stub 提供，本次 fixture 明确为两层配置 TLS；没有证明所有远端部署都同时加密两层。
- 未实测双向 TLS、证书过期/撤销、企业 CA、代理/NAT、非 loopback 网络、主机名不匹配或不同 IDEA/JDK。不能把证书链信任测试称为完整 TLS 安全审计。
- [Java 21 SslRMIClientSocketFactory](https://docs.oracle.com/en/java/javase/21/docs/api/java.rmi/javax/rmi/ssl/SslRMIClientSocketFactory.html)（官方 API，2026-09-23 查阅）说明该工厂使用默认 SSLSocketFactory，共享 JVM 默认 truststore。因此测试客户端必须隔离到独立 JVM，避免污染 IDEA/JUnit 进程的 SSL 默认状态。
- [Java 21 JMX monitoring and management](https://docs.oracle.com/en/java/javase/21/management/monitoring-and-management-using-jmx-technology.html)（官方说明，2026-09-23 查阅）区分认证、TLS 和 RMI registry 风险。本项目没有以测试方便为理由自动关闭这些机制。
