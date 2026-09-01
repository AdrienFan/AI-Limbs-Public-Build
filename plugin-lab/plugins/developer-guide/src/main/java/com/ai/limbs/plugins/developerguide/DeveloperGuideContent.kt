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
            title = "1. AI Limbs 插件机制",
            description = "Stable Kernel + Plugin Center + 可撤销插件贡献。",
            lines = listOf(
                "核心原则：业务能力默认做成插件；基座只承载通用 Contract、安全与稳定性基础设施。",
                "Kernel 负责生命周期、信任、权限、Host Surface Policy、Extension Router、贡献注册、隔离与回滚；插件不得改写这些 Kernel Invariant。",
                "顶层插件使用 .ailp；父插件拥有的子扩展使用 .ailx。安装、授权、启用、ACTIVE 是不同状态，不得混为一谈。",
                "插件 mount 时通过受控 Host Contract 注册 capability / provider / extension；disable、uninstall 或策略阻断时必须 revoke。",
                "Host Surface 被管理员关闭后，依赖该 Surface 的已启用插件应转为 BLOCKED 并撤销运行；重新开放后自动尝试恢复。",
                "热插拔的本质是 Kernel 管理可注册、可撤销、可停止的运行参与，不是要求代码从不进入内存。",
                "android_inprocess 只用于批准的高信任系统插件；普通第三方插件不应获得任意宿主内部类、Activity、Repository 或 Kernel 对象。"
            )
        ),
        GuideSection(
            providerId = "plugin.developer_guide.ailp",
            title = "2. 顶层插件 .ailp 开发规范",
            description = "AIL_PLUGIN_V1：统一安装包、声明式权限、运行时与贡献清单。",
            lines = listOf(
                "根清单必须是 plugin.json，format=AIL_PLUGIN_V1，schema_version=1；plugin_id 使用稳定命名空间，version 使用 SemVer。",
                "display.name 与 display.description 必填；api.target / api.min 明确宿主插件 API 兼容范围。",
                "runtime.kind 当前包含 none、declarative、android_inprocess；android_inprocess 仅批准的系统插件可用，并且只能携带清单声明的 APK。",
                "provides.capabilities / services / providers / extensions 必须先在 manifest 声明，运行时注册不得超出声明。",
                "插件 capability 必须使用 plugin.* 命名空间；宿主 core.* capability 只能通过 invokeHostCapability 调用，不能抢占注册。",
                "permissions.requested_scopes 只申请真正需要的 Host scope；未授权 scope 不得在运行时绕过。",
                "扩展点必须写 point、id、api；宿主 Extension Router 只接受已登记且 API 匹配的 Contract。",
                "包结构原则：plugin.json + payload；安装经过容器校验、Manifest/ABI/语义、Trust、权限与 Runtime Preflight 后才可 INSTALLABLE。",
                "安装≠备份：安装完成后 Plugin Store 运行区只保留 content/ + install.json，不长期保留原始 package.ailp；只有用户或自动策略触发备份时，才从已安装 content/ 重新打包 .ailp。",
                "备份是独立可删除资产；当前版本已存在备份时不重复生成，插件升级后新版本可重新备份。恢复必须先校验备份完整性，再重新走正常 Plugin Center install pipeline。"
            )
        ),
        GuideSection(
            providerId = "plugin.developer_guide.ailx",
            title = "3. 子插件 .ailx 开发规范",
            description = "AIL_EXTENSION_V1：只能挂到一个父插件拥有的 Extension Point。",
            lines = listOf(
                "根清单必须是 extension.json，format=AIL_EXTENSION_V1，schema_version=1，extension_id/version/display 必填。",
                "target.plugin_id 指向唯一父插件；target.extension_point 指向父插件发布的点；target.api 必须与父插件当前 API 完全匹配。",
                "当前子插件 runtime.kind=android_child，entry 必须是唯一 APK；入口类实现 ChildExtensionEntry。",
                "ChildExtensionHost 只提供 extensionId/version/target/scope/dataDir/cacheDir、单次 publish() 与受控 invokeHostCapability()。",
                "子插件 mount 后必须 publish 一个且仅一个 binding；没有 publish、重复 publish 或 payload 类型错误都会失败。",
                "permissions.host_capabilities 必须先由父插件 Extension Point 明确 delegated；子插件不能越权直接向宿主索要额外 core.* 能力。",
                "父插件通过 ExtensionHubService.publishPoint() 发布 point/api/allowedHostCapabilities/binder；父插件停用后子插件进入 BLOCKED。",
                "API 不匹配时必须 BLOCKED，而不是勉强加载；破坏性 payload/Contract 变化必须提升 target.api。",
                "Bridge 当前范例：ai_limbs.bridge.provider@3，子插件发布 BridgeProviderContribution(factory + panel + optional notification)，Provider 专属 UI 由子插件负责。",
                "子插件同样遵守安装≠备份：安装只保留解压后的扩展内容，不长期保存原始 .ailx；备份时由 Extension Hub 从已安装内容重新打包 package.ailx。",
                "父插件被卸载或 Extension Point 暂时不可用时，子插件备份仍应保留；恢复时重新校验父插件 ID、Extension Point、API 与备份 SHA，不能静默跨 Contract 恢复。"
            )
        ),
        GuideSection(
            providerId = "plugin.developer_guide.ui",
            title = "4. UI / Provider 设计规范",
            description = "父插件提供容器；具体 Provider/子插件拥有自己的业务控制面板。",
            lines = listOf(
                "不要在父插件中用 if(RDC)/if(TRIGGERcmd) 写死子插件特殊按钮；父插件只能依赖版本化 Contribution Contract。",
                "需要动态控制面板时使用 InProcessDynamicPanelProvider + InProcessPanelState；字段、动作和状态由 Provider 自己描述。",
                "密码输入使用 SECRET field；动作可声明 requiredFieldIds，宿主负责在必填字段为空时禁用按钮。",
                "Provider 选择状态可通过 InProcessSelectionProvider 同步，避免下拉框显示与真实 Manager 当前 Provider 不一致。",
                "通知栏遵守内容与渲染分离：子 Provider 通过 BridgeProviderNotification 声明状态、actionId、label、priority；Bridge 只转发当前 Provider，禁止写死 RDC/TRIGGERcmd 分支。",
                "真正 Android 通知由 ai_limbs.notification.surface@1 统一渲染；插件不得创建 PendingIntent/NotificationManager。通知 Host 通过既有 providers.resolve(system.notification.host) 获取，不向 InProcessPluginHost 新增抽象方法，避免破坏旧插件 ABI。",
                "所有可能增长的插件集合栏统一采用：折叠 + 数量 + 搜索；输入搜索词时可自动展开。",
                "长页面统一纵向滚动，并在右侧显示滚动条；开发说明本身也遵循这一规则。",
                "当前 Provider 控制区应放在已安装子插件列表之前，避免列表过长时把日常操作挤到页面底部。"
            )
        ),
        GuideSection(
            providerId = "plugin.developer_guide.security",
            title = "5. 安全边界",
            description = "插件可扩展 AI Limbs，但不能改写 Kernel 的安全与治理边界。",
            lines = listOf(
                "Kernel Invariant：Trust/签名、管理员安全、Host Surface Policy、Extension Router、贡献注册约束、生命周期、崩溃隔离、回滚核心。",
                "禁止把 PluginCenter 内部 Repository、DAO、Policy Engine、Trust verifier、Secret store、SharedPreferences 或 registry mutation 当成插件 API。",
                "Host Capability、Host Provider、Extension Point、Provider/Service/Capability Bus 都必须是显式、版本化、可撤销 Contract。",
                "系统插件属于高权限组件，禁用/卸载必须走管理员验证；普通插件验证频率设置不得豁免系统插件。",
                "android_inprocess 当前会给批准的系统插件 applicationContext，但这不是面向第三方的通用授权，不应据此扩散裸 Context 依赖。",
                "敏感 Secret 必须经批准的 secret broker / scope 流程提供，不得把 API key、恢复密钥或私有签名材料写入插件包。",
                "开发说明插件自身只读：不修改 Host Surface、不写配置、不执行 shell；它只读取受控 Host Surface snapshot。"
            )
        ),
        GuideSection(
            providerId = "plugin.developer_guide.maintenance",
            title = "6. 升级与维护交接",
            description = "用于新窗口、上下文丢失或后续维护者快速恢复正确开发方式。",
            lines = listOf(
                "判断原则：业务变化默认只升级 .ailp/.ailx；只有现有 Contract 无法表达通用能力，或 Kernel 安全/稳定/Android 兼容需要修复时才升级基座。",
                "新增 Host Surface 时必须在 HostSurfacePolicy 注册 title/detail/kind/publicContracts；管理员开发模式与本说明的动态接口章节会读取同一数据源。",
                "Contract payload 或语义发生破坏性变化时必须提升 API；不要让旧插件看似兼容后在运行时崩溃。",
                "先在 Plugin Lab 用真实安装包验证 install → enable → invoke/open → disable → re-enable → uninstall → reinstall → restart restore，再迁正式 AI Limbs。",
                "稳定基线 V0.6.4.7.8 只读保护：dev/v0.6.4.7.8 与 baseline/v0.6.4.7.8-pre-plugin-center 不得覆盖或直接开发。",
                "当前实验主线：dev/plugin-lab-alt-v0.1；实验基座用于逼出并验证稳定 Contract，不把具体业务逻辑塞回基座。",
                "构建优先 GitHub 云端；普通开发不依赖本地 Gradle。修改前先静态检查、git diff --check、Manifest/API 对账，再提交推送。",
                "如果未来打开新窗口，先读本说明，再读管理员开发模式 Host Surface；不要凭记忆猜接口，也不要为了一个插件需求直接暴露内部类。"
            )
        )
    )
}
