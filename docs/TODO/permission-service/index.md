---
fork: https://github.com/AdrienFan/AI-Limbs-Public-Build
status: source_complete
---
# AI Limbs 权限服务 v0.1.0

现有基座只消费外部 Shizuku。新增工具箱插件，提供本机无线 ADB 配对、启动、停止、状态及日志；ADB/root 激活独立 Android 服务，不依赖 Ubuntu。

作用域：权限服务插件、上游 Apache-2.0 服务端与 ADB 客户端适配、基座受控 Binder 接入与明确后端选择、独立云构建。

1. 固定上游来源和依赖，隔离包名、进程名、配置和 Provider 地址。
2. 实现仅宿主 UID 可用的服务端；基座持有 Binder 并保持 Policy/Dispatcher 授权链。
3. 实现工具箱页面、配对密钥隔离、启动文件传送、生命周期和错误日志。
4. 静态审查、更新组件索引、推送云构建。真机配对与激活待用户安装后验证。

第一版只服务 AI Limbs，第三方应用授权不在本版范围。不得声称源码完成即代表真机验证通过。
