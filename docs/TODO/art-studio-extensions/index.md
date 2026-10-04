---
fork: https://github.com/AdrienFan/AI-Limbs-Public-Build
---
# 画室工具扩展菜单

按当前Hub 1.5.5与插件中心源码核对准入：.ailx使用AIL_EXTENSION_V1、SHA-256完整性和Ed25519发布者签名，由system.extension.hub@1安装服务校验父插件/点，再交Child Runtime。父级发布API1扩展点；子插件必须单次publish业务binding，停用/卸载/父点关闭时撤销。宿主权限为四层交集，不授予额外权限。

范围：仅画室插件。工具菜单新增扩展二级菜单，首项添加扩展打开现有共享子插件安装组件；后续菜单由激活子插件提供，事件回到子插件。采用版本化菜单业务binding，不注册共享UI组件，也不使用借用父身份的capability_button。

步骤：
- 发布画室菜单扩展点及类型/状态/生命周期约束
- 接入工具子菜单与限定父插件/点的添加对话框
- 写极简接入示例、递增版本、静态核对并提交

没有本地编译或测试；云端编译等待后续安排。

[准入与生命周期](01-admission.md)、[菜单与验证](02-menu.md)完成。

[DONE]
