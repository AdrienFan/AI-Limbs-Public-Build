---
fork: https://github.com/AdrienFan/AI-Limbs-Public-Build
branch: fix/bridge-receiver-output-pages
version: 0.0.18
---

# Android JSON 测试编译修复

0.0.17 云端任务 37433816798 已完成 APK assembleDebug，但 GatewayResultsTest 的两处 JSONObject.similar 调用无法通过 compileDebugUnitTestKotlin。

测试编译使用 Android org.json 接口，桌面 org.json 测试依赖提供的方法不代表 Android 编译接口也提供。此次移除这两处调用，用 Android 支持的访问接口逐层断言对象键集合、数组顺序和标量值。

作用域为 GatewayResultsTest、版本元数据和本记录。保留原有分页、中文与表情、来源对象不变性、首屏错误命名空间和 SHA-256 校验。

实现见 [01-android-json.md](01-android-json.md)。验证见 [02-validation.md](02-validation.md)。
