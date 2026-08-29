---
repository: https://github.com/AdrienFan/AI-Limbs-Public-Build.git
branch: dev/v0.6.4.7.4
baseline: 5022852
status: ready-for-cloud-build
---

# AI Limbs V0.6.4.7.4 统一执行政策

## 结果

- Resolver 与 Dispatcher 共用 transport-neutral `AiLimbsExecutionPolicyEngine`。
- RDC 与 External HTTP 都持有显式 transport/session scope；重连不再清除上下文收据。
- System Access Prompt 由不可变代码生成，旧 asset 仅保留退役标记。
- Work Manual 收据、统一权限、Ubuntu/UI availability、托管文档路径和 Laner Turn 规则均进入代码门禁。
- 长期文件写入确定归属、唯一规范地址与可恢复 storage index。
- 只有响应实际附带图像块时才标记 `IMAGE_PIXELS`。

## 作用域

- [规范化调用、会话与政策内核](./01_policy_kernel.md) [DONE]
- [入口接线与领域终态守卫](./02_transport_and_domain_guards.md) [DONE]
- [提示说明、测试与交付](./03_prompt_tests_and_delivery.md) [DONE]
