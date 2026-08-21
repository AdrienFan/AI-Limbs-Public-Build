# 通知控制面板与品牌图标

## 旧实现

前台通知使用标准大文本模板，只能显示三项操作。标题、正文和展开内容会重复显示“AI Limbs 正在运行”，Bridge Center 使用平铺单选列表，应用仍沿用 Operit 图标。

## 修改意图

- 折叠通知只展示当前 Provider、桥状态和有效运行信息
- 展开通知使用受系统模板约束的 `RemoteViews` 控制面板
- 第一排保留三个上下文操作，第二排提供语音悬浮窗和唤醒开关
- 桥名称入口直达 Bridge Center，Provider 选择改为应用内下拉菜单
- 使用第三张设计图提取的透明 AI Limbs 原标识作为自适应图标前景，并保留单色通知图标

## 验证范围

- 自定义布局只使用 `RemoteViews` 支持的系统控件
- 五个操作均绑定独立 `PendingIntent`
- 忙碌状态继续保留“停止当前操作”入口
- 桥操作仍由 `BridgeAction` 和现有权限链处理
- Android 小图标保持单色透明轮廓
- 彩色前景资源以 `18dp` 边距限制在自适应图标安全区内
- `v0.5.2-dev` 推送会自动触发 GitHub Actions `assembleDebug`
- 以 GitHub Actions 公有仓库构建结果作为编译结论
