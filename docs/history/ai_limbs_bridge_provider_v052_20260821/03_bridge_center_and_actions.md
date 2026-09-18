# 通用动作与 Bridge Center

## 旧实现

前台通知已经合并为单通知，但部分入口和工具调用者名称仍带 RDC 语义。

## 修改意图

- 定义通用 `BridgeAction`
- 通知仅消费 Bridge 状态与动作
- 工具调用者名称改为中性 Bridge 语义
- 建立简版 Bridge Center 管理 Provider 状态和选择

## 期待结果

界面和通知可以展示当前 Provider，但生命周期控制不依赖具体协议。


## 完成情况 [DONE]

- 通知根据 Provider 支持集和当前状态消费通用动作。
- Tool caller 已改为 `AI Limbs Bridge`，权限执行链未改变。
- Bridge Center 已加入工具箱，可查看状态、执行动作和切换 Profile。
- 页面没有命令输入框，也没有 External Process 执行实现。
