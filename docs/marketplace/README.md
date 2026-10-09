# JVM Beacon · Marketplace 发布素材

**2026-10-09：当前工程为 rc.3，扩展 IDEA 范围到2024.2+并取消上限，实际矩阵见 [兼容记录](../compatibility.md)。英文 listing/FAQ/更新说明已随策略更新；下方已归档 ZIP、六图与Ready结果仍属于原始 rc.2，并未重新标为rc.3。新安装包不能与旧manifest混用，rc.3发布材料Ready需对应版本GUI来源再验收。**

本目录准备 **1.0.0-rc.2** 的公开上架材料，更新于 **2026-09-30**。用户已决定免费闭源，源码继续保留私有；发布者为 **Philip Zhang**，使用已有 [PhilZ Dev Vendor](https://plugins.jetbrains.com/vendor/philz_dev)。当前任务是完成材料与本地验收，**用户自行上传；没有代为上传、签约或公开仓库的授权**。自动检查通过表示本地材料齐备，不表示商店审核通过。

## 可以直接使用的素材

| Marketplace 内容 | 本地入口 | 使用方式 |
|---|---|---|
| 名称 | `JVM Beacon` | 保留 10 字符品牌名；描述卡片第一句为 `JMX & JFR diagnostics in IntelliJ IDEA.`（39 字符） |
| 英文描述 | [listing.html](listing.html) | 插件描述和 Overview；不要插入私有源码链接 |
| Getting Started | [getting-started.html](getting-started.html) | 复制到商店 Getting Started 字段，包含实际步骤 |
| What's New | [release-notes.html](release-notes.html) | rc.2 更新记录；与包内 change-notes 保持一致 |
| FAQ | [FAQ.html](FAQ.html) · [Markdown 源](FAQ.md) | 已备好 HTML fragment；按 Custom Page 当前编辑器的输入模式粘贴并预览 |
| 使用许可 | [EULA.html](EULA.html) · [Markdown 源](EULA.md) | 免费闭源 Developer EULA；HTML 与源逐段一致，发布者审核后按 License 字段支持的输入格式填写 |
| 隐私说明 | [privacy-policy.html](privacy-policy.html) | 用作 Marketplace Custom Page 或账号提供的隐私说明位置 |
| 品牌图标 | [brand/README.md](brand/README.md) · [brand/preview.html](brand/preview.html) | SVG 源文件与原生 SVG 渲染记录；安装图标已进入 `META-INF` |
| 真实截图 | `media/` 及 `media/manifest.json` | 按最终 manifest 的顺序上传 Media，保留采集版本和原图来源记录 |
| 本地核对 | [verification.md](verification.md) | 包/图标/文案/截图/已决定分发信息检查，GUI 与兼容检查分别记录 |
| 发布决定 | [publishing-decisions.json](publishing-decisions.json) | 免费闭源、发布者和支持入口；`uploadAuthorized=false` |

下载入口为工作区 `build/distributions/jvm-beacon-1.0.0-rc.2.zip`。截图原图与验证报告位于工作区 `build/`，上传素材位于本目录；不要把测试 fixture、证书、原始诊断文件或本地沙箱加入分发 ZIP。

本次完整交付：[Marketplace 材料 ZIP](../../build/distributions/marketplace-1.0.0-rc.2-20260930-160434-763.zip)（1,317,080 bytes，SHA-256 `c18256ab5b4dd66caf74bc74f90d5cc2ef3226eb5575c6d0b04e208bc0f91312`）。解压后从 `READ-ME-FIRST.txt` / `handoff/README.md` 开始；仅 `plugin/` 内层 ZIP 用于插件安装或 Upload Plugin，外层 ZIP 是材料合集。归档共 31 文件，30 项文件哈希逐项重新读取 ZIP 核对，说明/图集/品牌预览相对链接通过；未包含源码、凭据、录制、现场或原始日志。工作区 `build/` 不入 Git，重新打包会生成新的时间戳入口和摘要。

`FAQ.html` 和 `EULA.html` 是 UTF-8 HTML 片段，不含整页模板、样式、脚本或外部依赖。已逐段核对纯文本和链接与 Markdown 源一致，HTML 字符正确转义；EULA 源与包内许可没有因格式导出而改动。当前尚未读取 Custom Page 格式规范或登录该账号的实际编辑器，**没有宣称已经确认其 Markdown/HTML 输入模式**。此前官方 listing 文档已确认 Getting Started 支持 HTML；这不能直接推断所有字段。若当前字段提供 HTML/source 模式，使用预制 HTML；若只收纯文本，使用源文档可读原文，并在提交前检查页面预览。

## 真实截图复现

截图采集使用本项目专用的、认证的 loopback 测试 JVM，避免调用陌生业务进程。设置 JDK 21 后运行：

```powershell
.\scripts\run-marketplace-demo.ps1 -JdkHome '<JDK21目录>' -DurationSeconds 600
```

脚本以 SecureString 提示输入临时密码。将输出的 loopback 端点和 `observer` 账号用于只读指标/线程；需要验证 MBean 写入与方法调用时使用 `operator`。两个账号使用本次临时密码，密码通过子进程标准输入传递，不放到命令行。目标最多运行 600 秒；到时退出或关闭该自有目标。该 fixture 仅用于真实截图与复测，**不是用户连接自己应用的运行依赖**。可见目标名 `beacon-demo` 是专用 fixture 的展示标识，图表、线程和 MBean 内容仍来自实际采集，不用它生成假数据。

## 名称、许可与支持

名称建议使用 **JVM Beacon**，将具体用途放在描述第一句和截图标题中。Marketplace 名称检索仅能作为可见列表的证据，**没有完成商标核查或名称/plugin ID 的正式保留**。现有开发 ID 为 `dev.jvmbeacon`；首次上传前在账号中确认可以使用并保留该 ID，避免发布后更换身份导致安装链中断。

许可是自有的免费闭源 Developer EULA，授权个人、教育和商业使用以及组织内部部署，保留源码与对外再分发权。协议列明 Philip Zhang 为 licensor、JetBrains 不是协议当事方，包含责任与强制法权利边界。它是可审阅的发布文本，并不代表法律意见或 JetBrains 预先认可；上传前由发布者最终确认条款与真实主体。源码继续私有，**Source Code URL 留空**，不选择开源许可证，不把私有仓库作为公开帮助或源码入口。

当前可公开填写的 Vendor/Website 联系入口是 [已有 Vendor 页面](https://plugins.jetbrains.com/vendor/philz_dev)。本次没有编造邮箱，也没有从 JavaScript 页面解析结果确认其 Contact Vendor 按钮存在。上传前在该账号的 Vendor settings 核对真实有效的联系邮箱/网站，以及 trader 或 non-trader 声明。公开资料使用已提供的真实联系人；若页面提供 Contact Vendor，用户可使用它。上架后的 Marketplace 插件页可接受不含敏感数据的评论/反馈。评论是公开内容，不能收集密码或生产录制；更完整的专用支持入口可在 Vendor 资料中维护。

隐私说明覆盖用户选择的 JMX/RMI 数据交换、PasswordSafe、本地配置、导出文件和主动支持分享；“无自动上传”不表示远程连接不经过网络，也不表示目标 getter 或读请求没有开销。Marketplace 和 IDE 自身的下载/更新/账号处理属于 JetBrains 自己的服务。

## 手动上传步骤

1. 审核本目录文案、EULA、隐私说明、名称与截图；看过最终 GUI/兼容验收范围，不把旧构建结果写成新包通过。确认使用候选版本还是另行验收后发布正式 `1.0.0`；rc.2 素材不能冒称稳定版。
2. 运行 `scripts/verify-release.ps1 -Version '1.0.0-rc.2'` 和 `scripts/verify-marketplace.ps1 -Version '1.0.0-rc.2' -Ready`，核对最终 ZIP 的 SHA-256 与 Media manifest 相同。脚本只核对本地材料，不代替人工、法务、账号或商店审核。
3. 用户登录 JetBrains Marketplace，在 Upload plugin 选择已有 **philz_dev** Vendor。若账号需要接受 Developer Agreement，由用户审阅后自行接受；核对账号角色、联系信息与 trader 状态。
4. 选择最终 ZIP，保持名称 `JVM Beacon`。定价选择免费、自有闭源 EULA；按 License 字段实际提供的格式填入 `EULA.html` 或源文档的可读原文，预览确认不会把 HTML 标签作为正文显示。Source Code URL 留空，Vendor/网站使用已有公开 Vendor URL，不填写未知邮箱或占位地址。
5. 粘贴英文 Overview / Getting Started / What's New；添加至少一个账号当前可选且确实相关的 tag。优先查找 Java、Monitoring、Profiling 或 JVM/JMX 相应分类，以上传表单实际标签为准，不声称这些标签当前全部存在，也不添加无关热门标签。
6. 上传 `media/manifest.json` 中的真实 PNG 及英文说明，使用一致长宽比和默认 IDE 主题。按 Custom Page 当前支持的输入模式维护 `FAQ.html` 与隐私说明，逐页检查预览；不要将原始诊断包或含本机信息的原图误当截图上传。
7. 对 rc.2 建议使用 **EAP 自定义通道**或 Hidden 状态供小范围验证；正式稳定包再考虑默认通道。由用户决定是否提交公开，按商店表单审阅最后预览后提交。
8. 等待 Marketplace 的自动与人工审核，必要时回复真实问题。审核通过后检查安装卡片、图标、截图、支持入口和实际版本；未通过不得把本地 ready 报告作为批准凭据。

## 官方依据与核验范围

以下页面均于 **2026-09-30** 访问；证据类型为官方发布要求，不是账号操作或成功上架实测。

- [Listing best practices](https://plugins.jetbrains.com/docs/marketplace/best-practices-for-listing.html)：名称与英文卡片摘要、Getting Started、Media、外部链接要求。官方建议截图至少 1200×760、比例一致、不要含个人信息。
- [Uploading a new plugin](https://plugins.jetbrains.com/docs/marketplace/uploading-a-new-plugin.html)：选择 Vendor、提供 Developer EULA、合理标签、候选通道和 Hidden；开源许可才要求公开源码链接。
- [Developer EULA](https://plugins.jetbrains.com/docs/marketplace/eula.html)：第三方作者是 licensor，JetBrains 不是协议当事方；自有 EULA 需至少保护 JetBrains 到官方 Standard EULA 的程度。没有原样套用标准模板。
- [Approval Guidelines v1.3](https://plugins.jetbrains.com/docs/marketplace/jetbrains-marketplace-approval-guidelines.html)：名称/身份、有效 Vendor 网站与邮箱、trader 声明及审核要求。与“XML email 属性可选”的技术说明分别对待，不能据此省略 Vendor 账号实际联系资料。
- [Vendor profile](https://plugins.jetbrains.com/docs/marketplace/organizations.html)：每个插件须关联 Vendor，发布前核对账号及公开资料。
- [Ratings and reviews](https://plugins.jetbrains.com/docs/marketplace/reviews-policy.html)：插件页反馈为公开内容，作者可回复；没有把评论宣传成私密支持渠道。
- [Developer Agreement 入口](https://plugins.jetbrains.com/legal/developer-agreement)：入口可访问但浏览检索仅返回 JavaScript 外壳；版本化 [2.0 正文](https://www.jetbrains.com/legal/docs/plugins_site/developer-agreement/2.0/) 可读取（2024-02-17 生效），其中第 10 节确认自有 EULA、不将 JetBrains 纳入当事方、不增加其 affiliates/resellers 责任。没有把该版本页当作账号提交时一定适用的最新协议，也没有代用户接受；实际表单中的条款仍由用户阅读。
- [已有 Vendor](https://plugins.jetbrains.com/vendor/philz_dev)：用户提供的实际公开 URL，检索结果只返回 JavaScript 外壳；未据此推断账号所有权、后台权限或已配置邮箱。

上述要求可能更新，实际上传当天应再检查提交表单和官方协议。材料准备本身不授予上传权限，也不公开任何源码。
