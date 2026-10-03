---
fork: https://github.com/AdrienFan/AI-Limbs-Public-Build
status: source-complete-runtime-pending
---

# 隐藏轮询与显示事务锁

基于已部署0.2.81及f93f1f43。实机副本定位仍4.5/14.3/7.5秒；Resident定位自身持锁约0.65秒，但等文件锁5.9/3.1秒，同期Host持锁约10秒。源码确认隐藏页面400ms循环未暂停，编辑器快照和像素合成处于同一文件锁内。

作用域仅画室ArtStudioPage、ArtStore、ArtReferencePreview和页面请求/资源租约辅助类。保留全部公开接口与历史规则，不改基座。不以删除校验或减画布分辨率来减少锁等待。

1. [隐藏轮询](01-visibility.md)：复用已公布可见性语义，保留旧画面。
2. [捕获与绘制](02-capture-render.md)：快照/资源句柄/版本同捕获，像素在锁外处理，过期帧拒绝。
3. [验收边界](03-validation.md)：静态核对、云端回归源码及实机必要项。

无PR创建；本轮开发分支提交，不推送、不启动新编译。运行时验收在后续部署进行。
