# 验证与交付

新增 23 项云端 JVM 回归覆盖来源回调调用次数、重名与多 owner 路由、汉字模块识别、前 20 个候选之后的 owner、完整候选保留、精确叶能力防折叠、索引复用与失效、卸载孤立条目、来源集合修改、综合排序对应的下一步地址。

另新增 3 项 Android instrumentation 回归检查实际 JSON 响应、索引重复使用与重挂载、中文与表情用途截断。现有弱查询回归更新为报告 low_confidence 而不进行强制扫描。云端工作流执行 JVM 回归并编译 instrumentation；编译 instrumentation 不代表已在设备运行这些测试。

版本为 0.8.0.16-build103，versionCode=198。云端一次生成 app-debug 稳定更新 APK 和 app-clone 对比 APK。既有稳定更新包名沿用 com.ai.assistance.operit.ailimbs.stable；对比包采用独立 com.ai.assistance.operit.ailimbs.build103，可与 build102 并存。

提交前只做静态源码、差异和工作流审查，不执行本地编译或测试。提交云端后确认任务 headSha 对应本次完整提交，再停止查看进度。

部署后的体验验收采用同一组精确地址、明确模块、跨模块自然语言和范围查询，记录实际 round-trip 与 timings_ms。耗时提升、云端构建通过及实机权限状态均需以后续真实结果确认，本记录不提前宣称达成。

[DONE]
