# 你画我猜 0.1.6

独立画室子插件，需要画室0.2.97或以上。先安装配套画室，再从“工具 → 扩展 → 添加扩展”选择签名游戏 `.ailx`；完成准入后，扩展菜单出现“你画我猜”。基座、Hub和插件中心业务没有改动。

## 第一版规则

准备页面有规则简介和大的准备圆按钮。双方准备后各掷一次骰子、各自确认；同点双方重掷，点数较大者选择先画或先猜。确定第一位画方后进入模式选择。每轮由当轮出题／绘画方选择本轮模式，选择权随角色交换。当前“系统出题”禁用，低／中／高难度仅预留；选择“自由出题”后由画方填写词语并确认封存，再填写两条非空提示，完成后创建1000×700白底临时画布。

绘画可使用画室工具。点“确定完成”后冻结；猜方收到图和自动字数星号提示。第一次猜错解锁提示一，第二次猜错解锁提示二，第三次猜错判失败；任意一次猜中立即成功。成功或失败均公布答案、清理画布和图片，自动交换角色，由新的画方选择下一轮模式；后续轮次不重复开局掷骰。不做系统提词，不做计时和积分。

答案去首尾空格后精确匹配，字数按Unicode码点计数。封存词语与尚未解锁提示仅存在子插件内存；猜测接口不返回这些信息。兰儿的封存词语不进入手机对手面板；阿伟的封存词语不进入兰儿入口。外部截图、系统日志和聊天记录不属于游戏的隐藏答案边界，不应在猜题时旁查。

浮窗拖动标题移动；“−”收起成标题，“□”展开。关闭会先询问退出，退出清理临时画布和题目。正常工程指针和作品不被替换。游戏缓存会在回合结束、退出或子插件撤销时清理；没有普通工程保存或最近列表记录。进程结束不会恢复游戏或封存题目。

## 兰儿入口

安装身份为 `plugin.art.studio.draw_guess`；能力按Child Runtime准入规则属于 `plugin.draw_guess.*`。兰儿先调用 `open` 打开游戏，再调用 `ready` 准备；两个动作独立，不会替阿伟准备。`open`、`ready` 与 `view` 都返回极简规则、当前阶段、revision、身份和allowed下一步；上下文中断后先view。关闭时兰儿的allowed返回open，已退出游戏可重新打开，重复打开不会重置进行中的回合。open、ready、view和picture无需revision；其他修改动作使用本次view返回的revision，不猜参数或直接调用普通工程绘画接口。

```json
{"capability":"plugin.draw_guess.open","args":{}}
{"capability":"plugin.draw_guess.ready","args":{}}
{"capability":"plugin.draw_guess.view","args":{}}
{"capability":"plugin.draw_guess.roll","args":{"revision":2}}
{"capability":"plugin.draw_guess.confirm_dice","args":{"revision":3}}
{"capability":"plugin.draw_guess.choose_order","args":{"revision":5,"drawFirst":true}}
{"capability":"plugin.draw_guess.choose_mode","args":{"revision":6,"mode":"FREE"}}
{"capability":"plugin.draw_guess.seal_word","args":{"revision":7,"word":"自行车"}}
{"capability":"plugin.draw_guess.seal_hints","args":{"revision":7,"hint1":"交通工具","hint2":"人力驱动"}}
{"capability":"plugin.draw_guess.paint","args":{"revision":8,"type":"STROKE_ADD","params":{"id":"f91df829-61bc-48af-8866-000000000001","tool":"ink","color":"#FF245364","width":6,"points":[[40,50],[180,130]]}}}
{"capability":"plugin.draw_guess.preview","args":{"revision":9}}
{"capability":"plugin.draw_guess.finish","args":{"revision":9}}
{"capability":"plugin.draw_guess.picture","args":{}}
{"capability":"plugin.draw_guess.guess","args":{"revision":10,"answer":"自行车"}}
{"capability":"plugin.draw_guess.exit","args":{"revision":11}}
```

兰儿的入口发现文档及封存能力说明包含保密提醒。兰儿作为画方时，从填写题目、填写提示到绘画、等待猜题的每次响应，均提醒不在任务进度、对外可见的推理说明或聊天中公开自己的考题及尚未解锁的提示。仅通过封存接口提交，按游戏规则解锁提示、回合结束公布答案；该提醒不替代状态机已有的对手视图隔离。

以上revision只是占位示例，替换为当前值。`canvas`可在兰儿自己的绘画阶段读当前画布；`paint`接受STROKE_ADD、SHAPE_CREATE、SHAPE_DELETE、LAYER_CREATE、LAYER_SELECT，参数遵循画室相应操作。`preview`附实际图像；`picture`仅在兰儿猜题阶段附待猜图。猜题时不使用普通画室工程、足迹、私有输入框或外部日志取答案。

当兰儿准备后正在等待阿伟，返回正常成功状态 `success=true`、`status=WAITING`、`waiting_for=AWEI`、`retry_after_ms=3000`，提示“兰儿已准备，阿伟正在准备中。请等待3秒后再次查询游戏状态。”。按返回的 `next_action.capability` 等待后调用view；仍未准备则继续返回同样的等待指引。查询不修改revision，不重复ready，也不强行执行下一步。阿伟准备后返回DICE和allowed中的roll，等待字段随之消失。每次兰儿接口响应都带 `opponent_status` 和明确的下一步提示。等待阿伟准备、选择模式、掷骰、确认点数、选择先后、提交猜测时查询间隔为3秒；等待阿伟封存题目、封存提示、绘画并确认完成时为5秒。掷骰与确认点数分开提示；猜错后提示已猜错次数；同点重掷直接返回roll。兰儿有自己的allowed动作时立即操作，不附等待时间；回合结束直接进入新画方选模式。关闭的游戏不要求继续轮询。默认间隔集中在 `DEFAULT_WAIT_RETRY_SECONDS` 和 `DEFAULT_LONG_WAIT_RETRY_SECONDS`，状态机可通过 `waitRetrySeconds`、`longWaitRetrySeconds` 调整。顶层success表示接口执行成功，猜测是否正确仍看lastResult.success。

## 架构与构建

游戏核心只有内存状态机，手机和兰儿各有固定身份入口。画室提供通用互动业务binding、私有临时画布endpoint与动态表单浮窗；子插件不借用父插件能力身份，也不申请宿主能力。老菜单扩展绑定保持兼容。

云端工作流 `draw-guess-build.yml` 测试游戏、编译APK、使用现有Child Ed25519签名身份生成 `.ailx`，记录源码证明并上传。配套画室由原 `art-studio-build.yml` 测试和构建。

## 0.1.1 启用修复

修正能力命名空间：运行时要求 `plugin.${extensionId最后一段}.*`，不能直接把完整extension_id作为能力前缀。所有能力、首次进入发现文档和极简示例统一使用 `plugin.draw_guess.*`；安装身份和父插件目标保持不变。新增入口挂载回归测试，按实际运行时命名校验注册全部14个能力并验证双方准备入口。

## 0.1.2 绘画示例修正

STROKE_ADD要求params.id为唯一笔画UUID；极简示例现已携带此字段，调用时每笔换一个新的id。配套画室0.2.95修正临时画布释放时旧页面任务的取消语义和图片关联交接。

## 0.1.3 兰儿打开入口

新增无参数 `plugin.draw_guess.open`，复用子插件已有open状态机。AI发现文档的start指向open，并单独公开ready地址。入口回归用例覆盖首次打开、双方准备、重复打开保留当前状态，以及退出后通过兰儿接口重新打开和准备。子插件版本更新为0.1.3；基座和画室无需修改。

## 0.1.4 连续等待指引

默认等待查询间隔为3秒。兰儿准备后的状态查询返回等待对象、下一步view地址及明确成功标记；其他等待对方的阶段也使用同一协议。新增回归用例覆盖连续查询不改变revision、对方准备后恢复下一步、关闭时停止轮询、间隔参数调整，以及调用成功与猜题正确的区别。子插件版本和载荷applicationId同步更新；基座、画室和桥接源码无需修改。

## 0.1.5 轮流选模式与分阶段反馈

开局顺序改为准备、掷骰并确认、赢家选先画或先猜、当轮画方选模式、封存题目、提示、绘画、猜题。新增兰儿 `choose_mode` 接口，且由状态机校验当前画方身份、阶段和revision；系统出题暂未开放。每轮结束清除已选模式并交换画方，由新画方再次确认模式。等待响应根据阿伟当前操作使用3秒或5秒间隔，兰儿可行动时不返回等待指令。配套画室0.2.97支持通用面板动作的enabled字段，以显示禁用按钮。回归用例覆盖模式权限、角色轮换、不可跳过或重复选择、禁用系统模式、阶段提示、间隔与同点重掷。

## 0.1.6 出题过程保密提醒

兰儿填写题目之前及填写题目、提示期间，接口返回保密提醒；绘画和等待对手猜题期间仍保留提醒。入口发现文档与两个封存能力同步说明公开边界。提醒内容不含考题或提示值；状态机的答案与提示隔离规则保持不变。仅更新游戏子插件版本，无需再次修改画室。
