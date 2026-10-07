---
fork: https://github.com/AdrienFan/AI-Limbs-Public-Build
branch: feat/visual-workbench-v02
version: 0.2.7
---

# 视觉工作台图片附件读取修复

## 证据与目标

2026-10-08 02:31:54 共享屏授权成功，02:31:55 创建 1812×2176 虚拟显示，02:31:56 工作台在 PreviewPanel 抛出 `org.json.JSONException: No value for data`。02:32:22 重进再次发生；前一版本日志也有同类错误。Core 单份附件传输已移除预览元数据的 data，而 UI 首帧及恢复预览仍直接读取该字段。

目标是让工作台正确消费既有图片附件协议，阻止格式异常逃逸到 Compose 渲染。范围仅视觉插件，不修改基座、桥、截图方向、观察等待算法或权限流程。

## 实现

1. 提取 VisualImageAttachment 为 Core 的统一附件生成器，保持一个 mcp_content 图片附件和无 data 的元数据，支持 result 与 image 为同一对象。
2. VisualPreview 在操作协程内验证唯一图片附件、MIME 一致性、非空数据、来源、时间及尺寸；转换成功后才赋给 UI 状态。首次 start/frame/capture 读取 preview 元数据，preview.read/images.read 显式读取结果本身。
3. 屏幕、相机、重新进入恢复、图像查看及缩略图统一消费类型化状态。异常进入现有错误提示；Base64/Bitmap 解码继续由 IO 协程处理，不在 Compose 中使用严格 JSON 图片字段访问。
4. 不添加旧字段兼容、缓存替代或自动重试；不复制 Base64，不清除已有预览和图像记录。
5. 插件版本 0.2.7，versionCode 13，payload applicationId com.ai.limbs.payload.visualmanager.v027，逻辑身份不变。

## 验证

新增七项 VisualImageAttachmentTest，直接调用生产附件生成器并经 JSON 序列化后送入生产读取器，覆盖屏幕/相机首帧、重进及存图的对象别名、缩略图尺寸、禁止旧字段回退、缺失/空白/格式不一致附件、重复附件、无效元数据。

云端 visual-manager-build.yml 执行来源检查、能力契约检查、testDebugUnitTest、assembleDebug 及正式签名打包。云端结果以对应提交运行记录为准。实机须部署 0.2.7 后验收开启共享、离开并重进、获取一帧、保存与查看图像、相机及缩略图；本轮不宣称已通过安装后实机验证。

[DONE] 代码与回归测试已完成；云端构建和实机验收分别记录结果。
