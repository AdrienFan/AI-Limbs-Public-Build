# 能力注册与云端交付（已完成）

## 修改

- 在 `AiLimbsOperitDispatcher` 实现聊天状态、会话、通知、收件与回复路由；取消继续复用聊天页
  已有停止按钮和 `MessageProcessingController`，不增加第二个远端取消协议。
- 同步在 `AiLimbsCoreCapabilityRegistry` 登记每个新调用地址及参数。
- 更新 AI Limbs System Access Policy，让兰儿接入时即可发现聊天能力。
- 记录 V0.6.3 版本、独立包名和开发日志。
- 只做静态预检，推送公有仓库并触发 GitHub Actions `assembleDebug`。
- 不发布可选 `chat.exchange`，系统接入策略明确要求正式三层协议并禁止猜测该调用。

## 期待结果

Capability Resolver 可搜索并描述全部聊天能力，代码与 Registry 不产生新的发现性缺口。

[DONE]
