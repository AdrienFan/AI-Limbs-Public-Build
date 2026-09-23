# Bridge 接收端版本与能力

本次修改从 `fae5b9d` 开始，该提交是 2026-09-22 Bridge 联合构建的源码。云端构建 `35709451915` 的 RDC 1.2.8 与 SentinelX 0.1.3 产物，和手机备份的两个安装包 SHA-256 完全一致。

两个子插件仍接入 `ai_limbs.bridge.provider@5`，由 `resident-bridge` 目标与 Bridge 1.3.11 一起编译和签名。新清单、APK 构建版本分别为 RDC 1.2.9、SentinelX 0.1.4；SentinelX 对 Hub 报告的本地 agent 版本同步为 0.1.4。先前安装包清单版本高于 APK 构建版本，容易造成版本判断混乱。

RDC 对 `edit_block` 的单次非空文本替换通过 Host 的 `edit_file` 能力派发，保留 Host 对权限及修改动作的审批。SentinelX 增加 `help` 元信息入口；它仍是 Android Bridge 接收端，不等同于上游 Python Agent 的全部操作。

安装前核对构建目标、Git 提交及 `.ailx` 清单的接口版本，不应使用同名旧分支构建产物覆盖手机当前版本。
