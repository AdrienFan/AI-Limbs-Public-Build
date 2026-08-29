# 提示说明、测试与交付

## 已完成

- Bootstrap 与中文政策说明由结构化代码生成，System Prompt 不再是可编辑政策源。
- Work Manual 有效版本覆盖受保护生成段与用户正文。
- 视觉结果仅在真实像素随响应附带时标记 `IMAGE_PIXELS`。
- 新增政策描述与 Registry 单元测试，覆盖工作手册路径、transport ABI、像素类型和不可变系统提示。
- `git diff --check` 与陈旧 API/构造器静态检索通过。
- 本地 Android 构建按用户要求不执行；仅提交后触发 GitHub Actions `assembleDebug`。
