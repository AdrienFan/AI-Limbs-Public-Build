# 验证记录

已对照检查：Manifest 静态入口、Host scope 和 owner 校验、启动 token 生命周期、服务 UID/Host UID 检查、客户端 Binder 注册、执行器缓存、原生库 ABI 和加载目录、插件页面注册、配对码与密钥日志脱敏、ADB 传送摘要核对、停用清理及上游许可。

运行了 git diff --check，未发现空白错误。父插件签名公钥指纹与现有信任链一致。

构建交给独立 GitHub Actions：基座 build20；插件 permission-service v0.1.0。此记录不代表编译或真机验证成功。按任务流程，发起云构建后不轮询构建进度。

尚待：云构建结果、安装、无线调试配对、ADB/root 激活、停止、后端切换及宿主重启恢复的真机验证。
