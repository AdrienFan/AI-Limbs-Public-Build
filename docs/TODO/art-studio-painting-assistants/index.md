---
fork: https://github.com/AdrienFan/AI-Limbs-Public-Build
status: source-complete
---

# 绘画辅助尺规

Krita 工具通过控制点创建文档级 assistant，画笔再把采样投影到尺规，并在一笔内锁定目标。画室当前只留灰色工具，没有尺规模型、编辑手势和吸附采样。

在插件内建立直尺、无限直尺、平行尺、椭圆、同心椭圆、消失点六种基础类型，保存、撤销、复制随工程一致。尺规为独立文档辅助对象，不成为图层或导出像素。画布可点击创建和拖动控制点/整体，双击参数浮窗配置。基础栅格自由画笔吸附采样并固化为笔迹，回放不重新吸附。共享 AI 创建、编辑、投影、沿尺绘画能力，复杂助手及未支持画笔吸附灰色说明。

作用域仅画室插件，不修改宿主和接收端，不上传或编译。本轮依据共享储存 Krita 6.0.4 RulerAssistant.cc、ParallelRulerAssistant.cc、EllipseAssistant.cc、VanishingPointAssistant.cc 与 libs/ui/kis_painting_assistants_decoration.cpp，以及官方助手文档。

- [x] 尺规模型、编辑与几何投影
- [x] 工程操作、实时编辑与绘画吸附
- [x] 参数浮窗、协作能力、反馈及版本文档

版本0.2.31 / versionCode34 / com.ai.limbs.payload.artstudio.v0231。只改画室插件；源码检查和本地提交完成后保留待编译验收状态，本轮不推送、不编译、不运行测试。

[DONE]
