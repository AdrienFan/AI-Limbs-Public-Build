# 01 文件交接与读取边界

旧实现：动态面板直接给业务 Provider 传 `content://`，Packager 在 Worker 中用 `ContentResolver` 读取。常驻模式无法解析文档 Provider。

新实现：`host_staged` 动作在应用进程内读取用户选择的文件，复制到 `cache/plugin-center-imports/<owner>/<URI 摘要>/<文件名>`；Worker 仅接受自己插件 ID 下的常规文件。APK 单文件上限 512 MiB，目录最多 200 个 APK、扫描最多 5000 项；移出队列或清空队列时移除暂存副本。输出仍去共享 Download 目录。

签名私钥使用 `host_inline`：应用进程最多读取 64 KiB，通过同 UID 的本地 Provider 事件传给 Worker，在导入加密签名仓后清零字节数组，不落明文暂存文件。Manifest 模板可经文件选择器暂存，现有手填共享存储路径仍可用。

兼容：未声明 `file_transport` 的现有动态面板继续收到原始 URI；已发布的 Packager 能继续用共享存储绝对路径。两个插件新版本应配套安装，总控台先于 Packager。

[DONE] 源码交接与作用域校验。
[TODO] 同批云编译、安装并验证真实 SAF Provider。
