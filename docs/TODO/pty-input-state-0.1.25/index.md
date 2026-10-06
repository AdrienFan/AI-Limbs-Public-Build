---
fork: https://github.com/AdrienFan/AI-Limbs-Public-Build
status: source-complete-cloud-validation-pending
---

# Ubuntu 0.1.25 PTY 等待输入状态修复

## 根因

0.1.24 的 PtyMode 将 PTY 没有未读输出解释为等待输入。休眠、计算和输出间歇因此被误判。OutputProcessor 只在部分无换行输出时更新交互状态；无提示文本的 input() 则可能一直显示未等待。process interact 还用输出末尾的 >、$、# 提前结束等待。

真机独立诊断确认：休眠阻塞在 nanosleep 系统调用，实际 input() 阻塞在 read，输入 fd 指向本 PTY。正常 UTF-8 流式输出和全局索引复用已通过此前验收，本次修改只针对等待输入状态。

## 修改

Ubuntu 原生 PTY 提供子进程 PID、前台进程组、slave 终端路径及当前 ABI 的 read/readv 系统调用编号。探测器只遍历这条 PTY 创建的进程树和线程，并要求前台组、同一会话、睡眠状态、实际终端 fd 的正长度读取同时成立。读取 syscall 后再次核对 syscall 和进程状态，避免把已经结束的 read 用于后续状态。

无换行内容独立更新行预览，保留 0.1.24 的流式解码和替换当前预览行逻辑。活动命令每 250 ms 观察一次，查询接口也重新采样；结束、关闭和释放会话时取消观察器。命令已替换时不写回旧结果。

process read_structured 保留 waiting_for_input，增加 input_state：WAITING、NOT_WAITING、UNKNOWN。无法读取进程信息和远程 SSH 没有本地可证明状态时返回 UNKNOWN，不推测等待输入。布尔值只有 WAITING 才为 true。interact 移除基于输出标点的等待判断，按实际进程等待或完成状态返回。

探测范围是本地前台线程直接阻塞在 read/readv 的终端读取。poll/select 等事件循环不在本次等待检测覆盖范围。外部操作参数和既有布尔字段保持兼容，基座及其他桥无改动。

## 版本与验证

Ubuntu 子插件 0.1.25、versionCode 26，payload applicationId 使用 v0125。

新增 15 个 JVM 用例覆盖休眠、计算、TTY read/readv、管道和其他终端、后台或无关进程、管道成员、线程、不可读状态、停止任务、零长度读取、会话归属以及输入转休眠时清除旧状态。包含带空格和括号的进程名称解析。原 UTF-8 与行预览测试继续由云端执行。

[DONE] 源码修改和静态审阅完成。

[TODO] 云端 terminal-core、Ubuntu 子插件单元测试及编译签名；部署后验证休眠时 false、input() 时 true、回复后状态解除，以及中文 emoji 连续输出无额外换行。
