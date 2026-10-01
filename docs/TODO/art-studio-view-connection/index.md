---
fork: https://github.com/AdrienFan/AI-Limbs-Public-Build
status: source-complete
scope: art-studio plugin only
---
# 手机页面与 AI 视图连接

基于已安装0.2.38、e1d056d4。业务端读取进程内 ArtStudioViewControl，而 Resident 手机页面持有另一份局部状态，导致画布明明打开却拒绝缩放与工具浮窗。保留现有 view.* 名称和参数，新增插件自有 InProcessUiStateProvider，使用 Runtime 已有 Provider stateJson/perform 通道同步真实页面状态并回传执行结果；不改基座、Host 原语或绘画数据。

- [x] 注册通用 UI 状态 Provider，加入页面会话、心跳、工程校验及命令回执
- [x] 手机页面接入；AI 视图入口读取页面状态并等待执行回执
- [x] 递增0.2.39，完成源码检查并准备提交既有云端工作流

验证覆盖关闭页面、会话替换、工程切换、过期命令、重复回执和参数窗口；安装后再验证真实 UI 交互。

检查结果：66个生产 Kotlin 文件词法与编码检查通过；158项唯一能力、216菜单项和灰色原因声明校验通过；版本0.2.39/code42/appIdv0239/菜单版本一致；Entry 的视图入口不再读取进程内 UI 单例。原Provider页面身份保留，新增view.control声明与注册匹配；git diff --check通过。未执行本地Gradle编译或JUnit；10个回归用例交云端执行。安装后的跨进程状态与触摸交互仍待验证，不宣称实机通过。

[DONE] 插件源码修复与云端回归构建准备完成；回执保存到工作上下文，提交后切回ChatGPT，不监控进度。
