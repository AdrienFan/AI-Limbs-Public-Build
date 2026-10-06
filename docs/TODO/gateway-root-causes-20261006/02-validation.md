# 验收计划

云端运行 GatewayReceiptsTest、GatewayResultsTest、GatewayTimingsTest、GatewayEngineTest 及已有回归，再编译签名 0.0.14。

关键断言包括旧账本迁移中断后恢复、去重不重执、单记录写入成本与历史无关、旧 shard 不回写、分页故障状态保留、慢 POST 不堵后续响应、POST 并发不超过 4。

新版部署后比较 request_timings 和调用总耗时，区分本地执行、持久化、HTTP 交付与未覆盖的上游耗时。此阶段不承诺已解决所有外部延迟。

仅启动云端检查与编译，不在 Ubuntu 本机运行 Gradle，也不持续监视云端作业。
