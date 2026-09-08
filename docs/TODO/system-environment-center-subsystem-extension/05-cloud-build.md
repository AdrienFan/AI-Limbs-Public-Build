# 05 提交与云端构建

## 约束

禁止 Ubuntu 本地 Gradle，不构建无关 Packager 或插件，不监控云端运行结果。

## 修改意图

完成静态检查后提交并推送当前 feature 分支，使用 Ubuntu 本地 git 与 gh 触发 GitHub Actions。

## 期待结果

workflow dispatch 被 GitHub 接受即结束本轮任务，汇报 commit、分支、关键文件与检查结果。
