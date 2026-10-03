# 画室能力回执与提交确认（第一条问题）

状态：源码与静态检查完成；随本次本地提交保存，等待其他体验问题。未推送、未编译、未执行测试。

已部署基线：0.2.76 / versionCode 79 / v0276；源码 HEAD 0a68e21c。
下一版本：0.2.77 / versionCode 80 / v0277。

## 已确认根因

画室成功写入草稿后，能力响应仍携带 state（全部帧）、operations、timeline 和分支记录。
动画实测响应达到 9,675,850 字节，超过基座 PluginRuntimeWire 的 1 MiB 帧限制。
Worker 在写回时拒绝大帧，因此调用方报错不等于业务操作失败。基座不需要插件特例或扩大帧限制。

## 本轮范围

- [x] 保持旧调用默认 full；同步能力显式支持 responseMode:receipt，仅投影完整工程快照。
- [x] 保留操作号、修订号、撤销状态与预览/预览错误；历史和帧数据不随每次编辑返回。
- [x] document.summary 提供小型工程摘要；document.snapshot.read 提供有界 JSON 字符串分页。
- [x] 分页绑定工程号、修订号及第一页 SHA-256；字符边界不拆 Unicode 代理对。
- [x] animation.configure/keyframe/seek 与 svg.apply 支持 requestId，随操作历史持久保存。
- [x] document.operation.status 精确查询提交记录；同一请求号再次执行明确拒绝。
- [x] 补充能力帮助、简洁示例与云端回归用例源码。
- [ ] 云端编译/JVM 用例与大工程 Host/Resident 实机验收（等待本轮其他问题汇总）。

## 验收边界

静态核对不等于编译通过或实机验证。部署后使用回家动画测试工程：
先读摘要，携新 requestId 和 receipt 执行定位、复制关键帧、SVG 编辑；确认原有几 MiB 响应变为小型回执且原工程/所有历史仍完整。
模拟丢失回复后按原 requestId 查询，确认 operationId/revision 与历史一致；重复请求不新增历史。
分页重建完整 JSON 并核验 SHA-256；换工程、修订变化、同版本快照元数据变化及代理对中间偏移应明确拒绝。
旧调用不传 responseMode 仍返回 full；错误参数在编辑前拒绝，图像确认请求仍为未执行结果。
预览失败仍返回明确的预览错误与已提交证据。连续编辑调用必须显式使用 receipt。

本轮不优化动画批量制作、GIF 调色板或编码压缩，也不触发本地构建、推送或云编译。
