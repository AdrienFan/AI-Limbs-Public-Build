---
fork: https://github.com/AdrienFan/AI-Limbs-Public-Build
status: source-ready
---

# 基础可编辑文字工具

基线 4127531d，画室 0.2.19 源码已提交、未编译。已安装版本为 0.2.18。用户选择先实现基础可编辑文字，不要求本轮完整 Krita SVG 排版。

原工具 svg_text 灰色占位，缺少文字对象、编辑入口和排版渲染。后台 app_process 的默认字体未初始化，普通 Canvas.drawText 曾触发 SIGABRT；不得重新引入这一调用。

作用域仅画室插件：文字作为独立 text 图层保存内容、字体标识、字号、颜色、行距、对齐与布局框，生成透明 PNG 渲染缓存，保存和复制工程均保留源文字。已有变换负责移动、缩放和旋转；撤销重做沿用工程操作日志。手机与 AI 共享 Store 写入口和缩略图回执，不修改宿主。

渲染使用 Android 12+ 的显式 Font.getGlyphBounds/getMetrics 与 Canvas.drawGlyphs，不使用默认 Typeface。直接 Unicode cmap 映射仅覆盖基础横排，缺字和复杂塑形字符明确拒绝，不替换方框或把 SVG 工具宣称为完整实现。完整富文本、双向塑形、竖排和路径文字仍待实现。Android 10/11 文字工具保留不可用原因。

- [x] 明确字体接口与可编辑对象模型
- [x] 实现共享文字写入口、渲染缓存、撤销和归档
- [x] 实现工具箱点击添加及选中文字重新编辑
- [x] 更新能力声明、版本与文档，静态审查

本轮未授权编译、推送、安装或实机验收。

[DONE] 0.2.20 源码与静态检查完成。versionCode 23，applicationId .v0220，保留0.2.19格式优化。78字面能力/94声明，216菜单叶子/65共享实现，git diff --check通过。源码完成勾选不包含编译与实机验收。

待安装验收：中文/英文混排、缺字明确提示、字体选择、多行及对齐、选中后编辑、锁定拒绝、两端过期编辑拒绝、撤销重做、保存重新打开、复制图层、移动/旋转/缩放和自动图片回执；对照插件进程确认文字操作不触发重启。

字体来源审查：AOSP Font.cpp 的枚举调用 minikin SystemFonts::getFontSet，字体集合来自已注册的 mCollections，不能假设后台进程已初始化。实现直接读取 /system/etc/fonts.xml 的 CJK 家族，用 Font.Builder 加载声明的文件、ttcIndex、weight/slant/axes；不调用 SystemFonts.getAvailableFonts、Typeface 或隐藏初始化接口。字体配置或声明文件不可用时明确报错。
