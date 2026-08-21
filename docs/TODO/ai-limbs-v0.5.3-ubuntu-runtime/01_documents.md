# AI Limbs 文档解耦

## 旧实现

`AiLimbsDocumentProvider` 通过 Linux 文件工具读写 `/root/laner/docs/LANER_ACCESS_PROMPT.md` 与 `/root/LANER_WORK_MANUAL.md`。桥结果注入、健康检查和工具箱页面都继承了这条 Ubuntu 依赖。

## 修正

- 在应用私有目录建立 `ai_limbs/docs/`
- 对 V0.5.1 和 V0.5.2 的既有文件执行一次数据迁移
- 所有 AI Limbs 接口只访问新文档存储
- 不通过 `TerminalManager` 或 Linux 文件工具读写 AI Limbs 文档

## 验收

Ubuntu 未启动时仍可读取、编辑和注入动态接入提示与工作手册。

[DONE]
