# 批量绘画事务

stroke.batch固定一个绘画图层，defaults共用样式，strokes中的points为单笔、segments为独立段。复用单笔normalization、选区、原笔刷及当前动画帧。准备所有段后追加普通STROKE_ADD事件，在同一工程/版本锁内重放、校验并一次原子保存；只有成功后才生成一次缩略图。任何校验失败都不保存半批。

每段都有独立strokeId/operationId，批次另带batchId。requestId覆盖整批，查询返回firstRevision、最终revision、operationCount和operationIds。回执中strokeResults可直接定位足迹；undo仍按一笔撤销，不改变历史语义。

[DONE] 源码；静态检查与云端/设备验收另记。
