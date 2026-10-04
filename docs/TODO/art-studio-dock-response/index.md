---
fork: https://github.com/AdrienFan/AI-Limbs-Public-Build
---
# 画室停靠面板与时间轴响应

范围：仅画室插件，保留现有能力接口、工程格式与历史语义，继承0.2.91空画室重新进入修复。

18:24–18:26实机0.2.90日志记录整图合成8.6–9.8秒、共享锁占用/等待约10秒，18:26:40主线程跳过377帧，HWUI随后记录约7.8秒延迟。旧图层缩略图onDraw直接绘制全部笔画，编辑路径无条件重绘并持有页面mutex。日志不能逐条归因到具体按钮，部署后的收益须重新测量。

步骤：
- [后台图层预览](01-layer-previews.md)
- [状态与像素分离](02-editor-pixels.md)

版本0.2.92 / versionCode95 / 独立applicationId com.ai.limbs.payload.artstudio.v0292。源码与静态核对完成后提交；没有本地编译/测试，未推送云端。新增回归测试源码由后续云端CI验证。
