# 动作上下文与发布

原动作记录在 updatePreview 之后附加，缓存元数据没有记录，后续取帧丢失。现在动作记录由 Core 按会话和停止代次保存；完成时间较旧的反馈不覆盖较新记录。后续帧、预览恢复、存图附 last_operation，age_ms 为返回时计算，frame_after_action 依据 freshness.captured_elapsed_ms，未知为 null。视觉等待信息也保存在当前预览中。停止不删除历史图像，但新会话不继承旧会话动作。执行成功、观察失败仍保留 action_success，禁止重复动作。

0.2.8 / versionCode 14 / payload com.ai.limbs.payload.visualmanager.v028，逻辑 plugin.system.visual_manager 不变。仅视觉包，无基座和桥编译。

安装后验收：
- 静止普通页面 wait_until_stable 成功，samples=1 时 observations 至少 2，真实 capture 时间不变；无变化的 change 仍超时。
- 动态区域持续变化不误报稳定；区域排除、旋转中等待和旧帧点击明确处理。
- 普通 App observe 自动 UI，游戏横屏自动 visual；显式覆盖和无会话错误符合契约。
- tap 和宿主反馈后再取帧/读预览/存图，动作 ID 保留，年龄递增，时间关系正确；重新开会话无旧动作。
- 原共享崩溃、重进、存图和相机预览不回归。

[DONE] 代码与版本已更新；云端构建记录与测试结果由对应源码 SHA 核对，实机待新包部署。
