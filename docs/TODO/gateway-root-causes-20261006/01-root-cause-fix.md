# 根因与修复

Global Scope Organizer 收拢插件分组后仍按原始 sourceIndex 排序。全局查询 Ubuntu command status session 中，其他模块仅匹配 status 或 session 也可能排在明确提及的插件之前。

对明确提到完整 scope、owner ID、显示名称或 owner 最后一个身份片段的查询，优先排列该 owner 的已有候选，再按原分数顺序。按完整词边界匹配，防止名称子串误命中。无硬编码 Ubuntu 或 ChatGPT 业务；其他模块仍保留为候选，显式 scope 搜索行为不变。

准确 capability ID 保持具体叶能力匹配；广义查询仍保留现有分组规则。变更只位于 Host 通用发现层。

源码实现 [DONE]
