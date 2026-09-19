---
fork: https://github.com/AdrienFan/AI-Limbs-Public-Build
branch: fix/resident-overnight-recovery-build58
status: source-reviewed-awaiting-device-validation
---

# build58：隔夜 owner 丢失后的恢复与取证

## 现场事实

设备：SM-F9460，Android 16，基座 0.8.0.5-build57 / code130，stable 包。以下时间均为手机北京时间 2026-09-20。

- Guardian 最后一条心跳为 00:10:30.405，PID 18956，Host PID 19090；当时记录 Host 存活，未请求 Core 恢复。此后无心跳不能单独区分进程退出、系统冻结或阻塞。
- 01:11:28.523，系统 crash buffer 记录 system_server 的 android.ui 线程发生三星 device care silent reset，随后其他系统应用报告 DeadSystemException。此事件晚于最后心跳，不能把它说成 00:10 心跳中断的已证实原因。
- Android exit-info 记录 Host 01:12:39 因 LOW_MEMORY 退出，02:16:52 因 TOO MANY EMPTY PROCS 退出。独立 app_process 的死亡原因不一定进入此列表。
- 05:32:19 Host 冷启动，05:32:42 收到 SIGKILL，05:32:48 再次以 LEGACY_HOST 启动；之后 Resident desired=false、takeover fence 已清除。这与已有显式 OFF 修复路径一致，但缺少此前 Host 日志，不能仅凭 SIGKILL 判定为未捕获异常。
- 旧 last_crash.txt 时间是 09-18，不是本次异常堆栈。

## Android Shell 断开

05:34:31.968 权限服务成功连接 uid=2000，05:34:38.280 Binder 死亡；系统同一时刻 05:34:38.226 记录 Disabling ADBd Wifi property，随后 adbd socket 断开。

重新启动后的权限服务 PID 17741 虽然 PPID=1，cgroup v2 仍为 /system/uid_0/pid_17494，17494 正是当前 adbd。setsid 只分离会话，不改变该控制组归属。当前 shell 身份不具备写系统 cgroup 根节点的权限。不能宣称该启动模式可以在关闭无线调试、adbd 退出或系统重置后继续存活，也不能通过偷偷重开无线调试改变用户设置。本轮不修改权限插件、不修改手机系统设置。

## 修正范围

1. Host 冷启动或刷新到 UI_PROXY_BLOCKED 时，主动请求已有 Core owner-loss 恢复；不再只等 Guardian 发出恢复 action。
2. 前台服务 watchdog 独立请求 Core 检查，再检查 Guardian；请求去重，仍由 lifecycleMutex 串行执行。Core 必须连续不可达且 takeover session/PID 未变；正常 Core、未开启 Resident、非 owned fence 均不触发回收。
3. 恢复继续使用现有 stopLocked 所有权核验、lease 释放和冷启动事务，保留 desired ON。UI_PROXY 不原地启动业务，Bridge/Dispatcher/插件归属不变。权限后端已经被系统终止时，仍须重新激活，不能伪造后端可用。
4. Guardian 对 Android host action 使用 10 秒有界等待，输出重定向到文件而非先阻塞读管道 EOF；超时终止本次子进程，记录错误并继续心跳。开始调用前记录 touching_host 以区分阻塞位置。
5. 阻塞 Host 在清理启动日志之前、OFF 清理 fence 之前保存事件记录。记录含上一轮日志尾部、Guardian 状态/心跳、Core 启动日志与 Android 进程退出原因。Core/Guardian 的未捕获异常和 Core 主循环异常独立持久化，不依赖 Host 日志。
6. 每份事件文件最多当前及上一份；各日志尾部上限 32 KiB，同一 Core session 的重复恢复保留第一次现场。诊断不读取权限令牌或 policy handoff 内容，也不参与所有权判定。

## 验证与边界

已对调用链、UI_PROXY 角色来源、OFF lease 核验、并发请求去重、超时清理和版本差异做源码审查，并运行 git diff --check。

用户要求送云编译，因此不执行手机本地全量构建。候选为 0.8.0.5-build58 / code131，沿用 stable 包名和更新签名。云任务触发状态由交付时单独说明。

待安装验收：Core/Guardian 同时消失后重新打开 APP、单独 Core 退出但 Guardian 存活、正常长锁屏以及 Resident OFF；应验证插件仍可操作、无双业务 owner、desired ON 保留、诊断文件完整。系统重置和无线 ADB 关闭本身不能由此补丁阻止；00:10 心跳停止的首因仍未被现有日志证明。
