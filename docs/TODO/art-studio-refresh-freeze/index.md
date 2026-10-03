# 复杂 SVG 作品页面冻结排查

证据：0.2.70，工程0100dce5-5c3c-4e99-ac1e-7f8bf0e6a91b，6次SVG_APPLY；工程和PNG已保存，另备份到/root/laner/reference/art-studio-incident-20261003。
该作品1024预览的Dispatcher执行约2.18s，页面400ms轮询会在帧完成前不断递增renderSerial，使慢帧一直作废并积累刷新任务。
Compose内同步clipboardInfo需要工程锁，可因后台绘图堵塞界面。退出日志还记录了ForgottenCoroutineScopeException，原生菜单须释放旧回调，已取消任务不得先设busy。
日志同时有Resident UI连接被拒绝、result_json缺失，早于此次绘图也出现；尚不能把它归为同一根因，不在画室添加跨层重试。

- [x] 单次刷新与编辑代次分开：重复轮询不作废正在生成的帧
- [x] 快照/像素/修订标记同一工程锁；取消结果位图释放
- [x] 剪贴板读取改为后台；刷新异常显式记录
- [x] 退出清理原生菜单回调；编辑busy仅在任务实际启动后设置
- [x] 版本0.2.71/code74/appIdv0271
- [ ] 静态检查、提交、云端测试与打包
- [ ] 安装后验证六层作品打开、左右折叠栏、页面退出重进与快捷工具回执
