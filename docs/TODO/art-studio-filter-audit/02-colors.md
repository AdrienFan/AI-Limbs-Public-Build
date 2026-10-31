# 第二阶段：颜色调整、曲线与兰儿入口

本阶段继续参考用户手机上的 Krita 6.0.4 源码，扩展画室自己的处理实现。代码尚未构建、部署或实机验收；整轮功能完成后才统一推云编译。

## 已接入源码

新增 13 项具体滤镜的已声明参数范围内实现：HSV/HSL 调整、减淡、加深、斜率／偏移／幂、自动对比度、色彩平衡、色阶、颜色调整曲线、跨通道调整曲线、快速颜色叠加、颜色转透明、法线归一化和渐变映射。

ArtColorFilters 接收不可变的线性预乘浮点输入，分离计算输出；ArtMenuOperations 复用公共选区混合。所有菜单、手机参数窗、menu.preview 和 menu.execute 调用同一核心。确认写入仍经过工程修订检查，记录完整结构化参数到 lastFilter，通过既有 MENU_LAYER_CHANGE 撤销／回放，不新增历史类型。

RGB 调整可选择线性光或带符号扩展 sRGB 工作域。自动对比度和色阶明度模式使用明确标注的 D65 Lab 工作模型，不是 ICC 引擎。法线归一化直接处理线性 RGB 数据向量 2×RGB−1，不是亮度拉伸。颜色处理工作缓冲预算估计为 64 字节／像素；曲线按节点上界估算计算预算，超限明确拒绝。当前仍为全图处理。

## 手机与 AI 使用入口

新增只读能力 plugin.art.studio.filter.catalog，插件声明、注册、参数和帮助均已同步。省略 action 列出所有具体滤镜；传 filter.* 精确查询。返回分组、implemented、enabled、unavailableReason、notice、parameters，以及已实现项目的 calls.preview 和 calls.apply 模板；无工程时模板中的 DOCUMENT_ID／0 仅为占位。

兰儿先读取 document.summary 或 filter.catalog 的工程号和修订，查询参数限制，再调用 plugin.art.studio.menu.preview。确认后以相同 documentId／expectedRevision 调用 plugin.art.studio.menu.execute，成功后重新读取修订。两者的 parameters 保持 JSON 对象／数组，不能把结构化值传成字符串。预览不写画稿、资源、历史或 lastFilter，输出仍为白底的当前层自身像素。

例如颜色曲线的参数可传：

```json
{
  "action": "filter.color_adjustment_curves",
  "parameters": {
    "space": "linear",
    "curves": {
      "master": [[0, 0], [0.5, 0.65], [1, 1]],
      "alpha": [[0, 0], [1, 1]]
    },
    "interpolation": "smooth",
    "outside": "linear"
  },
  "documentId": "DOCUMENT_ID",
  "expectedRevision": 0
}
```

色阶 levels 是按 master／red／green／blue／alpha 分组的对象；每组可包含 black、white、gamma、outBlack、outWhite。跨通道曲线 rules 是有序数组，每项含 driver、target、points；曲线输出 0.5 为中性，增量为 2×curve(driver)−1。渐变 stops 是 position 与线性直通 rgba 的节点数组，支持有限 HDR 颜色和透明节点。

手机参数窗增加数组／对象 JSON 编辑。曲线图支持选择通道或规则、点按新增节点、拖动、删除内部节点和重置为中性；端点 x 固定为 0／1，节点保持严格顺序。图形仅编辑 y 为 0–1 的曲线，范围外曲线明确提示使用 JSON，保留其数据。JSON 编辑过程中语法错误显示在预览／确认区域，不作为 UI 异常抛出。

## 与 Krita 的差异和未完成模式

implemented 表示插件有声明参数范围内的实现，不能解读为完整移植 Krita 的全部模式。

- HSV 当前提供 HSV／HSL，尚缺 HSI／HSY／YUV 与兼容模式。非中性有界操作明确拒绝越界工作域，或由 clipInput 显式裁切。
- 色彩平衡参考 Krita 的阴影／中间调／高光权重及 HSL 亮度恢复，不含 ICC 变换。自动对比度统计全层非透明像素的 4096 档 D65 Lab 明度，选区仅控制输出；统计范围限于 L*=0–100。
- 曲线使用线性或保形三次 Hermite 插值，不是 Krita 原样条实现。RGB master 先于单通道；跨通道规则按给定顺序执行，未复用 Krita 的虚拟通道排序。luminance 驱动为所选工作域的 Rec.709 加权和，目标 luminance 为等量 RGB 增量。
- 色阶不裁切时用带符号幂延展 HDR；负底数的 ASC-CDL 分数幂明确拒绝。Alpha 输出始终限制为 0–1。这组颜色滤镜保持原 alpha 为 0 的像素及其发光 RGB，透明度曲线不填充原空白位置。
- 颜色转透明采用工作域 RGB 最大分量色差，默认在线性域反合成；不是 Krita 的色彩空间色差度量。快速颜色叠加只声明 8 种已实现混合模式。
- 渐变映射采用显式节点与预乘平滑插值，尚无资源库、最近色和抖动模式。渐变 alpha 乘源 alpha，与 Krita 源码取两者最小值的规则不同。
- 仍要求可编辑根像素层；组内／变换写回、image 动画轨道约束、资源管理、预设、非破坏滤镜层／蒙版和 G’MIC 保留在后续范围。

Krita 对照文件包括 colorsfilters/kis_hsv_adjustment_filter.cpp、kis_cross_channel_filter.cpp、colorsfilters.cpp，dodgeburn/DodgeBurn.cpp 及其颜色变换，asccdl/kis_asccdl_filter.cpp，levelfilter/KisLevelsFilter.cpp，colors/KisFilterFastColorOverlay.cpp、kis_color_to_alpha.cpp，normalize/kis_normalize.cpp，gradientmap/KisGradientMapFilter.cpp。路径均相对于 plugins/filters，另参考 plugins/color/colorspaceextensions 下的 HSV 与色彩平衡变换。

本阶段后，开发源码在 53 项具体菜单滤镜中有 35 项提供声明范围内的实现，18 项仍灰显。这是源码覆盖数，不是完整 Krita 参数复刻数，也不是部署或运行通过数。剩余灰显项：像素化、半色调、油画、索引颜色、雨滴、扩散颜色、颜色迁移、边缘检测、高度转法线、小波降噪、高斯降噪、Phong 凹凸、圆角、小瓷砖、调色板量化、波浪、随机噪点、随机拾取。另有滤镜库、装饰／非真实感分类占位及两个 G’MIC 入口。

## 核对与待验证

静态能力／菜单／帮助／足迹元数据检查通过：269 项能力声明及帮助、216 个菜单叶入口，其中 96 项有共享执行分支、83 种回放事件。差异空白检查通过。

新增 14 项 JVM 用例源码，覆盖 HDR 中性、Lab 往返、HSV 主色、曲线单调与外推、跨通道中性与规则顺序、减淡／加深公式、ASC-CDL／色阶、反合成、透明 HDR 渐变、法线向量、通道开关、退化直方图、非法配置、预算和取消。新增 3 项 Android 用例源码，覆盖结构化曲线预览／确认／重复／撤销、选区与无效配置无写入、发现入口的可用状态与调用模板。

依整轮统一收尾安排，未执行 Gradle 构建、JVM／Android 用例和曲线手势实机验收。不能表述为编译或运行已通过；本阶段不推送云端。
