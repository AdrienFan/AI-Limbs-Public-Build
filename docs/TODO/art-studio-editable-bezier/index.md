---
fork: https://github.com/AdrienFan/AI-Limbs-Public-Build
status: source-complete
---

# 可编辑贝塞尔路径

基线82237a4f、画室0.2.25源码，用户授权先调查Krita再实现。Krita6.0.4 KisToolPath委托KoCreatePathTool创建，KoPathTool另做节点编辑；官方path/shape_edit手册核对点按/拖柄、结束/取消，以及corner/smooth/symmetric约束。

本轮复用path的points/commands/closed格式，添加可选nodeModes元数据，与旧0.2.25路径兼容；不迁移或替换原路径模型。只在画室插件实施，不新增宿主依赖，不构建测试推送。绘制和节点编辑放在vector_bezier的两种模式中，保留原栅格bezier工具。

- [x] 节点解码/编码，移动节点与控制柄、节点类型、精确分段插入/删除、线曲转换与开闭
- [x] 共享path.create/nodes/edit能力与SHAPE_PATH_EDIT日志、版本绑定、预算和缩图
- [x] 手机/鼠标创建及编辑预览，结束/闭合/撤回/取消、选项和键盘入口
- [x] 文档/版本/差异审查、Git提交；待编译安装验收

编辑器本轮支持单路径、单节点/控制柄拖动与数值节点编辑；多节点框选、多个子路径、跨对象端点合并、布尔/SVG等未实现，不伪装为Krita完整工具。路径和对象变换坐标明确：创建节点为图层局部，已存在路径节点编辑为对象局部。两端按docId/revision拒绝过期操作，触摸预览不写历史，释放一次提交。

[DONE] 0.2.26/code29/v0226源码完成，实际交互与工程往返待后续编译安装验收，未执行编译测试或云端推送。
