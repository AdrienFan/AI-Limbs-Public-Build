# 图片格式与动画导出

## 原状与目标

0.2.86 的文件菜单只导出 PNG/JPEG，完整 GIF 独立存在于时间轴。0.2.87 将“导出”和“导出更多选项”统一接入 PNG、JPEG、WebP、BMP、单帧 GIF、HEIC、HEIF 和 AVIF；只有图层实际存在非空动画关键帧轨道时，显示“当前帧 / 完整 GIF”选择。

## 实现边界

- 静态输出通过 ArtStore.exportImage 捕获工程与资源租约，释放文档锁后显式投影时间轴当前帧，再执行裁切、缩放与格式编码。PNG/JPEG 已发布能力保留；新增 export.image，带 format/documentId/expectedRevision 和可选裁切尺寸。image.formats 增加 export 格式支持信息。
- 播放中打开菜单先提交当前可见播放帧并停播，再绑定工程 ID/revision。弹窗期间工程改变会禁用确认，编码入口再次复核；不输出旧弹窗对应的新工程内容。
- 完整 GIF 复用 animationExport，保留播放范围、FPS、循环、全局调色板和压缩计划；在两个菜单中可以指定最大边64–1024。更多选项的裁切/精确缩放只用于静态或当前帧。整段 GIF 保留32 Mi逻辑帧像素限制。
- 保存选择器同时接收真实 MIME 和文件名；所有格式均保留自定义默认 imagesDirectory 或用户文件选择器。取消清理临时文件，失败不换格式、不显示成功。
- WebP 无损；BMP 24位 BI_RGB，BGR底向上、四字节行对齐；单帧 GIF 学习255色并使用现有LZW，最大16 Mi像素、alpha128二值透明。JPEG/BMP/HEIC/HEIF/AVIF将透明区域合成为白色；PNG/WebP保留alpha。
- HEIC/HEIF 和 AVIF 使用 androidx.heifwriter:heifwriter:1.1.0。分别预检HEIC/HEVC与AV1 surface编码能力；不可用格式禁用并展示原因。预检不保证具体尺寸或运行时资源条件编码成功，失败保留明确错误。

官方接口依据：
https://developer.android.com/jetpack/androidx/releases/heifwriter
https://developer.android.com/reference/androidx/heifwriter/HeifWriter
https://developer.android.com/reference/androidx/heifwriter/AvifWriter

## 版本与验证

0.2.87 / versionCode90 / applicationId com.ai.limbs.payload.artstudio.v0287。仅修改画室插件与其能力声明/说明，不修改基座或其他插件。

已补云端JVM回归源码：BMP固定像素颜色/行顺序/头部与尺寸拒绝；动画模式仅对实际轨道开放；现有能力帮助覆盖数同步为259。未在本地编译或执行测试。

本轮仅做静态核对：Kotlin词法结构、manifest与能力/示例一致性、版本号、git diff --check和正式源码身份。云端测试/构建/打包及手机实际导出均待阿伟安排。

待部署验收：逐格式打开文件并检查文件类型、尺寸与透明度；静态GIF与完整GIF帧数；动画当前帧与播放中暂停位置；裁切缩放；默认目录/自选保存/取消；设备编码器缺失与编码失败；弹窗期间工程切换/编辑。

[DONE] 源码与静态核对完成；云端构建和运行验收待安排。
