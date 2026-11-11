# AI Limbs 权限服务 v0.2.3

管理 AI Limbs 自身的 ADB/root 权限服务，并为当前 Android 用户中的兼容 App 提供 Shizuku API v13 接入、逐 UID 授权及 UserService。原有无线配对、root 启动、后端选择和内置无障碍入口继续保留。

## 运行要求

- 原有功能沿用 `host.privileged.runtime@1`；配对浮窗新增 `host.window.overlay@1`，插件中心需要批准所请求的 scope。系统还需要允许 AI Limbs“显示在其他应用上层”。
- 第三方接入需要支持 `rpc` 的基座及 v0.2.2 或更新插件。配对浮窗沿用 build122 已有的通用窗口接口；本次 v0.2.3 无需修改基座。独立服务端仍为 v0.2.2，已运行该服务端的设备不必为浮窗功能重新启动服务。
- 旧基座返回的状态没有 `backend_rpc=true` 时，页面明确显示需要新版基座，第三方授权管理不可用。
- 普通 App 必须集成 Shizuku API v13，并发布受 `INTERACT_ACROSS_USERS_FULL` 保护的 `${applicationId}.shizuku` Provider；仅给任意普通 App 设置权限不能替代客户端适配。
- 此版本不实现 API v12 旧附着协议、Rish、官方管理器包身份/自定义接口、跨 Android 用户或工作资料空间，以及 UserService 的 JDWP 调试启动选项。依赖这些功能的 App 不能据此认定兼容。

## 使用

1. 点“打开开发者选项”，插件通过宿主创建配对浮窗后跳转系统设置。首次缺少浮窗权限时，宿主打开系统授权页；允许后返回插件再次点击。
2. 在开发者选项中打开无线调试，选择“使用配对码配对设备”，保持系统配对窗口打开，在浮窗填写配对端口和六位码。成功后浮窗切到“2 启动”，填写无线调试主页的连接端口启动。浮窗顶部可拖动，标题栏可以收起或关闭；原页面也可操作同一份表单。
3. 在连接状态卡片右侧打开“授权管理”。窄屏时入口自动换行。
4. 开启“允许第三方 App 接入”。初次安装默认关闭，已有保存的设置会在新服务实例初始化时恢复。
5. 找到兼容 App，点击“连接”，目标 App 会自动打开；启动完成后服务才交付 Binder。在该 App 中发起 Shizuku 权限申请，然后回到管理页处理申请。
6. 可选择“允许并记住”“仅本次服务运行期间允许”“拒绝并记住”“撤销授权”。共享 UID 的所有包会明确显示，授权决定作用于整个 UID。
7. 关闭第三方接入或撤销授权时，会关闭对应 UID 的 App，终止本服务记录的远程进程和 UserService；已记录的长期决定按界面说明保留。

已运行的 Shizuku / Sui 可能占用客户端现有 Binder。先显式停止原服务，再连接本服务；本插件不会杀掉或替换官方管理器。客户端 Provider 返回成功只表示已处理交接；是否真正附着，以“客户端已连接”为准。

**当前接入采用手动连接。** App 进程退出、被系统回收或服务重启后，需要在此页重新连接。每次点击连接会明确启动该包名下已验证的 Launcher Activity，并等待系统确认完成；应用没有 Launcher 界面、启动失败或超时会明确报错。不进行周期性的全应用唤醒。申请最多等待 120 秒；过期返回拒绝，之后可以重新申请。服务端扫描范围明确为 AI Limbs 所在的 Android 用户。

配对端口与连接端口不同，端口必须在 1–65535 内；root 设备也可明确选择 root 启动。设备重启后需重新启动权限服务。无线配对支持 Android 11+；本插件配对原生库为 arm64-v8a。

## 授权和生命周期

- Binder 交付不等于授权。除 v13 附着及附着后的权限查询/申请外，第三方事务必须通过当前授权检查。
- 管理接口和 Resident ownership 接口仍仅接受已验证的 Host UID。服务 UID 子进程还必须凭随机 UserService token 认领真实 PID，之后才允许加载第三方代码。
- 授权身份包含 Android 用户/UID、共享 UID 的完整包名集合、当前签名证书摘要及首次安装时间。重装、签名或共享身份变化后旧授权不再生效。
- 长期决定保存在插件私有数据目录 `external_authorizations_v1.json`，通过 AtomicFile 提交；会话允许不持久化。服务端在每个实例中只接受一次初始化恢复。
- UserService 支持普通 Binder、Context 构造函数和无参构造函数（由既有 Shizuku UserService loader 实现）；记录子进程启动时间以避免 PID 重用误杀。
- 授权撤销、关闭外部接入及服务正常退出会清理本服务记录的特权进程。服务强制终止时，UserService bootstrap 的 Binder death 监听使子进程退出。
- 任意已经获准的 root/shell 代码仍具有实际系统能力；这里的清理对象是服务记录的任务，不能声称能追溯撤销其已完成的系统修改。

## 架构

插件拥有页面、配对密钥、授权文件和服务端 APK。服务端拥有客户端注册、兼容 Provider 交接、请求队列、授权检查、进程与 UserService 生命周期。Host 和 Resident 共用同一业务实现与 UI 状态协议。

基座只为既有 `host.privileged.runtime@1` 新增通用 `rpc` operation：校验所有者、使用已验证的后端 Binder、转发有界同步 JSON。基座不认识第三方 App 名单、授权模式或 Shizuku 客户端业务，插件也不取得原始 Binder。

私有服务端协议：

- transaction: `0x414950`
- descriptor: `ai_limbs.permission.clients.v1`
- operations: `snapshot / restore / enable / connect / decide`
- JSON 请求上限 65,536 UTF-16 单元；响应序列化上限 200,000 单元，并受 Host Parcel 大小限制。
- `decide` 必须携带当前 UI 快照的 UID、identity 和决定；身份变化会明确拒绝该次操作。

上游 `server-shared` 的局部扩展：远程进程创建 hook 供本服务追踪任务；UserService package 索引在记录移除时清理，并以快照迭代批量删除。

## 构建和交付

独立云任务仍为 `android-build.yml` 的 `target=permission-service`。任务产物标记已更新为 v0.2.3。插件 payload applicationId 为 `com.ai.limbs.payload.permission.v023`，服务 APK 为 `com.ai.limbs.permission.server.v022`；既有可信签名与摘要打包流程不变。

本轮未执行 Gradle 编译或测试；明确请求构建后使用上述云任务。完整功能仍需安装新产物后验证，重点包括：Host/Resident 的 RPC 路由、兼容 App 的授权回调、UserService 认领/返回、撤销清理、重启后身份恢复、官方 Shizuku 存在时的明确提示，以及宽窄屏布局。


## v0.2.1 页面挂载修复

日志确认：插件业务入口已经挂载，但 Resident 展示入口在创建 controller 时立即解析 UI-state Provider，缺少该 binding 就抛出异常，因而页面 Provider 不会注册。此后相同展示版本的失败缓存使页面持续不可用。

展示 controller 现在订阅统一 ProviderDirectory 的 observe 接口。页面注册不再依赖状态 Provider 已经存在；未发布时呈现同步状态并禁止操作，发布/替换时订阅当前状态，每个动作重新解析并核对当前 owner/id/type，避免沿用升级前的对象。这里没有新增 Host/Resident 分支、替代后端或绕过授权的路径。

增加三个回归用例：先创建页面状态控制器后发布 Provider；替换/移除 Provider 后的状态和动作路由；拒绝错误 owner。权限插件的云构建任务先运行这些 JVM 单元测试，再生成安装包。

若热升级后独立权限服务仍运行旧版协议，管理页明确提示先停止再按连接端口启动，不会把协议错误当作新接口可用。已安装 build122 基座可以继续使用，本次仅需更新权限插件并重新启动权限服务。用户已在 v0.2.1 确认页面恢复。


## v0.2.2 第三方连接启动顺序修复

真机日志显示 Canta 和 MT 管理器均在交接时返回 `Client Provider unavailable`，失败发生在授权审批前。ADB 对照中，两者未运行时 `content call ... getBinder` 无法获取 Provider，显式启动其 Activity 后可以访问；临时省电白名单没有改变未运行时的失败。这里记录可观察到的启动状态差异，不推断具体厂商内部拦截机制。

“连接”现在是明确的前台操作：核验目标 UID/签名及受保护 Provider，按当前用户查询 Launcher Activity，验证其包名/UID/exported/enabled，使用参数化 `cmd activity start-activity -W` 启动该组件。系统确认该包启动完成后，再次验证接入状态与身份，仅进行一次 Provider 获取和 Binder 交接。启动命令最长等待 30 秒，超时结束命令进程并报错；不同目标的启动/交接在后端队列中串行执行。同一 UID 连接未完成时禁止重复提交。

失败提示现在包含有界的异常原因；“已发送”仍不等于“已连接”或“已授权”。启动输出的单元用例覆盖该设备真实 HOT 启动成功、零退出码超时、错误目标包及平台启动错误；云任务运行这些服务端用例和既有页面状态用例。手机上的启动/Provider 对照已完成，新版插件的完整连接、授权回调和 UserService 仍待安装验证。


## v0.2.3 配对浮窗

浮窗通过 `host.window.overlay@1` 使用独立声明的 `plugin.system.permission_service.pairing_overlay` Provider。Host 管窗口权限、位置、触摸与输入焦点；插件提供 View/Compose 和操作语义。普通 Host 主入口及 Resident 展示入口分别通过原有统一 Page Provider 路径提供相同的展示内容。此分支编译 SDK 补入 build122 已存在的 `InProcessOverlayPageProvider` 接口声明，保持宿主既有 ABI。

点击入口先创建并展开窗口，成功后才跳转开发者选项；若系统需要浮窗权限，不会立即覆盖系统授权页面。展开时可以输入，收起为可拖动的按钮；小屏和横屏下内容区域滚动，收起和关闭按钮保留在顶部。系统可以限制应用浮窗的可见性，实际系统配对对话框及输入法共存仍需真机验证。

页面和浮窗复用 `PairingFormState` 与原有 `PermissionPageController`，不保存配对码到文件或 UI-state 快照。提交配对、启动服务或关闭浮窗时清除当前码；配对失败需重新填写，操作结果独立于服务轮询。操作使用插件运行时 scope，关闭浮窗不会取消已经提交的配对或启动。插件停用或展示入口卸载时清码并请求宿主移除窗口。

新增表单状态用例覆盖提交前清码、成功后步骤切换、失败不遗留配对码、关闭时在途操作继续、重复提交阻止与无效端口拒绝。用例已加入现有测试源码，尚未执行；本轮完成源码检查后再按明确的构建请求编译验证。
