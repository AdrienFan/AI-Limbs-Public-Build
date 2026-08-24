---
fork: https://github.com/AdrienFan/AI-Limbs-Public-Build.git
branch: fix/v0.6.3.6-external-vision-bus
status: active
---

# V0.6.3.6 External Vision Bus

## 现状

V0.6.3.5 能将 Laner Chat 图片注册到 ImagePool，并对附件读取尝试输出 MCP image content，但外部 ChatGPT 仍不能稳定消费真实像素。RDC 顶层 read_file 同时被 Adapter 固定为 text_only，屏幕截图等其他图片结果也没有统一多模态出口。

## 意图

把 AI Limbs 到 RDC 的外部视觉做成统一结果通道，不再针对单一附件工具做特判。

## 期待结果

Laner Chat 图片、当前屏幕截图和普通 Android 图片文件都能通过 RDC 以真实图像内容交给外部 ChatGPT。验收不得依赖 OCR、get_page_info、文件名或路径推断。

## 作用域

仅修改外部视觉传输与 V0.6.3.6 版本标识。UI_CONTROLLER 内部视觉子代理及其模型配置不在本次范围。