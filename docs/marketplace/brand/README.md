# JVM Beacon brand assets

原创的「运行时信号」标记：深色控制台方块、青色脉冲、两条向外发射的信号线。延续现有工具窗口图标的框与脉冲，增强商店和插件管理器中的辨识度；不使用 Java、JetBrains 或第三方产品标识。图形是品牌符号，不表示连接状态或健康诊断。

| 文件 | 用途 | 画布 |
|---|---|---|
| `src/main/resources/META-INF/pluginIcon.svg` | IDEA 插件管理器和 Marketplace 默认图标 | 40 × 40 |
| `src/main/resources/META-INF/pluginIcon_dark.svg` | IDEA 深色主题图标 | 40 × 40 |
| `jvm-beacon.svg` / `jvm-beacon-dark.svg` | 同形高分辨率矢量母版 | 512 × 512，viewBox 40 × 40 |
| `jvm-beacon.png` / `jvm-beacon-dark.png` | 从同形 SVG 实际渲染的透明品牌预览，非产品截图 | 512 × 512 |
| `preview.html` | 本地浅/深主题、40/80/240 像素与实际列表比例检查 | 响应式页面 |

所有图标的最外绘制边界为 x/y = 2.75…37.25：40 像素时四边有 2.75 像素透明留白。图形只使用 `rect` 与 `path`，无字体、文字、图片、脚本、滤镜、渐变或外部引用；各 SVG 均小于 1 KiB。默认图标使用深青边框，深色版本提高边框与脉冲亮度；两版几何完全相同。

配色：控制台 `#10272F`；默认边框 `#007E83`；信号青 `#48D5C5`；深色高亮 `#68F4D9`。现有 16 像素工具窗口图标仍保持原样。

依据：[JetBrains Plugin Icon File](https://plugins.jetbrains.com/docs/intellij/plugin-icon-file.html)（查询日期 2026-09-30；官方规范；40 × 40 SVG、2 像素透明边距、深色变体）。原创不等同于已完成名称或商标核查。

打开 `preview.html` 可查看品牌资产；它是资产预览，不是商店真实页面截图。

PNG 仅用于预览和宣传资产。商店和 IDEA 的插件 Logo 从安装包 `META-INF/pluginIcon.svg` 自动提取，不需要把 PNG 当作另一个必须上传的图标。

2026-09-30 使用本地 IC 2025.1.3 的 `SVGLoader` 和 Corretto 21.0.9，已将两版图标各渲染为 40/80/240/512 像素 PNG 并检查。40 像素输出的非透明像素包围盒为 `[2, 2, 38, 38)`，最外两行/列透明；80 像素为 `[5, 5, 75, 75)`，240 像素为 `[16, 16, 224, 224)`。40 与 240 像素图已做视觉检查；渲染产物在 ignored `build/reports/brand-preview/`，512 像素预览复制到本目录，不冒充 IDEA 插件管理器 GUI 验收。
