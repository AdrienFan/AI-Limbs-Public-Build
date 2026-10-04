---
repository: https://github.com/AdrienFan/AI-Limbs-Public-Build
status: source-complete; static-review-complete; runtime-pending
---

# 足迹单笔删除

原来点击足迹会回到那一步，也会撤回后续构造。目标是独立选择并删除某笔，保留其他内容，在底部固定窄操作条右侧放置删除图标，同时提供兰儿能力入口。

1. [对象对应与安全边界](01-delete-object.md)：保留对象删除，不做历史回退。[DONE]
2. [固定操作条与能力合同](02-panel-capability.md)：独立选择、并发校验、可撤销。[DONE]

作用域仅画室插件、能力清单/帮助、版本及文档；不改基座/Ubuntu/Runtime API，不推送或启动云构建。0.2.88 / code91 / v0288。

静态核对：能力注册与清单/帮助对齐，Kotlin词法结构、补丁空白检查、版本一致性、定向删除链路及禁止历史跳转核对。回归源码交云端执行，本地未编译或运行测试。

待设备验收：连续三笔中删除中间一笔且后续形状保持，撤销/重做，滚动后固定栏位置，工程切换与并发拒绝，当前关键帧局部删除，接续/合成与像素依赖的禁用说明。
