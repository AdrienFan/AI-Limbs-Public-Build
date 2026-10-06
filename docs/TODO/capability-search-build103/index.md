---
fork: https://github.com/AdrienFan/AI-Limbs-Public-Build
branch: feat/quick-chat-overlay-host
version: 0.8.0.16-build103
---

# 通用全局能力搜索迭代

此前范围查询仍收集全部工具包、技能与 MCP 元数据，全局评分又先限制到前 20 个候选，再按 owner 折叠和排序。多来源同名能力及插件底层残留还可能造成错误定位或重复卡片。

本次在 Host 通用 Resolver 中改进目录路由、元数据索引和结果导航，ChatGPT、RDC 与其他使用 capability.search 的接收桥共享同一实现。插件业务及能力权限仍归原有 owner 和 Policy Engine 管理。

已识别的模块名会限定当前注册 owner，返回 search_mode、effective_query 与 resolved_scopes 表明实际检索范围。未指定模块的自然语言意图检索所有当前目录来源。

作用域为通用 Registry、Resolver、ToolCapabilityCatalog、PackageManager 的目录快照、回归代码及云端工作流。公共 search 参数和 protocol_version=3 保持兼容，保留 results 与 scope_results。

实施见 [01-search-routing.md](01-search-routing.md) 和 [02-index-delivery.md](02-index-delivery.md)。验证与交付见 [03-validation.md](03-validation.md)。
