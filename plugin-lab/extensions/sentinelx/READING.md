# SentinelX 完整结果读取 v0.1.7

v0.1.6 在 v0.1.5 源码上修正首次分页的并发边界；v0.1.7 在同一提交基础上修复云端 SDK 初始化，保留该修正。此前 store 保存结果并释放缓存锁后，调用 page 再查一次游标；并发请求挤掉该条目时，强制解包会报异常。现在首次响应直接从刚保存的不可变 SavedResult 生成，续读同样在获取 SavedResult 后独立生成页面。

结果格式与分页协议保持一致。超过 12000 UTF-8 字节的响应按 4000 UTF-16 字符一页返回，使用 ai_limbs.bridge.result_page 及 cursor、offset 续读；边界不拆开表情字符，SHA-256 用于核对拼接结果。缓存最多四份，每份不超过 4 MiB，十分钟过期。并发超过缓存容量时，已淘汰游标的后续读取仍明确报告 invalid_result_cursor。

新增回归用例覆盖十二线程并发保存、第一页内容与摘要对应、Unicode 全文拼接，以及游标淘汰、清空和非法偏移。测试接入单独的 SentinelX 云端构建。本地只检查源码、版本和差异，未编译或运行测试。

单独签名使用原有 Child Extension 签名身份，输出 v0.1.7 的 ailx、SHA-256 和 source.json。来源记录绑定包摘要、完整 Git 提交和源码树，便于安装前核对；基座、Host 原语和其他组件本次不修改。

v0.1.6 构建在 setup-android 默认请求已移除的 tools 包时失败，尚未开始编译。v0.1.7 显式指定 packages: platform-tools，随后安装声明的 Android 平台与 build-tools；版本号与载荷 applicationId 同步递增，子插件 ID 与签名身份保持一致。

## 开发版 0.1.8：图片附件与原生媒体通道

通用业务结果可携带 mcp_content=[{type:"image",mimeType:"image/jpeg",data:"Base64"}]。接收端提取图片，原业务 JSON 保持文字分页，Base64 不进入文字分页。成功结果的回复增加 media_attachments，内含虚拟路径、MIME、大小、SHA-256、期限与 delivery。

图片合计不超过 96 KiB，且整份控制回复不超过 120 KiB 时，回复同时携带 mcp_content 图片块，delivery=inline。超过这个文字边界的图片指定 delivery=binary，由原生 sentinel_read_media 读取同一份附件字节，避免云端裁坏大段编码。这里不重新生成缩略图，也不重新执行源工具。exec 的闭源云端仍按文字包装；调用方要呈现内联图片块或接收官方媒体结果，不能把文字编码当成已经看见图片。

官方媒体入口要求 file_export_init、file_export_chunk 和 file_export_complete。接收端按公开协议实现三步导出，图片以二进制 WebSocket 帧传输：16 字节 transfer_id、4 字节大端 chunk_index、最多 1 MiB 的图像字节。先发送二进制帧，再发送关联请求的 JSON 确认。完整顺序读完后返回 SHA-256，未完成则 sha256_complete=false。

只允许本接收端从已授权工具结果缓存的 /ai-limbs/media/ 虚拟句柄，不读取实际文件系统。PNG/JPEG 的 Base64、文件头和图片尺寸须有效，长边最多 8192、总像素最多 32 Mi；编码总量上限仍为 2 MiB。缓存合计 4 MiB，最多 16 张图，最多 4 个同时导出会话，10 分钟过期。活跃传输固定图片，过期句柄不允许新建传输；会话结束释放固定，断线或接收端停止清理全部图片。忙、过期、无效、越界或发送失败都给出明确错误码。无效图块、编码或附件保存失败另报 media_delivery.errors；部分成功时标记 partial，并保留源工具的业务结果及 operation_completed=true，不能重放已经完成的动作。

在支持代码编排的调用端，一次业务调用可以自动完成图片呈现：

```javascript
const result = await tools.mcp__codex_apps__sentinelx_sentinel_exec(request);
const data = result.structuredContent;
for (const block of data.mcp_content || []) {
    if (block.type === "image") image(block);
}
for (const attachment of data.media_attachments || []) {
    if (attachment.delivery === "binary") {
        const media = await tools.mcp__codex_apps__sentinelx_sentinel_read_media({
            host_id: request.host_id,
            path: attachment.path
        });
        for (const block of media.content || []) {
            if (block.type === "image") image(block);
        }
    }
}
// 原业务 JSON 位于 bridge_result/output，文字仍按原分页协议续读。
// 媒体错误只报告传输问题，禁止重新执行 request。
```

协议参考：

- [官方导出处理](https://github.com/pensados/sentinelx-cloud-core/blob/main/src/sentinelx_core/handlers/file_export.py)
- [官方发送顺序](https://github.com/pensados/sentinelx-cloud-core/blob/main/src/sentinelx_core/client.py)
- [官方二进制帧](https://github.com/pensados/sentinelx-cloud-protocol/blob/main/python/sentinelx_protocol/binary.py)

基座、Host 原语、权限、子插件 ID、签名和文字分页协议不变。本轮仍未推送编译或安装，原生媒体的云端端到端呈现待统一编译后验证。


## 开发版 0.1.9：原生 SentinelX 操作适配

0.1.9 不改变 AI Limbs Bridge 架构。SentinelX 仍只负责远程传输与协议翻译；所有文件、进程和服务动作都经 BridgeRemoteIngress 进入现有 Dispatcher / Policy Engine，子插件不维护第二套 capability allowlist，也不复制 Ubuntu 业务实现。

新增官方可见的 read / list / search / edit / script_run / service / restart。read/list/search/edit 映射到 AI Limbs Host 文件能力；Linux 搜索、一次性脚本、后台任务和 systemd 服务动作映射到现有 System Environment 能力。通用 AIL_SENTINEL_BRIDGE_V1 exec 保留为扩展能力入口，继续用于画室、Chat、插件能力以及未来动态 capability。

执行目标增加正式语义：接收端可识别扩展 payload 中的 target/environment（android、linux，ubuntu 作为 linux 别名）；标准 SentinelX MCP 工具当前没有 target 字段，因此绝对路径仍使用与 RDC 一致的命名空间回退，/root、/home、/etc、/usr、/var、/tmp 进入 Linux，其余默认 Android。这个回退是兼容层，不是把 Ubuntu 能力写死进 SentinelX。

错误会尽量映射成 not_found、target_not_running、capability_not_found、permission_denied、timeout、unsupported_op 等稳定错误码，并保留 Host execution_policy / next_action 详情。script_run background 使用现有 System Environment process 能力返回 pid/session_id，不另造一套交互终端；RDC 的持续交互 session 仍是 RDC 自身优势。

edit 覆盖 replace、regex、replace-block、append、prepend、write，并保留 count、multiline、dotall、interpret_escapes、dry_run、allow_no_change、create 的核心语义。sudo、validator、validator_preset、backup_dir、diff 目前明确返回 unsupported_option，禁止静默降级。script_run 同样对当前无法忠实映射的 sudo、cleanup=false、filename 和通知参数明确失败。

新增回归测试覆盖 Android/Linux 路由、显式 ubuntu target、glob、文本替换、UTF-8 截断和 capability 声明。版本同步升到 0.1.9 / versionCode 10 / applicationId v019。最终编译与签名仍走 sentinelx 专用 GitHub Actions，避免在设备本地编译。


## v0.1.10 实机回归修正

0.1.9 首次部署后，官方原生 op 已能进入 Linux/Ubuntu，但 AI Limbs Host/System Environment 成功响应会保留空字符串 error 字段。0.1.9 适配层把“存在 error 字段”误判成失败，导致 read/list/search/script_run 对实际成功结果返回 ok=false / error=null。0.1.10 改为只有非空、非 JSON null、非字符串 null 的 error 才视为失败，并新增回归测试。

## 开发版 0.1.11：部署回归收口

0.1.9 实机验证确认原生 read/list/edit 已真实进入 Android/Linux 文件系统，但 AI Limbs 成功结果中的 error=null 被旧判定误当失败；0.1.10 修复空 error 判定。继续回归时确认当前 Ubuntu 子环境没有 systemd（systemctl is-system-running=offline），因此 service/restart 无法忠实映射，0.1.11 不再向 SentinelX Hub 宣告这两个 op，避免“看起来支持、实际必失败”。

script_run 的 background=true 也改为保持上游语义：后台调度由 SentinelX Hub 的 job/notifications 负责，接收端本身仍等待 System Environment 命令完成并返回最终 stdout/returncode，不再内部二次启动后台 process。前台 timeout 上限 600 秒，Hub 后台 job 上限 3600 秒。

/tmp 等临时路径当前可以 read，但 AI Limbs Host 的持久写能力会拒绝把临时路径当 durable artifact；这属于现有 Host 写入策略，不在 SentinelX 里绕过。源码项目目录等持久路径的 edit 已在 0.1.9 实机验证实际写入成功。


### 0.1.10 实机继续回归

0.1.10 已确认 read/list/search/edit/script_run 在 Linux 与 Android 路径均可正常执行；源码目录的结构化 edit 已实际把 beta 改成 gamma。继续错误分支回归发现两处边界：AI Limbs execution_policy.reason_code=null 会被 JSONObject.optString 读成字符串 "null"，导致缺失文件错误码显示成 null；script_run 成功结果里的 JSON null 也会被上游呈现成字符串 "null"。0.1.11 改为统一 jsonTextOrNull，并在成功响应中直接省略空 error 字段。

同时确认 SentinelX Hub 的 background=true 已经负责 job/notifications；0.1.10 接收端再内部启动后台 process 会造成双重后台语义，旧测试 job 最终变成 orphaned。0.1.11 只复用同步 System Environment command，让 Hub 自己负责后台调度和通知。

当前 Ubuntu 子环境 systemctl 存在但 is-system-running=offline，并非 systemd init 环境，因此 service/restart 不能忠实工作。0.1.11 从 advertised native ops 中移除这两个能力，而不是继续宣告一个必失败的接口。
