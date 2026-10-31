# 第五阶段：提交 0.3.0 云端验证

2026-10-31，阿伟明确要求“提交云编译”。本授权接替此前暂缓推送安排；本次提交累计四阶段具体滤镜源码，尚未完成的模式差异与公共机制继续按原记录保留，不宣称完整 Krita 移植。

版本统一为 0.3.0 / versionCode103 / applicationId com.ai.limbs.payload.artstudio.v030，manifest、Gradle和菜单版本同步。插件身份、既有JNI接口和签名身份保留。

推送权威手机开发仓库 feat/art-studio-v01 的累计提交，由 .github/workflows/art-studio-build.yml 的 push 事件触发。使用既有流程执行能力／版本检查、JVM用例、APK编译、独立OpenEXR互读、API29／34 Android用例、签名打包和来源记录。此分支同时配置你画我猜工作流，其构建任务不是第二次推送。此次不修改子扩展业务或工作流触发条件。

提交前核对远端分支、版本一致性、能力与帮助清单及差异空白；只提交本轮版本和发布说明，保留已有0.2.99设备记录的未提交修改。本地不执行Gradle构建或运行测试。

推送后确认本轮SHA对应的Art Studio构建记录已创建即可。构建结果与设备部署另行确认，不把任务排队表述为编译成功。

## 首次云端验证修正

2026-10-31，构建 41177294322 在 JVM 测试阶段失败：382 项用例中 381 项通过，唯一失败为 `ArtCapabilityHelpTest.everyExampleMatchesItsRegisteredParameterContract` 第10行，仍断言旧版267项能力。本轮新增 `menu.preview`、`filter.catalog`、`filter.resources`、`filter.reference`、`filter.hidden` 后已为272项，源码编译和能力声明静态核验均已通过。

更新测试的清单预期为272，并明确断言以上五个入口均有帮助；其后的逐项参数、必填参数、类型与枚举校验继续完整执行。保留原测试流程，重新提交云端验证；不将此前源码编译通过等同于 APK 打包或设备测试通过。
