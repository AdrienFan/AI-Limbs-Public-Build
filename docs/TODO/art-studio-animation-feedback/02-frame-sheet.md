# 第二步：批量目标帧反馈

[DONE] animation.poses.apply保留当前帧thumbnail，并附一张全部目标帧总览；1–32帧按时间排序、每行最多四格、F加几何数字标帧号。帧清单、行列顺序、版本及图片下标随animationFeedback返回。128像素格、JPEG不超过512KiB，逐帧释放位图。输出图片是瞬时回执。

[DONE] 常规缩图与总览分别报告错误，正常图片保留；失败明确operationApplied=true并保留原提交证据。receipt包含反馈，默认full保持完整快照接口。有效提交才反馈，未提交的SVG输入不触发渲染。

入口和例子见插件README的0.2.83节及animation.poses.apply自说明。放大用animation.preview按帧号读取；动作完成用原有时间轴播放检查连贯性。没有新增自动播放或自动GIF导出。
