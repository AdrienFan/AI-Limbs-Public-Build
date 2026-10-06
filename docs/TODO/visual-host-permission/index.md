---
fork: https://github.com/AdrienFan/AI-Limbs-Public-Build
status: source-complete-validation-pending
---
# 视觉工作台的通用 Host 支持

基座 0.8.0.16-build105，versionCode 200。沿用已完成的 build104 全局搜索修正，不改其行为。

host.permission@1 从声明状态接为实际可调用。check/request/open_settings 使用 Host 定义的 camera/microphone token，插件不能传任意 Android 权限；owner_plugin_id 与已授权 Host scope 由已有网关绑定。Android 系统同意由非导出的 Host Activity 完成；没有新增兰儿向用户 UI 发放批准的 ASK。

相机同一来源独占，设备断开后退休会话，停止会取消等待中的帧。停止不会在正在打开的相机尚未释放时虚报完成。打开超时后的迟到设备回调仍可关闭设备。JPEG 方向默认结合传感器与屏幕旋转。

相机操作统一绑定 Host affinity。非导出的可见 Activity 自动取得前台服务启动条件，无新增确认按钮；相机前台服务声明 camera 类型和对应权限，按会话租约维持资源。通知停止或服务销毁时关闭设备、取消帧等待；最后一个会话停止后等待服务退出，再允许下一次获取。切回 ChatGPT 后的后台使用和系统停止仍待实机验收。

屏幕只声明实际支持的内置屏幕取图，避免枚举外接显示却拿到其他画面。第一帧必须检查真实 capture success；停止中取帧或系统结束投影不会留下虚假活动会话。

此轮为视觉插件提供通用宿主原语与生命周期支持，UI、取帧策略、状态组织、图像记录均在插件实现。源代码静态核对后交付；没有运行本地测试、编译或构建，尚未推送/部署。
