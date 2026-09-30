---
fork: https://github.com/AdrienFan/AI-Limbs-Public-Build
status: source-only
---

# 通用工具结果的内联图片

基线 dfb029e，SentinelX 0.1.7、RDC 1.2.11。保留现有业务结果、文本分页和权限链，识别成功业务结果附带的 mcp_content 图片块，将图片编码从文本分页中分离，不增加画室专用判断，不修改基座。

RDC 直接追加 MCP image 内容块。SentinelX exec 云端入口不是原生媒体工具，保留同一次 response 的 mcp_content，由调用方直接呈现；不能声称未修改的云端会自动把 exec JSON 转成图片。内联媒体限制 96 KiB，过大明确报告 media_delivery 错误，业务结果不丢弃，禁止为取图重放已完成操作。

本轮用户要求暂不推送编译；开发版本 SentinelX 0.1.8、RDC 1.2.12。后续统一验证成功、失败、分页与图片并存，以及云端/客户端呈现行为。

[DONE] 源码实现与静态审阅完成，git diff --check 通过。未执行编译、构建、测试或安装，未推送云端；实际 MCP 媒体呈现和性能待后续验证。
