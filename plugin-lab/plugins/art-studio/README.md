# AI Limbs 画室（0.2.21 源码；修复 0.2.20 编译错误，格式与基础文字待编译验收）

画室是独立的 android_inprocess 插件。页面与兰儿能力共用 ArtStore 工程目录、文件锁和当前工程指针；本次文件菜单迭代没有改动基座，也没有改变 .ailart 的格式号。UI 创建或导入的新工程记录 createdBy=AWEI，兰儿通过能力创建、导入、模板创建、另存为或复制的新工程记录 createdBy=LANER；画布编辑历史仍以 AWEI / LANER 标注。

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
| 三次贝塞尔曲线 | 默认依次点起点、两处控制点与终点，第四点保存；打开连续曲线模式后每三个点接一段，完成的端点上双击保存，未完成段只预览 | stroke.add，tool=bezier，points 为 1+3n 点（4–1024 点） |
| 颜色取样 | 点击合成画布更新当前笔色；半径可设为 0–32 px，圆形范围内的像素按透明度混合；可在合成画布与可见根绘画／图像层之间切换，还可选取样色与当前色的混合比例 | color.sample（画布像素坐标，可选 radius、blend、sampleMerged；blend<100 需 baseColor，sampleMerged=false 需 layerId） |
| 连续区域填充／擦除 | 左侧「填充选项」可设容差（0–100）、参考当前图层／所有可见图层及擦除模式；遵循当前选区，使用 PNG 像素掩码可撤销地修改当前可编辑根图层 | fill.contiguous（x/y/color，可选 tolerance、referenceAllLayers、erase、expectedRevision） |
| 线性、径向、角度渐变 | 拖动定义渐变距离；终点可设为透明或指定颜色，可切换线性／径向／角度和反向，在当前绘画层记录渐变事件 | stroke.add，tool=gradient，可选 gradientMode=linear/radial/angular、gradientReverse、gradientEndColor=#AARRGGBB |
| 矩形、椭圆、多边形、自由套索选区 | 拖动创建矩形/椭圆/套索；逐点点击、双击最后一点结束多边形；复制/清除/填充沿真实边界裁剪 | selection.create、selection.ellipse、selection.polygon、selection.freehand |
| 裁剪画布 | 拖动矩形裁剪；裁到画布范围且边长至少 64 px，保留操作历史 | canvas.crop |
| 移动与变换图层、平移画布 | 移动和变换工具都可拖动当前图层；变换工具的「变换参数」可设置位置、缩放和旋转，打开时读取当前图层值；有选区时拖动沿用当前选区移动操作 | transform.move/scale/rotate、selection.edit；视图平移只属于当前页面 |
| 测量距离、缩放画布 | 拖动可读两点距离与角度；点击缩放画布视图，双指缩放沿用已有手势 | canvas.measure；视图缩放只属于当前页面 |

## 工具箱待实现位置

左抽屉上部为画室已接入的基础工具，下部为「待实现」灰色区。灰色按钮不可选中、不会触发画布操作，长按与无障碍标签会说明「尚未实现」。清单按手机上的 Krita 6.0.4 源码中实际注册的工具工厂核对，独立工具位与工具内部选项分开：填充阈值、笔刷参数和渐变预设等属于已有工具的选项，不另造假按钮。画室现有铅笔、栅格书法笔及栅格贝塞尔曲线不能代替 Krita 的可编辑矢量路径；相关矢量工具因此单独留位。

| 待实现工具 | 工具 ID | Krita 6.0.4 源码入口 | 所需基础能力 |
| --- | --- | --- | --- |
| 形状选择 | shape_select | plugins/tools/defaulttool/defaulttool/DefaultToolFactory.cpp | 矢量对象 |
| SVG 文字高级排版 | svg_text_advanced | plugins/tools/svgtexttool/SvgTextToolFactory.cpp | 完整 SVG 排版、富文本与源码编辑；基础可编辑文字已单独实现 |
| 矢量徒手路径、可编辑贝塞尔路径、矢量书法笔 | vector_freehand、vector_bezier、vector_calligraphy | plugins/tools/basictools/kis_tool_pencil.h、kis_tool_path.h；plugins/tools/karbonplugins/tools/CalligraphyTool/KarbonCalligraphyToolFactory.cpp | 可编辑路径与控制点 |
| 参考图像 | reference_images | plugins/tools/defaulttool/referenceimagestool/ToolReferenceImages.h | 参考图像资源 |
| 绘画辅助尺规 | assistant | plugins/assistants/Assistants/assistant_tool.cc | 辅助对象与笔画约束 |
| 智能修补、上色蒙版编辑 | smart_patch、colorize_mask | plugins/tools/tool_smart_patch/kis_tool_smart_patch.h；plugins/tools/tool_lazybrush/kis_tool_lazy_brush.h | 区域修补与上色蒙版 |
| 围合填充、漫画分格编辑 | enclose_fill、comic_panel | plugins/tools/tool_enclose_and_fill/KisToolEncloseAndFillFactory.h；plugins/tools/tool_knife/KisToolKnife.h | 封闭区域计算与分格对象 |
| 贝塞尔曲线选区、连续区域选区、相似色选区、磁性套索选区 | select_bezier、select_contiguous、select_similar、select_magnetic | plugins/tools/selectiontools/kis_tool_select_path.h、kis_tool_select_contiguous.h、kis_tool_select_similar.h、KisToolSelectMagnetic.h | 像素选区蒙版及相关路径算法 |

清单单一来源为 ArtToolCatalog.kt。兰儿读取 toolbox.catalog 可得到各项 id、label、implemented 与 status；待实现项还返回 Krita 相对源码路径，且没有执行能力。现有工具的 status=basic 只表示本画室已有可用入口，不表示已达到 Krita 完整行为。每次真正完成工具时，应在清单中把它从 pending 移至 implemented，并同时接通画布、工程记录与兰儿入口；保留的灰色位置不能冒充实现。

这仅是 Krita 左侧工具的第一批真实操作：尚缺颜色标签图层参考及边界填充、渐变预设与色彩空间、可编辑贝塞尔控制点/自由路径、书法笔矢量轮廓及速度调角、矢量形状、完整 SVG 文字排版、高级变换、参考图像、辅助尺规、蒙版及磁性套索、相似色等其他选区。它们各自需要补画笔引擎、矢量对象、像素选区蒙版或相应的资源类型；不得将现有笔画、矩形选区或移动操作改名冒充。连续区域填充默认按 RGBA 像素完全匹配，容差 0–100 映射到每个通道 0–255 的最大差值；可参考所有可见图层，但仍只写当前图层；正常填色仍拒绝完全透明的颜色；擦除模式用独立掩码清除图层像素；选择非根图层、隐藏/锁定或已变换的图层时也会拒绝，避免编辑到错误像素。渐变提供前景色到透明或指定终点色的线性／径向／角度基础模式，不具备 Krita 的完整预设和混合选项。动态画笔当前只移入质量／阻力轨迹过滤，Krita 的固定角度与速度相关笔宽尚未移入；栅格书法笔不生成 Krita 的矢量轮廓。这些工具在本画室的数据模型中实现；完整 Krita 行为与手机端交互需按各项边界验收。

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
