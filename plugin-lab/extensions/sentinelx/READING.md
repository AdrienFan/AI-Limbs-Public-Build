# SentinelX 完整结果读取 v0.1.7

v0.1.6 在 v0.1.5 源码上修正首次分页的并发边界；v0.1.7 在同一提交基础上修复云端 SDK 初始化，保留该修正。此前 store 保存结果并释放缓存锁后，调用 page 再查一次游标；并发请求挤掉该条目时，强制解包会报异常。现在首次响应直接从刚保存的不可变 SavedResult 生成，续读同样在获取 SavedResult 后独立生成页面。

结果格式与分页协议保持一致。超过 12000 UTF-8 字节的响应按 4000 UTF-16 字符一页返回，使用 ai_limbs.bridge.result_page 及 cursor、offset 续读；边界不拆开表情字符，SHA-256 用于核对拼接结果。缓存最多四份，每份不超过 4 MiB，十分钟过期。并发超过缓存容量时，已淘汰游标的后续读取仍明确报告 invalid_result_cursor。

新增回归用例覆盖十二线程并发保存、第一页内容与摘要对应、Unicode 全文拼接，以及游标淘汰、清空和非法偏移。测试接入单独的 SentinelX 云端构建。本地只检查源码、版本和差异，未编译或运行测试。

单独签名使用原有 Child Extension 签名身份，输出 v0.1.7 的 ailx、SHA-256 和 source.json。来源记录绑定包摘要、完整 Git 提交和源码树，便于安装前核对；基座、Host 原语和其他组件本次不修改。

v0.1.6 构建在 setup-android 默认请求已移除的 tools 包时失败，尚未开始编译。v0.1.7 显式指定 packages: platform-tools，随后安装声明的 Android 平台与 build-tools；版本号与载荷 applicationId 同步递增，子插件 ID 与签名身份保持一致。

## 开发版 0.1.8：同一次结果中的图片块

通用业务结果可携带 mcp_content=[{type:"image",mimeType:"image/jpeg",data:"Base64"}]，接收端从 JSON 对象中提取图块并移除 events，将文本继续按原协议分页，图片只在首次 response 的 mcp_content 返回一次。续读不会重发图片。需要把 image 块直接呈现给模型，不要打印 Base64，也不要重新执行业务操作来获取图片。

此适配不修改 SentinelX 闭源云端的 exec 包装；客户端必须消费图片块。目前不可宣称 exec 自动成为原生图片工具。媒体不超过 96 KiB，超过时返回明确 media_delivery 错误而保留业务文字结果，避免云端 128 KiB 边界裁坏编码。能力、权限、子插件 ID、签名和文本分页协议不变。尚未推送编译或安装。


在支持代码编排的调用端，同一次 sentinel_exec 结果可以直接呈现图片，例如：

```javascript
const result = await tools.mcp__codex_apps__sentinelx_sentinel_exec(request);
for (const block of result.structuredContent.mcp_content || []) {
    if (block.type === "image") image(block);
}
// 原业务 JSON 仍在 bridge_result/output；按原分页协议续读文字。
```

图片呈现不再发起画室回读请求，也不依赖屏幕共享。没有图块时保留原工具结果，媒体错误按 media_delivery 明确报告。
