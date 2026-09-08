# 03 Ubuntu .ailx

## 旧实现

Ubuntu Runtime、RootFS、PTY、TerminalManager、CanvasTerminalView 与专属能力嵌在父插件目录中。

## 修改意图

建立独立 Ubuntu system extension，迁入全部 Ubuntu 专属 Runtime、Display 与业务逻辑，并发布唯一 typed binding。

## 期待结果

Ubuntu 通过系统环境 Extension Point 插入父插件；旧顶层 Ubuntu 0.3.7 保持不动并可共存。


[DONE]

Ubuntu 已迁为 ai_limbs.system_environment.subsystem@1 的独立 android_child 扩展：拥有 rootfs、PTY、TerminalManager、文件/进程/会话能力和终端 Display Adapter；父级通用控制已从子 UI 移除，旧顶层 Ubuntu 0.3.7 未改动。
