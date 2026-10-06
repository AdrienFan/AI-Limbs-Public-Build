# 验收计划

云端增加 AiLimbsNamedScopePriorityTest，覆盖未知插件名称、多词显示名称、名称子串不误命中、普通查询原序、精确 capability ID 不被折叠、候选保留以及 limit=1 时优先具名插件。

沿用基座现有云端定向 JVM 检查和 instrumentation 源码编译，构建 build102。部署后再通过自制桥验证原 Ubuntu 查询。

仅启动云端检查与编译，不在 Ubuntu 本机运行 Gradle，也不持续监视云端作业。
