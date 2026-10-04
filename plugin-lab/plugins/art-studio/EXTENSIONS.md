# 画室工具菜单扩展 API 1

画室0.2.93在工具菜单提供“扩展”二级菜单。第一项“添加扩展”固定由画室维护；之后按激活顺序显示子插件提供的菜单项。空画室也能添加扩展，画布处理或扩展操作进行中暂时禁止执行子菜单项。

## 准入和所有权

安装对话框复用插件中心的 `plugin_center.shared.child_extension_installer`，由当前组件所有者限定父插件，并传入扩展点。当前Hub 1.5.5通过 `system.extension.hub` API 1安装服务核对包和目标，再交给Child Runtime加载。新包须满足Hub的AIL_EXTENSION_V1、APK、SHA-256和Ed25519受信发布者签名规则；本接口不替代任何校验，也不授予额外宿主权限。Hub不是已安装子插件的运行依赖。

`extension.json` 的target为：

```json
{"plugin_id":"plugin.art.studio","extension_point":"plugin.art.studio.extension_menu","api":1}
```

以上只是完整AIL_EXTENSION_V1清单中的target片段。扩展点ID不带 `@1`，版本由api字段表达。子插件不能指定另一个父级、冒充父级执行能力，或注册/修改插件中心共享组件定义。

## 菜单业务绑定

子插件通过 `ChildExtensionHost.publish()` 发布恰好一个 `InProcessUiStateProvider`，这是画室原生菜单的业务绑定。初始stateJson必须非空，格式为：

```json
{"schema":1,"items":[{"id":"example","title":"示例工具","enabled":true}]}
```

items允许为空，每个子插件最多32项；id须匹配 `[a-z][a-z0-9._-]{0,63}` 且在该子插件内唯一；title修剪后为1–80字符，不含控制字符；enabled必填。状态字符串最多16384字符。动态更新非法时清除该子插件菜单并记录错误，不继续执行旧菜单。

点击回到这个子插件provider的 `perform(item.id, "{}")`。事件实现和返回的JSON由子插件拥有；画室不将父级能力身份借给子插件。多个子插件允许使用同一个item.id，宿主验证的extensionId与每次绑定生成的令牌负责区分。停用、卸载、替换或父扩展点关闭会撤销绑定，旧菜单点击被拒绝。此版不委托额外host capabilities。

## 极简接入示例

```kotlin
import com.ai.limbs.plugin.runtime.*
import kotlinx.coroutines.flow.MutableStateFlow

class ExampleMenuExtension : ChildExtensionEntry {
    override suspend fun mount(host: ChildExtensionHost): ChildExtensionHandle {
        val menu = object : InProcessUiStateProvider {
            override val stateJson = MutableStateFlow<String?>(
                """{"schema":1,"items":[{"id":"example","title":"示例工具","enabled":true}]}"""
            )
            override suspend fun perform(eventId: String, payloadJson: String): String {
                require(eventId == "example")
                host.logger.i("ExampleMenu", "示例工具已执行")
                return """{"ok":true}"""
            }
        }
        host.publish(menu)
        return ChildExtensionHandle { }
    }
}
```

将入口类填入完整子插件清单，按Hub规则构建和签名 `.ailx`，再从“工具 → 扩展 → 添加扩展”选择包。示例只记录日志；实际工具自行实现菜单事件、资源释放和必要的子插件能力入口。

画室向Host演示侧发布 `plugin.art.studio.extension_menus` UI状态目录，以Host核定身份聚合菜单。目录activate事件仅用于父插件的菜单路由，令牌不是可转借给其他插件的授权。
