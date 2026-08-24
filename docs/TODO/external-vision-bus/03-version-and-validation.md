# 03 版本与验收

## 版本

V0.6.3.6 使用独立 applicationId 与 versionName，不覆盖 V0.6.3.5。

## 静态验证

检查 git diff、git diff --check、ail-preflight，以及外部视觉相关源码的一致性。正式 Android 构建仍只走 GitHub Actions。

## 实机验收

安装 V0.6.3.6 后分别测试 Laner Chat 上传图片、当前屏幕截图、普通图片文件。每项都要求外部 ChatGPT 直接描述像素内容，不使用 OCR、get_page_info、文件名或路径推断。

## 完成条件

三个外部视觉入口共享同一多模态结果机制，并能在实机测试中被 ChatGPT 直接消费。
Implementation and static preflight are complete. Runtime pixel-vision acceptance remains pending on the V0.6.3.6 device.
