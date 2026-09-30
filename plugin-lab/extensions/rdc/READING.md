# RDC 完整结果读取 v1.2.11

文件读取默认 100 行，上限 1000 行；进程读取默认 200 行，上限 1000 行。源工具报告内容上限时返回明确错误，不将省略文本当成完整结果。

超过 12000 UTF-8 字节的结果按固定 cursor 保存，返回 4000 UTF-16 字符一页。通过 start_process 的 shell=operit 发送 read_invocation 即可续读，直至 next_offset=null。分页保留完整结果和 SHA-256，边界不拆开 Unicode 代理对。

缓存最多四份，每份不超过 4 MiB，十分钟过期；接收端停止时释放。原 events 为重复展示事件，接收端保留正式 result 与 structured_result。Ubuntu 进程自身仍有输出保留窗口，分页只能保存源工具实际返回的内容。

SentinelX 已有完整 cursor 分页，此次不改其版本。云端 resident-bridge 流程增加 RDC 分页回归用例；本地未编译或运行测试。

## 开发版 1.2.12：原生内联图片回执

通用业务 JSON 附带的 mcp_content image 块被提取为 MCP content 数组中的图片；原业务字段仍按原协议文本返回/分页，Base64 不进入文本分页。图片随首次调用返回，续读只取剩余文字。图片仅支持 PNG/JPEG，最多四张、编码合计 2 MiB。

正式插件能力可直接返回业务对象而无 success 包装；没有 error 或显式 success=false 的业务对象按成功处理，不再误标 isError。基座和权限链不修改。本轮源码尚未推送编译或安装，云端实际转发图片需要后续验证。
