---
fork: https://github.com/AdrienFan/AI-Limbs-Public-Build
---

# Plugin Worker 请求等待与页面回执解锁

2026-10-03 的 build98 / 画室 0.2.74 实机记录显示：Worker 在 invoke_capability 内等待页面回执约 5000 ms，同一串行 accept 循环无法处理 status、snapshot_plugin 和 provider_ui_event。Core 的 3000 ms 健康查询先超时，使页面连接失效；随后能力等待超时，排队的状态响应写入已断开的客户端，出现 Broken pipe。已知会话请求又尝试旧端点，用 Connection refused 覆盖首次超时。

修复作用域仅为基座 Plugin Runtime transport，不增加插件身份分支，也不修改画室业务、Provider 协议或权限。已接受连接独立处理；健康查询独立执行，页面回执和贡献快照共享生命周期读锁。其他业务仍经过同一串行锁。挂载、停止和子插件生命周期先取得业务串行锁，再取得生命周期写锁，防止排队的停止操作堵住正在等待的页面回执。所有调度操作在锁内再次检查运行时停止状态。

已知 session 只访问该 session 的端点，保留首次请求的真实错误。无 session 的原有调用形式仍访问旧端点，不在异常后切换端点。

计划：

- 实现通用请求门控、并行接收处理和停止清理
- 增加等待回执、业务串行、生命周期互斥与停止拒绝的回归源码，并接入云端工作流
- 执行静态契约与差异检查；本地不运行编译或单元测试
- 等阿伟提供下一处 bug 后统一决定发布版本并推送云编译

当前基线为 a93840e968752f5166c04c128709c6db7f50b127。设备仍为 build98，修改尚未部署。


源码实施已完成：Main 使用独立客户端线程与请求门控，停止在锁内标记并唤醒 accept；Wire 保留单一会话端点的真实错误。5 个 JVM 并发回归用例已加入云端测试选择，覆盖回执继续处理、排队停用、业务串行、生命周期互斥和停止后的请求拒绝。原有操作处理器逐项静态核对一致，仅 stop 的布尔标记改为原子标记。

验证：runtime capability registry、canonical contribution contract、canonical Provider transport、presentation runtime ownership、Service/Extension/Child canonical transport、unknown plugin unified receiver 六项静态检查通过，git diff --check 通过。单元测试、Kotlin 编译、云端构建和安装后的实机验证均未执行。

交接：当前改动保存在上述基座 worktree，未提交、未推送。基座版本、applicationId 与画室源码均未更改；下一处 bug 处理完成后再统一安排发布，发布前须跑云端新增回归并实机复测 view.tool_options show ink 的页面回执与重连。


2026-10-03 17:40 阿伟授权推送云端编译：基座递增为 0.8.0.15-build99 / versionCode194，沿用已安装稳定包 com.ai.assistance.operit.ailimbs.stable 和更新签名。新增并发回归纳入云端测试选择；此次只提交、推送和云端构建，本地不运行编译或测试。
