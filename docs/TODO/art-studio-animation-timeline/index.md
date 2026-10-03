# 原生动画时间轴与GIF导出

## 基线与参照
基线0.2.71，HEAD b001ef8605c6fcafc5ca5cef8fe2e01fa437aba4。阿伟2026-10-03要求读取Krita 6.0.4动画时间轴，并按画室可实现的方式接入停靠板。此前三批绘图体验、SVG视口、足迹源码修改一起保留。当前继续暂停发布和编译。

用户提供路径实际是已解压目录：
/storage/emulated/0/Download/krita-6.0.4-source.tar/krita-6.0.4-source/krita-6.0.4

已读取：plugins/dockers/animation/KisAnimTimelineDocker.cpp（播放控制、帧号、添加/复制/删除帧、洋葱皮入口）；KisAnimTimelineFramesView.cpp（关键帧和保持帧操作）；libs/image/kis_keyframe_channel.cpp、kis_raster_keyframe_channel.cpp（关键帧时序、复制和范围）；libs/ui/animation/KisAnimationRender.cpp、KisLibavMediaEncoderRunnable.cpp（逐帧渲染与独立GIF编码/调色板）。
时间轴管理帧与时序，不是GIF编码器，也不是作品足迹。参照行为自行实现Kotlin/Compose与原生工程事件，未复制Qt/GPL代码，也未接入Krita/FFmpeg库。

## 已实现源码
- [x] 新animation停靠板、窗口菜单与旧停靠配置的字段补充，保留旧展开状态/排序；文件菜单“导出动画(GIF)”打开时间轴入口。逐帧导入和独立洋葱皮停靠板仍明确未实现。
- [x] paint/image/vector图层原生可编辑关键帧；文字、组、参考图、辅助尺规和上色蒙版等既有非动画数据保持静态/全局。图像层空白帧可以没有源asset，渲染器明确处理空图像内容。
- [x] 首次建轨道保留第0帧内容；空白/复制、移动/删除、明确停用轨道；保持前一关键帧直到下一帧。第0帧是不可删除/移动的锚点，停用保留当前显示内容为静态层，可撤销。
- [x] 编辑保持区会修改来源关键帧，面板明确说明；需要独立新内容先创建关键帧。现有工具编辑通过共同事件入口捕获到所属帧，不把足迹当动画帧。
- [x] 复制帧的原生内容存入提交事件，后续来源变化不改写已复制内容。编辑记录实际帧号和来源关键帧依赖，选择性撤销/移动来源与后续编辑冲突时拒绝。图层/组复制同时重映射所有活动和非活动cel的形状/笔触ID及layerId，保留同轨相同对象的身份关系。
- [x] ANIMATION_SETTINGS/KEY进入既有撤销与工程持久存储；ANIMATION_TIME导航事件增加原工程revision防旧手势写错帧，保留重做，不占可撤销足迹步。原始history.list仍可见导航事件；eventCount与editCount因此不同。
- [x] 播放FPS、起止范围、循环、帧号定位、上一/下一帧及关键帧；图层行、关键帧点与保持格、横纵滚动，支持切换当前图层。
- [x] 播放用单个协程顺序渲染，按单调时钟推进，慢帧丢预览帧而不排队；保持画布原分辨率和视口，显式检查当前编辑/播放/渲染位图预算；播放时禁止画布编辑。暂停定位最后展示帧；外部修订/工程切换停止预览。取消/失败释放位图并显示原因。
- [x] 编辑洋葱皮为前/后关键帧红/蓝淡色，保留组变换/显隐，并计入额外位图预算；播放/普通导出不带该叠加。
- [x] GIF89a原生流式输出，255色固定RGB332加一个透明色，alpha<128透明，无半透明保留；逐帧输出完整图像、按百分之一秒分配帧时长，无限循环开关，不含尺规/参考图/洋葱皮。生成工程快照后释放项目锁，帧与资产保持快照身份；临时文件成功后改名。一次只保留当前渲染帧，不持有全段位图。
- [x] 七项AI入口animation.info/timeline/configure/keyframe/seek/preview/export，参数范围、使用边界与简例已发布到本地源码能力元数据；253项完整帮助、270个参数名。
- [x] 现有保存/另存/模板/归档递归收集原生帧及历史asset引用，不遗漏非活动帧资源；旧无动画工程继续读取，不需要转换。
- [x] git diff --check、菜单/能力/帮助静态检查通过；现有78类加动画3类，共81类事件有足迹名称。
- [x] 新增11项原生帧/保持编辑/复制隔离/导航历史/依赖冲突/ID重映射云端用例，另2项GIF独立ImageIO解码与时长量化用例；更新能力数量断言。均未运行。
- [ ] 云端编译、测试和实机安装验收，等待阿伟发布指令。本轮未提交、未推送、未编译，版本/code/applicationId仍0.2.71/74/v0271。

## 当前范围与预算
帧号0–9999；FPS 1–60；起止范围含两端、最多600帧；每层最多128关键帧、全工程最多512。工程原生数据上限32MiB，与现有归档读取上限一致，超限拒绝保存该操作。GIF默认最大边512、允许64–1024且不放大，帧像素合计<=32Mi。固定调色板属于基础导出品质，未实现自适应调色/抖动优化；LZW以9-bit字面码和定期清字典流式输出，压缩率有限。

有动画轨道时，全工程裁剪/调整画布、会移除动画轨道的合并/扁平化和改变动画层类型明确拒绝；不暗中烘焙其他帧。对保留原层身份/类型的菜单滤镜，替换当前cel并保留轨道。音轨、参数曲线/插值、克隆共享帧、批量保持帧插入/拉动、视频编码和外部逐帧导入未实现，不能宣称完整Krita复制。

## 兰儿入口与示例
先animation.info {}读范围，再animation.timeline {}取documentId、revision与tracks[].id。每次定位/编辑/设置后使用新revision；下例身份和修订号必须替换。动画帧号不同于revision和足迹步号。

animation.configure {"documentId":"DOCUMENT_ID","expectedRevision":0,"fps":12,"start":0,"end":23,"loop":true,"onion":true}
animation.seek {"documentId":"DOCUMENT_ID","expectedRevision":1,"frame":6}
animation.keyframe {"documentId":"DOCUMENT_ID","expectedRevision":2,"layerId":"LAYER_ID","frame":6,"action":"duplicate"}
之后调用既有stroke/shape/移动/填充等该图层可用工具，使用上一步返回revision。无独立关键帧的保持区编辑修改来源帧。
animation.keyframe {"documentId":"DOCUMENT_ID","expectedRevision":3,"layerId":"LAYER_ID","frame":12,"action":"duplicate","sourceFrame":6}
animation.preview {"documentId":"DOCUMENT_ID","expectedRevision":4,"frame":12,"maxEdge":512}
animation.export {"documentId":"DOCUMENT_ID","expectedRevision":4,"maxEdge":512}

preview只读不改播放头，export返回该快照的path/尺寸/帧数/时序与透明规则；默认写现有共享导出目录。sourceFrame是源位置的保持内容；move另传targetFrame，不覆盖已有帧。

## 待实机验收
旧作品、空白/复制/保持、已有关键帧拒绝覆盖；笔刷/渐变/填充/移动/矢量/SVG/滤镜在正确cel；静态文字、图层显隐/父组、图像空白帧、复制图层/组的非活动形状与笔触ID；旧来源修改不改克隆内容；普通与选择性撤销/重做、时间导航不挤历史及保留重做；保存/另存/模板后重开包含所有帧和资产。
横纵滚动对齐、右侧折叠/关闭/恢复/排序、键盘输入、暂停/非循环末帧、退出与取消、外部编辑/工程切换、旧revision拒绝；复杂画面实际FPS、丢帧行为与内存峰值，尤其此前画布刷新问题。
洋葱皮前后帧、静态背景遮挡、组显隐/变换及额外预算；GIF用独立查看器检查多帧/循环/时长/透明清除/调色板，固定色彩限制和32Mi总帧像素拒绝；断写无半成品与源版本准确。未安装验收前不把这些行为当作已实证。

## 2026-10-03 发布接力
阿伟15:07授权统一提交、推送和云编译。四批修改一起递增到0.2.72 / versionCode75 / 独立applicationId com.ai.limbs.payload.artstudio.v0272；本节接替此前暂停发布状态。静态检查通过，本地未编译/未运行测试；云端构建与安装验收结果待确认。
