# 01 统一 RDC 多模态结果

## 旧实现

AiLimbsRdcToolAdapter 只在 ai_limbs.chat.attachment.fetch 时解析 ImagePool link 并追加 MCP image block。其他能够产生 image link 的工具仍按纯文本结果返回。

## 修正意图

让 RDC 结果适配器按结果内容识别并输出图片，而不是按工具名特判。文本块继续保留，图片块从 ImagePool 提取真实 base64 与 mimeType。

## 新实现期待

任何经过 AiLimbsRdcToolAdapter 返回、且结果中包含有效 image link 的成功工具调用，都能统一输出 text + image content。没有图片的工具行为保持原样。

## 最小验收

屏幕截图工具与 Laner Chat 附件读取都走同一多模态适配逻辑。
[DONE]
