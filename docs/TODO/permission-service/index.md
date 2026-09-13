---
fork: https://github.com/AdrienFan/AI-Limbs-Public-Build
status: source_complete
---
# AI Limbs 权限服务 Host 接入

从 build19 增量迭代到 build20，保持长期包名及签名。

1. 独立 Provider 接收 shell/root Binder，并校验受控启动 token。
2. host.privileged.runtime@1 仅向已授权权限服务 owner 提供状态、配对许可、准备、停止和后端选择；不返回 Binder。
3. DebuggerShellExecutor 按显式后端选择工作，缓存校验真实 Binder，避免同 UID 服务切换串线。
4. 为可信父插件运行时加载已验签 APK 中对应 ABI 的原生库。
5. 配对、服务端和工具箱 UI 留在独立插件工程。源码审查后云编译，真机验证另记。
