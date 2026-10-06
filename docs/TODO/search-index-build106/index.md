---
fork: https://github.com/AdrienFan/AI-Limbs-Public-Build
status: source-complete-cloud-validation-pending
---

# build106 搜索索引复用

基于 build105 继续迭代，保留其通用权限与视觉生命周期支持。稳定 Android applicationId 保持不变，versionCode 由 200 增至 201。本轮云编译只执行 assembleDebug，产出一个可覆盖更新的基座 APK。

## 根因与修改

原缓存只保存最后一次目录索引，全局、模块及精确能力查询互相覆盖。每次构建又在逐字段循环中编译 Unicode 正则并重复归一化同一份文字，导致索引准备时间被混入 ranking。

现在保留最多 8 份目录视图、4096 份词条文档缓存，使用 LRU 淘汰；目录视图共享已处理的词条。注册代次变化清除旧目录视图，当前目录仍由实时注册状态决定，未变化的词条可复用。权限、回执和可用性继续实时检查。

Unicode 正则只编译一次，每个词条内部复用归一化文字与分词结果，不修改搜索权重及排序规则。搜索诊断新增 index_prepare、scoring、prepared_documents、reused_documents，用于分别核验准备成本和评分成本。

## 验证与验收

新增全局/模块/精确查询交错、模块后首次全局增量构建、视图 LRU 重组、单词条变化、卸载及重挂载、文档 LRU 共 6 项 JVM 回归。现有云端测试任务已包含该测试类。

静态审阅已完成，尚未执行云端测试，也未宣称真机性能验收通过。部署后按全局→画室→全局顺序核验复用，再测冷查询时延、元数据更新及权限实时性。

[DONE] 源码和回归用例完成；云端与真机结果待记录。
