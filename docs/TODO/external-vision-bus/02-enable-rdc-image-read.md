# 02 恢复 RDC 图片文件读取

## 旧实现

AiLimbsRdcToolAdapter.readFile 固定传入 text_only=true，导致图片在进入特殊文件处理前就以 Skipped non-text file 结束。

## 修正意图

让 RDC 顶层 read_file 对图片保持多模态语义：图片进入 direct_image 路径并通过统一结果适配器输出真实 image content，文本文件继续按原文本路径读取。

## 新实现期待

RDC read_file 可以直接读取 Android 图片文件，不依赖 OCR，也不需要先通过 Laner Chat 附件系统。

## 最小验收

对 PNG/JPEG 调用 RDC read_file 时返回真实图片内容；对普通文本文件保持现有文本读取行为。
[DONE]
