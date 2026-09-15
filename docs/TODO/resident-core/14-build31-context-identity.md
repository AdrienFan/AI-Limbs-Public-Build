# build31：Resident Core 调用身份与跨域存活判断修复

## 现场与作用域

从 build30/code103、提交 d4c5e973d46502cf9bf2e493a625120037b0dce9 的开发工作树继续修复，目录仍名为 AI-Limbs-Base-build24，分支为 feat/resident-runtime-build26。工作树中已有的五个门禁语义修复文件保持原内容，并随基座源码保留。

真机 bootstrap.log 记录 Core 已以应用 UID 10649 启动，随后请求失败：Package android does not belong to 10649。shutdown.result.json 显示业务仍为 detached，退出与权限后端释放已确认。原日志没有完整堆栈，不能断言 WakeLock 与网络监听中具体哪一步首先抛错。

## 修复

- ResidentCoreContextBootstrap 使用同 UID 的 ApplicationInfo 与 LoadedApk，通过 ContextImpl.createAppContext 创建没有系统父 Context 的应用根 Context。禁止调用 makeApplication 或执行 OperitApplication，以免在 Core 中提前启动第二套 Host 业务。
- 启动前校验应用 UID、包名、opPackageName、资源包名；Android 12 及以上还校验 AttributionSource 的包名与 UID。验证底层 ContextImpl，不能只覆盖外层 ContextWrapper 的包名 getter。
- Core 状态增加 op_package_name、attribution_package_name、attribution_uid。它们是诊断字段，既有控制操作与业务 owner 判定不变。
- ResidentProcessLiveness 统一五处存活判断。ESRCH 表示进程不存在；EPERM/EACCES 表示无法通过信号确认退出，继续保留等待与所有权屏障；其他错误仍抛出。正 PID 校验防止进程组信号语义。业务所有权仍由 lease 和经过认证的 IPC 确立。
- Core 请求与业务异常在 bootstrap.log 保存完整堆栈和 cause；请求日志带 operation/request_id。持续资源申请错误附带 acquire_wake_lock、register_network_callback 或 read_active_network 阶段。IPC 仍只返回长度受限的摘要。
- 版本递增为 0.8.0.5-build31/code104，稳定包名与签名配置不变。

实现机制核对来源：[Android 16 ContextImpl](https://raw.githubusercontent.com/aosp-mirror/platform_frameworks_base/android16-release/core/java/android/app/ContextImpl.java) 与 [ActivityThread](https://raw.githubusercontent.com/aosp-mirror/platform_frameworks_base/android16-release/core/java/android/app/ActivityThread.java)。三星真机上的反射工厂和业务接管仍需安装验证。

## 总控台

已核对 Plugin Center 1.3.28/code32 工作树：Resident 使用 host.resident.runtime@1 的 status/set_enabled，读取 runtime_phase、enabled、last_error。此次基座修复不需要修改总控台 Resident 契约；其已有两个未提交门禁修改文件原样保留，本轮不重新构建总控台。

## 核查与验收边界

源码审查和 Git 差异检查已完成；核对原有门禁文件、ResidentCoreWire、AiLimbsResidentMain 的 SHA-256，保证没有覆盖已有修改。LocalSocket 仍先 connect 再设置 soTimeout，Guardian 仍持有 guardian lease；Host 退出并释放业务锁之后 Core 才接管。

本轮不执行本地编译或测试。按用户后续指令提交并推送此分支、发起 GitHub Android 云构建；云任务创建不代表编译成功，不监控进度。

安装后需验证 Core 启动身份、完整 ON 接管到 UI_PROXY、OFF 资源释放，以及真实 Bridge → Ubuntu 调用。长锁屏与持续工作能力仍未验收。

[DONE] 源码修复与静态审查；云构建结果及真机验收另行确认。
