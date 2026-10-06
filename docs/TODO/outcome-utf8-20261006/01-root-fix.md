# 根因与实现

后台 shell 用 String(buffer, 0, count, UTF_8) 独立转换每次字节读取。800 行汉字及 emoji 实测出现 U+FFFD，而 ASCII base64 传输再解码完全一致。

改为每个 shell 独享 InputStreamReader，以保留跨读取、跨命令的 UTF-8 解码状态。按字符块发送，不等待换行或进程结束，不改进程生命周期和取消协议。

回归覆盖 1 至 4097 字节碎片、两次命令标记、800 行中文与 emoji，以及完整字符到达前等待、无换行提示符及时返回。

[DONE]
