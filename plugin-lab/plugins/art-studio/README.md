# AI Limbs 画室 0.1.0

独立的 `android_inprocess` 插件，工具箱页面和 AI Capability 操作同一个插件数据目录中的工程。Host 与 Resident 通过同一个工作目录及文件锁协调，页面上的操作固定记录为 `AWEI`，公开能力中的修改固定记录为 `LANER`。不使用模拟点击。

工程草稿保存在插件私有 `drafts/<id>.json`；显式保存时生成 `documents/<id>.ailart`，ZIP 内含 `project.json` 与实际引用的 PNG 资源。导出图片进入系统图库的 `Pictures/AI Limbs Art Studio`。插件卸载并删除数据将删除这些工程；重要工程应另行备份。

每次笔画与图层编辑是独立 Operation。指定撤销通过 `REVERT` 记录目标操作，随后重放仍启用的操作；如果后续操作依赖被撤销的对象，操作失败且原工程不变。`RESTORE` 能恢复指定操作。此版本支持普通尺寸画布；每层由位图缓冲绘制，尚无 tiled canvas。

能力注册名为 `plugin.art.studio.*`，同时声明 `art.*` 调用别名；均由 AI Limbs Resolver、Policy Engine 和 Dispatcher 执行。图像导入的 `base64` 上限为 8 MB；查询工程请谨慎处理大体积笔画数据。UI 直接修改插件私有工程文件，不借用公开 AI 调用来伪装 `AWEI` 身份。

## 已知限制

- 图层组具有创建及显示层级；子图层绘制沿用画布坐标，组级变换和混合还未实现。
- 矩形选区记录为结构化操作；选区裁切笔画、局部复制仍待后续版本。
- 墨笔/铅笔/软笔/喷枪为基础笔触，压感影响线段宽度；并非专业笔刷引擎。
- 画布首次渲染按图层创建位图，建议从 1024×1024 开始；4K 画布的多图层内存消耗较高。

用户要求正式基座原位升级；本插件复用现有 SDK 和插件中心接口，**不修改 build67 的 applicationId、签名或基座代码**。
