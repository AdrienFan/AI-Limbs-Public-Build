# 按需足迹

history.timeline无参数保持完整timeline/otherBranches。可选offset/limit/branch只为所需行生成label、summary和删除资格；compact省去分类/摘要/actor/timestamp。初始画布计timeline第0行，分页总量包含它。nextOffset/complete用于续页，续页须携同次documentId/expectedRevision，变化拒绝。

history.entry绑定工程与版本，按id返回entry及branch，只生成一行。不复制原始笔触/层数据，不渲染缩略图；支持当前已应用、待重做、其他分支与初始画布。canGoto/applied/canDelete/deleteReason分别陈述跳转、应用、删除资格；原history.delete校验不变。

[DONE] 源码；旧完整读取、分支顺序、重新应用标签、分页尾部与单条删除资格的回归源码交云端执行。
