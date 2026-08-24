# 04 清理 RDC 模型侧图片结果

## 实机现象

V0.6.3.6 的截图、普通图片和 Laner Chat 附件都能进入 ImagePool，并能生成 MCP image block，但外部 ChatGPT 仍只能看到内部 image link 占位，无法描述真实像素。

## 对照结论

Desktop Commander 当前模型侧 `read_file` 使用干净的 text block 加原生 `type=image`、`data`、`mimeType`。AI Limbs 此前同时把 Operit 内部 `<link type="image">` 留在 text block，并追加 MCP image block，形成两套并存的图片协议。

## V0.6.3.7 修正

- 先从内部 image link 解析真实 Base64 与 MIME
- 在模型侧 text block 中删除全部内部 image link
- 只通过标准 MCP image block 对外暴露像素
- 日志仅记录 MIME 与 Base64 字符长度，不记录图片正文

## 验收

实机通过 RDC `read_file` 读取未知图片后，外部 ChatGPT 必须直接描述像素内容；不得使用 OCR、文件名、路径、元数据或 `get_page_info` 代替视觉。

[DONE]
