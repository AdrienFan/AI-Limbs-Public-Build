# 实现范围

工具箱插件独立持有配对客户端、服务端 asset、启动流程、页面、状态与日志。Host 提供有身份和 scope 校验的 Binder 入口、后端选择及卸载撤销。

首版只连接本机 ADB 或明确请求 root，通过 Host UID 对接现有 DEBUGGER 执行器。Android 用户权限级别不自动提升；第三方应用授权、Rish 和外部 UserService 不在本版范围。

[DONE] 源码实现完成，安装和激活仍须真机验证。
