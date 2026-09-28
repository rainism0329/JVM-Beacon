# 第三方组件与资源

本项目从空目录实现，图标为自绘 SVG，未复制竞品代码或界面资产。

| 组件 | 用途 / 分发 | 许可证来源 |
|---|---|---|
| Gradle Wrapper 9.5.0 | 仓库内构建启动器，不进入插件安装包 | Apache-2.0；脚本保留上游版权头。[上游](https://github.com/gradle/gradle) |
| IntelliJ Platform Gradle Plugin 2.18.1 | 构建工具，不进入安装包 | Apache-2.0，[仓库](https://github.com/JetBrains/intellij-platform-gradle-plugin) |
| JUnit Jupiter 5.10.0 / Platform 1.10.0 | 仅测试，不进入安装包 | EPL-2.0，[JUnit](https://junit.org/junit5/) |
| JUnit 4.13.2 | 仅 IntelliJ 测试引导兼容，不进入安装包 | EPL-1.0，[JUnit4](https://github.com/junit-team/junit4) |
| IntelliJ Platform / Java plugin | 使用宿主 IDE 提供的 API，不分发 IDE | [JetBrains 平台仓库](https://github.com/JetBrains/intellij-community)及所选 IDE 的产品条款 |
| Java Attach / JMX | 使用构建 JDK / 宿主 JBR 提供的模块，不分发 JDK | 所选 JDK/JBR 的随附许可 |

2026-09-23 核验：本地 Wrapper JAR 的 SHA-256 为 `497c8c2a7e5031f6aa847f88104aa80a93532ec32ee17bdb8d1d2f67a194a9c7`，与 [Gradle 官方校验值](https://services.gradle.org/distributions/gradle-9.5.0-wrapper.jar.sha256)相同；分发包 SHA-256 已写入 wrapper properties。脚本/JAR 从本机已有 Wrapper 复制，未复制其他项目业务代码。

运行时无新增第三方库。当前为私有试用发布候选；公开分发前需要最终确定本项目源码与分发许可证、发布者信息和 Marketplace 身份。此候选不代表授予公开再分发许可，未完成商标核查。竞品许可调研见 `docs/research.md`。
