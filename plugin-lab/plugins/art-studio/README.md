# AI Limbs 画室（0.2.30 源码；统一工具参数浮窗，待编译验收）

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

## 工具箱待实现位置

左抽屉上部为画室已接入的基础工具，下部为「待实现」灰色区。灰色按钮不可选中、不会触发画布操作，长按与无障碍标签会说明「尚未实现」。清单按手机上的 Krita 6.0.4 源码中实际注册的工具工厂核对，独立工具位与工具内部选项分开：填充阈值、笔刷参数和渐变预设等属于已有工具的选项，不另造假按钮。画室现有铅笔、栅格书法笔及栅格贝塞尔曲线不能代替 Krita 的可编辑矢量路径；相关矢量工具因此单独留位。

| 待实现工具 | 工具 ID | Krita 6.0.4 源码入口 | 所需基础能力 |
| --- | --- | --- | --- |
| SVG 文字高级排版 | svg_text_advanced | plugins/tools/svgtexttool/SvgTextToolFactory.cpp | 完整 SVG 排版、富文本与源码编辑；基础可编辑文字已单独实现 |
| 矢量书法笔 | vector_calligraphy | plugins/tools/karbonplugins/tools/CalligraphyTool/KarbonCalligraphyToolFactory.cpp | 可编辑路径与控制点 |
| 参考图像 | reference_images | plugins/tools/defaulttool/referenceimagestool/ToolReferenceImages.h | 参考图像资源 |
| 绘画辅助尺规 | assistant | plugins/assistants/Assistants/assistant_tool.cc | 辅助对象与笔画约束 |
| 智能修补、上色蒙版编辑 | smart_patch、colorize_mask | plugins/tools/tool_smart_patch/kis_tool_smart_patch.h；plugins/tools/tool_lazybrush/kis_tool_lazy_brush.h | 区域修补与上色蒙版 |
| 围合填充、漫画分格编辑 | enclose_fill、comic_panel | plugins/tools/tool_enclose_and_fill/KisToolEncloseAndFillFactory.h；plugins/tools/tool_knife/KisToolKnife.h | 封闭区域计算与分格对象 |
| 贝塞尔曲线选区、连续区域选区、相似色选区、磁性套索选区 | select_bezier、select_contiguous、select_similar、select_magnetic | plugins/tools/selectiontools/kis_tool_select_path.h、kis_tool_select_contiguous.h、kis_tool_select_similar.h、KisToolSelectMagnetic.h | 像素选区蒙版及相关路径算法 |

清单单一来源为 ArtToolCatalog.kt。兰儿读取 toolbox.catalog 可得到各项 id、label、implemented 与 status；待实现项还返回 Krita 相对源码路径，且没有执行能力。现有工具的 status=basic 只表示本画室已有可用入口，不表示已达到 Krita 完整行为。每次真正完成工具时，应在清单中把它从 pending 移至 implemented，并同时接通画布、工程记录与兰儿入口；保留的灰色位置不能冒充实现。

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
