# 验收计划

云端运行 test_ubuntu_direct_routing.py，其中新增已映射 inode 保留、相同内容不替换、失败不破坏旧文件检查。云端运行 UbuntuCapabilityOutcomeTest，再编译并签名 0.1.22。

部署后通过自制桥交替创建多个隐藏执行器，检查 Bash、DIRECT 出口和显式 laner-net VPN 出口。完整新版 Android 部署验收尚待云端产物和用户安装。

仅启动云端检查与编译，不在 Ubuntu 本机运行 Gradle，也不持续监视云端作业。
