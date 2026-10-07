# 01 安全分类

区分过期、尚未生效、证书链验证、协议失败、EOF 关闭和套接字中断；输出最多 8 层类名和标准证书验证枚举。对异常文本仅匹配固定 TLS alert、信任锚和连接关闭信号，输出固定代码，不存储原文本、回调域名/URL/密钥或证书。

旧 reason、stage、exception_type 保持存在，新增 cause_types、certificate_reason、tls_signal；状态工具和面板都保留这些字段。DNS 路线、公网检查、TLS 信任、超时和业务执行语义不变。

[DONE] 分类、交付与回归用例完成，云端 89 项测试通过；实际 TLS 根因仍待复验。
