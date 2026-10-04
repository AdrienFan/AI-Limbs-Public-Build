# 子插件准入及生命周期

旧实现：画室没有子插件扩展点。先核对当前Hub 1.5.5服务、Child Runtime SDK与插件中心共享安装器，不沿用旧版Hub provider。

新实现：发布plugin.art.studio.extension_menu API1，接受InProcessUiStateProvider业务绑定；初始及动态菜单校验、真实子插件身份、每次绑定令牌、停用/卸载清除、父级关闭释放观察任务。事件回到child provider，使用记录通过Child Runtime维护；没有扩大父级委托权限。

极简接入及完整契约见 [EXTENSIONS.md](../../../plugin-lab/plugins/art-studio/EXTENSIONS.md)。

[DONE]
