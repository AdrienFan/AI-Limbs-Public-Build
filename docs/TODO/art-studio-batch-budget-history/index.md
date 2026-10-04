---
repository: current feat/art-studio-v01 checkout
status: source-complete; static-review-complete; runtime-pending
---

# 实际绘画体验优化

《晨雾湖光》绘画时，单笔能力往返约 3–9 秒；使用压力为零的连续移动模拟抬笔触发笔触预算上限；读取 49 步完整足迹约 15 秒，包含桥接分页。上述时间是整条调用链观测值，不用于认定渲染或手机 UI 的单独耗时。

1. 批量绘画：新增 stroke.batch，在同一版本锁内准备、校验和原子保存整批，末尾一次画布反馈。每段保留独立 STROKE_ADD 足迹、撤销和单笔删除。[DONE]
2. 多段与预算：显式 segments 表示独立落笔，段间距离不生成笔尖；增加 stroke.budget 只读预检与明确的预算数量/上限/是否下界说明。旧笔触渲染保持原有语义。[DONE]
3. 足迹读取：history.timeline 增加可选分页与精简行，history.entry 读取指定一笔；先选行再计算删除资格。无参数调用仍返回完整足迹。[DONE]

作用域：仅画室插件、能力清单/极简帮助、回归用例、版本和文档。递增为 0.2.90 / versionCode 93 / v0290。不在本地编译或运行测试；云端编译和设备性能验收待安排。

静态核对已完成：263个能力/示例/字段清单一致，Kotlin词法结构、版本与applicationId一致，批量单次反馈及唯一持久写入路径、分页/单条工程版本守卫。回归用例已补充但未在本地执行；不把静态检查称作编译或性能验收。
