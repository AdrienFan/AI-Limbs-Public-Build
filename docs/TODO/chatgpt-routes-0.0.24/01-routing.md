# 路由选择与验证

旧实现把 DoH A/AAAA 交给 OkHttp，retryOnConnectionFailure=false，网络未提供 IPv6 时仍可能尝试 IPv6。

本轮连接前读取当前默认网络的源地址族与路由，按最长前缀选择 unicast 路由。网络每次重读，不缓存节点、地址或路由；IPv4、IPv6、双栈分别按实际能力选择。所有回答先检查公网属性，即使非公网地址之后会被路由排除也仍拒绝整个回答。没有可路由地址时明确报错，不改变 TLS 信任，不重发已执行操作。

测试覆盖 IPv4 VPN 的 unreachable IPv6、双栈、IPv6 网络、显式更具体的拒绝路由、网络切换、公网校验先于路由过滤及仅不可路由回答。只在云端运行；本地仅静态审阅。

实机证据：0.0.23，active_subscriptions=0，未创建测试任务；DoH successful_lookups=1/failed=0，stage=connect/NoRouteToHostException。tun0 LinkAddresses 只有 172.19.0.1/30；IPv6 表 1113 中 unreachable default；IPv4 route get 1.1.1.1 走 tun0，IPv6 route get 返回 No route to host。该证据确认设备路由限制和代码选择缺陷，不能证明此前 TLS 错误的原因。

源码与测试已编写 [DONE]。云端测试/构建结果和部署验收待反馈；用户要求提交编译后停止监控。
