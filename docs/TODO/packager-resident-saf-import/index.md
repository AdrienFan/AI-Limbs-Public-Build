---
status: in-progress
repository: https://github.com/AdrienFan/AI-Limbs-Public-Build
branch: fix/packager-resident-signing-v042
---

# 常驻模式下打包中心文件导入

旧路径由系统文件选择器返回 SAF URI，随后独立的 `app_process` 插件业务进程直接访问 Downloads 文档 Provider。这个进程并非 Android 注册的应用进程，Provider 请求被系统拒绝，APK 在加入队列之前失败；签名仓检查不经过该路径。

本次仅对打包插件的选择器动作声明 `file_transport`。总控台在有 Activity 的应用进程读取文件，将 APK、Manifest 暂存至其归属目录；私钥通过内存消息交接，不写明文暂存文件。旧插件的未声明动作保持原有 URI 协议。

作用域：总控台动态面板和 Packager 源文件读取，不修改基座的常驻进程启动架构。验收须包含 APK 多选、目录扫描、Manifest 模板、私钥导入、队列清理，以及非打包插件原有 URI 选择行为。

云编译与装机验证待同批其他 bug 确认后进行。
