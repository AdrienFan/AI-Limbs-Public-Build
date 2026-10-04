# 部署验收

已进行源码差异、Kotlin词法括号、旧对话框入口移除、版本与JSON声明一致及能力清单静态检查。新增StudioTextInputSpecTest覆盖输入不写入或重定位修订、字符样式区间继承、默认参数隔离、UTF-16输入与空草稿、SVG明确转换及传输参数不泄漏UI字段。这些是云端回归源码，本轮未执行测试或本地编译。

部署后依序验证：

- 单击文字工具再点击画布，仅显示局部光标并打开输入法；中文组字、Emoji、换行、删除、长按选区及粘贴正确
- 双击文字工具，在统一浮窗中改变字体、字号、颜色与全部高级参数；输入期间改参数不丢正文与组字，不重置未应用设置
- 完成一段只增加一条文字历史；取消与新建空光标不增加历史；已存文字未改完成不增加历史；旧文字不与输入区重叠
- 点不同位置先提交后放下一光标；缩放、旋转、镜像、父组变换后的输入位置正确，画布其余区域可操作
- 竖排、路径、形状内、富文本与SVG来源完成后保持原排版；SVG原格式只能明确转换，缺字及无效参数仍明确拒绝
- 全屏切换和回工具箱重进时保留Host进程内草稿；重启后新建参数保留；工具切换不丢草稿
- 另一端编辑或工程切换后保留过期草稿及复制入口，不覆盖新版本；锁定文字或父组拒绝写入
- 文字已写入但显示失败时只更新显示，不重复写入；背景准备失败可重试；反复开始取消不积累Bitmap和文件句柄
- 原画布工具、SVG代码面板、动画时间轴、撤销重做、保存重开、AI的text.create/update/source保持工作

Android API依据：[EditText](https://developer.android.com/reference/android/widget/EditText)、[TextView](https://developer.android.com/reference/android/widget/TextView)、[View](https://developer.android.com/reference/android/view/View)。接口文档核对不等于手机输入验收。

[DONE] 静态检查与验收安排完成；云端及实机均待进行。
