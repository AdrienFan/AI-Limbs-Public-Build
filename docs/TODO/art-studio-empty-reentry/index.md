---
fork: https://github.com/AdrienFan/AI-Limbs-Public-Build
branch: feat/art-studio-v01
version: 0.2.91
---

# 空画室重新进入

0.2.90退出关闭当前工程后，重新进入仍从restoring=true开始；文件菜单统一busy禁用新建/打开。页面初始化recent会在共享锁内重放每份旧工程，与初始指针检查竞争。实机当前工程确实为空，document.recent单次9.31598秒，日志多次记录约10秒占锁。

1. [工程列表投影](01-list-projection.md)：只读名称与尺寸，尊重撤销/重做，不重放旧画。[DONE]
2. [页面与文件菜单](02-page-state.md)：核对指针后才恢复，空画室不渲染，新建/打开与只读恢复分开互斥。[DONE]

范围仅画室插件，保持既有能力参数、响应、工程格式、保存确认和宿主/插件边界。版本0.2.91 / code94 / v0291。无本地测试或编译；回归源码与静态核对完成，云端构建和部署后的退出/重进验收待安排。
