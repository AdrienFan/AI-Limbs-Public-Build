## 0.2.44 动态画笔（开发源码，尚未编译）

参照用户提供的Krita 6.0.4 `plugins/tools/tool_dyna/kis_tool_dyna.cpp`（Dyna过滤后调用Freehand）以及 `libs/ui/tool/kis_painting_information_builder.cpp` / `kis_tool_freehand_helper.cpp`（位置调整再进入共享平滑/笔刷），由本插件实现采样惯性阶段。现有Mass/Drag及默认值保持：质量 `1+159×Mass`，阻尼 `0.5×Drag²`，范围0–1。不是移植Krita引擎源码，也不新增该文件中未启用的固定笔角/动态笔宽控制。

动态画笔可选六支现有栅格笔，接入同一预设、笔尖、纹理、压力/速度/倾斜/方向曲线、流量、间距、散布、持续喷绘、加权平滑、稳定器与像素模式面板；笔粗、颜色、透明度与自由画笔共享。手机和兰儿的顺序一致：原始指针 → 惯性过滤 → 可选尺规吸附 → 共享平滑/稳定器 → 笔刷印章。尺规投影在惯性之后，避免先吸附再过滤反而偏离尺规；后续平滑作用于投影轨迹。静止喷绘采样仍保留传感器及时间并继续逐采样惯性处理。抬笔不强追原始指针；共享平滑的finish只补到惯性轨迹终点。

每笔保存原始动态输入、惯性/尺规中间点、Mass/Drag、完整笔刷与最终轨迹；重放、撤销恢复、缩略图及导出不会重复计算惯性，不随后续工具设置或尺规编辑变化。旧版无brush的动态笔触按原格式渲染。

兰儿入口：`plugin.art.studio.dyna.info`、`plugin.art.studio.dyna.stroke`；每项都有参数说明和简洁示例。原有 `stroke.add(tool=dyna)`、`assistant.stroke(tool=dyna)` 同步支持 `brushTool`、完整brush/预设与Mass/Drag。直接入口可选assistantId绑定可见启用尺规。直接轨迹为图层局部坐标，assistant.stroke输入为文档坐标；均传原始指针点。

```json
{"layerId":"PAINT_LAYER_ID","points":[[20,30,0.4,0],[120,70,0.8,16],[180,150,1,32]],"color":"#FF245364","width":6,"mass":0.5,"drag":0.15,"brushTool":"ink","brush":{"smoothing":{"mode":"weighted","window":8}},"expectedRevision":0}
```

替换当前图层ID及最新revision；沿尺规可用assistant.stroke额外传documentId/id/tool=dyna。主笔类型与brushPresetId须匹配，细节按需读取brush.info(tool=brushTool)。继续使用共享采样、印章、粒子和内存预算，超限明确拒绝。

版本0.2.44 / versionCode47 / applicationId v0244。仅做静态源码、元数据及传输完整性检查；不编译、不运行测试、不推送，实机效果等后续云编译验收。使用本插件dab-v1引擎，未兼容Krita全部引擎及.kpp/.abr资源。

## 0.2.43 多重画笔（开发源码，尚未编译）

多重画笔新增 `mirrorAngle`（-360–360度，正数逆时针）；镜像、四象限与雪花模式按旋转后的轴反射，随机平移旋转确定性偏移。旋转对称的相对角步长不随轴角变化（轴线显示旋转）；自定子画笔及间隔复制保留原布局，与Krita对应变换的语义一致。八种模式、原有基础画笔数量含义和48支总上限保留。

`brushTool` 选择自由画笔/铅笔/软笔/喷枪/橡皮擦/栅格书法笔，直接使用0.2.42已有的完整dab-v1配置与预设（笔尖、纹理、动态曲线、平滑、稳定器、像素模式）。手机主笔选择、预设面板和精确角度输入均已接上。处理顺序是主轨迹尺规吸附 → 共享平滑/稳定器 → 固定变换生成副笔；副笔不各自重新选择尺规。手机预览、历史重放、缩略图和导出使用相同保存格式。每笔保存镜像参数、完整笔刷、传感器采样和变换矩阵，不随后续工具设置变化；旧版不含brush的多重笔触保留原渲染格式。

兰儿入口：`plugin.art.studio.mirror.info` 查询模式/轴角/主笔/预算，`plugin.art.studio.mirror.stroke` 专门落笔；原有 `stroke.add(tool=mirror)` 与 `assistant.stroke(tool=mirror)` 也支持 `brushTool`、`mirrorAngle`、`brush`、`brushPresetId` 及全部旧参数。查询引擎细节用 `brush.info(tool=brushTool)`。各入口含简洁schema和示例。

```json
{"layerId":"PAINT_LAYER_ID","points":[[80,100,0.4,0],[180,180,1,100]],"color":"#FF245364","width":6,"mirrorDirection":"vertical","mirrorAngle":45,"brushTool":"ink","brush":{"smoothing":{"mode":"stabilizer","delay":10}},"expectedRevision":0}
```

图层ID和revision取当前工程；直接笔触、对称中心和子笔中心均为图层局部坐标，尺规接口的输入轨迹为文档坐标。预设类型须与brushTool匹配，实际笔粗/颜色/透明度仍由本次笔触指定。全部副笔合计最多60000印章/240000粒子，超限明确拒绝；不会悄悄减少画笔或降低采样。副笔共用一次生成的笔尖与纹理蒙版，避免按笔数重复构建资源。

版本0.2.43 / versionCode46 / applicationId v0243。仅做静态源码、元数据与传输完整性检查；不编译、不运行测试、不推送，实机绘画验收留到后续云构建。引擎为本插件dab-v1，尚未兼容Krita全部引擎及.kpp/.abr资源。

## 0.2.42 栅格笔刷能力（开发源码，尚未编译）

自由画笔、铅笔、软笔、喷枪、橡皮擦、栅格书法笔共用版本化 `dab-v1` 印章引擎。参数面板支持内置/自定义预设保存和删除、圆/椭圆/方形/图像笔尖、纸粒/帆布/棋盘/图像纹理、流量/间距/散布/数量/角度抖动/持续喷绘。四个动态通道（笔径、流量、间距、角度）可以绑定压力、速度、倾斜、笔方向、轨迹方向或确定性随机值；每条曲线2–16个点，支持编辑和预览。加权平滑、拖尾稳定器和像素完美模式在保存前处理一次；像素模式对齐像素中心、去掉折角冗余像素并关闭抗锯齿。

手机和AI使用相同的采样处理与渲染入口；尺规吸附也接入。实际触笔压力/倾斜/方向和历史采样传入引擎，静止喷绘按时间生成印章。无硬件倾斜输入为0；Android方向轴不是所有设备都具备的笔身旋转传感器。每笔保存完整配置、原始/最终轨迹和随机种子，修改或删除预设不改变历史作品；新笔尖/纹理资源使用工程既有asset打包和重映射机制。旧工程未带brush的笔触继续按原始格式渲染。

兰儿入口：`brush.info`、`brush.presets`、`brush.preset.get/save/delete`、`brush.resources`、`brush.resource.import`。前缀均为 `plugin.art.studio.`；每个入口的schema和suggestedParamsJson含简短说明与示例。笔触通过原有 `stroke.add` 或 `assistant.stroke` 的 `brush` 部分配置、`brushPresetId` 与可选 `brushSeed` 设置。

```json
{"layerId":"PAINT_LAYER_ID","tool":"ink","points":[[10,10,0.3,0],[100,80,1,100]],"color":"#FF245364","width":6,"brush":{"smoothing":{"mode":"stabilizer","delay":10},"dynamics":{"size":{"curve":[[0,0.15],[1,1]]}}},"expectedRevision":0}
```

先读取当前图层ID和revision替换模板。完整配置结构和枚举读取 `brush.info`；预设列表只返回摘要，按需get取完整参数，减少上下文。资源图像最长边512px、输入8MiB、最多128个；自定义预设最多128个。单笔最多10000采样、60000印章/240000粒子、3分钟，超限明确拒绝，不稀疏或改写用户输入。像素线稿建议1px方笔尖、流量1、关闭笔径动态和纹理（内置像素铅笔）。

本版本实现的是本插件的可配置笔刷引擎与预设，不兼容Krita `.kpp`/`.abr` 文件、Krita全部引擎及资源包。版本已递增为0.2.42/versionCode45/applicationId v0242。仅做静态源码和元数据检查；没有本地/云端编译、运行测试或推送，实机效果待后续云编译验收。

# AI Limbs 画室（0.2.41 源码；能力示例与简短参数说明）

画室是独立的 android_inprocess 插件。页面与兰儿能力共用 ArtStore 工程目录、文件锁和当前工程指针；本次文件菜单迭代没有改动基座，也没有改变 .ailart 的格式号。UI 创建或导入的新工程记录 createdBy=AWEI，兰儿通过能力创建、导入、模板创建、另存为或复制的新工程记录 createdBy=LANER；画布编辑历史仍以 AWEI / LANER 标注。

## 未发布：文字工具入口整理

基于 0.2.41 继续迭代。折叠工具侧栏移除独立的高级文字占位和“可使用”“待实现”分类标签，同时去掉原分类分隔线。现有文字工具保持原位置与编辑行为；双击打开参数浮窗后，基础编辑入口下方统一列出 SVG 排版、富文本、SVG 源码编辑，均明确标示为尚未实现且不可点击。toolbox.catalog 将这些项目放在文字工具的 advancedOptions 内，不再作为独立工具返回。Android 10/11 的基础文字平台要求仍保留。

本次仅修改画室插件的工具清单、参数浮窗与相关文档，没有修改文字渲染、工程格式、绘画执行器或宿主。暂不推送、不编译、不安装；后续功能一起迭代后再发布版本并做实机验收。

## 文件菜单

菜单行为参照手机里解压的 Krita 6.0.4 源码 libs/ui/KisMainWindow.cpp：slotFileOpen、slotFileOpenRecent、slotFileSave、slotFileSaveAs、slotExportFile、slotExportAdvance、slotFileCloseAll、slotFileQuit，以及官方 5.3 文件菜单说明。菜单结构采用 Krita 的顺序，内部实现基于本画室的文档格式与 Android 文件选择器。

| 菜单项 | 页面执行效果 | 兰儿能力 |
| --- | --- | --- |
| 新建 | 设置像素尺寸、名称、透明背景并创建工程 | document.create |
| 打开、打开最近图像 | 打开本画室工程，或选手机上的 .ailart / PNG / JPEG；最近图像按打开顺序列出 | document.open、document.import、document.open_image、document.recent、document.list |
| 保存 | 将工程写入私有 .ailart；关联了手机文档时，同步覆盖该文档。同步失败时保留私有稿，标为待同步 | document.save；结果包含 externalUri |
| 另存为 | 先选手机保存位置，建立新 ID 的工程并切换过去；取消选择则原工程不变 | document.save_as；返回私有 .ailart 路径 |
| 会话管理 | 命名、查看、恢复或删除当前单工程会话 | session.save、session.list、session.open、session.delete |
| 导入－打开为无标题图像 | 把外部图像或工程作为独立的未命名工程打开，原工程保留 | document.open_image、document.import、document.rename |
| 导出、导出－更多选项 | PNG / JPEG；高级导出支持裁切和调整输出像素尺寸，不改原工程 | export.png、export.jpeg；可选 x、y、cropWidth、cropHeight、width、height |
| 保存增量版本 | 创建并切换到编号为 _v001 等的新工程；旧版本保留 | document.incremental_version |
| 保存增量备份 | 有上次存档时复制到 backups/ 的编号文件，再保存当前工程 | document.incremental_backup |
| 新建模板－基于当前图像 | 把完整分层画布保存为画室模板，可从模板创建新工程 | template.create、template.list、template.open |
| 新建图像－复制当前图像 | 用当前合成状态创建独立的未保存工程，原工程不变 | document.duplicate |
| 图像信息 | 查看工程名称、像素尺寸、背景、图层数、修订号与保存状态 | document.info、layer.list |
| 关闭、退出 | 未保存时询问保存、舍弃或取消；退出仅返回宿主上一页，不退出 AI Limbs | document.close、document.discard_and_close |

“全部关闭”保持不可用：画室目前只有一个活动视图；保存的其他工程可以从“打开”访问。逐帧动画导入和动画导出保持不可用：工程没有时间轴与帧数据，不能将静态图层冒充为 Krita 动画。会话管理目前也只保存一个活动工程，不具备 Krita 多文档窗口会话能力。模板使用本画室 .ailart 结构，无法读写 Krita .kra 或 .kpl。

## 编辑菜单（本轮）

菜单顺序和动作入口对照手机上 Krita 6.0.4 的 krita/kritamenu.action、libs/ui/kis_selection_manager.cc；画室使用现有 .ailart 数据模型实现，未引入 Krita 内核或 .kra 支持。

| 项目 | 画室行为 | 兰儿入口 |
| --- | --- | --- |
| 撤销、重做 | 原有 REVERT / RESTORE 操作日志；菜单根据真实撤销栈启用 | history.undo、history.redo |
| 剪切、复制 | 截取活动绘画或图像图层的矩形、椭圆、多边形或自由套索选区像素；未建选区时处理整张画布；剪切追加可撤销的清除操作 | edit.cut、edit.copy |
| 合并复制 | 按当前可见图层和画布背景截取合成像素 | edit.copy_merged |
| 粘贴、粘贴到光标处、粘贴到活动图层 | 内部剪贴板的 PNG 居中粘贴为独立图像层；光标粘贴以最近一次画布触点居中；或按时间顺序写入当前图层的像素编辑记录 | edit.paste、edit.paste_at（画布坐标）、edit.paste_into |
| 粘贴为新图像 | 用内部剪贴板的原始像素尺寸创建独立工程；边长支持 1–16384 px，按当前处理预算预检；需要缩小时先请求确认 | edit.paste_new |
| 清除、填充前景色、填充背景色 | 作用于活动图层选区；每次操作按顺序保存，之后的新笔画绘在上方。背景色由菜单对话框指定 | edit.clear、edit.fill_foreground、edit.fill_background |
| 剪贴板信息 | 查询可用像素尺寸及剪贴来源 | edit.clipboard_info |

剪贴板存放在插件私有目录，由阿伟和兰儿共用；目前没有与 Android 系统剪贴板互通。剪切、清除、填充和粘贴到活动层只对根层级、未平移/旋转/缩放且可见未锁定的绘画层或图像层启用；复制允许可见根图层经过变换或锁定，合并复制读取可见合成画布。选区支持矩形、椭圆、多边形与自由套索，越过画布的部分裁到画布范围；非矩形选区的复制、剪切、清除、填充与连通填充均按形状边界裁剪。工程 PNG 单资源限 64 MiB，图片原始输入及像素剪贴板仍限 8 MiB；现阶段不支持群组、复杂变换、磁性套索或相似色等选区；非矩形选区的旋转尚不可用。自由套索超过 2048 个采样点时会稀疏采样，以限制工程数据与绘制开销。剪切、清除、填充和粘贴会记录操作历史、进入工程归档，导入归档时重新映射引用资源。

以下 Krita 菜单项在界面显示为不可用：锐利剪切/复制、图层样式复制与粘贴、参考图像粘贴、矢量形状样式粘贴、填充图案及额外属性、选中形状与选区描边、拾取屏幕颜色。当前工程没有对应的锐利边缘剪辑、图层效果、参考图、矢量形状、图案资源、描边或屏幕采样数据与授权链路；不把普通剪贴和填充操作伪装成这些动作。

## 保存与工程数据

草稿位于 drafts/<id>.json，显式保存位于 documents/<id>.ailart，内含 project.json 和图层引用的 PNG 资源；包括由“复制当前图像”生成并写进 base 的图片图层。保存摘要位于 documents/<id>.sha256，用于判断已有存档是否仍对应当前操作。外部文档 URI 存在 external-links.json；文件选择器返回可持久写入权限时才会关联。私有副本已写入但外部同步失败，会显示待同步状态，菜单“保存”可重试。“另存为”在用户选定位置且写入成功后才切换活动工程；新工程有独立 ID 和操作历史。

最近打开的工程 ID 按顺序写入 recent.json。关闭没有改动的工程仅取消当前指针；关闭时明确选择舍弃会恢复上次保存的私有存档，未曾保存的草稿则删除。外部保存待同步时，界面禁止“舍弃修改”，避免把私有唯一有效稿误当成已同步内容丢掉。增量备份和模板存放在插件私有目录；它们不会自动出现于系统相册或手机 Download。

AI 能力直接操作相同的私有工程；对带外部 URI 的工程，兰儿的 document.save 会写私有存档并留下“外部待同步”状态，阿伟可以打开页面执行“保存”完成 Android URI 写入。兰儿拿到导出结果的私有路径，不会自动将文件复制到系统相册。需要防止双方同时编辑时，对结构化图层编辑使用 expectedRevision。

## 画布与图层

新建支持 1–16384 像素边长，创建前检查当前进程工作预算、工程名称及透明背景；当前工程只有 RGB 8 位与一个初始绘画图层，不存储 ICC、DPI 或 Krita 原生色彩模型。画布底栏左侧的 ↩️/↪️ 调用与编辑菜单、兰儿能力相同的撤销/重做历史；没有可撤销或可重做操作时按钮置灰，忙碌时暂停操作。右侧“居中”只重置缩放、旋转、平移；旁边“全屏”通过 host.ui.presentation@1 控制页面；宿主未确认模式请求时，对话框保留并显示返回的错误。左右抽屉默认收起。左侧工具栏占可用宽度的四分之一且最大 96 dp，右侧面板保留原本较宽的布局。两侧各有图钉按钮：固定后点击画布不会收起该栏，画布视口避开固定栏；取消固定会立即收起，点击侧栏把手也会取消固定并收起。未固定的侧栏仍可点画布区域收起，点击侧栏内部控件不会误触画布；固定右栏或同时打开两侧时，右栏按可用宽度收窄，给画布预留至少 112 dp 的目标空间。右侧为多功能拾色器、图层、笔刷预设和足迹四个手风琴面板，保持一次展开一个面板。标题单击展开、双击折叠，双击窗口为 300 ms；折叠保留标题。总标题“视图列表”双击折叠全部，单击恢复折叠前的活动面板；原本全折叠则保持全折叠。长按标题可拖动排序，隐藏面板的排序位置保留。× 确认后隐藏标题和内容，提示可在「设置 → 停靠面板」重新显示；勾选“以后关闭面板时不再提示”只在确认关闭时保存，取消不保存。提示偏好全局生效，可在「设置 → 配置画室…」重新开启。停靠菜单带勾选，取消勾选直接隐藏。左抽屉只列出已经接入画布行为的工具；点击图钉可固定抽屉，普通工具选择后可继续作画。菜单栏从图层到帮助的七栏现已接通共享菜单清单，可用状态与能力入口见下文。

## 左侧工具栏（开发中）

对照 Krita 6.0.4 的 plugins/tools/basictools/default_tools.cc、plugins/tools/selectiontools 与各工具插件的注册入口。左栏在窄抽屉内用两列图标呈现；图标设有中文无障碍标签。当前工具和项目存储、撤销/重做、导出以及兰儿的能力共用同一套操作日志，不能直接运行 Krita 的 Qt/C++ 工具实现。

| 已接通的工具 | 操作 | 兰儿入口 |
| --- | --- | --- |
| 自由画笔、铅笔、软笔、喷枪、橡皮擦 | 在绘画图层记录笔画；笔压供基本笔宽使用 | stroke.add，tool=ink/pencil/soft/spray/eraser |
| 多重画笔 | 拖动画笔生成原笔画和镜像笔画；左侧「多重画笔选项」切换左右、上下、四象限镜像，或 2–12 支旋转对称、4 倍画笔数的雪花对称，以及按固定随机种子重现的平移画笔、自定位置的子画笔、横纵间隔复制画笔（最多 48 支），对称模式显示轴线；左栏「移动中心」可点画布设置中心，中心随图层变换 | stroke.add，tool=mirror，可选 mirrorDirection=vertical/horizontal/quad/radial/snowflake/translate/copytranslate/interval、mirrorCount、mirrorRadius、mirrorSeed、mirrorCenters、mirrorIntervalX/Y、axisX、axisY |
| 动态画笔 | 左侧「动态选项」调惯性和阻力，按 Krita 动态工具的质量与阻力模型过滤采样轨迹，预览和重放共用同一处理 | stroke.add，tool=dyna，可选 mass、drag（0–1） |
| 斜头书法笔（栅格） | 固定角度的宽笔尖沿触笔轨迹生成有笔压变化的带状笔画；保存为绘画层笔画 | stroke.add，tool=calligraphy，可选 nibAngle（0–180°） |
| 直线、矩形、椭圆 | 拖动生成线框；矩形、椭圆可用前景色填充；以两端点记录到当前绘画层 | stroke.add，tool=line/rectangle/ellipse，闭合形状可选 fillShape |
| 多边形、折线 | 逐点点击，至少 3 / 2 个顶点后双击最后一个点结束；多边形闭合后可用前景色填充 | stroke.add，tool=polygon/polyline，多边形可选 fillShape |
| 可编辑贝塞尔路径 | 绘制模式点按放节点、拖动出柄，编辑模式修改路径节点与控制柄；角点、平滑、对称，插入删除和线曲转换 | path.create/nodes/edit |
| 矢量徒手路径 | 在矢量层按住拖动，抬手生成原始折线、拟合曲线或直线化路径；可闭合填色，之后用形状选择修改整体 | shape.freehand；shape.create 支持 path 几何 |
| 基础矢量形状与形状选择 | 在矢量层使用直线、矩形、椭圆和多边形工具；选择工具支持点选、方向框选、多选、移动、缩放与旋转 | layer.vector、shape.create/list/hit/box/select/transform/style/delete |
| 三次贝塞尔曲线 | 默认依次点起点、两处控制点与终点，第四点保存；打开连续曲线模式后每三个点接一段，完成的端点上双击保存，未完成段只预览 | stroke.add，tool=bezier，points 为 1+3n 点（4–1024 点） |
| 颜色取样 | 点击合成画布更新当前笔色；半径可设为 0–32 px，圆形范围内的像素按透明度混合；可在合成画布与可见根绘画／图像层之间切换，还可选取样色与当前色的混合比例 | color.sample（画布像素坐标，可选 radius、blend、sampleMerged；blend<100 需 baseColor，sampleMerged=false 需 layerId） |
| 连续区域填充／擦除 | 左侧「填充选项」可设容差（0–100）、参考当前图层／所有可见图层及擦除模式；遵循当前选区，使用 PNG 像素掩码可撤销地修改当前可编辑根图层 | fill.contiguous（x/y/color，可选 tolerance、referenceAllLayers、erase、expectedRevision） |
| 线性、径向、角度渐变 | 拖动定义渐变距离；终点可设为透明或指定颜色，可切换线性／径向／角度和反向，在当前绘画层记录渐变事件 | stroke.add，tool=gradient，可选 gradientMode=linear/radial/angular、gradientReverse、gradientEndColor=#AARRGGBB |
| 矩形、椭圆、多边形、自由套索选区 | 拖动创建矩形/椭圆/套索；逐点点击、双击最后一点结束多边形；复制/清除/填充沿真实边界裁剪 | selection.create、selection.ellipse、selection.polygon、selection.freehand |
| 裁剪画布 | 拖动矩形裁剪；裁到画布范围且边长至少 64 px，保留操作历史 | canvas.crop |
| 移动与变换图层、平移画布 | 移动和变换工具都可拖动当前图层；变换工具的「变换参数」可设置位置、缩放和旋转，打开时读取当前图层值；有选区时拖动沿用当前选区移动操作 | transform.move/scale/rotate、selection.edit；视图平移只属于当前页面 |
| 测量距离、缩放画布 | 拖动可读两点距离与角度；点击缩放画布视图，双指缩放沿用已有手势 | canvas.measure；视图缩放只属于当前页面 |

## 工具参数与未实现选项

左侧工具栏统一呈现工具图标，不显示“可使用”或“待实现”分类标题。基础文字只有一个工具入口；完整 SVG 排版、富文本与 SVG 源码编辑属于该文字工具的高级参数，集中在参数浮窗中以不可点击的待实现项展示，不另占工具位。设备低于 Android 12 时，基础文字入口仍明确显示系统版本要求且不允许绘画。

清单单一来源为 ArtToolCatalog.kt。兰儿读取 toolbox.catalog 可得到各项 id、label、implemented 与 status；文字工具还返回 advancedOptions，其中 implemented=false、status=planned 表示未来选项，不是已实现的执行能力。现有工具的 status=basic 只表示本画室已有可用入口，不表示已达到 Krita 完整行为。

这仅是 Krita 左侧工具的第一批真实操作：尚缺颜色标签图层参考及边界填充、渐变预设与色彩空间、多节点及多子路径编辑、书法笔矢量轮廓及速度调角、高级矢量路径、完整 SVG 文字排版、高级变换、参考图像、辅助尺规、蒙版及磁性套索、相似色等其他选区。它们各自需要补画笔引擎、矢量对象、像素选区蒙版或相应的资源类型；不得将现有笔画、矩形选区或移动操作改名冒充。连续区域填充默认按 RGBA 像素完全匹配，容差 0–100 映射到每个通道 0–255 的最大差值；可参考所有可见图层，但仍只写当前图层；正常填色仍拒绝完全透明的颜色；擦除模式用独立掩码清除图层像素；选择非根图层、隐藏/锁定或已变换的图层时也会拒绝，避免编辑到错误像素。渐变提供前景色到透明或指定终点色的线性／径向／角度基础模式，不具备 Krita 的完整预设和混合选项。动态画笔当前只移入质量／阻力轨迹过滤，Krita 的固定角度与速度相关笔宽尚未移入；栅格书法笔不生成 Krita 的矢量轮廓。这些工具在本画室的数据模型中实现；完整 Krita 行为与手机端交互需按各项边界验收。

## 右侧手风琴布局

右侧栏顶部的图钉位于左侧，「视图列表」居中。折叠标题与展开面板同在可滚动列表里：切换面板时滚动到当前标题，上方折叠标题可向上划出视口，下方折叠标题排列在当前内容之后、继续向下划动时才出现；两侧标题均不预占当前内容的显示空间。当前标题停留在面板顶部，内容占用标题下方的可见高度。足迹自身的历史列表仍可独立滚动，面板标题仍可长按拖动排序。

## 右侧足迹

右侧折叠栏新增「足迹」。每次落笔保存为一条 STROKE_ADD 操作，列表按当前可到达的历史状态展示初始画布、每一笔及其他图层／选区操作；行内显示操作名称、笔画颜色、阿伟或兰儿和时间。当前状态高亮，已撤销但可重做的后续状态灰显。点任意行会一次性提交所需 REVERT / RESTORE 记录，画布、图层和撤销／重做按钮同步更新；再次从旧状态落笔后，可重做分支不再出现在当前足迹中。选择性撤销造成的非线性顺序会标成「重新应用」，避免误认作原时间点的画面。跳转带 expectedRevision 校验，另一端更新工程后会拒绝过期操作。拖动右侧面板标题仍可调整四块面板顺序，原有保存的面板顺序自动包含新增足迹。

兰儿用 history.timeline 获取 timeline、position 和 revision，调用 history.goto(id, expectedRevision) 跳转；初始画布的 id 为空字符串。history.list 保留原始操作日志，history.undo／history.redo 保持原有行为。足迹读取共享工程日志，保存工程后仍能看到历史；这是画室的工程行为，与 Photoshop 关闭文档会清空会话历史不同。当前没有 Photoshop 的快照、历史画笔或删除历史状态功能；缩略图暂未加入，以免每一笔都额外保存整幅画布。实现交互参考 Adobe Photoshop「History panel settings」及「Manage image states」的状态列表和点击回到旧状态。

右侧图层管理参考 Krita 6.0.4 的 plugins/dockers/layerdocker/WdgLayerBox.ui、LayerBox.cpp 和 NodeDelegate.cpp，提供名称筛选、缩略图、显隐、锁定、混合模式、不透明度、绘画层与组、复制、同级排序、属性和删除。兰儿对应使用 layer.list、layer.search、layer.create、layer.group、layer.select、layer.set_visibility、layer.set_lock、layer.set_blend、layer.set_opacity、layer.rename、layer.copy、layer.move_up、layer.move_down、layer.properties 与 layer.delete。底到顶合成；图层背景不是可编辑图层。笔刷仍是基础画笔与喷枪，4K 多层画布可能消耗较多内存。

源码对照入口：https://invent.kde.org/graphics/krita/-/blob/master/libs/ui/KisMainWindow.cpp
Krita 文件菜单说明：https://docs.krita.org/en/reference_manual/main_menu/file_menu.html

## 视图菜单（Krita 6.0.4 对照）

依据手机上的 Krita 6.0.4 源码 `krita/krita5.xmlgui` 的 View 顺序，以及 `libs/ui/KisViewManager.cpp` 中的操作注册。本菜单仍保留画室顶部横向菜单栏；「隐藏面板模式」只隐藏画布两侧工具栏和底栏，菜单本身保留为退出入口。显示选项只影响画布视图，不写入 .ailart 工程或图层像素。

| 项目 | 画室状态与行为 |
| --- | --- |
| 隐藏面板模式、全屏模式、显示状态栏 | 可用。隐藏面板后用同一菜单恢复；全屏调用宿主页面模式，仍可选横屏或竖屏，只有宿主确认后才标记选中；状态栏控制画布底部操作条。 |
| 缩放、旋转、镜像 | 子菜单可用：放大、缩小、100%、适合窗口／宽度／高度、左右各旋转 15°、重置旋转、镜像画布、重置显示。画布镜像同样用于触摸坐标反算，不翻转保存的像素。 |
| 显示网格、显示像素网格 | 可用。网格按当前画布每 64 像素绘制；像素网格在单个图像像素达到屏幕 8 像素时显示。均为显示叠层，不参加导出。 |
| 刷新画布 | 可用，重新读取和绘制当前工程。 |
| 独立画布窗口、四方连续显示及方向、快速预渲染、色彩校样、色域警告、打印大小 | 灰色。当前没有多窗口、平铺视图、细节层级、ICC 校样或物理 DPI 支持。 |
| 围绕光标／画布镜像、标尺及游标、参考线及锁定、吸附全部子项、辅助尺及预览、参考图像、色板操作菜单 | 灰色。需要各自的锚点变换、标尺与参考线数据、吸附引擎、辅助对象或色板模块。 |

兰儿可用 `view.state` 读当前状态；`view.set(option, enabled)` 控制 panelsHidden、statusBarVisible、gridVisible、pixelGridVisible；`view.command(command)` 逐条执行缩放、旋转、镜像和刷新，画布未打开时明确报错；`view.presentation(mode)` 请求宿主确认 normal、fullscreen_portrait、fullscreen_landscape。灰色项目没有执行能力入口。连续命令使用事件队列，不会把两次放大合并成一次。

## 图像菜单（Krita 6.0.4 对照）

按手机所示菜单顺序与 Krita 的 `krita/krita5.xmlgui` Image 节点列出项目。此阶段仍以画室自己的结构化工程为数据模型，灰色项目不会误把预览变换当成像素/图层变换。

| 项目 | 画室行为 |
| --- | --- |
| 图像属性 | 可用，展示当前名称、像素尺寸、图层数、RGB 8 位、背景、修订及保存状态。兰儿用已有 `document.info` 读取同一工程快照。 |
| 图像背景色与透明度 | 可用，输入 `#AARRGGBB` 或切换完全透明/不透明。写入 `IMAGE_BACKGROUND` 操作，参与撤销、保存与渲染；兰儿用 `image.set_background(color)`。 |
| 更改画布大小 | 可用，设置 1–16384 px 宽高，写入前检查处理预算和旧图像左上角在新画布中的偏移；不重采样图层，内容按新边界裁切。写入 `CANVAS_RESIZE` 操作；兰儿用 `image.resize_canvas(width,height,offsetX?,offsetY?)`。 |
| 裁切至选区大小 | 有选区且与画布相交的边界宽高均至少 64 px 时可用。按选区的边界矩形裁切，清除选区并写入可撤销的 CROP 操作；兰儿用 `image.crop_to_selection`。 |
| 转换图像色彩空间、裁切至图像／当前图层、清理未使用数据 | 灰色。需要色彩管理、图层内容边界或资产回收语义。 |
| 旋转图像子菜单、斜切、水平／垂直翻转、缩放图像大小、偏移图像 | 灰色。需对结构化笔画、图层变换、图片资产和后续编辑坐标一致地变换。 |
| 切割图像、小波分解、分离图像通道 | 灰色。需要分片、滤波器或通道工程模型。 |

这里的“更改画布大小”对应 Krita 的 Canvas Size，不等于“缩放图像大小”（像素重采样）。操作记录保留了阿伟和兰儿各自的 actor。


## 完整剩余菜单（0.2.14）

本轮覆盖图层、选择、滤镜、工具、设置、窗口、帮助七栏。结构和动作身份对照手机源码
`/storage/emulated/0/Download/krita-6.0.4-source.tar/krita-6.0.4-source/krita-6.0.4/` 的
`krita/krita5.xmlgui`、各插件 action 定义、`libs/ui/kis_node_manager.cpp`、`KisMainWindow.cpp` 和滤镜注册。
前四栏沿用现有实现。`ArtStudioMenuCatalog.kt` 是本轮七栏的唯一清单：每项包含源动作 ID、参数、实现状态与灰色原因。
未接入的项不会拥有假执行器；需要当前工程、图层、剪贴板或选区的动作还会按实时状态灰显。

| 栏目 | 已接通的行为 | 主要灰色边界 |
| --- | --- | --- |
| 图层 | 图层树剪切/复制/粘贴、新建绘画层与组、复制、选区到新层、从可见层新建、导入、图层/组导出、转换为绘画层、快速分组/取消分组、旋转/偏移、基础翻转、向下合并/平整/合并画布、直方图 | 实时克隆、矢量、蒙版、填充/文件层、动画、ICC、多选、复杂变形、透明度拆分和图层样式 |
| 选择 | 全选/取消/重新选择、尺寸/位置编辑、矩形扩大/缩小、显示选区 | 反选、像素选区蒙版、羽化/平滑、颜色与不透明度选区、矢量转换 |
| 滤镜 | 反相、六种去色算法、阈值、RGB8 色调分离、最大/最小通道、重置透明像素、再次应用及重新配置 | Lab 自动对比度、曲线、ICC、卷积/模糊/艺术算法及预设引擎、G’MIC |
| 工具 | 完整脚本菜单位置 | 当前未接入脚本执行器/权限链路，脚本入口灰色 |
| 设置 | 基础默认画笔宽度/不透明度、面板开关和定位、重置基础配置 | 资源包、主题/样式、工具栏重排、多语言、作者资料、支付订阅 |
| 窗口 | 当前活动画布信息 | 多窗口新建、平铺、层叠和窗口切换 |
| 帮助 | 画室手册、说明、提示、实际系统/问题报告信息、关于画室 | 问题提交、日志归档、ICC 报告与 KDE 运行时 |

### 协作入口

兰儿先调用 `plugin.art.studio.menu.catalog`，读取真实动作 ID、`parameters`、`enabled`、
`unavailableReason`、`documentId` 和 `revision`；再用 `plugin.art.studio.menu.execute` 执行。
`documentWrite=true` 的项必须同时携带工程 ID 和修订号。例：

```json
{
  "action": "filter.threshold",
  "parameters": { "threshold": 128 },
  "documentId": "从 menu.catalog 复制真实工程 ID",
  "expectedRevision": 12
}
```

手机参数对话框和兰儿能力都调用 `ArtStore.executeMenu`；写入前持有同一文件锁，
验证工程 ID 和修订号，执行者仍记录 `AWEI` 或 `LANER`。关闭并打开另一个同修订工程也会拒绝过期操作。
图层结构/像素组合变更保存成一个可撤销事件；选择性撤销若破坏合并、滤镜的来源画面，会明确拒绝，
可按足迹顺序撤销。文件归档收集初始图层、当前层、已撤销历史和剪贴图层树里引用的 PNG；导入时统一映射资源 ID。

图层剪贴板在插件私有 `layer-clipboard.json`，保留根图层或根组的结构、属性、像素编辑与子层；
与编辑菜单的像素剪贴板分开。选区到新层共用像素提取，剪切和新建层在一个事件里提交。
保存图层使用系统 PNG 保存选择器，导出组使用系统目录选择器；兰儿得到私有导出路径列表。
能力入口的导入参数为 PNG/JPEG base64，手机入口使用系统文件选择器，二者共用相同数据处理。

### 当前实现限制

- 本工程仍为画室自己的 RGB 8 位模型；尺寸规则见下方图片预算说明，没有引入 Krita/Qt 内核或 .kra。
- 向下合并限定可见、未锁定、正常混合的相邻根图层/组；子树包含隐藏或锁定层时不可合并。
  平整/转换限定可见未锁定根树。取消分组只允许无变换、正常混合、100% 不透明的可见未锁定组。
- 像素合并、滤镜、导出在当前画布范围内处理。合并画布会移除隐藏层并将背景烘焙进单层，但历史中仍保留来源。
- 滤镜只写可见、未锁定、无变换的根像素层，遵循当前矩形/椭圆/多边形选区。
  保留层不透明度和混合模式；去色默认明度，可选 BT.709、BT.601、平均、最小和最大。
  阈值使用 RGB8 亮度并保留透明度。色调分离在 sRGB8 模型中使用 16 位整数步长量化，包含透明度通道，
  不宣称支持 Krita 的任意 ICC 颜色转换。Krita 自动对比度需要 Lab16，因此保留灰色。
- 当前根图层旋转和所有层旋转围绕画布中心；所有层只变换根节点一次，子组随父组运动。
  基础翻转在无选区、无变换的可编辑根像素层上执行；整体翻转要求所有层均满足此条件。
- 选区尺寸和位置保留原形状；扩大/缩小仅支持矩形，不冒充像素蒙版形态学。
- 设置只配置画室的基础默认笔宽/不透明度，不冒充 Krita 完整配置页。
  面板请求保存在插件私有文件中，由打开的画室页面消费并确认；能力返回 `accepted` 表示提交。
  作者身份固定 AWEI/LANER。重置仅影响这些基础配置，保留工程和足迹。
- 当前单画布不伪装多窗口；工具菜单脚本灰色。关于页说明画室自身版本与 Krita 参考关系。

本轮只更改画室插件、它的声明与相称的校验脚本；宿主权限仍为既有 `host.ui.presentation@1`，没有修改基座或引入插件特判。

## 默认保存目录（0.2.15 已部署）

0.2.15 基于 0.2.14 迭代并已部署。保留插件内默认位置；在“设置 → 默认保存目录…”填写有文件访问权限的绝对目录路径，留空可显式恢复插件内默认位置。当前入口是路径输入，不是 Android 文档树 URI 选择器；不接受 content:// 地址。目录先经过真实写入与删除验证，失败时原配置不变。后续目录被删除或权限撤销时，保存明确报错，不改用其他位置。

配置保存在插件的 save-directory.json，两位协作者共用。所选根目录下 documents/ 保存新工程，exports/ 保存 PNG/JPEG 和图层/组导出，backups/ 保存增量备份。已成功保存的工程路径记录在 document-locations.json；改默认目录后仍保存到原位置。0.2.14 的旧工程通过既有 documents/ 档案与摘要识别，格式不变，也不自动搬迁。

草稿、图片资产、修订摘要、剪贴板、模板、会话与足迹保留在插件工作区，不随默认输出目录迁移。重置基础画笔配置不更改作品目录。

选择自定义目录后，手机导出、图层/组导出和另存为可直接使用该目录。普通和高级导出、另存为仍可勾选“本次选择其他保存位置”；没有设置自定义目录时沿用系统保存选择器。普通和高级导出选择器只使用临时缓存文件，成功、失败或取消后均清理，不累积永久私有导出副本。

兰儿用 plugin.art.studio.storage.settings 读取实际目录；用 plugin.art.studio.storage.set_directory(directory) 更改，directory 为空字符串时显式恢复插件内位置。同一设置也可经 menu.catalog / menu.execute 的 art.storage_directory 动作调用。document.save、document.save_as、export.png、export.jpeg 及菜单导出共用相同路径规则。

保持现有 host.ui.presentation@1 权限声明；不修改基座、Runtime 或宿主源语。目录可用性受 Android 文件权限约束，没有权限的路径不会被视为可用。

0.2.15 已完成云端编译与安装；实机已确认目录切换、不可写路径拒绝、已有工程原位保存及恢复原设置。系统保存选择器的成功/取消清理仍需继续人工验证。0.2.16 本轮仅保存下述空画布修复源码，尚未推送、编译或安装。

### 成功变更后的画布缩略图（0.2.15 已部署；空画布修复见下文）

修改画布、图层、笔画、选区、滤镜或历史的工具，以及新建/打开/切换/关闭工程的工具，在原返回字段之外自动附加 thumbnail 元数据和 mcp_content 图片块。设置保存目录、只读查询、保存及导出等不会改变画布的操作不附图。无需另外调用缩略图工具。缩略图为长边 256 的 JPEG，保留比例、工程编号、revision 和源画布尺寸；透明区域以白色衬底显示，元数据明确说明。关闭后返回标明 empty=true 的无活动画布图。

操作和回执在同一跨进程锁内完成，局部方法重入同一把锁。预览只在内存生成，不写保存目录、不追加操作日志。操作提交后如果预览失败，thumbnail.status=error、operationApplied=true，并保留原操作编号与版本；不能因缺图重放已完成笔画。操作自身失败则不生成成功缩略图。

细节读取使用 plugin.art.studio.canvas.region：x、y、width、height 是画布像素坐标，maxEdge 默认 512、范围 64–1024。返回 regionPreview 的坐标、放大比例、文档与版本以及图片块，越界明确拒绝。手机仍使用同一合成器显示实时画布，通过原有缩放/平移检查细节。

RDC 接收端开发版 1.2.12 将 mcp_content 提升为原生 MCP image 内容，不把编码塞入文本分页。SentinelX 接收端开发版 0.1.8 把图块放在同一次 exec response 的 mcp_content，与原文本分页分开；当前云端 exec 本身按文字包装，调用方必须直接呈现图块。文字 JSON 或图片路径不等于模型已看到图片。SentinelX 小图片内联上限 96 KiB，整份控制回复限制 120 KiB；较大附件在同次回复中给出二进制媒体句柄，通过官方 sentinel_read_media 传送原图字节。调用方按 delivery 自动呈现，不能重新执行绘画操作来取图。

0.2.15 与 SentinelX 0.1.8 已编译安装并完成缩略图、局部图和原生二进制媒体传输验收；RDC 1.2.12 图片适配仍为开发源码，未在当前设备验证。绘制后渲染预览会增加合成开销，性能仍需随日常使用评估。

局部回读可传 documentId 与 expectedRevision，使用缩略图的编号/版本拒绝读到另一个工程或另一版画布。自动缩略图的画布与图层缓冲区直接按缩图大小分配，并保留逻辑像素坐标；手机和导出维持原分辨率。局部细节仍按原分辨率合成后裁切。

## 空画布缩略图修复（0.2.16 源码待编译）

2026-10-01 清理草稿时，系统崩溃栈定位到 ArtCanvasFeedback.emptyCanvas → Canvas.drawText，原生字体断言 src == nullptr && gDefaultTypeface == nullptr 令 ail_plugin_runtime 以 SIGABRT 退出。清理已先完成，但图片回执未生成，桥断线并丢失成功返回。

空画布缩略图现在只绘制画布框和叉号，不调用字体或文字渲染；保持 256×128 JPEG、empty=true、documentId/revision=null，并补 label=无活动画布。document.close 与 document.discard_and_close 仍共用相同生成器，原 closedId/discarded 返回语义保持。正常画布缩略图和局部图不受本次修改影响。

本次修复只改画室，版本 0.2.16、versionCode 19、applicationId com.ai.limbs.payload.artstudio.v0216。尚未编译或安装；后续实机需验证关闭已保存工程、舍弃未保存草稿均收到完整关闭结果和空缩略图，并确认两条桥与 Ubuntu 的运行会话不中断。

## 图片尺寸与显式缩小确认（0.2.17，待编译验收）

有效画布宽高结构范围统一为 1–16384 px；不再把 64 px 作为最小图片边长，也不再在打开、渲染、裁切、剪贴板及导出中沿用 4096 px 限制。这个范围不是可无条件处理的分辨率承诺。图片解码失败或格式不支持会报格式错误，不再误报为边长不符。图片原始文件输入仍限 8 MiB；0.2.19 将转换后的工程 PNG 单资源上限改为 64 MiB，并增加编码缓冲和归档读取预算检查。

预算由当前执行进程 Runtime 最大堆与已用堆计算，预留至少 32 MiB 或剩余空间的四分之一，单次预算不超过 384 MiB。图片导入保守按每像素 32 字节估算采样解码、缩放和工作副本；导入图层额外计入当前画布合成。合成预检计算渲染位图、图层临时缓冲、嵌套组缓冲与最大解码资源；导出和连通填充再计入额外工作空间。预算是保守估计，不是对系统可用物理内存或不会 OOM 的保证；Host 与 Resident 各自使用执行进程的实时预算。历史重放只校验结构范围，不把内存波动写入工程语义。

手机打开 PNG/JPEG、导入图层或剪贴板创建工程时，超出边界或预算会显示原尺寸、建议尺寸和减少细节的说明。确认前只读文件头，不做完整位图解码，不创建工程、不改变当前工程指针、不写新图片资源。取消保持当前画布。用户明确选择缩小后才采样解码并缩放到已展示的尺寸，原文件保持不变；预算下降时再次请求确认，不偷偷换成更小尺寸。图层导入保留首次操作的 documentId 与 expectedRevision，等待确认期间另一端改动后会明确拒绝。

兰儿入口保持现有必填参数，增加可选 confirmResize 对象：

- document.open_image(base64,name?,confirmResize?)
- image.import(base64,confirmResize?)
- edit.paste_new(confirmResize?)
- menu.execute 导入图层项：parameters.base64，以及取得同意后提供的 parameters.confirmResize
- image.limits() 读取结构范围、当前工作预算与估计规则

需要确认时返回 status=needs_confirmation、operationApplied=false、imagePlan，不附成功操作的缩略图。imagePlan 包含 originalWidth/Height、suggestedWidth/Height、reason、message、workingBudgetBytes 和 confirmation。先向用户展示尺寸变化并取得同意，再使用同一输入及 imagePlan.confirmation 原对象作为 confirmResize 重试。该对象绑定源文件 SHA-256 和明确目标尺寸；不同源图、越界、放大或长宽比不符都会被拒绝。不要把确认请求当作已经创建或导入。

成功结果额外返回 imageImport，含原尺寸、实际宽高、mime、resized。成功改变画布仍自动返回 thumbnail 与 mcp_content；缩略图/局部预览的输出长边规则保持原样。

本轮仅完成源码与静态检查；未执行编译、构建或测试，未推送远端、未安装到手机。后续云端编译并安装后需实测：小于 64 px、长边大于 4096 px 且预算允许的图片、超预算确认/取消、确认后内存变化、两个导入图层菜单、MCP 确认返回及成功缩略图、编辑/撤销/保存/导出。

## 停靠面板协作入口（0.2.18）

dock.state 读取持久化的 visible、activePane、allCollapsed、restorePane、confirmClose 与 revision。dock.command 支持 set_visible（panel+enabled）、expand/collapse（panel）、collapse_all/restore 与 set_confirmation（enabled）。panel 取 color/layers/brushes/footprints。手机与 AI 共用插件的 dock-panels.json、ArtStore 文件锁与状态转换；无需改动宿主。AI 显式隐藏不弹手机确认框。显示/展开写入页面打开请求；隐藏/折叠直接保存实际状态，未打开页面时仍有效。

已有 menu.execute 的 docker.* 空参数调用仍然显示并展开面板；新增 enabled=false 显式隐藏。配置画室新增 confirmPanelClose；旧调用未传此字段时保留当前提示偏好。未实现的停靠面板仍为灰色占位。

本版云端编译包含 0.2.16 空画布预览避开默认字体的修复及 0.2.17 图片尺寸/内存预算与显式缩小确认。安装后的鼠标/触摸双击、关闭取消、提示恢复、隐藏后的排序、AI 与页面同步及重启持久化需真机验收。

## 常用图片格式读取（0.2.19）

打开为新工程、图层菜单导入、image.import 与 document.open_image 共用 ArtImagePolicy.decode。通过 ImageDecoder 的图像头识别 PNG、JPEG（jpg/jpeg/jpe）、WebP、BMP、GIF、HEIC/HEIF、AVIF，不以文件后缀或选择器 MIME 决定真实编码。后缀为 .jpg 的 HEIC、.png 的 WebP 等同样按实际内容解码。HEIC/HEIF 和 AVIF 依赖当前 Host/Resident 运行环境的系统解码器，缺失或损坏会返回明确错误；不引入另一条解码回退路径。

GIF 和动态 WebP 当前导入首帧，成功结果的 imageImport 包含 mime、animated、frameIndex=0、animationPreserved=false、warnings、headerColorSpace、colorSpace=sRGB 与 bitDepth=8；页面以长提示告知动画未保留，AI 应向用户说明 warnings。原文件不修改。静态图片的 warnings 为空。多帧编辑、HDR/高位深工程与 TIFF 后续单独推进。导出仍为 PNG/JPEG，此轮只增加读取。

image.formats 返回格式、扩展名、MIME、系统声明的 decoderAvailable、输入/工程资源字节上限和静态导入范围。手机图层选择器与此清单使用同一格式定义；通用“打开”仍同时允许选择 .ailart 工程。旧工具 ID、base64、confirmResize、imageImport 和缩略图结果协议保留。

图像头回调在像素分配前进行原尺寸预检；超预算保持 needs_confirmation 和绑定源内容 SHA-256 的确认，未确认不创建工程或修改指针。解码使用软件位图并归一化为 sRGB 8 位；照片方向交由系统 ImageDecoder 处理。工作估算由 24 增为 32 字节/像素，涵盖软件解码、8 位转换和 PNG 缓冲；这是估算，不是 OOM 保证。

压缩照片转为工程内无损 PNG 可能明显变大，故原文件输入上限 8 MiB 与单工程资源上限 64 MiB 分离。PNG 编码在越过限额时停止缓冲；归档读取同样检查单资源、当前内存及导入聚合资源预算，防止仅放大单资源限额后无界累积。尺寸边界仍为 1–16384；超预算仍需明确缩小确认。较大的新资源需用此版本或更新版本读取；旧工程可继续打开。

验证用例应覆盖各格式的静态样本、GIF/动态 WebP 首帧、错误后缀、损坏内容、透明背景、照片方向、尺寸预算取消/确认、图层导入及工程保存再打开。当前仅完成源码检查，尚未编译和执行真机解码验收。

## 0.2.20 基础可编辑文字

在 0.2.19 格式拓展源码上继续迭代，版本码为 23，payload 为 artstudio.v0220。包含前一轮全部格式导入优化，本轮只提交源码，尚未推送、编译或安装。

工具箱的 svg_text 位启用为「文字（基础可编辑）」；高级 SVG 排版仍单独灰色留位。Android 10/11 显示明确的 Android 12+ 要求。选择文字工具后，点击画布添加；选择已有文字图层后点击文字区域或「编辑选中文字」再次编辑。输入多行文字，选择系统中英文字体、字号、颜色、行距、左/中/右对齐、换行框宽度及位置。框宽按字形宽度自动换行；移动、缩放、旋转使用已有图层工具，删除使用图层删除。

文字保存为 kind=text 的独立图层，text 包含原文与样式，asset 是透明 PNG 渲染缓存。编辑只重新生成缓存，保留图层锁、显隐、组关系、混合、不透明度、缩放与旋转；不把源文字烧录后丢掉。复制图层、另存为、工程归档保留文字与历史中的全部缓存；撤销重做能恢复旧文字。打开工程和正常画布/图层缩略图、导出只读取缓存，因此重新打开不要求当前字体仍然存在。原字体不可用时，必须明确选择当前字体才能编辑，不隐式替换。旧工程仍可读取，含 TEXT_CREATE/TEXT_UPDATE 的新工程需要新版画室读取。

手机与 AI 共用 ArtStore.writeText 和现有跨进程锁；提交必须携带捕获的 documentId 与 expectedRevision，拒绝跨工程或过期编辑。新增能力 text.fonts、text.create、text.update；读取 document.info 获取工程 id/revision 和 state.layers 中的文字对象，text.update 必传完整 content，未传样式保持原值。成功写操作自动附带原有 thumbnail 与 MCP image，局部检查仍用 canvas.region。

后台默认字体未初始化曾导致 drawText 原生崩溃。本轮读取系统 fonts.xml 中的 CJK 字体、集合索引与变体轴，使用公开的 Font.Builder 显式构建，再以 Font 度量与 Canvas.drawGlyphs 渲染，避免默认 Typeface；没有增加基座代码、隐藏 API 或字体初始化补丁。Unicode 字符到字形的 cmap 格式 4/12 按 OpenType 规范读取；缺字明确拒绝。PNG 缓存以当前内存预算和 32B/px 的排版、压缩缓冲预算预检，且受工程资源大小限制，失败不写入操作历史。

这一版覆盖基础横排中文、预组合拉丁字母、日文、预组合韩文；不提供 OpenType 复杂塑形、字偶距/连字、组合附加符号、双向文字、Emoji 序列、富文本、竖排、路径文字或 SVG 源码编辑。显示和导出使用栅格缓存，放大有栅格边缘；源文字保留，字号变化可重新生成缓存。本版不是 Krita SVG 文字引擎的完整实现。

静态声明/菜单校验通过：78 个字面运行时注册、94 个能力声明，216 个菜单叶子和 65 个菜单共享实现。没有运行 Gradle、构建或实机文字操作；安装后仍需检查中文混排、字体选择、多行换行、编辑、撤销重做、保存再打开、旋转缩放、锁定图层拒绝与两端并发冲突，并确认插件进程不重启。

参考：[Android Font](https://developer.android.com/reference/android/graphics/fonts/Font)、[Canvas.drawGlyphs](https://developer.android.com/reference/android/graphics/Canvas#drawGlyphs(int[],%20int,%20float[],%20int,%20int,%20android.graphics.fonts.Font,%20android.graphics.Paint))、[Android 字体配置](https://android.googlesource.com/platform/frameworks/base/+/master/data/fonts/fonts.xml)、[OpenType cmap](https://learn.microsoft.com/en-us/typography/opentype/spec/cmap)。


## 0.2.21 编译修复

0.2.20 云端编译任务 36802315874 在 compileDebugKotlin 失败：格式说明读取的 ArtStore companion 是 private；图片缩小确认的旧 perform 位置参数调用误绑定到新增 onSuccess 回调。仅将图片限额常量的 companion 可见性调整为插件模块内 internal，进程锁继续 private；确认调用改用 confirmation/action 命名参数。

版本码 24，payload 为 artstudio.v0221。保留 0.2.19 格式拓展与 0.2.20 基础可编辑文字，未改宿主。提交云端重新编译；尚未完成编译或安装验收。


## 0.2.22 缩放工具双方向

基于已安装验收的 0.2.21；用户确认常见格式打开及字体有效。缩放工具默认放大，角标“大”；工具选中后再次点图标切为缩小，角标“小”，继续点交替切换。切换到其他工具再回来保留本次会话方向。方向切换本身不缩放；点击画布以点击点为中心，放大 ×1.5、缩小 ÷1.5，限制沿用相对适屏比例 0.1–16。停靠栏使画布偏移时仍使用真实显示中心计算锚点，不修改图像尺寸、像素或撤销历史。双指与菜单缩放保留原行为。

手机、AI 使用同一原子视图方向状态。view.state 返回 zoomToolMode（in/out）、zoomToolBadge（大/小）；view.zoom_tool 的 mode 为 in/out/toggle，只设置方向，不立即缩放或选择工具。AI 立即缩放仍用 view.command zoom_in/zoom_out；toolbox.catalog 提供模式和入口。版本码 25，payload artstudio.v0222。仅画室插件改动，本轮未编译、推送或安装。


## 0.2.23 滚轮与底栏缩放

基于 0.2.22 源码继续迭代，包含“大/小”双方向工具。画布接收 Android ACTION_SCROLL/AXIS_VSCROLL：鼠标向前滚放大，向后滚缩小，每单位滚动 1.25 倍，支持小数滚动量，以事件在画布内的悬停坐标锚定，不需要选中缩放工具。仅消费画布内有效的垂直滚动；其他滚动事件交给正常分派，不抢工具栏/面板滚动。

底栏“居中”左侧新增细轨道和圆形滑块，支持点击与连续拖动，旁边显示实际显示比例百分比，100% 为图像像素 1:1 屏幕像素。轨道用对数刻度映射既有相对适屏范围 0.1–16；滑动条以可视区域中心锚定。所有缩放写入都会发布实际比例，图片/页面尺寸改变也重新发布，因此工具、滚轮、双指、菜单、居中/适屏和 AI 请求同步到滑块与百分比。

新增 view.zoom(documentId, percent) 设置显示比例，与滑动条共享画布处理；先读取 view.state.canvasZoom 获取 documentId 与 minPercent/maxPercent。返回 accepted 表示请求已排队，实际应用值读取状态；过期工程请求不操作新工程。视图缩放不修改图像尺寸、像素或工程历史。无宿主变更。版本码 26，payload artstudio.v0223；本轮未推送、编译、测试或安装。

接口依据：[Android MotionEvent](https://developer.android.com/reference/android/view/MotionEvent)、[Compose Slider](https://developer.android.com/develop/ui/compose/components/slider)。

## 0.2.24 基础矢量形状与选择

基于 0.2.23 源码迭代，保留缩放工具状态、鼠标悬停点滚轮缩放和底栏滑条。新增功能全部位于画室插件，宿主权限和接口依赖未增加。versionCode 27，applicationId com.ai.limbs.payload.artstudio.v0224。本轮只提交源码，未编译、测试、安装或推送云端。

### 阿伟的操作入口

在「图层 → 新建 → 矢量图层（基础形状）…」、图层面板添加菜单或形状选择选项中新建矢量层。直线、矩形、椭圆及多边形工具在该层保存独立对象；多边形逐点添加后双击末点结束。已有绘画层继续保存原有笔画，不自动转换。

选择「形状选择（基础矢量）」后，点选描边或填充区域最上方对象，点击选择框外空白取消；拖动空白框选，从左到右要求完整包含，从右到左选择相交对象。Shift 或左栏「多选」用于追加框选和点选切换；当前只在活动矢量层内选择。拖动选择框内部移动，八个方块缩放，顶部圆点旋转；Shift 拖角保持比例，Ctrl 旋转按 45° 吸附。左栏还提供全选、取消、数值位移/缩放/旋转、前景色填充、取消填充、描边和删除。拖动期间预览选择框，释放时一次提交形状变换。锁定对象仍可选中，但修改会拒绝。

### 兰儿的共享入口

先读取 document.info 的工程 id/revision 和图层列表，再调用 layer.vector 或 shape.*。shape.list 返回对象源参数、文档坐标边界、选中编号和锁定/可见信息；shape.hit 在文档坐标查询实际几何命中，shape.box 查询矩形完全包含或相交对象，查询不改变选择。shape.select 传同层 ids，空数组取消。shape.create 支持 kind=line/rectangle/ellipse/polygon 和图层局部 points；颜色采用 #AARRGGBB。shape.transform 的 matrix=[a,b,c,d,tx,ty] 是相对现有对象的图层局部增量变换：x'=a*x+c*y+tx，y'=b*x+d*y+ty。shape.style 支持 fill/stroke/strokeWidth/opacity；shape.delete 删除指定编号。

所有矢量写操作必须携带 documentId/expectedRevision，手机手势从按下起绑定工程、版本和图层；另一端改动后拒绝过期提交。对象模型、渲染、命中和修改共用 ArtShapes/ArtStore，操作进入现有足迹与撤销重做，保存后可重新打开；图层复制为新对象编号。影响画布的成功写操作沿用自动缩略图，shape.list/hit/box 只读查询不额外附图。单层最多 512 个对象、32768 个顶点，多边形单对象最多 2048 顶点；结构性边界不替代原有渲染工作预算。

### 当前边界与待验收

这是基础矢量对象阶段，未实现 SVG 导入、可编辑贝塞尔控制点、自由路径、渐变网格、布尔运算、斜切手柄或叠放对象循环选择。相关高级工具继续灰色。现有文字仍是独立文字层，不能用形状选择编辑。含新矢量操作的工程需要新版本读取，旧版本不能编辑这些新操作。

已做源码与差异审查。后续编译安装后验收：四类对象的创建、透明填充与细描边命中、双方向框选、多选、控制柄与数值变换、锁定及隐藏父组、旋转/缩放视图与嵌套组、手机与兰儿并发冲突、复制删除、撤销重做、保存重开、图层与工具缩略图反馈，并确认旧图片、文字和绘画层行为。

## 0.2.25 矢量徒手路径

基于 0b7bd269 的 0.2.24 迭代，保留基础矢量对象和形状选择，以及此前的字体、格式、缩放改动。versionCode 28，applicationId com.ai.limbs.payload.artstudio.v0225。只改画室插件，不新增宿主依赖。本轮未编译、测试、推送或安装。

### 手机操作

新建或选择矢量层，点击左栏「矢量徒手路径」。工具选项可新建矢量层，并选择「原始轨迹」「拟合曲线」「直线化」；原始模式保留采样折线，曲线模式生成分段三次贝塞尔路径，直线化按距离误差简化折线。精度为 0.25–32 个图层局部像素，默认 2；值越小越贴近采样，原始模式不使用该值。直线化参数不是 Krita 的角度合并阈值。

按住拖动显示原始轨迹预览，抬手后在后台拟合并一次保存；最终曲线与预览可能不同。勾选「闭合路径」或抬手时按 Shift 闭合；「闭合填色」使用前景色。开放路径只描边，闭合路径才能填充。绘制使用当前前景色、描边宽度和不透明度，不模拟矢量笔压宽度或笔刷纹理。

画完切到「形状选择」即可点选、框选、多选，移动、缩放、旋转、调整样式或删除。节点/控制柄编辑、已有路径端点接续和矢量书法笔仍未实现。绘画层行为保留；该工具明确要求矢量层，不将它自动变成栅格笔画。

### 兰儿入口与保存格式

shape.freehand 参数：documentId、expectedRevision、layerId、points 必填；points 是 2–2048 个图层局部 [x,y] 采样点。mode 为 raw/curve/straight，precision 默认为 2，closed 默认为 false；style 支持 fill/stroke（#AARRGGBB）、strokeWidth（0.1–512）、opacity（0–1）。重复相邻点不产生额外几何，闭合输入至少三个不同位置。拟合精度约束采样点误差，不承诺连续轨迹的全局距离或自动保留每个微小角点；数值过大可能抹掉很小的轮廓，应降低精度。

shape.create 也接受 kind=path：points 第一个是起点，commands 逐段给出 L/C；L 消耗一个终点，C 依次消耗两个控制点和一个终点，closed 指定闭合。每条路径最多 2048 段、6145 几何点；单层仍限制 512 对象、32768 几何点。shape.list 返回完整几何及 freehand 的模式/精度/采样数量元数据，可沿用 shape.hit/box/select/transform/style/delete。

ArtFreehand 在写操作前生成几何，Store 只把最终路径、工程版本和图层写入 SHAPE_CREATE 日志，避免重复保存采样数组或回放重拟合。渲染、图层缩图、命中和框选使用同一 ArtShapes 路径；保存、撤销/重做及复制沿用 0.2.24 链路，成功 AI 写操作附原有缩略图。手机采样包含历史触摸点，按下捕获工程/图层/工具参数，释放绑定版本提交；绘制中另端修改或滚轮改变坐标系会中止，双指手势取消轨迹。超出采样上限明确报错，不静默删点。拟合采用迭代分段和受正则约束的最小二乘控制点求解，绘制与预览不依赖默认字体。

### 待编译后验收

源码与差异审查已完成，未执行构建或测试。后续检查三种模式、小精度/大精度、重复点/点击零轨迹、闭合轮廓与开放水平线、细描边命中和填充区域框选、旋转视图及父组、锁定隐藏、双指取消/滚轮中止、手机与 AI 并发、采样上限、复制/变换/撤销/保存重开和缩略图，同时确认旧栅格画笔、基础形状与文字。新路径工程需 0.2.25 或之后版本读取，旧版本不支持 path。

## 0.2.26 可编辑贝塞尔路径

基于 82237a4f 的 0.2.25 源码迭代，versionCode 29，applicationId com.ai.limbs.payload.artstudio.v0226。对照 Krita6.0.4 KisToolPath/KoCreatePathTool、KoPathTool 和节点控制柄命令，复用已有矢量层与 L/C 路径格式，独立实现适合本画室的交互。全部在插件内，宿主依赖未增加。本轮只提交源码，未编译、测试、推送或安装。

### 绘制与编辑

左栏「可编辑贝塞尔路径」分为「绘制」「编辑节点」。绘制时点击放节点，按住拖动产生控制柄；单击未拖动的点保持角点和直线段。可选独立柄、平滑柄、对称柄，Alt 拖动只设置独立出柄。完成按钮、末点双击、Enter 或 Shift 抬手结束；点击起点或「闭合完成」闭合。Backspace/Delete、鼠标右键触摸事件或「撤回节点」移除最后一个草稿节点，Esc 或「取消绘制」取消。点与拖柄是草稿，完成前不写工程历史；首点固定工程版本、图层和样式。开放路径不保存边界外的孤立控制柄。切换绘制/编辑模式前须完成或取消草稿，切换其他工具会取消。双指缩放保留已完成节点，取消正在放置的点或节点拖动；按住拖动时滚轮改变视图会拒绝混用坐标，节点之间可调整视图继续绘制。

编辑模式可点选当前矢量层中的一条路径，点击节点选择并拖动；活动节点显示入/出控制柄。移动节点携带两柄平移；角点两柄独立，平滑节点保持反向共线并保留对柄长度，对称节点保持反向且等长。零长度柄没有方向，平滑模式保留对柄，对称模式将对柄一起收至节点。工具选项可逐个选节点、改变类型、数值修改节点/入柄/出柄、插入当前段中点、删除节点、当前段转直线或曲线、打开或闭合路径。当前段默认从所选节点向后，开放末节点使用前一段。闭合时根据端点节点类型生成连接控制柄，角点可直接以直线连接。

「形状选择」选中一条路径后，可点「编辑路径节点」直接切换。旧徒手路径也能解码编辑；基础矩形/椭圆等仍使用形状选择，不自动转换为路径。编辑拖动只显示几何辅助预览，抬手一次提交，轻点不会跳移节点。锁定对象可查看节点，修改按钮禁用，写接口仍校验锁定/工程版本。

### 兰儿共享接口

path.create 需要 documentId/expectedRevision/layerId/nodes；每节点为 x/y，可选 in/out=[x,y]、type=corner/smooth/symmetric。创建坐标为图层局部，closed/style 可选，样式沿用 #AARRGGBB、strokeWidth、opacity；类型约束会在创建时校准。path.nodes 需要 documentId/layerId/id，返回逻辑 nodes、closed、revision、可见与锁定标志、objectToDocument 矩阵。现有路径的节点坐标是对象局部，包含对象矩阵和父组变换时不要直接把文档坐标当节点坐标。

path.edit 需要 documentId/expectedRevision/layerId/id/edits；每次1至64动作，按顺序在同一次原子操作里执行。动作包括 move_node(node,x,y)、move_handle(node,side=in/out,x,y)、node_type(node,type)、insert_node(segment,t默认0.5)、delete_node(node)、segment_type(segment,type=line/curve)、closed(value)。node 与 segment 为零基索引，节点在插入/删除后会重新编号，须使用最新 revision。读入口不附图，成功创建和编辑沿用自动缩略图。

逻辑节点只是现有 points/commands 的编辑视图，不另存第二份路径。闭合路径的重复终点折回首节点，隐式闭合线也纳入可编辑段；metadata nodeModes 可选，旧路径未记录时按角点处理，不改变已有几何。移动或拖柄、线曲转换和开闭沿用同一几何辅助；插点用 de Casteljau 精确分段保持曲线形状，分段后原对称节点可能转为平滑，因为控制柄长度变了。删除节点连接剩余邻点，轮廓可能改变。SHAPE_PATH_EDIT 保存顺序动作，回放确定性，无新图片资产。含这种新编辑操作的工程需要0.2.26或之后版本读取。

最多2048段/6145几何点，单层512对象/32768点等预算继续生效。开放路径至少2逻辑节点；闭合路径支持旧徒手拟合产生的单节点曲线环，打开前须插点，不能删除至空路径。

### 范围与待验收

当前是单路径、单节点/柄编辑，未实现多节点框选、多个子路径、跨路径端点合并/拆分、拖动曲线段塑形、自动平滑/角度吸附、SVG及布尔操作，不能称为Krita完整节点编辑器。

源码与差异审查完成，未执行构建测试。编译安装后验收点击直线/拖柄曲线、开放/闭合/完成撤回取消、双击与键盘、双指保留草稿、单击不跳移、三节点类型、零长度柄、精确插点/删除/线曲转换、旧徒手闭合曲线环、对象和嵌套组变换、并发冲突、锁定隐藏、复制/撤销/保存重开、两端坐标和缩略图；同时确认旧栅格bezier、绘画、图片、字体和缩放工具。

## 矢量书法笔（0.2.27 源码）

对照 Krita 6.0.4 的 KarbonCalligraphyTool / KarbonCalligraphicShape 和官方 [Calligraphy Tool](https://docs.krita.org/en/reference_manual/tools/calligraphy.html)。此实现使用本插件的普通封闭路径，保存最终轮廓，不引入 Qt 参数形状。绘制后可由形状选择变换、改色、删除，或由贝塞尔节点工具编辑两侧轮廓与圆头控制柄；修改的是轮廓而非书法中心线，原笔尖参数仅为来源元数据。

手机工具 vector_calligraphy 支持新建矢量层、宽度（共用笔刷大小）、笔尖角度、固定度、笔压、速度变细/变粗、时间平滑、平头/圆头。鼠标和手指按恒定压力1处理；数位笔读取实际压力。角度从图层局部+X顺时针；固定度1保留斜头，0随轨迹法线转动。速度用局部像素/秒，达到1000 px/s后变细系数封顶；负thinning反向变粗，实际宽度限制0.1–512。椭圆笔尖厚度是宽度5%（至少0.1 px），避免沿笔尖平行移动生成不可见轮廓。平滑是时间常数最多120 ms的一阶滤波，未冒充Krita质量/阻力模型；沿选中路径、数位笔角度、质量/阻力、预设为灰色待实现。

兰儿入口 shape.calligraphy 与页面共用 ArtCalligraphy 和 ArtStore：必传 documentId / expectedRevision / layerId / samples，samples为2–1000个{x,y,time,pressure?}，time为非负递增或相等毫秒；默认pressure=1。可选width、angle、fixation、thinning、smoothing、usePressure、cap、color、opacity见能力说明。笔画的最终几何只提交一次SHAPE_CREATE，保留版本/锁定/预算校验、撤销重做、工程保存和缩略图反馈；不存原始采样、不在回放中重新生成。往返尖角采用轮廓收拢处理；自交仍按普通路径的非零绕组规则填充，不承诺复杂自交区域布尔并集。输入最多1000点、最终最多2048段，超限明确提示分段，不静默截断。

版本0.2.27 / code30 / v0227，承接0.2.26源码。仅做源码和差异审查，尚未编译、云端推送或安装验收。

## 参考图像（0.2.28）

对照 Krita 6.0.4 ToolReferenceImages / KisReferenceImage 与官方 [参考图像工具](https://docs.krita.org/zh_CN/reference_manual/tools/reference_images_tool.html)。reference_images由占位改为可用。参考是工程state.references里的独立视图对象，PNG原图嵌入既有assets，资产遍历自动包含当前、base及撤销历史并在.ailart往返重映射asset。参考不进入layers，不参与ArtRenderer作品合成、图层缩图或PNG/JPEG导出。添加/选择/变换/样式/删除/整体显隐均是REFERENCE_*历史操作，绑定docId/revision，支持两端冲突校验和撤销重做。旧工程缺省无参考，旧功能接口保留。

页面通过系统文件选择器按内容导入已有图片格式，动图只取首帧并显示警告，输入仍为8 MiB；超工作预算沿用明确缩小确认流程。默认参考摆在画布右侧，成功添加后自动将画布和可见参考一起入镜；视图缩放仍遵守0.1–16倍范围。拖图片移动、8柄缩放/圆柄旋转、多选/框选、90°旋转、保持比例、锁定/显隐、删除确认、透明度和饱和度均可用。整体显隐也保存于工程，两端共享。多选样式显示首对象的数值，应用到所有选中对象。参考图几何选择使用瞬态虚拟矩形适配已有选择控件，该适配绝不写为绘画层。

兰儿共享入口 reference.list/preview/region/add/select/transform/style/delete/show。必传documentId；写操作必传expectedRevision，操作对象用ids。矩阵是文档坐标增量仿射、左乘原矩阵。style支持opacity/saturation、visible/locked/keepAspect/name，锁定对象只能单独改locked；keepAspect约束页面手柄，显式API矩阵允许非等比。reference.add输入base64，可选name/matrix/confirmResize；默认位置和页面一致。reference.preview返回包含画布外参考的256边长视图，reference.region按嵌入原图像素读取x/y/width/height，maxEdge64–2048默认512，忽略显示变换和颜色效果便于检查细节。参考写操作与改变参考的撤销重做，在原返回里附加参考视图缩图。

一工程最多16张参考，页面每张解码为至多512边长的预览位图，并计入双帧内存预算，原图保持在资产中，局部细节按需区域解码。显隐只控制视图；外部链接、系统剪贴板和.kref集合导入导出仍灰色，尚未实现参考取色和斜切专用手势。作品导出没有参考图。

版本0.2.28/code31/v0228，基线9d13cc3f（0.2.27）；包含之前未编译的0.2.22–0.2.27工具迭代。用户已授权这批源码推送触发云编译，提交后不监控运行进度。源码校验与云端提交记录见本轮TODO；待安装验收。

## 底栏缩放条收窄（0.2.29 源码）

用户已确认0.2.28安装成功，但底栏滑条撑满剩余空间。现将滑条限制为最多120 dp，两层Row权重使用fill=false；窄屏仍按剩余空间收缩。撤销/重做留在左边，比例/居中/全屏随短滑条放在右边，中间留出空白。缩放范围、锚点与百分比映射没有更改。

0.2.29/code32/v0229源码基于已安装0.2.28提交cee0acf0，仅修改插件页面布局和版本。尚未推送或编译。

## 工具参数浮窗（0.2.30 源码）

工具格单击选择工具，双击打开共用的工具参数浮窗；缩放工具单击仍交替切换大小，双击只打开选项并选中，不额外切换方向。鼠标和触摸使用同一 combinedClickable 入口，沿用页面已有的 300 ms 双击上限，无需自行延迟或叠加两次单击。

原先堆在左侧工具图标上方的参数、操作按钮和说明已经迁入窗口。取色器、填充、渐变、多重画笔、栅格书法笔、动态画笔和图层变换的原选项模态框也已移除，参数直接出现在浮窗中。文字的新建/编辑内容表单、文件选择和删除确认仍执行各自原有操作流程。

窗口由插件内 Surface 绘制，位于画室内容顶层，不创建 Android 系统悬浮窗，不添加权限或宿主源语。拖动标题栏移动，右上角缩小成标题栏，点击标题或恢复按钮展开，关闭仅隐藏窗口。位置在当前插件会话中保留，展开、旋转或窗口尺寸变化时重新限制在可见范围内。内容内部滚动；参数模型仍属于 Studio，隐藏窗口不会重置已经应用的参数。

共用一个浮窗，切换可用工具同步更新其内容，双击已打开工具会恢复展开。灰色待实现工具双击只显示未实现说明，不选中、不替代执行；没有独立参数的可用工具显示说明。右侧画笔、颜色等共用停靠面板保留。打开浮窗自动收起未固定侧栏，防止其全画布关闭遮罩拦截第一笔；固定面板保留。浮窗本身只在自己的矩形内接收操作，不增加全屏模态遮罩。工具格提供无障碍自定义“打开工具参数/查看工具说明”动作。

兰儿使用 toolbox.catalog 获取 toolId 与 parameterWindow 声明，再调用 view.tool_options：
- action=show，toolId 为目录中的工具：打开并恢复浮窗，可用工具同时被选中
- action=minimize/restore/close：缩小、恢复或隐藏窗口
- action=move，xDp/yDp 为非负有限 dp 坐标：移动请求，最终位置由页面布局限制
- view.state.toolOptionsWindow 返回 activeTool、toolId、open、minimized、xDp/yDp、visible；accepted 表示共享状态已更新，布局坐标随后读取

绘画和参数操作仍通过既有绘画能力及参数执行，不改变文档格式、撤销记录或图片反馈桥。本轮为本地源码迭代，未编译、未上传；部署后需验收手机/鼠标双击、拖动、缩小恢复、边画边调和窄屏旋转。

Compose 点击接口依据 [Android 官方 combinedClickable 文档](https://developer.android.com/reference/kotlin/androidx/compose/foundation/combinedClickable.modifier)；手势参数使用 onClick/onDoubleClick，无双击标签参数，自定义无障碍动作单独声明。

## 绘画辅助尺规（0.2.31 源码）

缺口是文档级辅助对象、控制点编辑和画笔采样约束，已有矢量形状无法直接代替尺规：尺规不属于作品图层，移动尺规也不能移动已有笔迹。本轮在插件内部补齐这三个环节，没有更改宿主源语或接收端。

已实现直尺、无限直尺、平行尺、椭圆、同心椭圆和消失点。前两种投影到固定线段或直线；平行尺通过本笔起点构造平行线；消失点通过起点与消失点构造射线所在直线。椭圆用两点主轴及经过第三点的椭圆方程定义，第三点需在主轴两端之间的侧方；同心椭圆按起笔点等比缩放并在本笔固定。控制点为文档坐标，允许消失点放在画布外。

在工具栏选择辅助尺规，双击打开统一参数浮窗，选择类型和创建模式，再依次点击1/2/3个控制点。完成后切到编辑模式；拖圆点修改控制点，拖本体整体移动。参数浮窗还提供数值控制点、显隐、允许吸附、锁定、直尺刻度、消失点预览线数和删除确认。Esc、切换工具、切换创建类型、多点触控会取消未完成的创建或编辑。创建跨点击保留原工程编号、版本和视图矩阵，途中协作编辑或视图变化会明确拒绝旧手势。

在尺规参数或基础画笔参数窗口开启“吸附到辅助尺规”，选择尺规后切回自由画笔即可沿尺绘画。支持自由画笔、铅笔、软笔、喷枪、橡皮擦和栅格书法笔的中心轨迹。起笔范围使用4–64 dp，默认16；“只吸附选中尺规”默认开启，也可以自动按起笔范围与最初方向选择，在一笔内锁定一个目标。超出固定尺规起笔范围时自由绘制；平行尺、消失点及同心椭圆按起点建立本笔约束。颜色、宽度、不透明度、笔尖角度、工程/图层编号与版本、视图和图层矩阵都在起笔时捕获。多点触控、取消事件和冲突不会提交半笔；正常松开只提交一次。

笔迹以投影后图层局部坐标固化到 STROKE_ADD，回放、保存和导出不重新依赖尺规。修改、隐藏或删除尺规不会改变已有笔迹。assistants、selectedAssistantId、assistantSettings 为可选文档字段，旧工程缺失时表示没有尺规；保存、模板、复制、撤销/重做随现有文档操作统一处理。裁剪和带偏移的画布尺寸调整会同时移动尺规。作品 PNG/JPEG 导出仍使用 ArtRenderer，不含辅助线。

兰儿能力：
- assistant.list：读取尺规、选择、设置、可用/待实现类型和支持画笔
- assistant.create/select/update/delete/settings：绑定 documentId/expectedRevision 的工程编辑
- assistant.project：显式指定尺规，输入文档坐标及可选压力，返回投影坐标；不写作品、不使用手机吸附距离阈值
- assistant.stroke：显式指定尺规及绘画图层，输入文档坐标，投影并转成图层局部坐标，一次写入笔迹
- assistant.preview：读取辅助线、作品和画布外控制点的256边长视图缩略图

尺规写操作和沿尺绘画在原返回结果中附带 assistant-view 图片反馈，撤销/重做改变尺规时也使用相同视图反馈。此视图同时包含参考图像，是检查用图，不是作品导出。显示辅助线和吸附分别控制；全局隐藏辅助线不关闭吸附，编辑工具仍显示控制点，各尺规隐藏或禁用则不参与吸附。预览绘制只用几何，避免常驻进程中的原生默认字体依赖。

本轮尚未实现三次曲线尺规、透视网格、透视椭圆、双点透视组合、鱼眼、曲线透视、局部作用区域和固定长度单位，参数窗口显示灰色占位。矢量、动态与多重画笔的吸附也尚未接入。两点透视可先用两个消失点配一把垂直平行尺搭建，自动模式选择本筆目标；不冒充完整双点透视助手。

设计参考用户共享储存中 Krita 6.0.4 的 RulerAssistant.cc、InfiniteRulerAssistant.cc、ParallelRulerAssistant.cc、Ellipse.cc、EllipseAssistant.cc、ConcentricEllipseAssistant.cc、VanishingPointAssistant.cc 和 libs/ui/kis_painting_assistants_decoration.cpp，以及 [官方助手工具说明](https://docs.krita.org/en/reference_manual/tools/assistant.html) 和 [绘画辅助尺规说明](https://docs.krita.org/en/user_manual/painting_with_assistants.html)。使用本插件的数据、矩阵、交互与绘画能力实现，没有引入 Qt/Krita 插件内核。

本轮只做本地源码迭代，未编译、未上传或进行运行测试。源码核对包括版本、capability/manifest、控制点验证、坐标投影与固化、冲突/取消路径以及导出排除；编译安装后需验收手机/鼠标创建编辑、变换图层的吸附、保存重开、撤销、导出和六种尺规。

## 0.2.32 基础智能修补

smart_patch 从灰色工具格升级为基础局部纹理修补。单击选中，拖动涂抹粉色蒙版，松手后在后台计算；双击打开统一可拖动参数浮窗，调整笔径、补丁半径、搜索半径、精度和边缘融合。Esc、换工具或多指操作取消尚未提交的涂抹；计算期间使用已有 busy 控制，参数和工程绑定均在起笔时捕获。

参考本地 Krita 6.0.4 的工具及 kis_inpaint.cpp，并核对[官方工具说明](https://docs.krita.org/en/reference_manual/tools/smart_patch.html)。本插件编写独立 Kotlin 局部 PatchMatch：有效未涂抹源补丁、确定性随机搜索、交替扫描传播、补丁投票及预乘 alpha 边缘混合，不依赖 Qt、模型或宿主新增能力。与 Krita 完整多尺度求解器并不等价；大面积多尺度、跨图层、变换/分组图层及 HDR 继续灰色说明。

目标为当前未锁定的可见根绘画/图像层，位置0、缩放1、旋转0。选区仅限制修补写入，取样允许位于选区外；参考图像、辅助尺规与其他图层不进入取样。只改蒙版覆盖的像素。完整结果以 PIXEL_REPAIR 一次提交，存储嵌套 asset 字段的擦除和补丁 PNG，复用既有 erase/paste 合成、历史、图层复制与归档资源扫描，不在重放时运行算法。透明源像素以“擦除旧像素再写最终像素”的顺序保留透明度；最终图层不透明度和混合模式保持原值。

兰儿入口：
- plugin.art.studio.patch.info：参数默认值、范围、预算及未实现项
- plugin.art.studio.patch.apply：documentId、expectedRevision、layerId、points、width 必填。points 为1–4096个文档坐标二维点，圆头路径形成蒙版；width 1–256、patchRadius 1–8 默认4、accuracy 1–100 默认40、searchRadius 16–256 默认64、feather 0–8 默认2。返回 repair 统计和自动256缩略图；细节继续使用 canvas.region

单笔蒙版最多32768像素；包含搜索余量的局部矩形最多1048576像素；颜色样本比较最多8000万。空间和比较预算超限、没有有效纹理、文档/版本/目标层变化时直接拒绝，不缩图计算、不改变作品。计算和PNG编码全部完成后才写历史；写入失败清理新资源。内存遵循 ArtImagePolicy 的动态预算。

版本0.2.32 / versionCode35 / com.ai.limbs.payload.artstudio.v0232，131项能力。
验证仅源码审阅、JSON/接口一致性和差异检查；尚未编译、测试、上传云端或手机验证。纹理复杂、结构独特或大面积遮挡时效果仍需实际检查，可撤销调整后重试。

## 0.2.33 基础上色蒙版编辑

参考本地 Krita 6.0.4 的 tool_lazybrush、kis_colorize_mask、kis_colorize_stroke_strategy 与 KisWatershedWorker，并核对[官方上色蒙版说明](https://docs.krita.org/en/reference_manual/tools/colorize_mask.html)。原来灰色的 colorize_mask 已成为基础工具；图层菜单「添加 → 上色蒙版」同样接通。它保存独立 colorize 图层，关联源线稿 ID，单独记录颜色线索、调色板、参数和显式更新得到的 PNG；原线稿、源不透明度及混合模式不改写。

界面：选择未变换的可见根绘画/图像线稿，首次在画布点击建立蒙版，后续用前景色画颜色线索；也可在参数窗或图层菜单创建。双击工具使用统一可拖动浮窗。支持线索笔径、擦除、重新选择调色板颜色、透明标记/取消、移除颜色全部线索、暗线阈值、缺口闭合半径、限制到线稿/线索边界、编辑线索/显示结果、更新、清空线索和转绘画层。清空、删除颜色、转换有捕获工程版本的确认框。Esc、换工具、视图变化、多指及 ACTION_CANCEL 取消未提交笔画；文档/版本/层和视图在起笔时捕获。创建蒙版的首次点击不同时写一笔线索。

基础算法独立实现为暗线屏障加多颜色种子的测地距离传播，采用索引最小堆、确定性距离/颜色/坐标排序；通过滑动极值的形态学闭合补小缺口。与 Krita 的完整高度图分水岭和清理算法不等价。强线处的线索不作为填色种子；未有种子的封闭区域保持透明。阈值1–254默认180，缺口闭合半径0–8默认0；limitBounds默认false；区域上限4194304像素且遵循当前选区及动态内存预算，不降采样计算。颜色线索的选区剪裁随线索保存，擦除按绘制顺序清除所有颜色标签，后画颜色优先。最多256笔、每笔4096点、每蒙版32768点和32种颜色，笔径0.1–256。透明标记只使对应填色输出透明，不擦除原线稿。

结果层默认放在源上方，对亮色/透明区域输出颜色，并以原暗线强度保留轮廓，因此可处理白底暗线稿及透明底暗线稿。编辑视图中所选蒙版的输出半透明、线索可见；普通导出与图层缩图不包含线索，导出服从 showOutput。关掉编辑线索可检查完整填色。种子传播不识别语义，开放边界会漏色；需要修补线稿、适当闭合或添加透明标记。实心阴影边缘检测、精细出界线索清理、组/变换线稿、多尺度大画幅和动画/HDR保持灰色说明。

新建、笔画、删除线索、调色板、参数、清空、更新和转换均走 COLORIZE_* 操作，绑定 documentId/expectedRevision。更新成功才提交一次固化输出；失败不替换旧结果，写入失败清理新资产。保存/导入/复制/历史沿用通用 JSON 和嵌套 asset 扫描，复制组内关联源时重映射 sourceLayerId。删掉关联源后仍可显示现有缓存，状态明确提示源缺失，更新拒绝；可把缓存转绘画层。普通绘画与像素编辑工具不将蒙版冒充绘画层；转换后才使用普通工具。整体裁剪/移动保留现有像素和本地线索坐标，变换或分组后的重新求解属于当前不可用范围。

兰儿入口共有10项：
- colorize.list：documentId必填，expectedRevision/maskId/includeKeys可选，默认返回笔画摘要，按需 includeKeys=true 读取坐标
- colorize.create：documentId/expectedRevision/sourceLayerId必填，name可选
- colorize.stroke：documentId/expectedRevision/maskId/points/width必填；color在非擦除时必填，erase默认false，局部坐标当前与文档坐标一致
- colorize.remove_stroke：绑定工程与蒙版，指定strokeId
- colorize.palette：color/action必填，transparent操作额外传布尔transparent；remove移除该颜色全部线索
- colorize.settings：settings为指定参数的部分更新
- colorize.clear、colorize.update、colorize.convert：绑定工程、版本和maskId；convert明确固化当前缓存结果
- colorize.preview：256边长编辑检查图，maskId可选，不改变真实图层选择

成功修改时自动附带256缩略图，包含目标蒙版开启的编辑线索及半透明输出；colorize.preview可主动检查，canvas.region查看作品局部细节。编辑图与实际作品导出有明确区别。颜色线索与参数改变后不自动重算；dirty比较生成版本及源线稿签名，需显式更新。显示参数不使结果脏。

版本0.2.33 / versionCode36 / com.ai.limbs.payload.artstudio.v0233，141项能力。所有能力在画室插件内，未改基座、桥或宿主源语。
完成源码结构/字符串/括号、编码、菜单及清单JSON、接口/版本一致性与差异检查；未执行编译、构建、测试、云端推送或手机效果验证。

## 围合填充（0.2.34 源码，待编译验收）

对照 Krita 6.0.4 的 KisToolEncloseAndFill.cpp、KisEncloseAndFillPainter.cpp 与官方 Enclose and Fill Tool 文档，实现独立 Kotlin RGBA8 子集，不引入 Krita 内核或更改宿主源语。

单击选择工具，在画布中圈住想上色的内容，松手执行一次。双击打开统一可拖动参数窗，可缩成标题或关闭，参数保留到本次页面会话结束。矩形和椭圆拖两个对角点，自由套索自动闭合，画笔以圆头笔径涂出围合范围。围合是临时几何，不创建、替换或删除工程选区。

支持全部区域、透明区域、指定颜色、指定颜色或透明，以及三个排除条件。颜色比较使用预乘 RGBA 的最大通道差；透明比较使用 alpha。全部区域按固定种子颜色的四邻域连通区域划分，其余条件使用二值条件的连通区域。关闭“包含碰到围合边缘的区域”时，触及围合边界的区域被排除；反选在围合内进行。全部区域包含边缘且反选会没有输出。

参考可以取当前图层原始像素，或合成全部可见图层并考虑图层不透明度、变换、群组和混合方式。参考不包含文档背景、参考图像、尺规、蒙版编辑线索。只写入当前可见、未锁定、未变换的根绘画或图像图层；其他目标明确拒绝。现有选区只剪裁最终结果，不参与围合区域的封闭性判定。

参数支持颜色容差 0–100、不透明度 0–1、擦除、扩展或收缩 -16–16 px、羽化 0–8 px，以及非“全部区域”条件下的缺口闭合半径 0–8 px。缺口处理是二值形态学开运算，断开窄候选通道，恢复选中核心边缘到原候选范围；与 Krita 的传播间隙算法不同。区域临近围合边缘时采取边缘带判定，避免把外部背景错判为封闭区域。扩展采用方形形态学，羽化采用可分离方形均值滤波，最后保持围合和工程选区剪裁。

单次围合的包围矩形最多 4194304 像素、2048 路径点。计算前预检完整参考合成和每区域像素 96 字节的保守工作预算，不缩图求解。超预算和无效目标明确拒绝。零写入像素返回 changed=false，不添加历史；手机会提示没有符合条件的区域。Esc、取消、双指操作和工具切换取消临时路径；手势绑定工程 ID、版本、图层、视图与按下时的参数，不会把旧手势写到新工程。

结果保存为透明 PNG，通过 PIXEL_PASTE 的 ENCLOSE_FILL / ENCLOSE_ERASE 记录一次操作，沿用已有绘画内容顺序、资源引用、撤销重做、工程归档、导入重映射和清理。擦除使用 DST_OUT。重放使用固化结果，不重跑分区。算法完成、编码和资源写入都成功后才更新工程；失败清理本次新资源。

兰儿入口为 plugin.art.studio.enclose.info 和 plugin.art.studio.enclose.apply。apply 必须绑定 documentId、expectedRevision、layerId、shape 和文档像素坐标 points；矩形/椭圆恰好两个点、套索至少三个点、画笔至少一个点。非擦除时还必须 color=#AARRGGBB。其他字段与 info.defaults 相同。成功修改后在原结果中附带 thumbnail 与 mcp_content；进一步看细节仍用 canvas.region。

贝塞尔围合、按外围轮廓颜色判定、图案填充、颜色标签参考组、最暗像素停止扩展、变换及组内图层写入、HDR 和动画保持灰色。基础版参数、算法和结果需编译及手机实测验收；本次仅完成源码静态检查与本地提交，没有执行编译、构建或测试，也没有上传云端。

## 贝塞尔曲线选区（0.2.35 源码，待编译验收）

对照 Krita 6.0.4 的 plugins/tools/selectiontools/kis_tool_select_path.cc、既有贝塞尔节点实现与官方 Path Selection Tool 文档。新 select_bezier 工具实现真实闭合三次曲线选区，既不绘制作品笔画，也不把保存的曲线折线化。业务和 UI 均位于画室插件。

单击选中工具，点击放置节点，按住拖动拉出切线；点回起点、双击末节点、Enter 或参数窗中的完成按钮提交。双击工具打开统一可拖动参数窗。支持退回上一节点、取消、右键退回、Backspace 退回、Esc 取消。未完成路径只存在于编辑器；工程变化明确拒绝，取消、双指、工具切换、显示适配或切换编辑模式会结束临时手势，未完成路径需先完成或取消后才能切换新建／编辑。

参数窗可以选择替换、添加、减去和相交；快捷键 Shift 添加、Alt 减去、Ctrl 替换、Shift+Alt 相交，以第一节点按下时为准。未拖动的节点可在完成时自动平滑，默认关闭。选区必须有面积，至少两个节点；只有两个节点时需有弯曲控制柄。每条最多2048节点，节点和控制柄的文档坐标有限且绝对值不超过1000000。

新建贝塞尔选区保存为 shape=bezier 的规范化节点及框架。添加、减去和相交保存有界 shape=compound 的有序布尔分量，保留基本曲线及各步骤模式，绘制、像素剪裁和编辑时使用真实 Path 布尔运算。最多32分量、累计8192节点，不允许嵌套复合分量。移动、缩放通过选区框架映射各分量，节点和控制柄仍可按当前文档坐标编辑。

空选区保留为零面积矩形，不转换为取消选区。没有已有选区时，添加建立新选区，减去或相交得到空选区。运算消除所有面积也得到空选区；现有像素编辑范围检查或选区剪裁阻止写入，避免清掉选区后误编辑整张画布。

编辑模式支持选择复合里的贝塞尔分量、拖动节点及控制柄，节点类型尖角／平滑／对称，下一段插入节点、删除节点、下一段变直线或曲线。插入沿用 de Casteljau 细分；始终闭合且至少两个节点。每次节点拖动或参数命令只记一次工程操作，绑定工程 ID 与版本，支持撤销、重做、归档和重新打开。

复制、清除、填充、围合填充、上色蒙版、智能修补和图像过滤通过已有 ArtSelection.path 剪裁链路使用曲线。笔画选区编辑的中心线命中采用路径交集和0.125 px检测带，单点命中采用1/16 px网格；这是几何命中精度，不是统一像素选区的抗锯齿或羽化。选区仍不作为作品图层导出。

兰儿入口为 selection.bezier_info、selection.bezier_create、selection.bezier_nodes、selection.bezier_edit 和 selection.preview，完整前缀 plugin.art.studio。创建和编辑必填 documentId 与 expectedRevision。create 接收文档坐标 nodes 及 mode；edit 接收零基 componentIndex 和1–64顺序动作：move_node、move_handle、node_type、insert_node、delete_node、segment_type。nodes 返回已应用移动缩放的文档坐标。复合分量类型可由 document.info.state.selection.parts 读取。

成功的选区操作自动附带256长边的编辑检查缩图，显示浅蓝覆盖和虚线轮廓；撤销、重做等操作改变选区时也附带轮廓。selection.preview 可只读检查。canvas.region 新增可选 selectionOutline=true 用于局部边界细节，默认false保留原作品预览行为。轮廓反馈不写资源、不改作品、不进入导出。

抗锯齿／羽化统一像素选区蒙版、角度吸附、任意旋转选区及独立选区蒙版图层保持灰色。当前选择模型是几何剪裁，各像素操作的边缘策略尚未统一，不能把显示抗锯齿冒充为完整软选区能力。本次仅做源码静态检查和本地提交，未运行编译、构建或测试，未上传云端；手机上的节点拖动、布尔边界、撤销及保存效果仍待验收。

## 漫画分格编辑（0.2.36）

对照 Krita tool_knife 的切分及间隙合并与[官方说明](https://docs.krita.org/en/reference_manual/tools/comic_panel_editing_tool.html)。基础工具从灰色占位改为直边矢量编辑。单击选中，双击统一参数浮窗；新建分格矢量层、添加整页框、设定边距与边框，然后在画布拖线。不会拆分底下画作像素。

切分支持矩形、凸多边形及闭合 L 直线路径，最多256顶点。切线从框外完整穿过框，可一次穿过多个框。厚、薄、特殊间隙默认12/6/8文档像素，支持0–512；自动按水平、垂直、斜向选预设，角度容差默认15度。间隙是轮廓距离，描边会占去部分可见空白。间隙过宽拒绝，无交叉不记历史。

合并时从一个格内部拖到另一个格内部，恰好穿过一条间隙的两条相对平行直边。填满直边重叠区，继承图层中较靠下对象的样式与矩阵；不使用凸包扩张外轮廓。不同高度的格合并可产生凹轮廓，其进一步切分仍不可用。间隙与第三格重叠时拒绝；第三对象只接触边界允许。合并最多256顶点。

写操作严格绑定 documentId/expectedRevision/layerId。手机“只处理已选对象”对应 AI ids 参数；不传 ids 时处理手势范围内的可见对象。范围内包含不支持轮廓时明确拒绝，可先限定直边对象。图层、父组、对象锁定或过期拒绝，支持现有层及对象仿射矩阵，保留对象顺序；计算后一次存储最终几何和共享撤销。手势取消、双指、右键、Esc、工程/图层/版本变化不会写入。

兰儿入口：comic.info、comic.frame、comic.cut、comic.merge。对象读取和样式编辑复用 shape.list / shape.style，矢量层创建复用 layer.vector。成功返回自动缩图；切分/合并返回 removedIds / createdIds，切分附 gutterWidth，未切中 changed=false。

曲线边框、带孔轮廓、凹多边形切分、同轮廓内部间隙合并、非平行边合并及物理单位保持灰色待实现。此前约定的共享选区抗锯齿/羽化继续延后。

本轮仅画室插件源码：0.2.36 / versionCode39 / appId v0236。未执行编译、构建或测试，未提交云端；手机手势、合并边界、变换和归档需后续安装验收。

## 最后三个基础选区工具（0.2.37）

连续区域、相似色和磁性套索从灰色占位改为可用基础工具；单击选择、双击统一浮窗，所有参数和说明均留在浮窗。对照 Krita selectiontools 三个工具及 KisMagneticWorker，官方[连续区域](https://docs.krita.org/en/reference_manual/tools/contiguous_select.html)、[相似色](https://docs.krita.org/en/reference_manual/tools/similar_select.html)、[磁性套索](https://docs.krita.org/en/reference_manual/tools/magnetic_select.html)说明。

颜色区域采用预乘 RGBA 最大通道差，包括透明度。连续区域为固定种子的四邻域搜索；相似色扫描范围中全部相近颜色，保留互不相连区域。连续区域还支持边界色模式和缺口侵蚀／恢复半径0–8；两者支持颜色容差0–100、扩展／收缩-16–16以及替换、添加、减去、相交。修改键在操作开始时固定。缺口处理后种子失效明确拒绝，不换种子。

精确二值选区使用 deflate-rle-v1 压缩扫描段，保留孔洞和离散部分；native Region 合并扫描段边界，显示不产生内部扫描条纹。每个搜索范围最多4194304像素，单个或复合选区最多32768扫描段；复合仍有32分量和8192几何节点预算。移动、缩放、复制、填色、滤镜、笔画命中通过同一 ArtSelection 边界。解压严格检查尺寸、顺序、长度和编码，不读取无界数据。

参考 current 保留本层和父组变换，读取实际内容层并忽略其透明度／混合；visible 合成可见图层，不包含背景、参考图像、尺规和蒙版线索。当前层参考不支持图层组，可改用可见层参考。搜索可限定 bounds，页面可先建立矩形选区再启用“仅在现有选区范围查找”。超范围和内存预算明确拒绝，不缩图识别。

磁性套索使用 RGBA Sobel 对比边缘和八邻域 A* 路径，搜索被限制在锚点连线附近的有界走廊。filterRadius 是1–4像素的边缘采样半径，不冒充 Krita 完整滤波半径模型；searchRadius=2–64，threshold=0–255，strength=1–20，precision=0.25–4像素的轮廓简化误差。128锚点、每段262144搜索像素、最多2048最终顶点及动态内存检查。超预算请增加中间锚点或限制参考范围。

手机磁性工具在后台计算预览，并缓存已经求解的锚点段。点击放置或拖动按屏幕间距追加；首点、Enter或完成按钮闭合，Backspace或按钮撤回，拖动已有锚点修改，拖出参考范围删除。参数及参考从首点捕获；完成前只预览，完成时一次提交；工程、版本、图层、视图变化以及双指、右键、Esc、卸载视图取消任务。完成后重编辑磁性锚点、颜色标签、完整高级滤波及共享抗锯齿／羽化软选区继续灰色待实现。

AI 新入口 selection.color_info、selection.contiguous、selection.similar、selection.magnetic_info、selection.magnetic_trace、selection.magnetic_create。trace 是只读吸附路径预览，create 才写选区。写操作必填 documentId、expectedRevision、layerId；成功自动附带带轮廓的256像素缩图，与共享撤销及保存回放相同。二值扫描段不是软覆盖蒙版，先前约定的抗锯齿和羽化仍延后。

本轮仅修改画室插件，版本0.2.37 / versionCode40 / appId v0237；包括此前0.2.29–0.2.36未编译改动。源代码检查后提交云端，编译结果与手机交互效果仍待验收。

## 0.2.37 云端编译错误修正（0.2.38）

云端 Run 36853168556 在 Kotlin 编译阶段失败：颜色选区和磁性套索把 KeyEvent 的 isShiftPressed/isAltPressed/isCtrlPressed 属性用在 MotionEvent 上；漫画分格的 "$label使用" 将中文连同 label 解析为变量名。

修正为 MotionEvent.metaState 与 KeyEvent.META_SHIFT_MASK/META_ALT_MASK/META_CTRL_MASK 的位运算，覆盖左右修饰键，保留 Shift 添加、Alt 相减、Shift+Alt 相交和 Ctrl 替换；中文紧接变量时使用大括号插值。未更改工具能力、参数协议、宿主依赖或基座。

版本0.2.38 / versionCode41 / appId v0238。提交前执行源码一致性和正式能力校验，提交后执行来源校验，随后重新推送既有云端工作流；编译和实机结果仍待验收。

## 手机与 AI 视图连接（0.2.39）

0.2.38 实机绘画成功，但 Resident 业务端的 view.state 缺少 canvasZoom，view.command fit 在手机已经打开画布时仍报“请先打开画室画布”。原因是进程内 ArtStudioViewControl/工具浮窗 Flow 不会跨 Runtime 分享。

新增插件自有 plugin.art.studio.view.control Provider，使用现有 InProcessUiStateProvider.stateJson/perform 双向协议。手机页面上报真实 View 挂载、页面可见性、工程、缩放和工具浮窗；业务端将 view.* 请求投递到页面，页面在主线程执行并回传成功或明确错误。所有已公开的 view.* 名称、参数与 toolbox.catalog 保留。未增加 Host 原语、权限或基座特判。

每次页面挂载拥有独立 session；400毫秒心跳、5秒连接和命令期限。命令绑定当前页面与工程，执行前申请一次性 claim；关闭页面、更换页面、切换工程、过期或重复请求不会操作后来打开的画布。accepted 仅在页面执行后返回；浮窗最终布局坐标仍在布局后读取。页面尚未打开时，view.set/zoom_tool 等偏好保留到页面确认应用，兼容既有用法。视图控制不改作品历史、像素或保存文件。

版本0.2.39 / versionCode42 / appId v0239。新增10个 JVM 回归用例覆盖跨端状态、执行回执、关闭/替换页面、切换工程、心跳与请求过期、重复事件和错误传播；既有云端工作流先运行测试，再编译安装包。源码检查与编译、安装验收分别记录，不把源码完成当成实机验证成功。

## 兰儿独立预览与手机视图目标（0.2.40）

0.2.39 把 view.command / view.zoom 接到手机页面 Provider，仍把“AI 已打开工程”误当成“手机页面应当已挂载”，造成兰儿从自己的入口打开作品后仍无法适配、缩放。0.2.40 使 view.state / view.command / view.zoom 的默认目标为 assistant；它们直接读取同一 ArtStore 当前工程，在插件业务进程渲染真实的 1024×768 后台视口，不启动应用、不切换手机页面。

- view.state 的 canvasAttached 表示 assistant 工程已打开；pageVisible=false 如实表示没有手机页面。viewSurface=offscreen_bitmap 明确视图身份；canvasZoom 返回同一目标的比例与 documentId。未打开工程时没有 canvasZoom。
- view.command 支持原有缩放、旋转、镜像、适配、重置和刷新；成功时返回 viewPreview 元数据与 mcp_content 图片块。使用原始分辨率合成而非放大缩略图，沿用工作预算，超预算明确拒绝。
- view.zoom 仍要求当前 documentId 与范围；100% 是一个文档像素对应一个预览像素。换工程或尺寸变化重置预览；只改视图，不写作品像素、历史、修订号或导出文件。预览变换是插件当前运行态，不作为工程保存内容。
- 显式 target=phone 使用0.2.39的真实页面 Provider、心跳和执行回执；关闭或不可见时仍明确拒绝。目标只由参数确定，不按手机是否可见自动切换。
- view.set / view.zoom_tool / view.tool_options / view.presentation 继续表示手机菜单、方向、参数窗和显示模式；读取其手机状态使用 view.state(target=phone)。toolbox.catalog 中工具窗状态也属于手机页面。绘画与对象编辑继续使用原有结构化能力。

版本0.2.40 / versionCode43 / appId v0240。保留全部既有能力名与必填参数，给上述三项能力添加可选 target；手机控制仍通过显式 target=phone 提供。新增7项 JVM 回归用例，与既有10项连接用例一并由云端工作流执行。安装后需要验收：仅从兰儿入口打开工程、fit与缩放返回图片、旋转镜像真实改变预览、作品修订号不变，以及手机目标的可见性拒绝。源码完成不代表实机通过。

## 按需能力说明与示例（0.2.41）

158项能力均通过已有 Runtime API 发布 `suggestedParamsJson`，`capability.describe` 将它作为 `minimal_example` 返回。只读取当前工具的说明和示例，无需先加载整份工具目录。基座、搜索与业务执行器保持既有实现。

`ArtCapabilityHelp` 是插件能力说明的唯一来源：能力摘要说明效果及前置条件，参数说明包含格式、坐标空间、范围和条件依赖，确定的枚举也写入 `inputSchema`。示例只放在 `suggestedParamsJson`，不在摘要与 schema 再重复同一份 JSON。原来只有参数名的描述已补齐，重复的 `expectedRevision` 参数记录已去重，既有必填与可选含义不变。

示例中的 `DOCUMENT_ID` 用 `document.info.id` 替换；`PAINT_LAYER_ID` / `VECTOR_LAYER_ID` / `TEXT_LAYER_ID` 从 `layer.list` 按 kind 选择。其他对象ID的读取入口写在对应参数说明。`expectedRevision:0` 是模板数字，每次调用前替换为当前 `document.info.revision`，不能一直沿用0。`BASE64_*` 是文件字节编码占位，不能当真实输入，也不能传文件路径。

例如，在未锁定绘画层上画一笔：

```json
{"layerId":"PAINT_LAYER_ID","points":[[10,10],[100,80]],"tool":"ink","color":"#FF245364","width":6,"expectedRevision":0}
```

颜色统一为 `#AARRGGBB`。`stroke.add` 的点是图层局部像素；尺规笔迹、像素选区及分格使用文档像素；路径节点编辑使用对象局部像素。形状、渐变、曲线需要的点数和嵌套对象结构由该参数说明给出。显示操作优先使用默认 `target=assistant`，明确操作手机页面才选择 phone 或手机专用能力。

示例包含真实有效的数字、动作名和格式，同时如实说明动态对象、剪贴板、选区、图层锁定等前置条件。PNG/JPEG导出保存原尺寸文件并返回路径；它不会返回原尺寸图片块，实际预览使用 `view.command` 或 `canvas.region`。此轮不改变导出或传输机制。

版本0.2.41 / versionCode44 / appId v0241。增加云端 JVM 用例检查全部示例与注册参数的必填字段、类型、枚举、颜色和坐标说明；静态清单检查保证示例与manifest能力集合完全一致。只允许云端编译，安装后仍需通过实际 `capability.describe` 验收新说明。


### 0.2.45：当前笔刷直线与绘制中调整（尚未编译）

- 参照 Krita 6.0.4 `plugins/tools/basictools/kis_tool_line.cc` 的 `continuePrimaryAction/straightLine/snapToAssistants`，以及 `kis_tool_line_helper.cpp` 的传感器沿线重排与平移行为；采用插件自身 Kotlin/dab-v1 引擎实现，未复用 Krita 源码。
- 绘画层直线使用当前六类笔刷中的主笔（自由画笔/铅笔/软笔/喷枪/橡皮/栅格书法）、共享预设、笔尖、纹理和动态曲线。保留实际历史事件的压力/相对时间/倾斜/笔方向角，沿最终首尾轴按起点距离重排并去掉超出终点的采样，笔刷印章间插值。关闭设备传感器后，对应动态通道不参与；方向和随机通道仍可用。直线不运行自由轨迹的加权平滑/稳定器，禁用停驻定时喷绘，像素完美模式可用。
- 默认拖动抬手完成；起笔后 Alt 平移整条线（起笔时已按 Alt 要先松开再按），Shift 按文档角度 15° 约束。手机可开启“抬手暂存”，抬手后切换“移动起点”再拖动，或改终点，最后“完成直线/取消”；Enter/Esc 同样可用。选择工程/图层/工具、更新版本、双指视图操作及退出页面取消草稿；提交绑定起笔的 documentId/revision/layerId。
- 可吸附可见且启用的直尺、无限尺、平行尺、消失点；直线不沿椭圆变为曲线。手机在角度约束时优先角度，移动中保持线段平移；API 同时指定非零 angleStep 与 assistantId 会明确拒绝。角度与平移在文档空间完成，采样与线段端点保存为图层局部。矢量层仍创建可编辑的两端点描边线段，不应用栅格纹理/传感器。
- 兰儿入口：`line.info` 读范围，`line.geometry` 只算端点/采样，`line.draw` 绘画/矢量统一入口；`stroke.add(tool=line)` 和 `assistant.stroke(tool=line)` 接入同一几何/笔刷链路。`lineInput/lineEndpoints/lineVersion=1` 与通用 brushInput/最终 points 固化历史，重放不重算尺规；原有无 brush 的直线记录继续按原格式渲染。

简例（ID 和 revision 换成当前值）：
```json
{"documentId":"DOCUMENT_ID","expectedRevision":0,"layerId":"PAINT_LAYER_ID","points":[[20,30,0.3,0],[80,55,0.7,50],[160,95,1,100]],"color":"#FF245364","width":6,"brushTool":"ink","useSensors":true,"angleStep":15}
```
沿尺规：`angleStep:0, assistantId:"GUIDE_ID"`；整体移动起点：`lineOffset:[10,0]`（文档像素）；矢量层省略 `brush/brushPresetId`。新入口通过 capability 元数据直接携带简例和字段约束。版本 `0.2.45` / versionCode `48` / applicationId `com.ai.limbs.payload.artstudio.v0245`。本批只做静态检查和开发仓库保存，未执行编译、测试或云端推送，运行效果待后续部署验证。


### 0.2.46：矩形、椭圆约束与栅格轮廓/图案（尚未编译）

- 参照 Krita 6.0.4 的 `plugins/tools/basictools/kis_tool_rectangle.cc` / `kis_tool_ellipse.cc`，以及 `libs/ui/tool/kis_tool_rectangle_base.cpp` 的 applyConstraints/中心控制、`kis_figure_painting_tool_helper.cpp` 的笔刷轮廓/填充职责；以本插件 Kotlin、Android Path 和 dab-v1 实现，未复用源码。
- `fixedWidth/fixedHeight/fixedRatio` 为0时自由；两项尺寸均固定时宽高优先，宽+比例派生高，否则比例从高派生宽。输入始终是图层局部两个原始角点；`drawFromCenter` 将首点解释为中心、次点为边缘，自由宽高取位移的两倍，固定值仍是完整尺寸。手机提供尺寸/比例输入与应用按钮；Shift 在自由时约束1:1，有既定约束时临时解除，Ctrl 临时中心绘制。
- 矩形 `cornerRadius` 实际不超过短边一半；栅格和矢量共用圆角轮廓。矢量对象保留可编辑属性，选择矩形后也能在形状参数面板、`shape.style(style.cornerRadius)` 修改圆角，命中/框选/边界/渲染均读同一路径。
- 栅格 `outline=brush/basic/none`：当前六类笔刷共享预设、笔尖、纹理和曲线，轮廓闭合、压力固定1，以几何弧长合成采样时间，不使用加权平滑/稳定器/定时喷绘，像素完美仍可用。保留尖角精确采样；轮廓最多10000采样，超出直接拒绝。无描边与无填充不能同时使用。预览与提交都走 ArtFigure 约束和 ArtFigureRenderer；样式/种子/工程/版本/图层在起笔捕获，切换或双指视图手势取消草稿。
- `figureFill` 为 none/solid/pattern；图案支持棋盘、条纹、圆点及1–512px RGBA图片平铺，可设置缩放、旋转和偏移。图片通过共享 `brush.resource.import(kind=texture)` 或手机“导入图案图片”保存，读取原色和透明度。图案填充与轮廓一次合成透明度；橡皮作为整体透明度蒙版清除。资源按既有嵌套 asset 字段随工程保存、导入重映射；内存预算包括两层合成与图片缓存。矢量填充仅 none/solid，图案和栅格笔刷配置需绘画层。
- 兰儿入口：`figure.info` 读参数，`figure.geometry` 仅读求边界，`figure.draw` 按图层输出栅格/可编辑矢量；`stroke.add(tool=rectangle/ellipse)` 接入同一栅格链路。保存 figureVersion=1、figureInput、figureBounds、figureCorners、effectiveRadius 和 brushInput/最终 points，重放不再计算约束或轮廓。原有无 figureVersion 的栅格图形仍按既有记录渲染。

简例（调用 figure.draw，ID/revision 替换为当前值）：
```json
{"documentId":"DOCUMENT_ID","expectedRevision":0,"layerId":"PAINT_LAYER_ID","tool":"rectangle","points":[[40,50],[200,140]],"color":"#FF245364","width":6,"cornerRadius":12,"figureFill":{"mode":"pattern","pattern":{"kind":"checker","tileSize":16,"foreground":"#FFE8DCC5","background":"#FFB6C8C2"}}}
```
椭圆改为 `tool:ellipse, cornerRadius:0`；固定160×90且从中心起笔加 `fixedWidth:160, fixedHeight:90, drawFromCenter:true`；图片图案使用 `figureFill:{mode:pattern,pattern:{kind:image,asset:TILE_ASSET,scale:1,angle:0,offset:[0,0]}}`；矢量层省略 brush/brushPresetId 且填充限 none/solid。能力元数据自带字段约束和简例。

版本 `0.2.46` / versionCode `49` / applicationId `com.ai.limbs.payload.artstudio.v0246`。本批只做静态检查和开发仓库保存，未编译、未运行测试、未推送云端；运行效果待之后部署验证。


### 0.2.47：栅格多边形、折线、三次曲线的共享笔刷（尚未编译）

- 参照 Krita 6.0.4 的 `plugins/tools/tool_polygon/kis_tool_polygon.cc`、`tool_polyline/kis_tool_polyline.cc`、`basictools/kis_tool_path.cc`，以及 `libs/ui/tool/kis_tool_polyline_base.cpp` 的多点草稿/撤点/结束职责、`kis_figure_painting_tool_helper.cpp` 的 StrokeStyleBrush/FillStylePattern；本插件独立采用 Android Path/PathMeasure 与 dab-v1 实现，未复用源码。
- polygon 自动闭合，polyline 保持开放，三次 bezier 为起点+每段两个控制点和终点。线段逐段保留精确转角，三次曲线逐段按弧长生成采样并保留锚点；原始控制点独立保存于 `pathInput`，最终笔刷采样与随机种子进入不可变历史。最多2048折线/多边形控制点、1024曲线控制点、10000笔刷采样，零长度或超预算明确拒绝。
- 三工具共享六种栅格笔刷、预设、笔尖、纹理和动态曲线。几何路径压力固定1、倾角/旋转0、按采样序号合成时间；关闭加权平滑、稳定器与停驻喷绘，保留像素完美。`outline=brush/basic`，polygon 可用 none 加非空填充。
- 多边形 `figureFill` 支持 none/solid/pattern。棋盘、条纹、圆点及1–512px RGBA图片平铺复用 ArtFigureRenderer，图案缩放/角度/偏移、透明度合成、橡皮蒙版、资源保存/导入重映射和内存预算使用同一链路。折线及栅格曲线不接受填充；矢量工具不受此批修改影响。旧的无pathVersion记录继续按原有记录语义重放。
- 手机保留逐点/双击完成、单段四点曲线/连续曲线模式，新增完成/撤回一点/取消按钮及 Enter/Delete/Escape。首点捕获工程版本、图层变换、样式和随机种子；工程/版本/图层/工具切换或双指视图手势取消草稿。预览完整路径和保存共用生成器及渲染器，不完整曲线段仅显示控制线。
- 兰儿入口 `path.info / path.geometry / path.draw`，以及已有 `stroke.add(tool=polygon/polyline/bezier)`；每个入口提供精简示例和参数范围。绘画仍传原始控制点，不传 geometry 返回的 outlinePoints。

简例（调用 path.draw，ID/revision 替换为当前值）：
```json
{"documentId":"DOCUMENT_ID","expectedRevision":0,"layerId":"PAINT_LAYER_ID","tool":"polygon","points":[[40,50],[180,40],[210,150],[80,180]],"color":"#FF245364","width":6,"figureFill":{"mode":"pattern","pattern":{"kind":"checker","tileSize":16,"foreground":"#FFE8DCC5","background":"#FFB6C8C2"}}}
```
折线改 `tool:polyline` 并省略 figureFill；三次曲线改 `tool:bezier, points:[[30,120],[70,20],[150,220],[210,100]]` 并省略 figureFill。笔刷预设加 `brushPresetId:PRESET_ID`，图片填充加 `figureFill:{mode:pattern,pattern:{kind:image,asset:TILE_ASSET}}`。

版本 `0.2.47` / versionCode `50` / applicationId `com.ai.limbs.payload.artstudio.v0247`。本批仅静态检查和开发仓库提交；未编译、未运行测试、未推送云端，实际运行待之后部署验证。


### 0.2.48：可编辑子路径、多节点与高级对象样式（尚未编译）

- 参照 Krita 6.0.4 的 `libs/flake/commands/KoPathBreakAtPointCommand.cpp`、`KoSubpathJoinCommand.cpp`、`KoPathPointMergeCommand.cpp`、`tools/KoPathTool.cpp::convertToPath` 和 `KoShapeStroke.cpp`。独立以 Kotlin 的节点/控制柄、Android Path/Matrix/Paint 实现，未复用源码；业务仍全部在插件。
- 原有 points/commands 的 L/C 扩展 M（下一子路径起点，耗1点）、Z（闭合，不耗点），单一路径的 closed 仍保留。多子路径 closed=false，闭合信息由各 Z 保存；nodeModes对应全局逻辑节点，闭合重复端点不计入节点。最多64子路径、总2048段/6145几何点；每个开放子路径至少2节点。已有单路径接口、记录及节点地址保持原义。
- path.edit 支持全局节点，或 subpath+局部 node/segment；新增 move_nodes/node_types/delete_nodes 的批量移动、改类型、删点。手机有点选叠加、Shift增减、拖框、全选/清空；关闭叠加/框选后可拖动任一选中节点整体移动，柄随节点平移。几何预览与提交均走同一子路径编辑器，视图变换或工程上下文变化中止拖动。
- path.topology: break_node在内部节点复制端点断开，保留两侧曲线；闭合路径从该节点打开；break_segment移除对应后段；join可连接不同子路径或闭合同子路径，必要时反向并交换入出柄；merge将两端点移到中点并保留相邻柄偏移。仅开放端点可连接/合并，零几何、断开后不足节点明确拒绝。
- path.convert保留对象ID、矩阵和样式，将直线/多边形精确转段，矩形含圆角、椭圆用四分之一弧三次曲线（kappa近似）。path.combine将同层多个路径坐标映射到首对象局部空间，首对象ID/样式保留，其他对象在同一可撤销操作中消耗；独立子路径随后用topology连接。两种操作均有手机入口。
- shape.style(style.objectStyle)与path.create(style.objectStyle)支持线帽、转角、尖角限值、实线/虚线及偏移、nonzero/evenodd填充规则、填充/描边独立透明度、线性/径向填充和描边渐变。渐变坐标为对象局部，2–16个严格递增色站（手机面板编辑两端颜色），径向end定义半径。新高级样式将填充和描边一次合成对象透明度；无objectStyle的历史记录保留原有按画笔透明度绘制的语义。命中/框选使用同样的线帽、转角、虚线几何和填充规则，填充只覆盖闭合子路径。图片导出/缩略图共用对象渲染器；工程序列化原样保存样式与命令。
- 兰儿入口：path.topology_info、path.topology、path.convert、path.combine、shape.style_info，以及原有path.nodes/path.edit/shape.style。元数据自带最小示例、零起始地址与局部坐标说明，仅读入口不附加画布反馈。高级对象合成面的内存已加入既有预算。

简例（ID与revision替换为当前值；地址取path.nodes.subpaths）：
```json
{"documentId":"DOCUMENT_ID","expectedRevision":0,"layerId":"VECTOR_LAYER_ID","id":"PATH_ID","action":"break_node","at":[0,1]}
```
将上例传path.topology；断开后重读path.nodes，再传 `action:join, first:[0,1], second:[1,0]`（示例两条开放子路径各2点），或改 `action:merge`。
批量移动调用path.edit，`edits:[{action:move_nodes,nodes:[0,1],dx:10,dy:5}]`。
渐变/虚线调用shape.style，`style:{objectStyle:{strokeCap:round,strokeJoin:bevel,dashArray:[8,4],fillGradient:{type:linear,start:[0,0],end:[100,0],stops:[[0,"#FF245364"],[1,"#FFE8DCC5"]]}}}`，目标带ids数组。

版本 `0.2.48` / versionCode `51` / applicationId `com.ai.limbs.payload.artstudio.v0248`。仅静态检查和开发仓库保存，未编译、未运行测试、未推送云端；运行效果待统一部署验证。


### 0.2.49：矢量徒手接续与独立优化（尚未编译）

- 参照 Krita 6.0.4 `plugins/tools/basictools/kis_tool_pencil.cc`、`libs/basicflakes/tools/KoPencilTool.cpp` 的端点命中、connectPaths、多模式优化与combineAngle职责；以本插件 Kotlin 拟合器、子路径节点和矩阵独立实现，未复用源码。
- Raw默认保留原始采样，optimizeRaw=true以rawPrecision做距离减点；Curve默认沿用分段拟合，curvePrecision独立配置，optimizeCurve=false逐采样段做三次插值。两项优化开关彼此独立；Raw误差默认1，Curve误差省略则沿用旧precision，均0.25–32局部像素。
- Straight先按precision做距离简化，再以combineAngle合并相邻转角（0–90°，0关闭额外合并）。采用点积/acos比较转角以处理方位角跨0°，不合并反向段；所有被合并的原始采样须在弦投影范围内且偏差不超过precision，避免累计小角度把明显弧线拉直。保留路径端点，闭合边也受偏差保护。
- shape.freehand可传startEndpoint/endEndpoint:{id,subpath,node}；坐标仍是图层局部。开放且可见/未锁定的既有路径可从任一端接续，必要时反向并交换柄；新轨迹精确接点、接续柄随点移动，原目标节点不经过浮点矩阵往返。接续两条路径可自动合并，接回同路径另一端可闭合；未接续的其他子路径保留。结果使用起点目标（只有终点则使用终点目标）的ID、矩阵和样式，消耗其他目标对象；一笔为一个可撤销历史操作，先校验文档revision再保存，不在重放时重新拟合。
- 手机“接续已有端点”在12dp屏幕距离内查找开放端点，匹配无关对象层级只限定当前矢量层；闭合模式与接续互斥，接续时不使用Shift，接回另一端自动闭合。起笔捕获参数/样式/版本；新路径可设置高级对象样式，接续预览继承起点目标样式并在其对象坐标里渲染。拖动阶段预览Raw几何，收笔后一次拟合并显示最终路径。高级样式与上批共用；也提供合成所选路径按钮，节点编辑/连接用已有path.combine/path.topology。
- 兰儿入口shape.freehand_info、shape.freehand，以及path.nodes/path.combine/path.topology/shape.style；元数据有最小示例、参数默认值、端点地址和坐标空间。style.objectStyle支持已有线帽、转角、虚线、填充规则、独立透明度及填充/描边渐变。

简例（shape.freehand，ID/revision替换当前值）：
```json
{"documentId":"DOCUMENT_ID","expectedRevision":0,"layerId":"VECTOR_LAYER_ID","points":[[100,100],[130,85],[160,110]],"mode":"curve","optimizeCurve":true,"curvePrecision":2,"startEndpoint":{"id":"PATH_ID","subpath":0,"node":2}}
```
node=2仅适用于目标子路径有3点且该点为开放端点，请读path.nodes替换。加endEndpoint可桥接另一条路径或接回同路径另一端；Raw优化改mode:raw,optimizeRaw:true,rawPrecision:1；角度合并改mode:straight,precision:2,combineAngle:8。

版本 `0.2.49` / versionCode `52` / applicationId `com.ai.limbs.payload.artstudio.v0249`。仅静态检查和开发仓库保存，未编译、未运行测试、未推送云端；运行效果待统一部署验证。


### 0.2.50：基础矢量形状选择布局与剪切（尚未编译）

- 参照 Krita 6.0.4 `libs/flake/commands/KoShapeAlignCommand.cpp`、`KoShapeDistributeCommand.cpp`、`plugins/tools/defaulttool/defaulttool/DefaultTool.cpp` 和 `ShapeShearStrategy.cpp` 的轮廓布局、按位置分布、固定对边剪切与撤销职责；以 Kotlin/Android Matrix 和插件历史独立实现，未复用其源码。宿主不变。
- 六种对齐：左/水平中心/右、顶/垂直中心/底。reference=selection（默认，至少2个）使用所选轮廓联合范围；reference=canvas（至少1个）使用整张画布范围。UI明确选择基准，不自动切换语义。八种分布：上述六种边/中心加水平/垂直等间距，至少3个对象，保持首尾位置；同坐标用稳定列表排序保留所有对象，重叠时允许负间距。
- 布局使用画布坐标轴，先将各对象路径连同对象/图层/父组矩阵变换到画布，计算轮廓范围（不计描边），再将位移向量变换回图层局部；不会仅变换已有轴向包围盒造成旋转后范围膨胀；shape.list的documentBounds也共享该路径范围计算。间距按左/顶边排序，以首尾对象外边距和各自宽/高计算，固定首尾对象，允许中间宽对象或负间距。
- 形状选择参数增加对齐基准与六种对齐、八种分布按钮，以及“交互剪切”开关。开启后边中点显示菱形：上/下边横向剪切，左/右边纵向剪切，固定相对边；角点缩放、圆点旋转仍可使用。拖动只预览delta，收手通过现有SHAPE_TRANSFORM记录一次撤销。框选/平移原有交互保留，多选开关/Shift优先框选；剪切模式下先结束多选再拖柄。图层局部范围宽/高<=0.001时对应剪切明确拒绝；交互系数限制±100。视图坐标改变或多指触摸取消当前操作，防止混合坐标帧。
- 新增兰儿入口shape.layout_info、shape.align、shape.distribute、shape.shear。shearX/shearY为图层局部系数（不是角度），pivot:[x,y]省略则使用所选中心；系数±100、支点坐标±1000000，矩阵退化明确拒绝。同层对象须可见未锁定，图层/父组须可见未锁定，写入仍需当前expectedRevision。批量布局一次历史操作；重放通过相同布局分支，不修改路径节点或对象样式。
- 渐变、虚线、端帽与接合样式沿用0.2.48已实现的共享ArtObjectStyle/StudioObjectStyleOptions，在形状选择“高级对象样式”中操作；本批明确分组标签并补入口说明。支持线性/径向填充或描边渐变、2–16色标（API，UI编辑起终两色）、虚线数组与偏移、三种线帽/接合及尖角限值。shape.style接收style.objectStyle局部补丁，shape.style_info返回范围；命中、框选、缩略图和栅格导出共用样式渲染。

简例（替换ID和当前revision，各次成功后刷新revision）：
```json
{"documentId":"DOCUMENT_ID","expectedRevision":0,"layerId":"VECTOR_LAYER_ID","ids":["A","B"],"mode":"left","reference":"selection"}
```
以上调用shape.align；shape.distribute示例改ids为至少3个并用mode:"gap_x"（不传reference）；shape.shear示例用ids:["A"],shearX:0.25,shearY:0，可加pivot:[100,100]。样式简例（shape.style）：
```json
{"documentId":"DOCUMENT_ID","expectedRevision":0,"layerId":"VECTOR_LAYER_ID","ids":["A"],"style":{"objectStyle":{"strokeCap":"round","strokeJoin":"bevel","dashArray":[12,6],"fillGradient":{"type":"linear","start":[0,0],"end":[100,0],"stops":[[0,"#FFFF8800"],[1,"#FF3388FF"]]}}}}
```

版本 `0.2.50` / versionCode `53` / applicationId `com.ai.limbs.payload.artstudio.v0250`。仅静态检查和仓库提交，未编译、未运行测试、未推送云端；交互及升级后的历史重放待统一部署验证。


### 0.2.51：矢量书法路径、倾斜、惯性与配置档（尚未编译）

- 参照 Krita 6.0.4 `plugins/tools/karbonplugins/tools/CalligraphyTool/KarbonCalligraphyTool.cpp` 的calculateNewPoint/setAngle/setMass/setDrag，以及 `KarbonCalligraphyOptionWidget.cpp` 的配置档保存、加载、删除职责；以现有椭圆笔尖扫掠、PathMeasure、Android数位笔事件与插件JSON存储独立实现，未复用其源码，宿主不变。
- 跟随模式显式指定当前矢量层的可见路径、子路径（零起始）及正/反向；当前选中路径有快捷按钮，也可在参数面板选路径。对象矩阵先变换到图层局部；按鼠标累计移动距离推进弧长，从子路径起点（反向终点）开始，到末端停止，闭合路径只走一圈，不跳到其他子路径。原导引不改变，生成独立可编辑轮廓。导引只读，允许锁定路径，但绘制层与父组仍须可见未锁定。中心轨迹按不超过4局部像素插点，避免稀疏鼠标事件跳过曲线，最多1000中心点，超限明确拒绝、请分段。跟随时惯性/平滑不参与，避免偏离导引。
- 数位笔倾斜开关要求stylus/eraser输入以及AXIS_TILT、AXIS_ORIENTATION两个设备轴；不支持明确提示，手指/鼠标需关闭。当前与历史事件均采样，Android屏幕方位转成图层局部方向后，笔尖取倾斜方位的垂直方向，并按fixation与轨迹法向混合。API每点tilt=0–90度（离竖直）和orientation=0–360度（图层+X顺时针）。直立时保持本笔最近非零倾斜方向，起笔直立使用设置角；倾斜控制方向，不额外改变笔宽。实际硬件支持及方向效果待部署验证。
- Mass 0–20/Drag 0–1，逐输入点计算velocity=velocity*(1-drag)+(cursor-position)/(mass²+1)，再position+=velocity；因此采样密度会影响惯性，时间仍用于现有速度变细和平滑。默认0/1保留旧直接输入，新质量/阻力不强行将收笔终点吸到指针。惯性后再使用已有时间平滑，笔压和椭圆笔尖扫掠共享同一几何逻辑，轮廓段数仍<=2048。UI预览与写入同一函数，最终几何随SHAPE_CREATE保存，历史重放不重新计算惯性或读取导引。
- 新增calligraphy.info、calligraphy.profiles/profile.get/profile.save/profile.delete；已有shape.calligraphy新增mass/drag/useTilt/followPath/followPathId/followSubpath/followReverse/profileId。profileId装载完整设置，本次显式字段覆盖。请求仍需当前documentId/expectedRevision，解析配置档与导引、生成几何到写入在同一存储锁内；工程变更明确拒绝。
- 配置档保存于画室持久根目录calligraphy-profiles.json，复用现有原子写入，不与栅格笔刷预设混用。最多128档，UUID为键，名称1–64字符。保存笔宽/颜色/透明度、笔尖/笔压/平滑/Mass/Drag/倾斜/跟随方向等全部行为参数；不保存工程、路径ID或子路径引用，跨工程加载后须选择导引。UI支持加载、另存、更新选中和删除，IO在协程工作线程。删除只删参数档，不改当前面板参数或已画轮廓；取消手势、切工程/改版本、变换视图和多指操作不提交半笔。

简例：shape.calligraphy（ID/revision替换当前值）
```json
{"documentId":"DOCUMENT_ID","expectedRevision":0,"layerId":"VECTOR_LAYER_ID","samples":[{"x":10,"y":10,"time":0,"pressure":1},{"x":50,"y":35,"time":50,"pressure":0.8},{"x":100,"y":80,"time":100,"pressure":0.7}],"width":20,"mass":3,"drag":0.7}
```
跟随加followPath:true,followPathId:"PATH_ID",followSubpath:0；反向加followReverse:true。倾斜加useTilt:true且每个sample均包含tilt:30,orientation:45；角度均为度。calligraphy.profile.save最小例：
```json
{"name":"稳健书法","settings":{"width":24,"angle":45,"mass":3,"drag":0.7,"useTilt":false}}
```
保存返回档ID；读取/删除用calligraphy.profile.get/delete({id:"PROFILE_ID"})，更新在save请求加id。调用shape.calligraphy可加profileId:"PROFILE_ID"，显式width等覆盖；followPath档仍须请求提供路径引用。

版本 `0.2.51` / versionCode `54` / applicationId `com.ai.limbs.payload.artstudio.v0251`。仅静态检查和仓库提交，未编译、未运行测试、未推送云端；硬件倾斜、交互预览、配置档跨升级读取与历史重放待统一部署验证。


### 0.2.52：四种基本软选区（尚未编译）

矩形、椭圆、多边形、自由套索创建统一支持 replace/add/subtract/intersect/xor、抗锯齿强度0..1、羽化半径0..32 px、扩展／收缩-64..64 px。参数以文档像素计，先栅格化、扩展／收缩，再羽化，最后组合。矩形和椭圆拖动；套索松手闭合；多边形逐点点击，点击首点、双击末点、Enter或完成按钮闭合，退格撤回顶点，Esc／右键／双指取消。首点捕获工程、修订、视图和参数，工程或视图变化拒绝提交，超预算明确报错。

新增8位coverage（deflate-alpha-v1）随已有二值扫描轮廓保存，保留孔洞与不相连区域；扫描轮廓供命中／范围限制，真实像素效果使用0..255覆盖率。添加min(255,a+b)，减去max(0,a-b)，相交min(a,b)，异或abs(a-b)。没有已有选区时add/xor创建，subtract/intersect为空；显式空选区与取消选区不同，不会变成全画布操作。处理范围裁到画布，最多4194304像素／32768非零扫描段。旧几何选区和旧历史保持原语义；新操作直接存最终蒙版，撤销重放不重新羽化。

新笔画保存当时的选区和文档到图层的矩阵；自由笔刷、动态／多重笔刷、直线、栅格形状与路径使用共享软边描绘和擦除，预览与保存走同一渲染入口。像素填充、清除、复制／剪切、连续／围合填充、滤镜、修补输出按覆盖率处理。菜单扩展／收缩改为形态运算，不再缩放外框。矢量对象编辑、中心线命中及上色蒙版的颜色标签求解仍按几何／非零范围，不把这些离散标签解释成像素透明度。选区反馈显示覆盖率浅蓝蒙版，导出不含选区显示。

AI 原四入口 selection.create/ellipse/polygon/freehand 现有坐标参数保留，新增可选documentId、expectedRevision、mode、antialias、feather、expand。selection.basic_info读默认值和范围；selection.adjust对当前选区应用expand/feather（累积羽化，不接受mode/antialias）；selection.coverage只读返回坐标的覆盖率。三者及四个创建入口都有精简参数说明与可直接替换id/revision的示例。示例：读取document.info后，selection.create({documentId:ID,expectedRevision:REV,x:20,y:20,width:120,height:80,mode:"add",antialias:1,feather:4,expand:2})。下一次写操作先更新revision。

参考 Krita 6.0.4 的 kis_tool_select_rectangular/elliptical/polygonal/outline、kis_pixel_selection.cpp 与 kis_selection_filters.cpp，独立实现覆盖率组合规则。形态处理采用方形邻域最大／最小值，羽化采用三次滚动盒滤波近似高斯、画布外视为0；与Krita的圆盘形态及高斯滤波不逐像素等同。此前章节延后的软选区能力，本轮在四种基本创建工具及共享像素使用链路补齐；颜色／磁性／贝塞尔创建工具的独立抗锯齿和羽化参数仍另行迭代，可用selection.adjust统一处理现有选区。

版本0.2.52 / versionCode55 / applicationId com.ai.limbs.payload.artstudio.v0252。仅源码静态检查与仓库提交，未编译、未运行测试、未推云端；抗锯齿软边、擦除、组合、交互及保存重放待统一部署后实测。


### 0.2.53：连续区域与相似色软选区（尚未编译）

selection.contiguous与selection.similar保留现有取样/参考/容差接口，新增opacitySpread（覆盖硬度0–100，默认100）、antialias（0..1，默认1）、feather（0..32 px，默认0）、stopAtDarkest（默认false）、colorLabels（默认[1]）；expand扩展为-64..64 px。连续工具仍是固定取样色的四邻域搜索；相似工具扫描全部区域。两者直接生成上一轮8位coverage蒙版，replace/add/subtract/intersect/xor使用同一覆盖率公式，新历史保存最终蒙版，旧操作不重算。

颜色距离d是预乘RGBA最大通道差，容差T=tolerance*255/100。硬度100保留容差内二值选中；硬度H<100时coverage=clamp((T-d)*255*100/(T*(100-H)),0,255)，d>=T为0，越接近取样色覆盖率越高。边界色模式使用反向曲线。软覆盖要求tolerance>0，精确匹配使用tolerance=0/opacitySpread=100，非法组合明确报错；页面禁用容差0时的硬度调节，软硬度下容差下限为1。

处理顺序：颜色搜索与缺口处理 → 最大/最小覆盖率形态扩展/收缩 → 羽化，或feather=0时的可调抗锯齿 → 与范围限制选区的覆盖率取小值 → 组合。颜色覆盖与空间羽化彼此独立。抗锯齿仅对接触0覆盖的边界采用1px十字邻域（中心权重4、邻居各1）加权平滑，再按强度插值；羽化使用共享三次盒滤波近似高斯。全部结果限制在查找bounds/画布内，单次最多4194304像素和32768非零扫描段；内存不足不缩图搜索。返回selectedPixels为本次生成蒙版非零像素数、coverageSum为覆盖率总和（除以255可得等效全选像素数），不表示组合后面积。

stopAtDarkest仅作用于正expand：先得到正常扩展的覆盖率，再以原非零范围为种子沿8邻域传播。下一像素透明度须不下降，亮度须不增加；上一像素全透明时只检查透明度。进入更暗或更不透明的像素后，不越过局部暗峰向亮区传播。传播始终受正常扩展范围限制，输出保留其软覆盖，不把边缘强制全选。初始颜色搜索不受该开关影响；后续羽化会柔化停止边缘。

新增图层colorLabel属性及图层属性面板选项/列表标记：0无标签，1蓝、2绿、3黄、4橙、5红、6紫、7灰、8棕，是画室自定义稳定ID，不是像素颜色。旧工程缺该属性表示0，复制与保存沿用图层JSON，修改进入撤销历史。reference=labels配colorLabels=[1,2]仅合成匹配的可见内容层；匹配的非零标签组包含其可见子层，无标签组只逐层遍历。保留祖先组、层级顺序、变换、透明度和混合，隐藏层不参加；没有匹配内容明确报错，不改用全部可见层。current/visible原参考规则保持。

AI入口与精简示例：selection.color_info返回默认值、公式、标签目录及设置方法；layer.properties({id:LAYER_ID,colorLabel:1,expectedRevision:REV})设置标签；selection.contiguous({documentId:ID,expectedRevision:REV,layerId:LAYER_ID,x:30,y:30,reference:"labels",colorLabels:[1,2],tolerance:20,opacitySpread:50,antialias:0.75,feather:0,expand:2,stopAtDarkest:true,mode:"xor"})创建选区；相似色改用selection.similar。每次写后更新revision；selection.coverage可只读核对0..255软边。手机两个参数面板均提供全部选项，连续工具另保留边界色和缺口处理。

参考Krita 6.0.4的kis_tool_select_contiguous/similar、KisColorSelectionPolicies.h、kis_fill_painter.cc、KisGrowUntilDarkestPixelSelectionFilter与KisMergeLabeledLayersCommand的规则，独立实现；Krita色彩空间差值、圆盘形态、跨度插值抗锯齿、双扫描自适应增长与本插件的RGBA差值、方形形态、1px边界平滑、队列传播不逐像素等同。版本0.2.53 / versionCode56 / applicationId com.ai.limbs.payload.artstudio.v0253。仅静态检查与仓库提交，未编译、未运行测试、未推云端；实机取样、标签组参考、软边与撤销重放待统一部署验证。
