# 服务端与通用转发

原有服务仅验证 Host UID。此次保留管理入口和 Resident ownership 的 Host UID 限制，为普通 App 增加 v13 附着、申请队列和身份绑定授权。身份包含 UID、共享包集合、签名及首次安装时间。

普通 App 先由用户手动连接 protected Shizuku Provider。Binder 交付不构成授权，其他操作仍检查授权。UserService 在加载应用代码之前向服务端认领随机 token 和真实 PID，撤销时清理所记录的远程进程与服务。

基座扩展既有 host.privileged.runtime@1/rpc，保持原始 Binder 在 Kernel。Resident 持有连接时，经现有认证控制通道转发同一 JSON。基座只处理有界通信与运行时身份，不理解应用名单或授权模式。

实现源码已完成，未编译和真机验证。

[DONE]
