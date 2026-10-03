# 系统字体声明缺项阻断文字工具

现场 build98 / 画室0.2.74。2026-10-03 17:44:57 和 17:45:01 的 operit.log 记录 ArtStudio Text editor font catalog failed，异常来自 ArtText.kt configuredFonts() 的 require。

手机 /system/etc/fonts.xml 第1027行声明 NotoSerifKhmer-Regular.otf；ADB 实查 /system/fonts 同名文件不存在。画室把任一声明文件缺失当成整份字体目录失败，尚未扫描到后面的中文 TTC 条目就终止。错误属于插件字体发现，不增加基座字体 API。

修复：解析真实系统声明并明确区分可读取字体与未安装/不可读取条目；未安装声明只进入 unavailableFonts 诊断，不注册字体。保留可用字体原有路径、TTC index、weight/slant、axis settings 和 family 序号对应的稳定 fontId。文件存在但加载失败、明确选择了不可用字体、配置损坏仍报告原错误；不替换所选字体或字形。

解析与文件发现提取为独立 JVM 可执行组件，覆盖缺失声明前后仍能发现可用字体、保持字体身份、TTC/轴/语言元数据、空目录、非法路径与损坏配置。现有文字塑形与界面选字体入口不变。

画室0.2.75的断显修复云端测试、编译与签名打包已成功，run37114075906/commit30a85cc9。下一版画室包含该改动和本字体修复；基座build99保留通用通信修复，上一轮云构建已按阿伟指令取消，字体修复不增加基座改动。全部测试和构建继续交由云端。


实施：字体 XML 元数据/文件发现提取为 ArtSystemFontCatalog，字体缺项返回独立 unavailable 诊断。ArtText 原有显式 Font.Builder、glyph/cmap 检查、文字塑形与选定字体失败语义保持。手机 zh-Hans / zh-Hant 声明的 SECCJK-Regular.ttc 和 NotoSerifCJK-Regular.ttc 均经 ADB 确认存在且所有用户可读。新增8个回归源码。画室递增为0.2.76 / code79 / v0276，继承已云端通过的0.2.75；全部测试与编译只在云端执行。


静态能力、菜单、说明、足迹覆盖与 git diff --check 通过；已发布字段与字体身份格式保留，缺失条目不创建 Face，不吞掉配置或选定字体加载错误。提交后进行来源检查并推送，恢复已取消的基座build99云构建；本地未执行编译或单元测试。
