# RDC Provider 接入

## 旧实现

RDC Provider 与 Provider 接口位于 Manager 文件，Manager 直接持有具体类型。

## 修改意图

- 将 RDC Provider 移入 `providers/rdc/`
- 提供 RDC Factory
- 由应用 Registry 显式注册 Factory

## 期待结果

RDC 协议细节停留在 RDC Provider 和 `AiLimbsRdcToolAdapter` 内，核心不感知 RDC。

## 完成情况 [DONE]

- RDC Provider 已迁入 `providers/rdc/` 并提供 Factory。
- 应用 Catalog 是唯一注册 RDC 的组合边界。
- RDC Tool Adapter 与 RDC Client 保持协议专用，不提升为核心标准。
