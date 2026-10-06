---
fork: https://github.com/AdrienFan/AI-Limbs-Public-Build
status: source-complete-cloud-validation-pending
---

# Ubuntu 0.1.24 PTY 流式输出修复

## 根因与修改

0.1.23 隐藏命令已有 UTF-8 有状态读取器，但 TerminalManager 的 PTY 仍逐字节块独立转成字符串。中文和 emoji 跨读取边界时出现替换字符。另一层问题是未结束的物理行被清空缓冲区并以新行追加，真实无换行输出被切成多行。

隐藏命令和 PTY 改用共同的 Utf8OutputReader，一条流只创建一个 UTF-8 解码器，并在字符缓冲区边界保留完整的 emoji 代理对。旧读取器类和测试名已移除。

PTY 保留尚未遇到换行的原始行；命令历史与过程事件替换这一行的预览。后台进程缓冲区也替换对应的前一次预览，增量读取游标可重新读取更新后的行，共享终端显示使用当前快照。长行拆分避开 UTF-16 代理对。交互式提示仍即时显示，命令完成事件仍提供最终权威快照。

修改全部位于 Ubuntu 子插件，基座不承担 PTY 或进程业务。外部 process 调用参数保持原样。内部 CommandExecutionEvent 新增默认 false 的 replaceLastOutputLine 字段，供同一子插件内的生产者与消费者协作。

## 版本与验证

包版本 0.1.24、versionCode 25，payload applicationId 使用独立的 v0124。签名打包脚本动态读取扩展声明版本。

保留 3 项跨字节、未完整字符等待、无换行即时提示测试；新增 1 项字符缓冲区 emoji 边界测试与 3 项行预览组装回归。云端 ubuntu-system 任务运行 terminal-core 和子插件 JVM 测试后编译签名 AILP。

静态审阅已完成，云端测试与真机验收待完成。部署后使用逐字节输出“兰儿流式😀”、多页中文、无换行交互提示及完整命令标记核验乱码、重复与多余换行。

[DONE] 源码和回归用例完成；云端与真机结果待记录。
