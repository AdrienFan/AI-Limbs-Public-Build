---
fork: https://github.com/AdrienFan/AI-Limbs-Public-Build
status: source-complete-runtime-pending
---

# 文字参数与画布输入

基线3aa08a43，开发版0.2.84。旧文字工具点击画布后打开独立AlertDialog，混合正文与所有参数；双击工具的统一浮窗仅有再次打开对话框的按钮，每次新建又重置字号、字体和布局。

仅在画室插件内迭代UI。完整文字参数迁入既有可拖动、缩小的工具浮窗，删除独立文字对话框。画布放置原生EditText输入光标；单击工具选择，双击设置。已发布text.*能力、原生文字源与PNG缓存、工程格式、历史和宿主架构保持兼容。

0.2.85/versionCode88/applicationId com.ai.limbs.payload.artstudio.v0285，包含尚未编译的0.2.83与0.2.84改动。源码和静态检查完成，未运行本地回归、未推送或编译。输入法和触摸效果必须在部署后验证。

- [参数迁移与输入事务](01-input-and-options.md)
- [部署验收](02-validation.md)
