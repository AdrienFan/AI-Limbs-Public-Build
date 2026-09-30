# AI Limbs 旧 UI 入口清理

## 原因与范围

旧内置包 Automatic_ui_base 已不能经当前 Dispatcher 正常执行，但其脚本和 Resolver 独立语义表仍参与能力发现，导致截图、页面结构等查询返回失效入口。

本次移除该包的 assets、TypeScript/JavaScript 示例及打包白名单；同时移除 Resolver 的旧语义表、来源定位、旧能力示例，以及 UI 状态和政策分类中的包引用。AutoGLM 配置提示同步删除关闭旧包的描述。

## 保留的能力边界

视觉管理由插件提供业务入口，继续使用 host.screen.capture@1、host.screen.session@1 等通用宿主原语。原生 get_page_info、capture_screenshot、点击、输入等工具和 Automatic_ui_subagent 保留。

Resolver 直接采用现有目录与统一注册信息，不为旧包另设别名、替代路由或回退。包管理器原有 refreshToolPkgRuntimeState 会清理已不存在的包的启用记录，无需新增旧包专用迁移。

## 生效与后续验证

此修改须随基座重新构建、安装并重启后生效。安装后的能力搜索不应再出现 Automatic_ui_base:*；页面结构读取、视觉插件截图及原生 UI 操作须仍正常工作。本次不改截图目标选择、页面文字截断或体验优化。
