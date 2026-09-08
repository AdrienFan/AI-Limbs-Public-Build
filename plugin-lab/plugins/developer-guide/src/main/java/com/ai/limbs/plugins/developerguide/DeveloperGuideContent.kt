package com.ai.limbs.plugins.developerguide

internal data class GuideSection(
    val providerId: String,
    val title: String,
    val description: String,
    val lines: List<String>
)

internal object DeveloperGuideContent {
    val sections = listOf(
        GuideSection(
            providerId = "plugin.developer_guide.mechanism",
            title = "1. 当前插件架构与选型",
            description = "AI Limbs V0.8：Stable Kernel + Host Gateway + 统一入口 + 可替换能力 Provider。",
            lines = listOf(
                "AI Limbs 本体只保留稳定 Kernel、Host Gateway、Runtime、Policy、Dispatcher、基础服务与恢复能力；可升级业务能力优先外置为插件。",
                "Host Gateway V1 是稳定总线入口：通过 list / describe / operations / availability / invoke 访问版本化 Host Primitive；V0.8 当前 46 条 Primitive 均可发现，BOUND / KERNEL_GATE 必须真实可执行，DECLARED / PARTIAL 可以诚实返回不可用而不扩张 ABI。",
                "开发前先选包类型：普通顶层能力使用 .ailp；宿主级系统控制面使用 .ailpsys；父插件专属二级扩展使用 .ailx。三者不是同一种权限等级。",
                ".ailp 由 Plugin Platform 管理，强调声明、权限、可撤销贡献和可停止 Runtime；普通第三方插件不得依赖宿主内部实现类。",
                ".ailpsys 使用独立 System Plugin Protocol 与 System Host ABI，面向 Plugin Center、恢复、Host Adapter 等高信任系统角色。",
                ".ailx 不是独立顶层插件，只能挂到某个父插件发布的版本化 Extension Point，由 Plugin Extension Hub 管理。",
                "安装、授权、启用、mount、ACTIVE 是不同阶段；任何阶段失败都必须可回滚并撤销已注册贡献。",
                "业务需求能由现有 Contract 表达时只升级插件；只有缺少通用 Host Primitive、Runtime 或 Kernel 安全/兼容能力时才修改 AI Limbs 本体。",
                "V0.8 把连接层与能力层严格分开：RDC、TriggerCMD、Bridge、HTTP/API 与未来入口只负责把请求送入统一 Dispatcher/Policy/Capability Resolver；Ubuntu、Android Native 与其他插件是可替换能力 Provider，任何入口都不得拥有或硬绑定某个 Provider。",
                "System Environment 使用稳定别名 plugin.system_environment.* 解析当前 Provider；Ubuntu 可以实现这些别名，但 Ubuntu 插件不得知道调用来自 RDC/TriggerCMD/API，RDC 也不得 import 或实例化 Ubuntu 实现。验收不变量：删除 RDC 后 Ubuntu 仍可由其他入口调用；卸载 Ubuntu 后 RDC 仍能连接并调用其他能力。"
            )
        ),
        GuideSection(
            providerId = "plugin.developer_guide.ailp",
            title = "2. 普通顶层插件 .ailp",
            description = "AIL_PLUGIN_V1：默认开发入口，按声明获得能力并发布可撤销贡献。",
            lines = listOf(
                "根清单必须是 plugin.json，format=AIL_PLUGIN_V1，schema_version=1；plugin_id 必须稳定，version 使用 SemVer。",
                "display.name 与 display.description 必填；api.min / api.target 描述宿主插件 API 兼容范围。",
                "正式 .ailp 发布必须声明 integrity 与 signature：V1 integrity 使用 SHA-256，signature 使用 Ed25519；signer_id 必须存在于当前 Trust Keyring 且具备 parent_plugin purpose。",
                "integrity.entries 必须精确覆盖除 plugin.json 与 signature.entry 外的全部 payload，runtime.entry 必须受 integrity 保护；signature.entry 由 manifest 声明为安全相对路径，当前官方 Packager 固定使用 META-INF/AILIMBS.SIG。Ed25519 detached signature 验证的对象是包内最终 plugin.json 的原始字节。",
                "UNSIGNED、UNKNOWN_SIGNER、摘要不匹配或 Ed25519 验签失败都不能作为正式发布安装；Development Preview 只能用于明确的开发测试，不能替代正式信任链。",
                "runtime.kind 只能使用当前宿主已经注册的 Runtime。V0.8 当前主线注册 none、declarative，以及受限 android_inprocess。",
                "android_inprocess 不是普通第三方 Runtime。V0.8 当前批准的官方身份包括 plugin.system.extension_hub、plugin.system.bridge、plugin.system.developer_guide、plugin.system.packager、plugin.system.ubuntu_terminal，并且必须匹配 OfficialPluginIdentityRegistry 中对应系统角色与官方 signer。",
                "provides.capabilities / services / providers / extensions 必须先在 manifest 声明；运行时注册不得超出声明。",
                "InProcessCapabilitySpec 的富元数据属于正式运行时契约：id、displayName、description、invokeAliases、keywords、parameters(name/type/description/required/default)、suggestedParamsJson、inputSchema、effect、domain、workContextRequiredReceipts 与 executor 必须由 Provider 如实发布；旧 registerCapability(id,displayName,description,executor) 仅作为向后兼容入口保留。",
                "具体插件在 manifest 中声明自己的 canonical plugin.* capability；通用角色可通过 invokeAliases 暴露稳定别名，例如 plugin.system_environment.process / filesystem。别名用于 Provider 解析，不代表 Transport 与具体插件绑定。",
                "插件可执行 capability 必须位于 plugin.* 命名空间；宿主能力使用版本化 host.*@N Primitive，通过 Host Gateway 调用，插件不得抢占宿主命名空间。",
                "permissions.requested_scopes 只能申请当前 Kernel 标记为 BOUND 且 requestable 的 Host Primitive；目录中存在但尚未绑定的 Primitive 不能当作可用 API。",
                "当前普通插件 Host capability 绑定以实际 Kernel 为准；例如 host.logging@1 已接入正式调用链，其他 Primitive 必须先确认 exposure 与 runtime adapter。",
                "host.network@1 的 listeners 操作只返回 Host 侧当前 TCP LISTEN 端口快照；Host 内部可以使用受控系统能力采集，但插件不会因此获得 Shell、/proc 或任意命令执行权限。",
                "扩展声明必须包含 point、id、api；当前正式 UI 扩展点包括 ai_limbs.ui.home_tile、ai_limbs.ui.screen、ai_limbs.ui.theme，其中页面契约使用 ai_limbs.ui.screen@2。",
                "安装包只用于安装输入。安装完成后运行区保留已安装 content 与 metadata，不把原始 .ailp 永久复制为备份。"
            )
        ),
        GuideSection(
            providerId = "plugin.developer_guide.ailpsys",
            title = "3. 系统插件 .ailpsys",
            description = "AIL_SYSTEM_PLUGIN_V1：高信任、独立签名、版本化 System Host ABI。",
            lines = listOf(
                "根清单必须是 system-plugin.json，format=AIL_SYSTEM_PLUGIN_V1，schema_version=1，包扩展名为 .ailpsys。",
                "必须声明 plugin_id、version、display、system.role、host_abi.min/max、runtime、requested_scopes、signature 与完整 payload integrity map。",
                "V1 签名算法固定为 Ed25519；detached signature 验证的对象是包内最终 system-plugin.json 的原始字节，signer_id 必须在当前 Trust Keyring 中同时具备 system_plugin purpose，并被授权承担 manifest 声明的 system.role。",
                "integrity 使用 SHA-256，entries 必须精确覆盖除 system-plugin.json 与 signature.entry 外的全部 payload，runtime.entry 必须受完整性保护；signature.entry 由 manifest 声明为安全相对路径，当前官方 Packager 固定使用 META-INF/AILIMBS.SIG。签名与 payload 完整性校验均属于安装前硬门槛，系统插件不得依赖开发模式绕过信任。",
                "Host 只固定 Root Trust 公钥，不把业务发布者公钥永久写死为不可变 ABI；Root Trust 对版本化 Trust Keyring 签名，Keyring 再按 purpose / role 授权 system_plugin、parent_plugin、child_extension 发布者。",
                "Trust Keyring 更新必须通过 Root Ed25519 签名验证；低版本回滚被拒绝，同版本但内容不同也被拒绝。发布者密钥轮换应通过更高版本 Keyring 完成，而不是为了换 signer 重编 AI Limbs。",
                "System Plugin Runtime 当前协议支持 declarative 与 android_inprocess；android_inprocess 的 APK 在 DexClassLoader 加载前必须冻结为只读。",
                "入口类通过 SystemPluginEntryV1 挂载；Host ABI 2 提供调用方感知的服务发布与委托调用权限，不得把 PluginManager、Context、SharedPreferences、Trust verifier 等内部对象当成 ABI。",
                "协议定义的 system role 包括 plugin_center、extension_hub、host_adapter、recovery、system_service；是否可实际安装仍取决于当前 Host 是否提供对应系统槽位。",
                "V0.8 当前 Bootstrap 安装槽正式服务于 system.role=plugin_center；其他 role 不应因为出现在协议枚举里就假定已经开放通用安装。",
                "Plugin Center 属于 .ailpsys，并通过独立维护生命周期执行本地升级、修复当前、回滚上一版；不得在旧 ClassLoader / UI Session 上热替换。"
            )
        ),
        GuideSection(
            providerId = "plugin.developer_guide.ailx",
            title = "4. 二级扩展 .ailx",
            description = "AIL_EXTENSION_V1：父插件拥有 Extension Point，Hub 负责子插件生命周期。",
            lines = listOf(
                "根清单必须是 extension.json，format=AIL_EXTENSION_V1，schema_version=1；extension_id、version、display 与 target 必填。",
                "target.plugin_id 必须指向唯一父插件，target.extension_point 必须是父插件已发布的点，target.api 必须与当前 Point API 匹配。",
                "当前子插件 Runtime 为 android_child，entry 是唯一声明的 APK，入口实现 ChildExtensionEntry；APK 在加载前必须保持只读。",
                "ChildExtensionHost 暴露 extensionId、version、target、scope、dataDir、cacheDir、单次业务 binding publish()、受控 Host capability 调用，以及受父级 Slot 约束的 publishUiContribution()；UI Contribution 不授予组件定义权。",
                "子插件 mount 后必须 publish 一个且仅一个 binding；未发布、重复发布、payload 类型不匹配都必须失败并撤销已创建资源。",
                "父插件通过 ExtensionHubService.publishPoint() 声明 point、api、allowedHostCapabilities 与 binder；父插件停用或 Point 消失时子插件必须 BLOCKED/停止。",
                "子插件能力必须同时通过 Plugin Center 策略、父 Point 当前 allowlist、子清单声明与父插件当前实际授权四层交集；.ailx 不能越过父插件扩大宿主权限。",
                "Bridge Provider 正式示例为 ai_limbs.bridge.provider@3；RDC、TRIGGERcmd 等 Provider 作为 .ailx 发布 BridgeProviderContribution，而不是重新塞回 AI Limbs 本体。",
                "AIL_EXTENSION_V1 强制声明 SHA-256 integrity 与 Ed25519 signature；integrity.entries 必须精确覆盖除 extension.json 与 signature.entry 外的全部 payload，runtime APK 必须被覆盖，signature.entry 不得进入 integrity map。",
                "Ed25519 detached signature 验证的对象是包内最终 extension.json 的原始字节；Plugin Extension Hub 将 signer_id、manifest 原始字节与签名交给 Plugin Center 委托网关，按固定 child_extension purpose 验签。signature.entry 由 manifest 声明，当前官方 Packager 固定使用 META-INF/AILIMBS.SIG；Hub 不获得裸 Trust Gateway、Keyring 或密钥材料。"
            )
        ),
        GuideSection(
            providerId = "plugin.developer_guide.ui",
            title = "5. Contract、UI 与导航",
            description = "插件只通过显式、版本化、可撤销 Contract 进入宿主。",
            lines = listOf(
                "四类主要插件贡献为 capability、service、provider、extension；每一项都必须有明确 owner，并在 disable / uninstall / mount failure 时撤销。",
                "Capability 用于可执行动作，插件 capability 使用 plugin.*；Service 用于版本化依赖/RPC；Provider 用于受控对象目录；Extension 用于 Typed Extension Point 绑定。",
                "不要通过反射、全局单例或直接访问宿主 Repository 绕过 Contribution Registry；能被插件依赖的能力必须先成为稳定 Contract。",
                "UI 入口使用 ai_limbs.ui.home_tile；页面使用 ai_limbs.ui.screen@2；主题使用 ai_limbs.ui.theme。扩展 ID 必须稳定，不能拿显示标题当身份。",
                "screen@2 的 Host 契约只保留 owner、screenId、title、description、schemaId 与 opaque documentJson；Stable Kernel 不解析 blocks，也不认识 Text、Button、Selector、DynamicPanel 等具体控件。",
                "页面所有权与共享控件必须分离：插件自己的 Text、Button、TextField、Tabs、Canvas、布局、文案和业务状态由插件自己实现；Plugin Center 只拥有它明确声明为 Shared UI 的可复用控件。不得把一整个插件业务页面包装成所谓通用控件。",
                "插件若需要改变当前页面呈现方式，使用 host.ui.presentation@1 请求 normal / fullscreen_portrait / fullscreen_landscape；只允许当前正在显示且 owner 匹配的 screen 发起。Host 只管理 AI Limbs 外壳、Android system bars 与屏幕方向，插件仍完全拥有自己的页面内容与全屏入口 UI。",
                "Plugin Center Component Registry 是私有控制面：schema 页面可使用已经发布的 renderer；插件自持页面可通过 InProcessPageProvider + plugin_page 挂载完整页面，并通过 InProcessSharedUiHost 选择性嵌入 Plugin Center 明确发布的 Shared UI。插件不能注册、覆盖或删除共享控件定义。",
                "父插件通过 component_slot 对某一个组件实例做非破坏性定制；V1 提供 before/after 两个实例 Slot。slots 只影响当前父插件页面实例，绝不回写 Plugin Center Component Definition。",
                "父插件只有在 child_slots.<slot>.points 中显式列出 Extension Point，子插件才可向该 Slot 贡献 UI；Extension Hub 用已验证 .ailx 身份绑定 extensionId、parent、point，并在停用、卸载、失败或停止时自动撤销贡献。",
                "子插件使用 InProcessUiContributionProvider 提供 contribution document 与本地 perform(eventId,payloadJson)；V1 子贡献只允许 text 与 event_button。event_button 回调子插件自己的 provider，不得借用父页面 capability_button 或父插件 capability 身份。",
                "需要声明式动态控制面的插件仍可通过 InProcessUiStateProvider 暴露 stateJson 与 perform(eventId,payloadJson)，由 dynamic_panel 等通用 renderer 解释；自持复杂页面则使用 InProcessPageProvider。两种模式都不得把具体业务控件加入 Stable Kernel ABI。",
                "UI 触发 capability 时必须使用 Host 绑定到当前 screen owner 的调用通道；插件文档不能自行指定 pluginId，也不能借 UI 调用其他插件拥有的 capability。",
                "动态一级导航页面使用永久 surfaceId（user.navigation.<UUID>）；页面标题可以改，surfaceId 不变，插件 UI 绑定因此不会随重命名失效。",
                "动态页面只绑定插件已发布的 UI contribution；非空页面禁止直接删除，必须先解绑/迁移其中贡献。",
                "Provider 特有控制面应由 Provider 自己描述业务状态，并通过通用 UI state/event 通道交给 Plugin Center 渲染；父插件不得写 if(RDC)/if(TRIGGERcmd) 之类具体 Provider UI 分支。",
                "通知内容与 Android 渲染分离；批准插件通过 host.notification@1 的受控 Notification Host 发布状态与动作，不直接持有 NotificationManager/PendingIntent 作为插件 ABI。",
                "需要真实前台占用语义的 Provider UI 必须把 attach/detach 作为显式生命周期事件，由 Plugin Center 在组件进入/离开 Composition 时配对调用；插件 mount 本身不得永久冒充前台 UI 客户端。用户点击等明确动作继续通过 owner-bound ui_action capability 进入 Policy。"
            )
        ),
        GuideSection(
            providerId = "plugin.developer_guide.security",
            title = "6. 安全与生命周期",
            description = "扩展能力不能突破 Kernel Invariant。",
            lines = listOf(
                "Kernel Invariant 包括 Trust/签名、管理员安全、Host Surface Policy、Extension Router、Capability/Provider/Service 注册约束、生命周期、回滚与恢复。",
                "普通插件不得获得裸 Android Context、PluginManager、Policy Engine、Trust verifier、SharedPreferences、私有文件路径或可直接 mutation 的 Registry。",
                "少数官方 android_inprocess 插件获得更高信任边界，可由 Host 创建绑定其 APK Resources/ClassLoader 的 UI Context 以承载自持页面；仍受固定 plugin_id + role 白名单、唯一 APK、只读 Dex 等约束，不能把该特例推广成第三方标准。",
                "android_inprocess 自持页面若携带独立 APK Resources，runtime APK 不得复用 AI Limbs 基座默认 0x7f 资源 package-id；当前官方动态 runtime 使用 0x80。AI Limbs Host 必须以插件独立 Resources 为底叠加 AI Limbs APK Resources，使父优先 ClassLoader 中的 AndroidX/Compose 资源引用与插件自身 R.* 能在同一 UI Context 中共存。",
                "Host Primitive 目录包含 CONFIRMED/CANDIDATE、DECLARED/PARTIAL/BOUND/KERNEL_GATE 等状态；只有明确 BOUND 且授权可请求的能力才能作为插件依赖。",
                "敏感凭据必须通过受控 Secret/Credential Broker 或插件自己的受保护存储策略获取；禁止把 API key、恢复密钥、签名私钥写入分发包。",
                "Root Trust 私钥与各发布者私钥不得进入 Git 仓库、GitHub Actions、APK、.ailpsys / .ailp / .ailx 分发包或日志；云端构建只产出 payload，最终发布签名在受控安全环境完成。",
                "Runtime stop 前先 revokeAll()；停止超时、崩溃或策略阻断不能留下仍可调用的 contribution。",
                "动态 Dex/APK 在加载前必须只读；任何恢复、回滚或旧版本重新 mount 都必须重新检查，而不是只在首次安装时检查。",
                "System Environment 的进程与文件系统必须是 transport-neutral Provider 能力：通用入口调用 plugin.system_environment.process / filesystem，当前 Ubuntu Provider 可提供 start/read/interact/list/terminate 与 Linux filesystem；Local/SSH/SFTP 选择、PTY、rootfs、挂载映射都属于插件私有实现。",
                "Base 不得把 Linux 路径映射进插件私有 rootfs，也不得保存 Ubuntu lifecycle DTO 或 chassis 实现；需要归档等文件操作时通过 filesystem Provider，下层可在插件内部为 Local 模式使用流式 I/O，为远程 Provider 使用抽象文件接口。host.process@1 只承载真正的 Host/Android 进程语义，不能暗中充当 Ubuntu 进程入口。",
                "Android 对动态插件的 native 边界：插件可从自身只读 APK 提取并 System.load JNI，但需要作为独立进程 execve 的 ELF 不得从可写 code_cache/dataDir 执行。此类通用可执行载荷必须由版本化 Host Native Runtime 暴露，当前 v1 通过逻辑 ID 解析 Android 安装期 nativeLibraryDir 中的只读 executable；Host 不得因此恢复 Ubuntu lifecycle、rootfs 或 session 逻辑。"
            )
        ),
        GuideSection(
            providerId = "plugin.developer_guide.maintenance",
            title = "7. 版本、备份与发布",
            description = "版本变化必须可追踪，备份与安装严格分离。",
            lines = listOf(
                "版本使用 SemVer；Contract payload、调用语义或兼容边界发生破坏性变化时必须提升对应 API/ABI，而不是只改实现。",
                "安装≠备份：安装完成后不要把原始安装包再复制一份留在运行区；需要备份时从当前已安装内容重新打包。",
                "普通 .ailp 与 .ailx 都应把备份作为独立资产管理；恢复时重新校验 manifest、完整性、权限、依赖和当前 Contract，不做静默跨版本恢复。",
                "Plugin Center 自身只保留 Current Backup 与 Previous Backup 两个维护槽；修复使用 Current，不旋转；成功回滚后 Previous 被消费。",
                "Plugin Center 升级/修复/回滚由 Bootstrap 接管：停止旧 Runtime、撤销旧 UI、切换版本、health check，再重新创建新 Runtime/UI Session。",
                "当前官方 Packager 对其支持的单 APK .ailpsys/.ailp/.ailx 采用同一签名流水线：识别对应 manifest → 计算 runtime APK 的 SHA-256 → 生成最终 integrity/signature → 将最终 manifest 规范化为确定字节序列 → 分别使用 system_plugin/parent_plugin/child_extension signing profile 对这组字节执行 Ed25519 → 将完全相同的 manifest 字节、payload 与 META-INF/AILIMBS.SIG 封装入包 → 重新打开成品并复验 manifest 字节、payload SHA-256 与 detached signature。安装端始终对包内 manifest 的实际原始字节验签。",
                "发布者密钥轮换先发布更高版本、Root 签名的 Trust Keyring，再发布由新 signer 签名的插件；不要把私钥上传 CI，也不要通过修改 Host 硬编码新业务公钥来完成轮换。",
                "正式开发以当前 AI Limbs Host ABI、Manifest Parser、Host Primitive Catalog 与 Extension Point Registry 为事实来源；不要把历史分支、实验 App 或旧截图当成规范。",
                "上下文丢失或新维护者接手时，先通过 capability.search 搜索 Developer Guide / Recovery Handbook / 维护手册，再调用 plugin.developer_guide.handbook；需要节省上下文时用 plugin.developer_guide.section 分段读取。",
                "任何 Plugin Protocol、Host ABI、Runtime、安全边界或维护生命周期变化，都必须在同一发布批次同步更新 Developer Guide 并提升手册插件版本；禁止让恢复手册落后于实际宿主。",
                "提交前至少做 JSON/Kotlin 静态检查、manifest 与运行时 capability 集合级对账、git diff --check；还要双向扫描 Transport→具体 Provider 实现、Provider→RDC/TriggerCMD/API 等 Transport 依赖，并扫描旧 chassis、rootfs 路径与生命周期语义残留。构建优先使用 GitHub 云端，不因方便把本地实验依赖塞回基座。",
                "实机验收应覆盖 install → enable → mount/open/invoke → disable → re-enable → backup/restore → restart restore；有卸载能力的插件再覆盖 uninstall/reinstall。"
            )
        )
    )
}