# IDEA 兼容策略与验证

2026-10-09，rc.3 将声明范围改为 **IntelliJ IDEA 2024.2 / build 242 起，无固定上限**。构建 `untilBuild = provider { null }`，不是省略赋值后让 Gradle 恢复默认的 `242.*`。包内应只有 `<idea-version since-build="242" />`；验收脚本从实际 JAR 读取并报告。

编译默认依赖官方 Community 2024.2，以最低支持平台为基线。Java 21 是客户端真实要求，代码使用 `List.getFirst()/getLast()` 等 API，不能只改描述符就支持默认 JBR 17 的 2024.1 及更早版本。目标应用 JVM 的 Attach/JMX/JFR 能力与 IDE 运行时是不同要求，本轮未扩大目标 JVM 或跨 OS 验收范围。

不设上限允许后续 IDEA 安装插件，不表示此包永久兼容所有未来 API。新平台若破坏公共接口，要发布适配更新，必要时限制已知不兼容 build。保留 `com.intellij.java` 依赖，源码定位只使用声明的 Java PSI。不开启任意反射或内部 API 来假装版本兼容。

## 本轮结果

| 目标 | 状态与证据 |
|---|---|
| IC 2024.2 / 242.20224.300 | 最初发行版完整 SDK；最终编译成功、全量169 tests / 0 failure/error/skipped、官方Verifier Compatible / 1条既有API提示 |
| IC 2024.2.4 / 242.23726.103 | 最终同包官方Verifier Compatible / 1条既有API提示；初轮亦在此SDK编译及169 tests通过 |
| IC 2025.1.3 / 251.26927.53 | 最终同包官方Verifier Compatible / 4条API用法提示 |
| IU 2025.1.3 / 251.26927.53 | 最终同包官方Verifier Compatible / 4条API用法提示 |
| IDEA 2026.2.3 / IU-262.10968.63 | 当前最新稳定完整 SDK / JBR25；最终同包官方Verifier Compatible / 4条API用法提示 |
| 其他中间版本、未来/EAP版本 | 开放声明范围，未逐个验证；不作为实测通过 |

本轮使用 Windows / Corretto 21.0.9 构建；缓存 2024.2.4 SDK 启动 JAR、Java 插件与 JBR 21.0.4 完整。初轮 `test buildPlugin` 2m40s、三个缓存目标 `verifyPlugin` 1m04s。这是此环境具体一次耗时，不是性能保证。[编译/全量测试日志](../build/reports/compat-rc3-cache242-build.txt)、[缓存矩阵日志](../build/reports/compat-rc3-cached-verifier.txt)。下载官方 SDK 较大，安装插件自身仍是约 0.5 MiB；SDK 下载时间不能当插件打包时间。

最终新包从最初2024.2 SDK编译，`test buildPlugin verifyPlugin`（前四目标）3m04s；随后同一包五目标矩阵2m06s（Verifier1m42s），没有重新改生产代码。[最终最低构建/测试](../build/reports/compat-rc3-minimum-final.txt) / [最终五目标Verifier](../build/reports/compat-rc3-matrix-final.txt)。安装ZIP **492,095bytes**，SHA-256 `aafcd78925d8c23a65a5a2f6d7dec1efcb2f19150f7e2e5405fa5e63802511a2`；[安装包](../build/distributions/jvm-beacon-1.0.0-rc.3.zip) / [文件校验](../build/distributions/jvm-beacon-1.0.0-rc.3.zip.sha256)。运行显式五目标 `verify-release` 通过，读取实际 `since-build=242`、`until-build=null`，零兼容问题；[机器记录](../build/reports/release-checks-1.0.0-rc.3.json)。脚本模拟13项与公开FAQ HTML/Markdown47段校对也通过。

**GUI边界：** 本轮没有新开不同版本GUI、做rc.3加载/交互/长时复验；SDK完整性、最低编译、组件/核心测试和二进制Verifier分别记录，不能替代这些GUI结果。2025.2、2025.3、2026.1未单独下载检查；五目标不能证明所有中间版本或未来EAP。rc.3仍是发布候选，旧rc.2图集不冒充新包。

### API 警告

242 的 `FileSaverDescriptor` 只有公开 varargs 构造；251 新增单扩展等构造，并将旧 varargs 标记 `@Deprecated`，尚未标为 `forRemoval` 或 `ScheduledForRemoval`。最低 SDK 编译让保存 snapshot/interval/JFR 的三处字节码引用共同的数组签名，保持扩展过滤。当前 251 Verifier 共 4 条用法：3 个保存构造 + 1 个既有 `SslRMIClientSocketFactory` 提示。后者仍是客户端 TLS 所需的公开 JDK 类，独立 TLS 回归通过；不为消提示移除 TLS 或改用只在新 SDK 存在的构造。每个 IDE 的实际 warnings 以其报告为准。

源码显式传 `new String[]{"jvmb"}` / `new String[]{"jfr"}`，即使用新版 SDK 编译，也固定在最低版存在的数组签名。完整初版 SDK 的 JBR 为21.0.3；最新2026.2.3发行包实际为 **JBR25.0.4+1-b508.27**，与当前安装网页仍写21不同，以实物为准。Java21字节码是否在新版平台链接成功由实际Verifier报告判断。

官方完整 ZIP 与SHA-256：2024.2为1,014,192,727bytes / `3efc0d4d0f3950d81d2d7143ea3dfe2b42b16ed352779251d116bae6e9582c15`；2026.2.3为1,634,879,155bytes / `d113b117d72afe512626c478cbf952f6d1a234af5a1cb22cbf6089df55958f51`。[最低SDK证据](../build/compat-downloads/ideaIC-2024.2.win.zip.evidence.json) / [最新SDK证据](../build/compat-downloads/idea-2026.2.3.win.zip.evidence.json)。初次整体/串行Range下载遇等待/超时，保留局部文件，最低版经断点完成、最新版以4并发有界Range完成并整包校验；没有把局部ZIP当SDK，也未改变用户安装或证书信任设置。

2026.2.3完整SHA匹配发行包在Verifier读入时还输出 `Layout component ... nonexistent classPath` 元数据提示，包含多个合并模块/Java模块路径；SDK未按该提示人工修造或删除资源。最终Java插件依赖可解析且Compatible，未报告缺类/缺方法/实验或内部API用法。本机JAR与JBR检查用于补充说明，不将这些元数据提示隐藏成“零警告”。

## 重跑

先设置 JDK 21；完整最低 SDK 通过官方 SHA-256 后放到 `.intellijPlatform/ides/community-2024.2`。本机原有 Community 与用户 Ultimate 路径沿用构建参数，测试不关闭/写入日常 IDE。

```powershell
$env:JAVA_HOME = 'C:\Users\lenovo\.jdks\corretto-21.0.9'
$beaconMin = 'D:\IdeaProjects\JVM-Beacon\.intellijPlatform\ides\community-2024.2'
$beaconOthers = 'D:\.gradle\caches\8.13\transforms\f1ddadde8317737dfa783c99634eed9f\transformed\ideaIC-2024.2.4-win|D:\IdeaProjects\JVM-Beacon\.intellijPlatform\ides\community-2025.1.3|E:\JetBrains\IntelliJ IDEA 2025.1.3|D:\IdeaProjects\JVM-Beacon\.intellijPlatform\ides\idea-2026.2.3'
.\gradlew.bat test buildPlugin verifyPlugin "-PlocalIdePath=$beaconMin" "-PverificationIdePaths=$beaconOthers" --offline --console=plain
.\scripts\verify-release.ps1 -Version '1.0.0-rc.3' -ExpectedVerificationIde @('IC-242.20224.300','IC-242.23726.103','IC-251.26927.53','IU-251.26927.53','IU-262.10968.63')
```

只有各完整 SDK 已取得、报告确属新包且所有目标通过后，上述全矩阵命令才算验收结果。`verify-release` 默认保留历史 IC/IU251 对，用显式矩阵核对新包；禁止拿旧版/旧包大小结果充当新版本通过。新增脚本有 13 项独立模拟拒绝/接受检查，不是 IDE 兼容实测。

rc.2 的六图及材料合集仍是原版本历史证据，未将其 manifest 版本/hash 改成 rc.3。本轮更新版本范围、构建与文案，完整新版本 GUI/截图 Ready gate 另行验收；不从 rc.2 外推 rc.3 GUI。

本轮同步前通过 GitHub API 核对现有 `rainism0329/JVM-Beacon`，**2026-10-09 返回 isPrivate=false**。与此前仅允许私有同步的授权不同，因此新代码只做本地提交，未推送；已询问用户是否恢复私有后同步。本地兼容安装包不依赖此决定。没有代改仓库可见性、公开新代码或发布Marketplace/GitHub Release。

## 官方依据

2026-10-09 访问：[Build Number Ranges](https://plugins.jetbrains.com/docs/intellij/build-number-ranges.html)、[Gradle untilBuild](https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin-extension.html#untilBuild)、[Plugin Verifier](https://plugins.jetbrains.com/docs/intellij/verifying-plugin-compatibility.html)、[2026 API changes](https://plugins.jetbrains.com/docs/intellij/api-changes-list-2026.html)。这些是官方声明；编译/Verifier/SDK 校验是本机实测，未来保持兼容是维护策略。

官方 [IIC releases API](https://data.services.jetbrains.com/products/releases?code=IIC&latest=false&type=release) 给出最初 2024.2 / 242.20224.300；[IIU releases API](https://data.services.jetbrains.com/products/releases?code=IIU&latest=true&type=release) 给出查询日最新稳定 2026.2.3 / 262.10968.63。实际 product-info/JBR 和文件 SHA-256 取得后单列；不把网页中可能过时的 runtime 表当最终安装包数据。
