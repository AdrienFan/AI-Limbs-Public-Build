# 静止页面稳定计时

旧实现只计不同 frame_id 的采集时间，静止页不重绘时永久达不到 stable_ms。改用有效观察的单调时间，同时保留真实生产帧数量、编号和采集时间。重复帧必须时间与 RGB 样本一致；未验证采集来源的重复帧仍不能证明稳定。

每轮通过现有 host.screen.session@1 status 检查所有权/会话、projection_ready、READY、capture.active、frame_available、producer_error、可见性及 geometry_id。生产器报错、停止、不可见或旋转明确失败，不返回旧图充当成功。稳定计时不等于业务完成或生产器心跳；报告 samples、observations、reused_observations、stability_clock。变化必须由实际像素差触发，重复帧不发明变化。

回归：静止重复帧、未验证缓存、伪造采集时间/像素、两种时钟倒退、变化后静止、动画、慢漂移、区域边界及无效采集证据。

[DONE] 生产等待路径和证据检查已实现，测试交云端执行。
