# 根因与修复

0.0.13 每次 claim、ready、ACK 都序列化并加密写回整个 24 小时账本，成本随历史请求增长。结果交付逐个串行 POST，并固定等待 750 ms，慢响应阻塞后面的已完成请求。

改为每条 receipt 单独加密原子保存。旧 receipts 账本启动时做可恢复的前向迁移：保留全部请求身份、执行状态和结果；所有记录成功持久化后才删除旧账本；已迁移的新状态优先。初始化在 IO 协程完成后才启动收取请求，不阻塞 UI，也不绕开账本。

结果交付采用最多 4 个并行 POST，完成结果唤醒交付循环。重试只发送已记录的响应，绝不重复业务执行。旧 shard 的 ACK 或拒绝不得覆盖更新后的 shard。AtomicFile 的 .bak 纳入恢复枚举。

Ubuntu 负责产生领域错误码。桥在分页首包保留 error_code、status、exit_code、execution_state、automatic_reexecution、next_action。源码中没有证据证明 Bridge 或 Host 显式生成 INVALID_ARGUMENT；不能据默认显示标签把责任归到 Host。

加入最多 16 条单调时钟分段指标，只显示工具名称、时长和交付尝试数，不含请求参数、令牌、结果或请求标识。先消除已确认的本地成本，不能据此认定先前 29 秒全部来自桥。

源码实现 [DONE]
