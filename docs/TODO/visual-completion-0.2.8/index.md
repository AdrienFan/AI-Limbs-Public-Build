---
fork: https://github.com/AdrienFan/AI-Limbs-Public-Build
branch: feat/visual-workbench-v02
version: 0.2.8
---

# 通用视觉七项能力补齐

用户要求完成连续视频流以外的改修补。范围仅视觉插件；基座 build112 和各桥沿用现有 Host 原语、能力注册、图片附件。PR/构建基于现有 feat/visual-workbench-v02 分支。

0.2.7 实测：横竖屏帧和坐标映射正常，旧帧拒绝、动作后观察、变化检测、预览恢复、存图正常。静止相机选择页 stable 等待 2679ms，polls=7、samples=1、quiet=0 后超时；不同生产帧作为唯一稳定计时证据导致静止页无法完成。普通 UI/纯视觉自动选择尚未实现；动作记录仅随一次返回、未存入预览；page.text 公开 limit 与内部 length 不一致。

步骤：
1. [静止页面与采集有效性](01-static-stability.md)
2. [自动观察与快照契约](02-mode-and-page.md)
3. [动作上下文、版本及验收](03-action-and-release.md)

期望完成：干净 get_frame、横竖屏与比例坐标、动作自动回帧、变化/稳定等待、自动 UI/图像选择、持续采集会话、动作历史。连续视频推送到模型不在范围内。

云端统一运行能力契约检查、单元测试、assembleDebug、签名及打包；不在手机 Ubuntu 或本地环境编译/运行项目测试。新版本仍须部署后实机验收，本轮不宣称已安装。

[DONE] 七项代码补齐；云端结果随对应源码提交运行核对，实机验收待部署。
