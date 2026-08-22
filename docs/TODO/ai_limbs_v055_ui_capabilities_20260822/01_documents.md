# 文档界面与文件不变量 [DONE]

- 接入管理只加载并显示 `LANER_ACCESS_PROMPT.md` 与 `LANER_WORK_MANUAL.md`。
- 两张编辑卡不再展示保护说明。
- `LANER_TOOL_MANUAL.md` 保留系统读写能力，但不暴露在接入管理界面。
- 文档 Provider 初始化完成前确保三个文件都存在；只创建缺失文件，不清空已有内容。
