---
fork: https://github.com/AdrienFan/AI-Limbs-Public-Build
status: source-audit
date: 2026-10-31
---

# 画室菜单滤镜：Krita 6.0.4 源码核对

后续实施：[第一阶段进度与待验证项](01-engine.md)、[第二阶段颜色／曲线与兰儿入口](02-colors.md)、[第三阶段空间／表面与降噪](03-spatial.md)、[第四阶段五项资源型滤镜](04-resources.md)。下文保留开始时的菜单及源码核对快照；四阶段开发源码共新增 46 项滤镜的声明范围内实现，具体滤镜覆盖达到 53／53，但尚未构建或部署，也不表示完整 Krita 模式移植。

范围：用户要求依据手机上的 Krita 源码检查画室菜单栏滤镜。本轮完成在线菜单清单、画室实现和代表性 Krita 机制核对，形成后续迭代范围；没有修改运行代码、执行构建或运行滤镜测试，也没有编辑用户画稿。

## 核对来源

画室权威开发源码通过 ail-source dev “AI Limbs 画室”取得，位于 /root/laner/projects/Art-Studio-Accordion/plugin-lab/plugins/art-studio。所属 Git HEAD 为 5b0b0efcea03dccb25e8ef8ece65594de3b08688。本地对应 HEAD 为 5f6de2f4e19d9703d373e73315bc5ea1003d96da，既有 0.2.99 实机记录的未提交修改已保留。

用户给出的 /storage/emulated/0/Download/krita-6.0.4-source.tar 当前实际为目录。可读源码根为其下 krita-6.0.4-source/krita-6.0.4；已直接读取该目录内 C++ 文件。CMakeLists.txt 包含 6.0.4 版本分支，并有另一 5.3.4 分支，本文按用户所给源码目录称呼，不宣称它是当前上游最新版。

在线 plugin.art.studio.menu.catalog 与画室静态目录相符。implemented 表示实现标记；enabled 还受当前选中层及上次滤镜状态影响，不能混算。

## 清单结论

滤镜菜单共 60 个有 ID 的叶入口，其中 53 个为 filter.* 具体滤镜，7 个已实现、46 个未实现。

另 7 个入口：再次应用、重新配置后再次应用已实现；滤镜库、装饰分类占位、非真实感分类占位、G’MIC-Qt 和再次应用 G’MIC 共 5 个未实现。总计 9 个已实现入口、51 个未实现入口。两个分类占位不等同于两个具体滤镜，不能据此声称已经穷举 Krita 的全部滤镜。

| 分类 | 具体滤镜数 | 已实现 | 未实现 |
| --- | ---: | ---: | ---: |
| 调整 | 12 | 3 | 9 |
| 艺术效果 | 6 | 1 | 5 |
| 模糊 | 4 | 0 | 4 |
| 颜色 | 6 | 2 | 4 |
| 边缘检测 | 4 | 0 | 4 |
| 浮雕 | 6 | 0 | 6 |
| 增强 | 5 | 0 | 5 |
| 映射 | 6 | 0 | 6 |
| 其他 | 4 | 1 | 3 |

已实现的七项为去色、反相、阈值、色调分离、最大化通道、最小化通道和重置透明像素。完整 53 项见文末及 [结构化清单](inventory.json)。

## 当前公共执行流程

ArtStudioMenuUi.kt 的 showStudioRemainingMenu 按 catalog 生成菜单，未实现项灰显；StudioMenuParameters 使用普通参数输入和确定／取消按钮。当前该通用参数窗没有滤镜实时预览及专用曲线、直方图交互。

ArtStore.executeMenu 校验参数、documentId 和 expectedRevision；菜单和 AI 能力共用分发器。applyMenuFilter 渲染所选根层自身像素，交给 ArtMenuOperations.filter 处理，再编码资源，用 MENU_LAYER_CHANGE 替换为栅格层，保留历史和 lastFilter。源码已有浮点资源保存机制，不能把这条路径误报为固定 8 位 PNG。

ArtMenuOperations.filter 使用 ArtFloatPixels 读取线性预乘浮点 RGBA，解预乘后计算，再以选区覆盖率在预乘空间混合并写回。软选区及普通几何选区均有处理，已经不是“缺少像素选区”的阶段。

仍存在的公共限制：

- pixelsEditable 要求可见、未锁定、无父组、无位移／旋转／缩放／affine 的 paint/image 层。变换和组内写回虽已在其他工具接通，菜单滤镜尚未接通。
- 算法接口是原地逐像素 when 分支，适合点运算。邻域滤镜需要不可变输入、边界取样、扩展读取范围与实际写入范围；不能简单照此循环原地读写。
- 通用菜单滤镜缺少独立的参数定义／算法注册、通道开关、预览任务、取消与过期结果丢弃、资源型预设和共享缓存流程。
- 当前整图渲染／浮点数组／资源编码有多份工作内存。已有预算机制不代表已解决大半径滤镜的分块、重叠和任务取消。
- 滤镜图层、滤镜蒙版等入口仍是未实现项。独立 selection_mask 已有实现，但它不等于可持续重算的滤镜蒙版。
- 动画不能误报为“必然丢失轨道”：ArtStore.edit 会在同 ID 替换后恢复 animationKeys，再由 ArtAnimation.capture 更新活动 cel。paint 轨道有专门保护；image 层替换成 paint 与轨道 kind 一致性约束的组合、当前帧／多帧行为，仍需后续定向验证。

## 已实现滤镜与 Krita 的语义比较

| 项目 | 画室现状与核对结论 |
| --- | --- |
| 去色 | 六种公式和 BT.709／BT.601 系数与 plugins/color/colorspaceextensions/kis_desaturate_adjustment.cpp 对应；画室明确在线性 RGB 上计算，Krita 按图层色彩空间通道工作。不能承诺不同工作空间下逐像素一致。 |
| 反相 | 画室为 RGB 的 1-v，alpha 不变；Krita example/example.cpp 委托色彩空间的 createInvertTransformation。在线性浮点 RGB 范围内有可对应含义，HDR 产生负值不能自动当作错误或裁到 0。 |
| 阈值 | 画室比较线性 BT.709 亮度与从 0–255 sRGB 阈值转换的线性值；Krita threshold.cpp 使用图层色彩空间 intensity8，并保留透明度。存在定义差异，需选择明确的兼容语义及样例后验证。 |
| 色调分离 | 画室直接 round(linearChannel*steps)/steps，包含 alpha；Krita posterize.cpp 先转换到指定 sRGB-elle-V2-srgbtrc.icc 的 16 位 RGB，再用整数间隔量化 RGBA。工作空间、整数舍入和端点行为均不同。包含 alpha 是 Krita 本身的行为，不能单独当作画室缺陷。 |
| 最大／最小化通道 | 与 Krita colors/kis_minmax_filters.cpp 一样，保留等于极值的颜色通道，把其他颜色通道清零；不是空间最大／最小值滤波，不是把 RGB 全部写成极值。 |
| 重置透明像素 | 两者均有清理透明像素颜色的意图；画室仅对精确 alpha=0 的浮点像素处理并按选区混合，Krita 以 opacityU8 判定透明。低 alpha／HDR 发光像素必须定向验证，不能笼统称完全相同。 |

以上是源码语义比较，不是已执行的实机差异测试。

## Krita 最值得借鉴的公共机制

1. libs/image/filter/kis_filter.cc：process 计算 neededRect，必要时使用临时设备，运行 processImpl 后再按选区复制结果。邻域取样不能只读取选区内部，否则边缘会出接缝。
2. plugins/filters/blur/kis_gaussian_blur_filter.cpp：横纵半径、通道 flags、neededRect 和 changedRect 分开。libs/image/kis_gaussian_kernel.cpp 与 kis_convolution_painter.cc 提供核处理和边界规则。
3. plugins/filters/convolutionfilters/convolutionfilters.cpp：多个锐化／浮雕共用卷积机制，差异主要是核、归一化和偏移。应先补公共卷积流程，再接一组滤镜。
4. libs/ui/kis_filter_manager.cc：参数改变可取消现有预览 stroke；配置带资源快照；finish 才结束处理，并有对选定动画帧的处理路径。画室需要自己的预览生命周期，不能每次拖参数就写永久历史。
5. libs/image/kis_filter_mask.cpp：滤镜配置、输入区域和输出影响区域参与重算。非破坏滤镜层／蒙版需要工程模型及渲染依赖，不是将现有 selection_mask 改名。
6. plugins/filters/levelfilter/KisLevelsFilter.cpp 及 colorsfilters/kis_*channel_filter.cpp：曲线、通道与参数编辑器是功能的一部分；HSV 还有不同类型、着色及兼容模式，不宜只接最短公式后标称全部完成。

## 46 项缺漏如何分工

| 组 | 未实现项目 | 主要需补机制 |
| --- | --- | --- |
| 调整 9 项 | HSV、减淡、加深、ASC-CDL、自动对比度、色彩平衡、色阶、跨通道曲线、颜色曲线 | RGB／HSV／明度定义，点运算、直方图、曲线和参数 UI；Krita 自动对比度明确用 Lab16 亮度统计，完整对齐不能直接用 RGB 拉伸替代。 |
| 艺术效果 5 项 | 像素化、半色调、油画、索引颜色、雨滴 | 区块统计、屏幕／网点参数、邻域直方图、调色板和空间扭曲。不是共同缺少一个 G’MIC。 |
| 模糊 4 项 | 普通、运动、镜头、高斯 | 浮点邻域处理、不同核、预乘透明度与边缘扩展；Krita 普通模糊包含形状／旋转，镜头模糊使用光圈多边形，不能都代成一个高斯。 |
| 颜色 4 项 | 快速颜色叠加、扩散颜色、颜色转透明度、颜色迁移 | 颜色叠加可借当前混合机制；扩散颜色需要距离／扩展策略；转透明度需要颜色差与 alpha 求解；Krita 颜色迁移使用 Lab16、参考图像及统计。 |
| 边缘 4 项 | 上边缘、边缘检测、高度转法线、高斯高通 | 卷积／导数、核半径、向量归一化、坐标及输出通道约定。 |
| 浮雕 6 项 | 五种方向／拉普拉斯浮雕、可变深度浮雕 | 共享卷积核与偏移，可变深度参数。 |
| 增强 5 项 | 去除均值、反锐化蒙版、小波降噪、锐化、高斯降噪 | 卷积、高斯原图差、阈值与明度模式、多尺度小波／噪声估计。 |
| 映射 6 项 | Phong、圆角、小瓷砖、归一化、渐变映射、调色板量化 | 法线／光源、几何 alpha、重采样、通道统计、梯度及调色板资源。 |
| 其他 3 项 | 波浪、随机噪点、随机拾取 | 坐标重映射、确定性随机种子及采样。重做／历史重放须固定结果。 |

ArtColorizeFilters.blur 已有浮点标量的可分离三次盒滤近似高斯，可参考其边界、复杂度和取消检查，但它是上色用的单通道场处理，不是可直接调用的 RGBA 通用滤镜，也不是 Krita 高斯核的逐像素等价实现。软选区模糊和磁性边缘已有局部算法，同理不能将存在辅助算法当成菜单已经完成。

当前旧禁用理由中，“颜色转透明度／反锐化缺选区蒙版”已经过期。色彩平衡、RGB 曲线和单工作空间颜色叠加可以在现有线性 Rec.709 模型内实现，不必先完成整个 Krita 多色彩空间体系；但若目标是同一 ICC 工作空间下精确对齐 Krita，仍要补明确的色彩管理语义。不能把所有色彩滤镜都归因为缺 ICC，也不能全部按普通 RGB 近似并宣称等价。

G’MIC 是独立事项。提供的源码在 plugins/extensions/qmic 有桥接、图像转换和图层同步接口，不在上述普通滤镜分支里。是否引入其运行库、安卓 ABI、菜单 UI、参数和结果导入需要单列方案。普通滤镜可独立完善。

## 建议的迭代顺序与验收条件

1. 先定义公共滤镜配置、线性／编码色域及 alpha 规则、点运算／邻域输入输出、扩展取样、通道选择和预算。保留已发布菜单 ID、参数和历史事件的兼容语义；不能悄悄改变旧工程重放。
2. 接预览／取消／确认流程及当前帧写回，延伸至组内／变换层，梳理滤镜层与滤镜蒙版所需独立模型。
3. 首批完成高斯／普通模糊、基础锐化／浮雕、反锐化、高通，再接 HSV、色阶、曲线、颜色转透明度与快速颜色叠加；统一更新菜单参数和真实可用范围。
4. 之后补直方图／调色板／几何采样类及复杂艺术效果；G’MIC 作为独立模块评估，不与普通算法混为一个阻塞项。

后续验证必须覆盖透明边缘不染黑、选区外像素不变、邻域从选区外正确取样、HDR／负值保留或明确的处理边界、组内仿射与动画活动 cel、预览取消不写历史、一次确认一条逻辑历史、重做／归档一致、大图分块交界及最低 Android 版本。当前只能称源码核对和计划，不能称上述用例已通过。

## 逐项目录

| 分类 | ID | 菜单名 | 已实现 | Krita 目录中的参考路径 |
| --- | --- | --- | --- | --- |
| 调整 | filter.hsv_adjustment | HSV 调整… | 否 | plugins/filters/colorsfilters/kis_hsv_adjustment_filter.cpp |
| 调整 | filter.dodge | 减淡… | 否 | plugins/filters |
| 调整 | filter.burn | 加深… | 否 | plugins/filters |
| 调整 | filter.desaturate | 去色… | 是 | plugins/filters/colorsfilters/kis_desaturate_filter.cpp |
| 调整 | filter.invert | 反相 | 是 | plugins/filters/example/example.cpp |
| 调整 | filter.slope_offset_power | 斜率、偏移、幂 (ASC-CDL)… | 否 | plugins/filters/asccdl/kis_asccdl_filter.cpp |
| 调整 | filter.auto_contrast | 自动对比度… | 否 | plugins/filters/colorsfilters/colorsfilters.cpp |
| 调整 | filter.color_balance | 色彩平衡… | 否 | plugins/filters/colorsfilters/kis_color_balance_filter.cpp |
| 调整 | filter.levels | 色阶… | 否 | plugins/filters/levelfilter/KisLevelsFilter.cpp |
| 调整 | filter.cross_channel_adjustment_curves | 跨通道调整曲线… | 否 | plugins/filters |
| 调整 | filter.threshold | 阈值… | 是 | plugins/filters/threshold/threshold.cpp |
| 调整 | filter.color_adjustment_curves | 颜色调整曲线… | 否 | plugins/filters |
| 艺术效果 | filter.pixelize | 像素化… | 否 | plugins/filters/pixelizefilter/kis_pixelize_filter.cpp |
| 艺术效果 | filter.halftone | 半色调… | 否 | plugins/filters/halftone/KisHalftoneFilter.cpp |
| 艺术效果 | filter.oilpaint | 油画… | 否 | plugins/filters/oilpaintfilter/kis_oilpaint_filter.cpp |
| 艺术效果 | filter.index_colors | 索引颜色… | 否 | plugins/filters/indexcolors/indexcolors.cpp |
| 艺术效果 | filter.posterize | 色调分离… | 是 | plugins/filters/posterize/posterize.cpp |
| 艺术效果 | filter.raindrops | 雨滴… | 否 | plugins/filters/raindropsfilter/kis_raindrops_filter.cpp |
| 模糊 | filter.blur | 模糊… | 否 | plugins/filters/blur/kis_blur_filter.cpp |
| 模糊 | filter.motion_blur | 运动模糊… | 否 | plugins/filters/blur/kis_motion_blur_filter.cpp |
| 模糊 | filter.lens_blur | 镜头模糊… | 否 | plugins/filters/blur/kis_lens_blur_filter.cpp |
| 模糊 | filter.gaussian_blur | 高斯模糊… | 否 | plugins/filters/blur/kis_gaussian_blur_filter.cpp |
| 颜色 | filter.fast_color_overlay | 快速颜色叠加… | 否 | plugins/filters/colors/KisFilterFastColorOverlay.cpp |
| 颜色 | filter.propagate_colors | 扩散颜色… | 否 | plugins/filters |
| 颜色 | filter.maximize | 最大化通道 | 是 | plugins/filters/colors/kis_minmax_filters.cpp |
| 颜色 | filter.minimize | 最小化通道 | 是 | plugins/filters/colors/kis_minmax_filters.cpp |
| 颜色 | filter.color_to_alpha | 颜色转透明度… | 否 | plugins/filters/colors/kis_color_to_alpha.cpp |
| 颜色 | filter.color_transfer | 颜色迁移… | 否 | plugins/filters/fastcolortransfer/fastcolortransfer.cpp |
| 边缘检测 | filter.top_edge_detection | 上边缘检测… | 否 | plugins/filters/convolutionfilters/convolutionfilters.cpp |
| 边缘检测 | filter.edge_detection | 边缘检测… | 否 | plugins/filters/edgedetection/kis_edge_detection_filter.cpp |
| 边缘检测 | filter.height_to_normal_map | 高度转法线贴图… | 否 | plugins/filters/convertheightnormalmap/kis_convert_height_to_normal_map_filter.cpp |
| 边缘检测 | filter.gaussian_high_pass | 高斯高通… | 否 | plugins/filters/gaussianhighpass/gaussianhighpass_filter.cpp |
| 浮雕 | filter.emboss_horizontal_vertical | Emboss Horizontal  Vertical… | 否 | plugins/filters/convolutionfilters/convolutionfilters.cpp |
| 浮雕 | filter.emboss_in_all_directions | 全方向浮雕… | 否 | plugins/filters/convolutionfilters/convolutionfilters.cpp |
| 浮雕 | filter.emboss_with_variable_depth | 可变深度浮雕… | 否 | plugins/filters/embossfilter/kis_emboss_filter.cpp |
| 浮雕 | filter.emboss_vertical_only | 垂直浮雕… | 否 | plugins/filters/convolutionfilters/convolutionfilters.cpp |
| 浮雕 | filter.emboss_laplacian | 拉普拉斯浮雕… | 否 | plugins/filters/convolutionfilters/convolutionfilters.cpp |
| 浮雕 | filter.emboss_horizontal_only | 水平浮雕… | 否 | plugins/filters/convolutionfilters/convolutionfilters.cpp |
| 增强 | filter.mean_removal | 去除均值… | 否 | plugins/filters/convolutionfilters/convolutionfilters.cpp |
| 增强 | filter.unsharp_mask | 反锐化蒙版… | 否 | plugins/filters/unsharp/kis_unsharp_filter.cpp |
| 增强 | filter.wavelet_noise_reducer | 小波降噪… | 否 | plugins/filters/imageenhancement/kis_wavelet_noise_reduction.cpp |
| 增强 | filter.sharpen | 锐化… | 否 | plugins/filters/convolutionfilters/convolutionfilters.cpp |
| 增强 | filter.gaussian_noise_reduction | 高斯降噪… | 否 | plugins/filters/imageenhancement/kis_simple_noise_reducer.cpp |
| 映射 | filter.phong_bumpmap | Phong 凹凸贴图… | 否 | plugins/filters/phongbumpmap/kis_phong_bumpmap_filter.cpp |
| 映射 | filter.round_corners | 圆角… | 否 | plugins/filters/roundcorners/kis_round_corners_filter.cpp |
| 映射 | filter.small_tiles | 小瓷砖… | 否 | plugins/filters/smalltilesfilter/kis_small_tiles_filter.cpp |
| 映射 | filter.normalize | 归一化… | 否 | plugins/filters/normalize/kis_normalize.cpp |
| 映射 | filter.gradient_map | 渐变映射… | 否 | plugins/filters/gradientmap/KisGradientMapFilter.cpp |
| 映射 | filter.palettize | 调色板量化… | 否 | plugins/filters/palettize/palettize.cpp |
| 其他 | filter.wave | 波浪… | 否 | plugins/filters/wavefilter/wavefilter.cpp |
| 其他 | filter.resettransparent | 重置透明像素 | 是 | plugins/filters/resettransparent/KisResetTransparentFilter.cpp |
| 其他 | filter.random_noise | 随机噪点… | 否 | plugins/filters/noisefilter/noisefilter.cpp |
| 其他 | filter.random_pick | 随机拾取… | 否 | plugins/filters/randompickfilter/randompickfilter.cpp |

表中 source 为当前画室目录记录；少数条目仅指向 plugins/filters，后续实现时需补成具体文件。已实际定位的补充路径：减淡／加深为 dodgeburn/DodgeBurn.cpp；颜色曲线为 colorsfilters/kis_perchannel_filter.cpp；跨通道曲线为 colorsfilters/kis_cross_channel_filter.cpp；颜色扩散为 propagatecolors/KisPropagateColorsFilter.cpp。本次直接比对了公共流程和上文列明的代表性算法，没有将 46 项每个分支都宣称为完成逐行审计。
