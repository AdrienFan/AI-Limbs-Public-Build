# RDC 完整结果读取 v1.2.11

文件读取默认 100 行，上限 1000 行；进程读取默认 200 行，上限 1000 行。源工具报告内容上限时返回明确错误，不将省略文本当成完整结果。

超过 12000 UTF-8 字节的结果按固定 cursor 保存，返回 4000 UTF-16 字符一页。通过 start_process 的 shell=operit 发送 read_invocation 即可续读，直至 next_offset=null。分页保留完整结果和 SHA-256，边界不拆开 Unicode 代理对。

缓存最多四份，每份不超过 4 MiB，十分钟过期；接收端停止时释放。原 events 为重复展示事件，接收端保留正式 result 与 structured_result。Ubuntu 进程自身仍有输出保留窗口，分页只能保存源工具实际返回的内容。

SentinelX 已有完整 cursor 分页，此次不改其版本。云端 resident-bridge 流程增加 RDC 分页回归用例；本地未编译或运行测试。
