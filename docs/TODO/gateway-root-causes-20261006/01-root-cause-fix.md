# 根因与修复

Ubuntu 0.1.21 的 login_ubuntu 每次进入都以 busybox cp 覆盖 libproxychains4.so。运行中的 Bash 已映射该 inode，覆盖写入会破坏已重定位的内存映射。

在同一台 arm64 Ubuntu 上，隔离临时副本实验中相同内容覆盖 3 次均以 SIGSEGV 退出；相邻文件原子替换 3 次均正常退出。库来自 Ubuntu proxychains-ng 4.17，SHA-256 为 cf0904bf69e8fb4520828e1f5713724ddbaab0a7787fca55e7537329e1cd6d06。不是网络墙或 Host SOCKS5 识别失败。

修改 terminal-core 资产安装函数。内容相同时保持 inode；不同内容写入相邻临时文件并原子替换；失败显式中止启动并清理临时文件。保持 Host 原语与 Ubuntu 业务边界。

能力层原先用 require 表示 Runtime 尚未运行，并且 failure 没有领域错误码。改为带生命周期状态的异常；业务层声明停止、启动失败、进程退出、超时、输出协议失败、参数错误。非零退出码属于已完成的命令失败。取消继续传播，未知执行结果禁止自动重执行。

源码实现 [DONE]
