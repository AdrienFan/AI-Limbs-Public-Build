# 完整内容读取优化

Fork: https://github.com/AdrienFan/AI-Limbs-Public-Build

目标版本：基座 0.8.0.12-build96、RDC 1.2.11、Ubuntu 0.1.19、视觉管理 0.1.3。

## 目标与范围

页面的 compact tree 只用于预览，不能代替实际 ToolResultData。通用执行结果保留 structuredResult，Bridge 响应追加 structured_result；原 result 字段与既有预览调用保持兼容。get_page_info 的 json 与 full XML 输出保留完整节点文字。

视觉插件通过 host.ui.automation@1 提供页面目录和节点全文分段，业务缓存、UI 与 AI 入口属于插件。RDC 接收端放宽行数并保存完整大结果供 cursor 续读；Ubuntu 插件区分增量输出与完成快照，完成快照替换最终缓存而不重复追加。

## 验证与生效

源码检查覆盖原始数据、格式参数、Host/Resident 传递、插件声明与分页边界。重新构建安装后须核验长节点文字、XML 特殊字符、跨页 Unicode、分页拼接摘要，以及 300 行进程仍只有 300 行。本地仅做源码与差异检查；用户随后授权推送云端编译，回归用例随云端工作流运行。编译和设备运行结果仍待确认。
