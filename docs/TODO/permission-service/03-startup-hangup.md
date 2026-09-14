---
fork: https://github.com/AdrienFan/AI-Limbs-Public-Build
status: source_complete_device_validation_pending
---

# v0.1.1 后台启动与诊断

## 原状与现场证据

2026-09-14，基座 build21/code94，插件 0.1.0，源码提交 80825dc4e27536318e00b09935fa41623a430cb2。用户截图显示配对成功，约 16 秒后提示服务端未连接到基座。

现场复现使用已配对的本机 ADB TLS 端口，经插件正常 start 能力调用，同样超时。没有新的 PermissionServer Java 入口日志，server.log 为空，服务进程不存在。早先 librish.so、参数数目和错误 UID 的崩溃是历史诊断，不能作为这一次的错误原因。

使用同一 server.apk 的前台诊断以明确无效令牌启动：Java 入口执行，Provider 回答拒绝，服务正常退出。未修改 Host 令牌或绕过授权。

## 定位

现有 launchCommand 使用 setsid -d app_process ... &。父 shell 无需等待后台进程进入新会话即可退出。ADB legacy shell 在 raw 请求下仍使用 PTY，在终端关闭时发送 SIGHUP，因此把 setsid 放在后台子进程里尚有启动时序窗口。现场观察与此窗口一致；新版修复后的完整激活仍需安装验证。

上游依据：[ADB shell_service.cpp](https://android.googlesource.com/platform/packages/modules/adb/+/refs/heads/main/daemon/shell_service.cpp)，StartSubprocess 的 kNone/kRaw 转 PTY 行为。

## 修改

- 在父 shell 中执行 trap '' HUP，然后创建后台进程，子进程从出生起继承忽略挂断的设置。
- 使用 setsid 后的独立 shell 写 AIL_SERVER_SESSION_READY，再 exec app_process；不打印带启动令牌的命令。
- 服务端记录 Java 入口、宿主身份核验、Binder 初始化、Provider 请求和接收结果。
- 直接保留 stderr 文件描述符的单一 PrintStream，将启动异常与未捕获异常写入 server.log，供日志中心读取。
- 保留停止、令牌校验、插件权限范围及显式后端选择。
- 插件与服务 payload 版本提升 0.1.1/code2，插件 ID 与配对密钥存储保持一致。

## 核查与待验证

源码差异、版本与打包路径已静态核对。未在 Ubuntu 运行 Android Gradle。

云任务：android-build.yml / target=permission-service。构建成功后需安装新版验证：已配对直接启动、Host 接收后 Shell 执行、ADB 连接关闭后服务存活、显式停止、重复启动、root 路径和失败日志。当前不宣称这些新版真机验证完成。

[DONE] 源码修复与诊断记录；新版构建和真机验证状态独立跟踪。
