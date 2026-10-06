# 验收计划

云端运行 GatewayReceiptsTest、GatewayResultsTest、GatewayTimingsTest、GatewayEngineTest 及已有回归，再编译签名 0.0.15。

关键断言包括旧账本迁移中断后恢复、去重不重执、单记录写入成本与历史无关、旧 shard 不回写、分页故障状态保留、慢 POST 不堵后续响应、POST 并发不超过 4。

新版部署后比较 request_timings 和调用总耗时，区分本地执行、持久化、HTTP 交付与未覆盖的上游耗时。此阶段不承诺已解决所有外部延迟。

仅启动云端检查与编译，不在 Ubuntu 本机运行 Gradle，也不持续监视云端作业。

## 0.0.14 云端失败根因与 0.0.15 修正

2026-10-06 的 run 37423596034 已完成源码编译，并执行 44 项测试，其中 43 项通过。唯一失败为 metadataContractStaysStableAndRestartRequiresNewAccessEvidence：预期 0.0.13，实际 0.0.14。测试硬编码旧版本号阻断了后续 assemble 与签名；运行中的旧桥 0.0.13 仍在线。

0.0.15 启用 Gradle 生成的 BuildConfig，将运行时 PROBE_VERSION、initialize 元数据、诊断 source 标签和测试预期连接到构建版本，不再分别维护版本字符串。包 manifest 与 Gradle 版本仍由现有 source provenance 检查核对。

源码修正 [DONE]

重新启动全量 44 项云端回归与签名构建，未绕过或删除失败测试。新作业结果与安装后验收待确认。
