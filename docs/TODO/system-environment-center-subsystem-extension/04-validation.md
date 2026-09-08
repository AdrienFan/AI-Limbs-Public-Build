# 04 共存与静态检查

## 旧风险

父子插件可能在身份、capability、JNI、数据目录、Extension ID、namespace 与打包声明上冲突。

## 修改意图

逐项核对 manifest、权限交集、ID、namespace、资源与 workflow target，并检查旧 Ubuntu 目录未被误改。

## 期待结果

JSON/XML 与 ID 对账通过，git diff --check 通过；记录未做实机验证，不宣称运行完全成功。


[DONE]

静态检查通过：14 项 Contract ID 唯一且与 Ubuntu 子扩展声明集合相等；package JSON、AndroidManifest XML、GitHub Actions YAML、Python 打包脚本语法与 `git diff --check` 均通过；旧顶层 `ubuntu-terminal` 代码和 package 无差异，旧 namespace、旧 core module 名与 `ailp-native` 无残留。按约束未运行本地 Gradle，也未做实机运行验证。
