---
fork: https://github.com/AdrienFan/AI-Limbs-Public-Build
status: source-complete
---

# 底栏缩放条收窄

用户确认0.2.28安装成功，截图显示比例滑条占底栏大半宽度，要求缩短。基线cee0acf0；ail-current确认手机画室0.2.28 ACTIVE，ail-source dev确认当前开发源码。

- [x] 缩放Slider最大宽120dp；内外两层权重使用fill=false，窄屏按可用空间收缩
- [x] 撤销/重做仍在左侧，短缩放条、比例、居中、全屏在右侧；中间空出
- [x] 保留滑块触摸尺寸、缩放范围和比例映射；插件内布局修改、版本和差异审查

[DONE] 0.2.29/code32/v0229源码完成；这次未要求云编译，未构建、测试或推送。待后续编译后验收横竖屏底栏长度与拖动。
