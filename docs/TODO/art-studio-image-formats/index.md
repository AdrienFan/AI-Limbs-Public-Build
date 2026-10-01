# 画室常用图片格式读取（0.2.19）

Fork: https://github.com/AdrienFan/AI-Limbs-Public-Build

基础源码：已部署 0.2.18，ddda307c；CURRENT 与 ail-source 已核对。范围仅画室插件，不改宿主。

实物诊断：共享 Pictures/weibo/宋佳.jpg 为 HEIC 容器，ftyp 品牌 heic/mif1，1557676 字节；完整 ispe 为 4500×6000；SHA-256 8be59fe585ca5db982475e53f80248093ec25d5811752edf31fc203f7b4610c4。旧导入按 BitmapFactory 报出的 MIME 只允许 PNG/JPEG，因而拒绝真实 HEIC，而非 JPG 后缀不受支持。

- [x] 插件统一 ImageDecoder 解码，覆盖 PNG/JPEG、WebP、BMP、GIF、HEIC/HEIF、AVIF；按内容识别，保留预算与显式缩小确认
- [x] 动图仅导入首帧并告知；打开、图层导入、AI 工具及格式清单共用实现；支持软件位图与 sRGB 8 位工程
- [x] 修正高分辨率压缩照片转 PNG 后的资源上限，原始输入仍为 8 MiB；工程单资源改为 64 MiB，输出限额前停止缓冲，归档读写遵循同一上限
- [x] 迭代版本与文档，源码检查；编译及新格式真机验收待后续执行（不包含在本项完成标记中）

TIFF、多帧编辑、HDR/高位深工程及新增导出编码留待后续。原照片只读取，不改名、不转换覆盖。

技术依据：[Android ImageDecoder](https://developer.android.com/reference/android/graphics/ImageDecoder) 与 [Android 支持格式](https://developer.android.com/media/platform/supported-formats)。

源码实现 [DONE]。静态检查：91 个声明覆盖全部运行时注册字面量，216 个菜单叶子/65 个共享实现，JSON 与菜单清单可解析，git diff --check 通过。版本为 0.2.19、versionCode=22、payload 包名 v0219。内存工作估算提高至 32 字节/像素。此轮未编译、未真机解码；未来验收需使用多种格式样本及错后缀样本，不以单张示例代替通用验收。
