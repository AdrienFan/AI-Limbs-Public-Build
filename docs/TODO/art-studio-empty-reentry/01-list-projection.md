# 工程列表投影

此前list/recent为读取名称和宽高重放所有历史，每次遍历还替换单工程重放缓存。现在ArtDocumentListing仅读取base和已有事件的三种画布元信息修改：DOCUMENT_RENAME、CROP、CANVAS_RESIZE，使用ArtHistory.stacks判定已应用事件。批量笔触、矢量、文字、动画及资源不参与列表几何计算。真实打开仍完整重放和校验。

回归源码覆盖选择性撤销/恢复、撤销后新分支、无关绘画事件不读取参数、输入不可变及旧base无名称的既有默认行为。尚未执行测试或编译。[DONE]
