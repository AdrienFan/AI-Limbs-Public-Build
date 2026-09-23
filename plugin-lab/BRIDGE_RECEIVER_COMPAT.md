# Bridge 接收端版本与输出分页

这次从已安装的 Bridge ABI 5 接收端继续迭代。联合构建目标 `resident-bridge` 同时生成 Bridge 主插件与 RDC、TRIGGERcmd、SentinelX 子插件；RDC 更新为 1.2.10，SentinelX 更新为 0.1.5，接口仍为 `ai_limbs.bridge.provider@5`。

RDC 的 `read_file` 默认读取 20 行，`length` 超过 20 行时也只交付本页。响应中的 `page` 给出 `offset`、`total_lines`、`has_more` 和 `next_offset`。负数 `offset` 先向 Host 查询总行数，再从文件末尾定位。Host 每页超过 32000 字符时明确报错，调用方应缩小行数或改用有界命令。进程输出每次最多读取 20 行；初始预览过长会标出省略，完整内容按绝对行号续读。

SentinelX 的小结果继续返回 `output` 和 `bridge_result`；去掉 Host 中重复的 `events`。大结果的 `bridge_result` 标记 `paged=true`，给出 `cursor`、`next_offset`、`total_chars`、`sha256`；`output` 是当页内容。继续调用 `sentinel_exec`，命令为 `AIL_SENTINEL_BRIDGE_V1 {"tool":"ai_limbs.bridge.result_page","args":{"cursor":"<游标>","offset":<下一偏移>}}`。依序连接 `output` 并核对 SHA-256。结果最多缓存 4 项，每项最多 4 MiB，10 分钟到期；超限会明确报错，须从源头缩小查询。

AI Limbs Host 的 Ubuntu 进程收集链路仍可能重复记录输出，这不属于两个子插件的 APK。接收端只约束单次返回并保留续读路径，不擅自删除可能本来就重复的日志行。Host 的 32000 字符文件读取上限也仍然生效。
