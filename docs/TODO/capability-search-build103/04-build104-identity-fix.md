# build104：精确叶能力判断的全局唯一性

build103 云端任务 37441463025 对应提交 6fe29852aa9ec99f709eddfea1e9e70051efd51e。生产代码和 JVM 测试代码编译成功，127 项 JVM 回归中 126 项通过，manyPluginsStayBoundedAndNeverDuplicateScopes 失败：预期 5 个模块入口，实际 0 个。构建在测试阶段停止，APK 打包与 instrumentation 编译尚未执行。

根因是 Organizer 按单个能力判断显示名称完全相等，错误地把所有共用该显示名称的能力视为精确叶能力，禁止模块折叠。Planner 已经要求显示名称唯一，两处语义不一致。

将精确选择器统一为 AiLimbsScopeQuery.exactDefinition。先在完整候选中匹配 capabilityId、invokeId 和 aliases，仅唯一地址返回叶能力；不存在地址匹配时，显示名称也必须全局唯一。Organizer 在分 owner 前确定精确叶 ID，重名查询继续接受常规模块折叠。

保留原失败用例，新增 5 项云端 JVM 回归：同 owner 重名、跨 owner 重名但各 owner 内唯一、唯一显示名称、地址与其他 owner 显示名称冲突、唯一与重名别名。现有压力测试类已在云端过滤清单中，无需删减或跳过测试。

版本更新为 0.8.0.16-build104，versionCode=199。稳定更新包仍为 com.ai.assistance.operit.ailimbs.stable，对比包为 com.ai.assistance.operit.ailimbs.build104，显示名称 AI Limbs Build 104。

只执行静态差异审查。云端继续执行完整原有筛选回归、instrumentation 编译及稳定包和对比包构建。任务提交后核对完整 headSha，再停止监控；构建成功和实机效果尚待实际结果。

[DONE]
