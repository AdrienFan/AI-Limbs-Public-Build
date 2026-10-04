# 你画我猜 0.1.0

独立画室子插件，需要画室0.2.94或以上。先安装配套画室，再从“工具 → 扩展 → 添加扩展”选择签名游戏 `.ailx`；完成准入后，扩展菜单出现“你画我猜”。基座、Hub和插件中心业务没有改动。

## 第一版规则

准备页面有规则简介和大的准备圆按钮。双方准备后各掷一次骰子、各自确认；同点双方重掷，点数较大者选择先画或先猜。开始游戏后每轮由画方填写词语并确认封存，再填写两条非空提示，完成后创建1000×700白底临时画布。

绘画可使用画室工具。点“确定完成”后冻结；猜方收到图和自动字数星号提示。第一次猜错解锁提示一，第二次猜错解锁提示二，第三次猜错判失败；任意一次猜中立即成功。成功或失败均公布答案、清理画布和图片，自动交换角色进入下一轮。不做系统提词，不做计时和积分。

答案去首尾空格后精确匹配，字数按Unicode码点计数。封存词语与尚未解锁提示仅存在子插件内存；猜测接口不返回这些信息。兰儿的封存词语不进入手机对手面板；阿伟的封存词语不进入兰儿入口。外部截图、系统日志和聊天记录不属于游戏的隐藏答案边界，不应在猜题时旁查。

浮窗拖动标题移动；“−”收起成标题，“□”展开。关闭会先询问退出，退出清理临时画布和题目。正常工程指针和作品不被替换。游戏缓存会在回合结束、退出或子插件撤销时清理；没有普通工程保存或最近列表记录。进程结束不会恢复游戏或封存题目。

## 兰儿入口

所有能力属于 `plugin.art.studio.draw_guess`。`ready` 与 `view` 都返回极简规则、当前阶段、revision、身份和allowed下一步；上下文中断后先view。修改动作使用本次view返回的revision，不猜参数或直接调用普通工程绘画接口。

```json
{"capability":"plugin.art.studio.draw_guess.ready","args":{}}
{"capability":"plugin.art.studio.draw_guess.view","args":{}}
{"capability":"plugin.art.studio.draw_guess.roll","args":{"revision":2}}
{"capability":"plugin.art.studio.draw_guess.confirm_dice","args":{"revision":3}}
{"capability":"plugin.art.studio.draw_guess.choose_order","args":{"revision":5,"drawFirst":true}}
{"capability":"plugin.art.studio.draw_guess.seal_word","args":{"revision":6,"word":"自行车"}}
{"capability":"plugin.art.studio.draw_guess.seal_hints","args":{"revision":7,"hint1":"交通工具","hint2":"人力驱动"}}
{"capability":"plugin.art.studio.draw_guess.paint","args":{"revision":8,"type":"STROKE_ADD","params":{"tool":"ink","color":"#FF245364","width":6,"points":[[40,50],[180,130]]}}}
{"capability":"plugin.art.studio.draw_guess.preview","args":{"revision":9}}
{"capability":"plugin.art.studio.draw_guess.finish","args":{"revision":9}}
{"capability":"plugin.art.studio.draw_guess.picture","args":{}}
{"capability":"plugin.art.studio.draw_guess.guess","args":{"revision":10,"answer":"自行车"}}
{"capability":"plugin.art.studio.draw_guess.exit","args":{"revision":11}}
```

以上revision只是占位示例，替换为当前值。`canvas`可在兰儿自己的绘画阶段读当前画布；`paint`接受STROKE_ADD、SHAPE_CREATE、SHAPE_DELETE、LAYER_CREATE、LAYER_SELECT，参数遵循画室相应操作。`preview`附实际图像；`picture`仅在兰儿猜题阶段附待猜图。猜题时不使用普通画室工程、足迹、私有输入框或外部日志取答案。

## 架构与构建

游戏核心只有内存状态机，手机和兰儿各有固定身份入口。画室提供通用互动业务binding、私有临时画布endpoint与动态表单浮窗；子插件不借用父插件能力身份，也不申请宿主能力。老菜单扩展绑定保持兼容。

云端工作流 `draw-guess-build.yml` 测试游戏、编译APK、使用现有Child Ed25519签名身份生成 `.ailx`，记录源码证明并上传。配套画室由原 `art-studio-build.yml` 测试和构建。
