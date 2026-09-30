# SentinelX 完整结果读取 v0.1.7

v0.1.6 在 v0.1.5 源码上修正首次分页的并发边界；v0.1.7 在同一提交基础上修复云端 SDK 初始化，保留该修正。此前 store 保存结果并释放缓存锁后，调用 page 再查一次游标；并发请求挤掉该条目时，强制解包会报异常。现在首次响应直接从刚保存的不可变 SavedResult 生成，续读同样在获取 SavedResult 后独立生成页面。

结果格式与分页协议保持一致。超过 12000 UTF-8 字节的响应按 4000 UTF-16 字符一页返回，使用 ai_limbs.bridge.result_page 及 cursor、offset 续读；边界不拆开表情字符，SHA-256 用于核对拼接结果。缓存最多四份，每份不超过 4 MiB，十分钟过期。并发超过缓存容量时，已淘汰游标的后续读取仍明确报告 invalid_result_cursor。

新增回归用例覆盖十二线程并发保存、第一页内容与摘要对应、Unicode 全文拼接，以及游标淘汰、清空和非法偏移。测试接入单独的 SentinelX 云端构建。本地只检查源码、版本和差异，未编译或运行测试。

单独签名使用原有 Child Extension 签名身份，输出 v0.1.7 的 ailx、SHA-256 和 source.json。来源记录绑定包摘要、完整 Git 提交和源码树，便于安装前核对；基座、Host 原语和其他组件本次不修改。

v0.1.6 构建在 setup-android 默认请求已移除的 tools 包时失败，尚未开始编译。v0.1.7 显式指定 packages: platform-tools，随后安装声明的 Android 平台与 build-tools；版本号与载荷 applicationId 同步递增，子插件 ID 与签名身份保持一致。
