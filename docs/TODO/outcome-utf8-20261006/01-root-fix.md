# 根因与实现

同一次 exit 7 调用，接入后的顶层 error_code 是 INVALID_ARGUMENT，但桥的不可变缓存 JSON 保留 UBUNTU_COMMAND_EXIT_NONZERO。覆盖发生在桥保存、适配结果之后；现有证据不能指出上游具体内部函数，也不能归责于 Host 或 Ubuntu。

桥在 structuredContent 之外按 MCP 建议同步提供序列化 JSON TextContent。失败时另外声明 ai_limbs_outcome，逐字段复制源结果的错误码、状态、退出码、执行状态、重执行限制及恢复动作，保持与接入层顶层分类分离。顶层旧字段保持兼容。首个分页信封同样交付源结果命名空间，后续读取仅取不可变缓存，不重执行原能力。

规范来源：https://modelcontextprotocol.io/specification/2025-06-18/server/tools#structured-content

[DONE]
