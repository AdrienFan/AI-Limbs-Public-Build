# 固定操作条与能力合同

LazyColumn 使用剩余高度，底部固定条位于列表外。独立 Checkbox 选择，不触发行历史跳转；垃圾桶位于条右侧。非可删除记录可以选择查看原因，按钮禁用。原跳转入口保留，工程切换清除选择。

能力 plugin.art.studio.history.delete：必填 documentId、id（足迹事件 ID）、expectedRevision；可选 responseMode=receipt 与 requestId。先 history.timeline 取得同版本 canDelete=true 行。工程/版本校验、对象解析、删除写入在同一锁中完成。返回 deletedFootprintId 和 lastOperationId；history.undo 恢复，requestId 可经 document.operation.status 查询提交。

手机 actor=AWEI、兰儿 actor=LANER，复用同一 store 方法。发生修改后刷新资格；不接受其他工程、旧版本或未应用步骤。

[DONE] 源码与静态核对；发布和设备显示待验收。
