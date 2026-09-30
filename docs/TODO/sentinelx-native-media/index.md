---
fork: https://github.com/AdrienFan/AI-Limbs-Public-Build
status: source-only
---

# SentinelX 原生媒体通道

基线 1e960e5，继续完善尚未编译的 SentinelX 0.1.8，设备仍为 0.1.7。上一轮支持 exec 的 mcp_content，但官方 sentinel_read_media 报告接收端缺少 file_export_init / file_export_chunk / file_export_complete。本轮按官方三步导出和二进制分帧协议补齐，原业务返回与分页协议保持。

只读取已通过 AI Limbs 权限链返回的 PNG/JPEG 附件，使用 /ai-limbs/media/ 虚拟句柄；不访问任意路径，不增加基座或文件宿主源语。图片始终来自原工具结果，不重放绘画动作。自动缩略图仍附在原回复里；媒体入口传送已有附件字节，不是另一个画室缩略图生成能力。

附件只在内存，缓存合计 4 MiB、最多 16 张，最多 4 个导出会话，10 分钟过期；活跃会话固定附件，缓存忙明确报错，断开时清理。二进制帧为 16 字节 transfer_id + 4 字节大端 chunk_index + 原图字节，先帧后 JSON 确认；完成后给出 SHA-256。

参照官方 src/sentinelx_core/handlers/file_export.py、src/sentinelx_core/client.py 和 sentinelx_protocol/binary.py。云端 read_media 的实际缺失提示确认其依赖三步导出。本轮不推送、不编译、不安装；静态审阅后本地提交，随后续优化一起验证原生图片呈现、边界和并发。

[DONE] 官方帧头、先二进制后 JSON 确认、顺序完成 SHA-256、句柄权限、缓存固定与过期、原业务结果保留等代码已静态审阅。整份内联控制回复限制 120 KiB，图片部分限制 96 KiB。git diff --check 通过；未编译、测试、安装或推送。设备上的 0.1.7 不支持新能力，原生媒体呈现需要统一编译安装后验证。
