# 核心契约与注册表

## 旧实现

`AiLimbsBridgeProvider` 已抽象生命周期，但 Manager 直接创建 `RdcBridgeProvider`。

## 修改意图

- 新增 `BridgeProfile`
- 新增 `BridgeProviderFactory`
- 新增 `BridgeProviderRegistry`
- 用 SharedPreferences 持久化 active Provider

## 期待结果

Manager 只根据 active Profile 从 Registry 创建 Provider，不引用 RDC 的具体实现。


## 完成情况 [DONE]

- Registry 负责 Factory 与 Profile 的唯一性、类型和动作契约校验。
- SharedPreferences 保留原 `desired_connected`，新增 `active_provider`。
- Manager 只根据 Profile 创建和切换 Provider，不直接构造 RDC。
