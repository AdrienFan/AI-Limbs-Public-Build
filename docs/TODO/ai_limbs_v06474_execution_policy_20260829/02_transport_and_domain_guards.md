# 入口接线与领域终态守卫

## 旧实现

- RDC 与 External HTTP 组装不同的 Dispatcher 依赖。
- Laner Chat legacy reply 可跨过 managed turn 语义。
- Policy precheck 与并发执行之间可能发生状态变化。

## 新实现

- RDC、External HTTP 与未来 Bridge 使用同一个必选政策引擎。
- Resolver 的 permission、availability 与 Dispatcher 的决策来自同一政策描述。
- Laner Chat、Ubuntu 生命周期、托管文档和 UI readiness 在领域服务内原子复核。
- 任意命令执行按 effectful 处理；只读需求使用真实受约束能力，不解析命令字符串猜测。
- 托管文档按规范化目标隔离，不能借路径别名、符号链接或 shell 绕过。
