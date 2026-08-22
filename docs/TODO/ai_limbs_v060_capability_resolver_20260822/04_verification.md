# 静态核验与云端构建 [READY FOR CI]

- 检查 V0.6 调试包名与 V0.5.5 隔离。
- 检查两个 Resolver 工具能够通过 RDC 同名调用和 `shell=operit` 路由到核心 Dispatcher。
- 检查 Resolver 只读发现路径不进入真实工具执行器，不启动 Ubuntu 或 MCP。
- 检查真实工具执行仍由 `ToolExecutionManager` 与 `ToolPermissionSystem` 处理。
- 检查 CLI Tool Mode 改为共享 Catalog 后仍保留原搜索与代理公开接口。
- 不在本地下载 Gradle 依赖；推送后由 GitHub Actions `android-build.yml` 编译。

## 静态记录

- `git diff --check` 通过。
- Kotlin 文件花括号与圆括号结构检查通过。
- V0.6 独立 `applicationIdSuffix`、版本名和应用名已检查。
- Bootstrap、Dispatcher、RDC Adapter、共享 Catalog 与 Resolver 的调用名称一致。
- 按约定未运行本地 Gradle，也未下载本地构建依赖。
