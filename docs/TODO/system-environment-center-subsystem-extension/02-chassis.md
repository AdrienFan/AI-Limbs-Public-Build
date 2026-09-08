# 02 通用机箱与 Display Slot

## 旧实现

父插件页面、环境配置路由与终端外围 UI 仍由 Ubuntu 实现提供。

## 修改意图

把环境配置、通用外围控制和空状态移入父插件，只保留一个对子系统 Display Adapter 的承载插槽。

## 期待结果

系统环境中心在没有子系统时仍可独立 mount；添加系统环境只出现在父级环境配置页。


[DONE]

父级已改为通用空壳：独立环境配置页、共享安装/生命周期组件、active 与 foreground 分离的 registry、通用运行控制、Display Slot 和带 subsystem_id 的能力路由；父入口不再 mount Ubuntu。
