# 元数据索引与结果交付

索引一次准备规范化文本和词集合。索引键同时包含当前注册 generation 与完整目录元数据，注册、有效卸载、元数据或 sourceEnabled 改变均会更新索引。缓存拥有列表副本，来源修改可变集合不会原地改变旧索引键。

每次搜索仍读取来源当前公开的目录快照，不依靠长 TTL 固定整个目录。全局扫描中的工具包共用一份设备条件快照，避免每个含 state 的包重复检查权限与设备。单次查询的重复模糊词匹配也共用结果。

缓存不包含权限、receipt 或可用性结论。每张叶能力卡仍调用当前 Policy Engine 检查。执行入口仍走正式 Dispatcher。

保留 results 和 scope_results，同时增加只含 ID 引用的 items，保存两种卡片之间的 rank，避免重复整张卡片。next_action 根据第一条综合结果生成真实 capability_id 或 scope_id，并保留用户的剩余查询意图。

卡片增加最多 120 个 Unicode code point 的 purpose，并在存在 owner 时返回 scope_id。精确、命名模块与 scope 查询只显示热榜入口，完整跨模块搜索沿用每 Interaction Cycle 一次的热榜展开。

timings_ms 分别记录目录来源、索引与排序、权限、热榜及 resolver_total。它们是 Host Resolver 内部耗时，不包含隧道、上游 MCP 交付和模型等待时间。

[DONE]
