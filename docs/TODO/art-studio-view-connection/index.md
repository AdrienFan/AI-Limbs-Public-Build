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


2026-10-01 云端构建36864815024在生产源码编译阶段报告 ArtStudioPage.kt:1384 LocalView 未解析；尚未进入JUnit执行。补齐 androidx.compose.ui.platform.LocalView 导入；版本仍为未成功产出的0.2.39，重新提交同一版本修正构建。其余连接行为不变；新构建结果与实机验证待确认。

## 0.2.40：独立 AI 预览修复

根因：0.2.39 的解决范围是同步手机视图，却仍以手机挂载/可见为 view.command / view.zoom 的必要条件。AI document.open 已成功，view.command fit 仍因手机页面不在前台而被拒绝。通过切换用户页面验收不能满足兰儿独立入口用法。

实现：默认 assistant 目标复用 ArtStore 和 ArtRenderer，真实渲染1024×768后台视口；显式 phone 目标继续复用既有 Provider。没有新增宿主能力、权限、基座特判或按可见性自动分流。原能力名和必填参数保留，target 为新增可选参数。手机专用面板/参数窗/显示模式入口保留原语义。

检查：打开工程与视图操作在已有工程锁内核对工程身份和尺寸；图片成功渲染后才发布新视图状态与 accepted；换工程/尺寸变化重置；无工程明确拒绝；全尺寸合成沿用工作预算；不更改像素和修订号。

回归：新增7项 JVM 用例覆盖不依赖手机页面的工程视图、关闭/重开与尺寸变化、比例变化、过期工程及非法缩放、旋转适配与镜像重置、缩放上限。云端运行既有10项与新增7项用例并编译打包。用户明确要求不做本地编译，全部使用既有云端工作流。

状态：源码已实现；正式能力校验通过（142项字面注册，158项声明），216项菜单与67项业务实现校验通过，版本0.2.40/code43/appIdv0240一致，git diff --check通过。未运行本地Gradle或本地JUnit；云端构建结果与安装后的实机验收待确认。
