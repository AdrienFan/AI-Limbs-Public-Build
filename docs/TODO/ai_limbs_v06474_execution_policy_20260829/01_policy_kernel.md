# 规范化调用、会话与政策内核

## 旧实现

- Dispatcher 的 Access Gate 可为空，且只记录 system/custom prompt 的连接级收据。
- Core Provider 默认 ALLOW，工具目录没有 effect、domain、required receipts 等结构化字段。
- 外层 host-tool 包装隐藏了 RDC 固定工具的真实目标。

## 新实现

- 先把各入口转换为真实 capability、参数、transport 与 context 的规范化调用。
- 会话状态区分连接与模型上下文，收据绑定政策版本和有效工作手册版本。
- 政策决策统一返回 ALLOW、ASK 或 FORBID，并携带稳定 reason code 与可执行 next action。
- 未登记的执行政策元数据关闭执行，不使用推测或回退路径。
