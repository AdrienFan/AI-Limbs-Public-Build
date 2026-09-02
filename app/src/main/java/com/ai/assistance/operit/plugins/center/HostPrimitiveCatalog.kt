package com.ai.assistance.operit.plugins.center

enum class HostPrimitiveMaturity { CONFIRMED, CANDIDATE, TARGET_CONFIRMED }

enum class HostPrimitiveExposure { DECLARED, PARTIAL, BOUND, KERNEL_GATE }

data class HostPrimitiveDefinition(
    val number: Int,
    val id: String,
    val title: String,
    val description: String,
    val boundary: String,
    val maturity: HostPrimitiveMaturity,
    val exposure: HostPrimitiveExposure,
    val requestableScope: Boolean
)

data class HostPrimitiveSnapshot(
    val definition: HostPrimitiveDefinition,
    val policyAllowed: Boolean?
)

object AiLimbsHostPrimitiveCatalog {
    val all: List<HostPrimitiveDefinition> = listOf(
        HostPrimitiveDefinition(1, "host.filesystem@1", "Filesystem", "受控文件系统访问：列目录、读取、写入、删除、移动、复制、建目录、搜索与文件观察。", "Host 负责路径校验、权限、插件沙箱和跨环境基础 I/O；日志查看、备份、文件管理器等高层逻辑由插件组合。", HostPrimitiveMaturity.CONFIRMED, HostPrimitiveExposure.DECLARED, false),
        HostPrimitiveDefinition(2, "host.process@1", "Process / Terminal Session", "受控创建和管理进程/终端会话，包括输入输出流、交互、终止和会话生命周期。", "Host 负责进程资源与合法执行后端；具体命令和业务工具由插件定义。", HostPrimitiveMaturity.CONFIRMED, HostPrimitiveExposure.DECLARED, false),
        HostPrimitiveDefinition(3, "host.ubuntu.runtime@1", "Ubuntu Runtime", "管理 AI Limbs Ubuntu 运行时的状态、启动、停止和空闲策略。", "Host 只管理 Ubuntu 容器/运行时生命周期；Ubuntu 内的具体命令与工具仍属于插件或上层能力。", HostPrimitiveMaturity.CONFIRMED, HostPrimitiveExposure.DECLARED, false),
        HostPrimitiveDefinition(4, "host.ui.automation@1", "UI Automation / Interaction", "读取 UI 结构并执行点击、长按、滑动、文本输入和系统按键等界面操作。", "Host 屏蔽 Accessibility、Shower 等具体后端；插件只面向统一的 UI snapshot/node/action 语义。", HostPrimitiveMaturity.CONFIRMED, HostPrimitiveExposure.DECLARED, false),
        HostPrimitiveDefinition(5, "host.screen.capture@1", "Screen Capture", "获取设备屏幕或显示目标的截图/原始画面帧。", "Host 负责屏幕捕获授权与 capture 生命周期；压缩、OCR、视觉推理和归档由插件完成。", HostPrimitiveMaturity.CONFIRMED, HostPrimitiveExposure.DECLARED, false),
        HostPrimitiveDefinition(6, "host.network@1", "Network I/O", "提供受控网络连接与流式收发，并实施端点、代理、TLS 和监听端口策略。", "Host 管网络权限和连接资源；HTTP、WebSocket、Webhook 等具体协议和业务语义由插件实现。", HostPrimitiveMaturity.CONFIRMED, HostPrimitiveExposure.DECLARED, false),
        HostPrimitiveDefinition(7, "host.background.runtime@1", "Background Runtime", "为需要持续运行的插件能力提供前台服务、WakeLock、恢复和后台生存租约。", "Host 聚合声明式 runtime lease；心跳、重连、下载器等业务算法仍由插件负责。", HostPrimitiveMaturity.CONFIRMED, HostPrimitiveExposure.DECLARED, false),
        HostPrimitiveDefinition(8, "host.notification@1", "Notification", "发布受控 Android 通知，并为高权限场景提供经过授权的通知观察能力。", "Host 管通知渠道、权限、PendingIntent/action 和监听静态壳；插件只提交声明式通知或订阅授权事件。", HostPrimitiveMaturity.CONFIRMED, HostPrimitiveExposure.BOUND, true),
        HostPrimitiveDefinition(9, "host.android.settings@1", "Android Settings", "受控读取或修改 Android System/Secure/Global 设置项。", "Host 负责 namespace 白名单、系统授权和风险控制；亮度等具体功能由插件组合该原语实现。", HostPrimitiveMaturity.CONFIRMED, HostPrimitiveExposure.DECLARED, false),
        HostPrimitiveDefinition(10, "host.android.package@1", "Android Package / App", "查询、安装、卸载、启动或停止 Android 应用包。", "Host 管 PackageManager、安装器和受控进程操作；批量应用管理等属于插件业务。", HostPrimitiveMaturity.CONFIRMED, HostPrimitiveExposure.DECLARED, false),
        HostPrimitiveDefinition(11, "host.bluetooth@1", "Bluetooth", "管理 Classic/BLE 扫描、连接、监听、发现、订阅与字节收发会话。", "Host 管蓝牙权限、Adapter 和连接会话；设备协议解析与具体设备控制由插件负责。", HostPrimitiveMaturity.CONFIRMED, HostPrimitiveExposure.DECLARED, false),
        HostPrimitiveDefinition(12, "host.location@1", "Location", "在用户授权下获取设备位置和基础地理信息。", "Host 管定位权限、provider、采样和隐私策略；地图、天气、地理围栏等业务由插件实现。", HostPrimitiveMaturity.CONFIRMED, HostPrimitiveExposure.DECLARED, false),
        HostPrimitiveDefinition(13, "host.clipboard@1", "Clipboard", "受控读取、写入、清空以及在平台允许时观察系统剪贴板。", "Host 实施前台与隐私策略；粘贴工作流、浏览器脚本等上层逻辑由插件负责。", HostPrimitiveMaturity.CONFIRMED, HostPrimitiveExposure.DECLARED, false),
        HostPrimitiveDefinition(14, "host.permission@1", "Permission / Consent Broker", "统一检查和申请 Android 运行时权限及受支持的特殊访问权限。", "Host 用受控 capability token 映射实际 Android 权限并绑定插件身份；插件不能直接持有 Activity/Context 自行申请。", HostPrimitiveMaturity.CONFIRMED, HostPrimitiveExposure.DECLARED, false),
        HostPrimitiveDefinition(15, "host.audio.capture@1", "Audio Capture", "管理麦克风权限、AudioRecord 生命周期和 PCM 音频输入流。", "Host 管音源、采样率、通道与并发；STT、唤醒词、声纹等算法由插件完成。", HostPrimitiveMaturity.CONFIRMED, HostPrimitiveExposure.DECLARED, false),
        HostPrimitiveDefinition(16, "host.audio.playback@1", "Audio Playback", "提供受控音频输出会话，包括播放、暂停、停止、跳转、音量和音频焦点。", "Host 管基础播放资源和会话；TTS 合成、音乐队列、歌词等业务由插件负责。", HostPrimitiveMaturity.CONFIRMED, HostPrimitiveExposure.DECLARED, false),
        HostPrimitiveDefinition(17, "host.android.component@1", "Android Component Invocation", "受控启动 Activity、发送 Broadcast、调用 Service，并处理 Intent/URI/extras。", "Host 校验目标、参数和风险；Tasker、拍照、打开设置、分享等业务语义由插件组合。", HostPrimitiveMaturity.CONFIRMED, HostPrimitiveExposure.DECLARED, false),
        HostPrimitiveDefinition(18, "host.event@1", "Host / System Event", "向插件发布标准化的宿主/系统事实事件，如生命周期、屏幕、电源、网络和内存状态变化。", "Host 负责事件来源、命名空间和状态 replay；插件私有事件继续使用自己的 scoped event bus。", HostPrimitiveMaturity.CONFIRMED, HostPrimitiveExposure.DECLARED, false),
        HostPrimitiveDefinition(19, "host.device.state@1", "Device State / Capability", "提供归一化的设备能力与状态快照，如系统版本、显示、内存、电池、电源和网络类别。", "Host 只提供受控只读状态，不默认暴露稳定设备标识等高敏信息。", HostPrimitiveMaturity.CONFIRMED, HostPrimitiveExposure.DECLARED, false),
        HostPrimitiveDefinition(20, "host.scheduler@1", "Persistent Scheduler", "创建可持久化的一次性或周期任务，并管理约束、取消、去重和重启恢复。", "Host 负责 WorkManager 类持久调度；工作流、备份、同步和更新检查的业务内容由插件提交。", HostPrimitiveMaturity.CONFIRMED, HostPrimitiveExposure.DECLARED, false),
        HostPrimitiveDefinition(21, "host.ai.inference@1", "AI Inference / Model Routing", "按功能/能力调用当前 AI 模型，支持路由、流式推理和 token 估算。", "Host 解析模型配置、Provider、密钥和路由；普通插件不直接接触内部 AI Service、ModelConfig 或 API Key。", HostPrimitiveMaturity.CONFIRMED, HostPrimitiveExposure.DECLARED, false),
        HostPrimitiveDefinition(22, "host.chat@1", "Chat / Conversation Runtime", "管理会话和消息的创建、查询、切换、删除、发送与流式对话。", "Host 维护 conversation/message 稳定身份与基础持久化；Memory、角色卡、外部聊天等功能通过该接口使用会话。", HostPrimitiveMaturity.CONFIRMED, HostPrimitiveExposure.DECLARED, false),
        HostPrimitiveDefinition(23, "host.logging@1", "Structured Logging", "为插件提供带身份、级别、时间和标签的统一结构化日志写入能力。", "Host 管日志 sink、轮转和可观测性；插件不直接依赖 AppLogger 或决定宿主日志物理路径。", HostPrimitiveMaturity.CONFIRMED, HostPrimitiveExposure.BOUND, true),
        HostPrimitiveDefinition(24, "host.secrets@1", "Secret / Credential Broker", "按插件身份和声明的逻辑名称安全读取、撤销与轮换凭据/密钥。", "Host 统一加密存储、授权和日志脱敏；插件不能读取其他插件 secret 或直接操作宿主偏好存储。", HostPrimitiveMaturity.CONFIRMED, HostPrimitiveExposure.PARTIAL, false),
        HostPrimitiveDefinition(25, "host.ui.surface@1", "Host UI Surface / Route", "为插件提供 AI Limbs 内部稳定的页面、路由、槽位和受控 UI 渲染容器。", "Host 管导航与 surface 生命周期；插件贡献声明式 UI/route/action，不直接持有 Activity、NavController 或 AppWidgetProvider。", HostPrimitiveMaturity.CONFIRMED, HostPrimitiveExposure.BOUND, false),
        HostPrimitiveDefinition(26, "host.window.overlay@1", "System Overlay Window", "创建和管理跨应用系统浮窗，包括位置、尺寸、焦点、触摸策略和生命周期。", "Host 管 SYSTEM_ALERT_WINDOW 权限与 WindowManager 资源；插件只提交受控 overlay spec/UI surface。", HostPrimitiveMaturity.CONFIRMED, HostPrimitiveExposure.DECLARED, false),
        HostPrimitiveDefinition(27, "host.capability@1", "Capability Bus", "统一发现、描述和调用插件能力，并通过 Policy/Dispatcher 路由执行。", "Host 管命名空间、schema、owner、生命周期和 ALLOW/ASK/FORBID；插件不能绕过 Policy 直接取得 Dispatcher。", HostPrimitiveMaturity.CONFIRMED, HostPrimitiveExposure.BOUND, false),
        HostPrimitiveDefinition(28, "host.plugin.service@1", "Plugin Service RPC / Dependency", "为插件间显式版本化 Service RPC 和依赖解析提供受控通道。", "当前机制已存在但仍缺真实业务消费者验证，暂保持候选；执行型 AI 工具能力仍应走 Capability Bus。", HostPrimitiveMaturity.CANDIDATE, HostPrimitiveExposure.PARTIAL, false),
        HostPrimitiveDefinition(29, "host.extension.routing@1", "Typed Extension Point / Binding", "把类型化子插件/贡献绑定到父插件或 Host 的 Extension Point，并管理兼容性与生命周期。", "Host 管 point/API version、owner identity、冲突和 binding lifetime；不承担 Capability 执行 Policy 或通用 Service RPC。", HostPrimitiveMaturity.TARGET_CONFIRMED, HostPrimitiveExposure.BOUND, false),
        HostPrimitiveDefinition(30, "host.plugin.runtime@1", "Plugin Runtime Host / Isolation", "统一装载、挂载、停止和隔离不同插件 Runtime，并管理超时、资源 ownership 与失败回滚。", "Host 管 Runtime Adapter 和生命周期；普通 payload 只得到受限 PluginContext，不直接获得 Android Context。", HostPrimitiveMaturity.CONFIRMED, HostPrimitiveExposure.BOUND, false),
        HostPrimitiveDefinition(31, "host.pipeline.hook@1", "Typed Pipeline Hook / Interceptor", "允许插件在 AI Limbs 的消息、Prompt、Chat、Tool 生命周期等内部流水线挂接受控 Hook。", "Host 定义 hook catalog、schema、顺序、dispatch mode、timeout 和错误隔离；具体 Hook 逻辑由插件提供。", HostPrimitiveMaturity.CONFIRMED, HostPrimitiveExposure.DECLARED, false),
        HostPrimitiveDefinition(32, "host.android.usage@1", "Android App Usage / Activity History", "读取经过授权的应用使用时长、最近使用等历史活动数据。", "Host 管 Usage Access 特殊授权、时间窗、包范围和隐私策略；静态包信息仍属于 Android Package 原语。", HostPrimitiveMaturity.CONFIRMED, HostPrimitiveExposure.DECLARED, false),
        HostPrimitiveDefinition(33, "host.content@1", "Content / Document Broker", "受控处理 Android content/document、文件选择器、URI grant、流/文件描述符和跨 App 内容交换。", "Host 管 ContentResolver、授权和 Provider 静态壳；插件只得到受控 handle，不直接持有任意 URI 权限。", HostPrimitiveMaturity.CONFIRMED, HostPrimitiveExposure.DECLARED, false),
        HostPrimitiveDefinition(34, "host.web.runtime@1", "Embedded Web Runtime / Browser Session", "创建和控制嵌入式浏览器会话，包括导航、DOM/JS、Cookie、弹窗、资源拦截和网络观察。", "Host 管 Web runtime 隔离和 origin 安全；文件、权限、Intent 等分别交由对应 Host Primitive。", HostPrimitiveMaturity.CONFIRMED, HostPrimitiveExposure.DECLARED, false),
        HostPrimitiveDefinition(35, "host.ingress@1", "External Ingress / Request Broker", "接收来自其他 App、Broadcast、HTTP/A2A、Tasker 等外部主体主动发起的请求。", "Host 持有静态入口、身份鉴别、认证、schema、限流和回复通道；插件只声明 namespaced ingress route。", HostPrimitiveMaturity.CONFIRMED, HostPrimitiveExposure.DECLARED, false),
        HostPrimitiveDefinition(36, "host.authorization@1", "Execution Authorization / Policy", "对 Core、Host Tool 和 Plugin Capability 统一执行 ALLOW/ASK/FORBID、收据与用户授权策略。", "这是 Kernel 强制授权平面；插件只能得到决策结果，不能自我授权或绕过 Dispatcher。", HostPrimitiveMaturity.CONFIRMED, HostPrimitiveExposure.KERNEL_GATE, false),
        HostPrimitiveDefinition(37, "kernel.plugin.trust@1", "Plugin Package Integrity / Provenance Gate", "在安装前校验插件包结构、路径安全、摘要、签名/发布者和版本内容一致性。", "这是安装时 Kernel Gate；它回答“包能否被接纳”，不能替代运行时最小权限与 Authorization。", HostPrimitiveMaturity.CONFIRMED, HostPrimitiveExposure.KERNEL_GATE, false),
        HostPrimitiveDefinition(38, "host.ui.widget@1", "Desktop AppWidget Surface", "让插件通过受控描述和渲染路由向 Android 桌面 Launcher 提供 AppWidget。", "Host 持有 Manifest 静态壳、AppWidget 生命周期、配置与点击路由；插件不直接获得 AppWidgetManager/RemoteViews/PendingIntent。", HostPrimitiveMaturity.CONFIRMED, HostPrimitiveExposure.DECLARED, false),
        HostPrimitiveDefinition(39, "host.camera.capture@1", "Camera Capture / Visual Sensor", "在用户授权下发起相机拍摄并管理临时输出、捕获生命周期与结果内容。", "Host 管 CAMERA 权限、capture lease、ActivityResult 和受控 content handle；插件不直接取得 Activity/Context/CameraManager。", HostPrimitiveMaturity.CONFIRMED, HostPrimitiveExposure.DECLARED, false)
    )

    private val byId = all.associateBy { it.id.lowercase() }

    fun find(id: String): HostPrimitiveDefinition? = byId[id.trim().lowercase()]

    fun snapshots(surfacePolicy: HostSurfacePolicy): List<HostPrimitiveSnapshot> = all.map { definition ->
        val policyAllowed = if (definition.requestableScope && definition.exposure == HostPrimitiveExposure.BOUND) {
            surfacePolicy.isAllowed(PluginSurfaceIds.hostPrimitive(definition.id))
        } else null
        HostPrimitiveSnapshot(definition, policyAllowed)
    }

    fun requireInstallableScopes(scopes: Set<String>) {
        scopes.forEach { rawScope ->
            val scope = rawScope.trim().lowercase()
            val definition = find(scope)
                ?: throw PluginInstallException("PLUGIN_SCOPE_UNKNOWN", "Unknown AI Limbs Host Primitive scope: $rawScope")
            if (!definition.requestableScope || definition.exposure != HostPrimitiveExposure.BOUND) {
                throw PluginInstallException(
                    "PLUGIN_SCOPE_NOT_AVAILABLE",
                    "Host Primitive is not requestable in this kernel build: ${definition.id} (${definition.exposure})"
                )
            }
        }
    }
}
