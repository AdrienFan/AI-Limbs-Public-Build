# AI Limbs 画室（开发中，0.2.9）

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
| 粘贴为新图像 | 用内部剪贴板的原始像素尺寸创建独立工程；边长限 64–4096 px | edit.paste_new |
| 清除、填充前景色、填充背景色 | 作用于活动图层选区；每次操作按顺序保存，之后的新笔画绘在上方。背景色由菜单对话框指定 | edit.clear、edit.fill_foreground、edit.fill_background |
| 剪贴板信息 | 查询可用像素尺寸及剪贴来源 | edit.clipboard_info |

剪贴板存放在插件私有目录，由阿伟和兰儿共用；目前没有与 Android 系统剪贴板互通。剪切、清除、填充和粘贴到活动层只对根层级、未平移/旋转/缩放且可见未锁定的绘画层或图像层启用；复制允许可见根图层经过变换或锁定，合并复制读取可见合成画布。选区支持矩形、椭圆、多边形与自由套索，越过画布的部分裁到画布范围；非矩形选区的复制、剪切、清除、填充与连通填充均按形状边界裁剪。剪贴板 PNG 限 8 MB；现阶段不支持群组、复杂变换、磁性套索或相似色等选区；非矩形选区的旋转尚不可用。自由套索超过 2048 个采样点时会稀疏采样，以限制工程数据与绘制开销。剪切、清除、填充和粘贴会记录操作历史、进入工程归档，导入归档时重新映射引用资源。

以下 Krita 菜单项在界面显示为不可用：锐利剪切/复制、图层样式复制与粘贴、参考图像粘贴、矢量形状样式粘贴、填充图案及额外属性、选中形状与选区描边、拾取屏幕颜色。当前工程没有对应的锐利边缘剪辑、图层效果、参考图、矢量形状、图案资源、描边或屏幕采样数据与授权链路；不把普通剪贴和填充操作伪装成这些动作。

## 保存与工程数据

草稿位于 drafts/<id>.json，显式保存位于 documents/<id>.ailart，内含 project.json 和图层引用的 PNG 资源；包括由“复制当前图像”生成并写进 base 的图片图层。保存摘要位于 documents/<id>.sha256，用于判断已有存档是否仍对应当前操作。外部文档 URI 存在 external-links.json；文件选择器返回可持久写入权限时才会关联。私有副本已写入但外部同步失败，会显示待同步状态，菜单“保存”可重试。“另存为”在用户选定位置且写入成功后才切换活动工程；新工程有独立 ID 和操作历史。

最近打开的工程 ID 按顺序写入 recent.json。关闭没有改动的工程仅取消当前指针；关闭时明确选择舍弃会恢复上次保存的私有存档，未曾保存的草稿则删除。外部保存待同步时，界面禁止“舍弃修改”，避免把私有唯一有效稿误当成已同步内容丢掉。增量备份和模板存放在插件私有目录；它们不会自动出现于系统相册或手机 Download。

AI 能力直接操作相同的私有工程；对带外部 URI 的工程，兰儿的 document.save 会写私有存档并留下“外部待同步”状态，阿伟可以打开页面执行“保存”完成 Android URI 写入。兰儿拿到导出结果的私有路径，不会自动将文件复制到系统相册。需要防止双方同时编辑时，对结构化图层编辑使用 expectedRevision。

## 画布与图层

新建支持 64–4096 像素边长、工程名称及透明背景；当前工程只有 RGB 8 位与一个初始绘画图层，不存储 ICC、DPI 或 Krita 原生色彩模型。画布底栏左侧的 ↩️/↪️ 调用与编辑菜单、兰儿能力相同的撤销/重做历史；没有可撤销或可重做操作时按钮置灰，忙碌时暂停操作。右侧“居中”只重置缩放、旋转、平移；旁边“全屏”通过 host.ui.presentation@1 控制页面；宿主未确认模式请求时，对话框保留并显示返回的错误。左右抽屉默认收起。左侧工具栏占可用宽度的四分之一且最大 96 dp，右侧面板保留原本较宽的布局。两侧各有图钉按钮：固定后点击画布不会收起该栏，画布视口避开固定栏；取消固定会立即收起，点击侧栏把手也会取消固定并收起。未固定的侧栏仍可点画布区域收起，点击侧栏内部控件不会误触画布；固定右栏或同时打开两侧时，右栏按可用宽度收窄，给画布预留至少 112 dp 的目标空间。右侧依次为多功能拾色器、真实图层管理、尚未实现的笔刷预设。图层与笔刷预设各有最大化按钮；最大化其中一块时，另外两块只留下固定高度的标题栏，当前块占据剩余高度。点击折叠的图层栏可切换到图层最大化，点击折叠的拾色器栏可恢复三段布局；最大化按钮再次点击也恢复三段布局。左抽屉只列出已经接入画布行为的工具；点击图钉可固定抽屉，普通工具选择后可继续作画。菜单栏文件和编辑以外的九项目前只有标题。

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
| 三次贝塞尔曲线 | 依次点起点、两处控制点与终点；点击时预览控制点连线，第四点落下后保存曲线笔画 | stroke.add，tool=bezier，points 恰好 4 点 |
| 颜色取样 | 点击合成画布更新当前笔色；半径可设为 0–32 px，圆形范围内的像素按透明度混合；还可选取样色与当前色的混合比例 | color.sample（画布像素坐标，可选 radius、blend；blend<100 需 baseColor） |
| 连续区域填充 | 选中工具后从左侧栏「填充选项」设置颜色容差（0–100）及参考当前图层/所有可见图层；遵循当前选区，填色仍写入可编辑根图层并记录可撤销的 PNG 像素编辑 | fill.contiguous（x/y/color，可选 tolerance、referenceAllLayers、expectedRevision） |
| 线性、径向渐变 | 拖动定义前景色至透明的渐变距离；可切换线性／径向和反向，在当前绘画层记录渐变事件 | stroke.add，tool=gradient，可选 gradientMode=linear/radial、gradientReverse |
| 矩形、椭圆、多边形、自由套索选区 | 拖动创建矩形/椭圆/套索；逐点点击、双击最后一点结束多边形；复制/清除/填充沿真实边界裁剪 | selection.create、selection.ellipse、selection.polygon、selection.freehand |
| 裁剪画布 | 拖动矩形裁剪；裁到画布范围且边长至少 64 px，保留操作历史 | canvas.crop |
| 移动与变换图层、平移画布 | 移动和变换工具都可拖动当前图层；变换工具的「变换参数」可设置位置、缩放和旋转，打开时读取当前图层值；有选区时拖动沿用当前选区移动操作 | transform.move/scale/rotate、selection.edit；视图平移只属于当前页面 |
| 测量距离、缩放画布 | 拖动可读两点距离与角度；点击缩放画布视图，双指缩放沿用已有手势 | canvas.measure；视图缩放只属于当前页面 |

这仅是 Krita 左侧工具的第一批真实操作：尚缺区域填充的透明色擦除、颜色标签图层参考及边界填充、渐变预设与色彩空间、多段可编辑贝塞尔/自由路径、书法笔矢量轮廓及速度调角、矢量形状、文字、高级变换、参考图像、辅助尺规、蒙版及磁性套索、相似色等其他选区。它们各自需要补画笔引擎、矢量对象、像素选区蒙版或相应的资源类型；不得将现有笔画、矩形选区或移动操作改名冒充。连续区域填充默认按 RGBA 像素完全匹配，容差 0–100 映射到每个通道 0–255 的最大差值；可参考所有可见图层，但仍只写当前图层；透明颜色填充会拒绝；选择非根图层、隐藏/锁定或已变换的图层时也会拒绝，避免编辑到错误像素。渐变只提供前景色到透明的线性／径向基础模式，不具备 Krita 的预设和混合选项。动态画笔当前只移入质量／阻力轨迹过滤，Krita 的固定角度与速度相关笔宽尚未移入；栅格书法笔不生成 Krita 的矢量轮廓。手机端、构建与触屏操作仍待验证，当前源码修改没有编译。

右侧图层管理参考 Krita 6.0.4 的 plugins/dockers/layerdocker/WdgLayerBox.ui、LayerBox.cpp 和 NodeDelegate.cpp，提供名称筛选、缩略图、显隐、锁定、混合模式、不透明度、绘画层与组、复制、同级排序、属性和删除。兰儿对应使用 layer.list、layer.search、layer.create、layer.group、layer.select、layer.set_visibility、layer.set_lock、layer.set_blend、layer.set_opacity、layer.rename、layer.copy、layer.move_up、layer.move_down、layer.properties 与 layer.delete。底到顶合成；图层背景不是可编辑图层。笔刷仍是基础画笔与喷枪，4K 多层画布可能消耗较多内存。

源码对照入口：https://invent.kde.org/graphics/krita/-/blob/master/libs/ui/KisMainWindow.cpp
Krita 文件菜单说明：https://docs.krita.org/en/reference_manual/main_menu/file_menu.html
