# 项目工作约束

- 本项目允许修改的范围仅为 `D:\Study\TVApp\TVBoxApp` 当前目录及其子目录。
- 当前目录之外的路径只允许读取，不创建、不修改、不删除文件。
- 执行命令前确认工作目录为 `D:\Study\TVApp\TVBoxApp`。
- 依赖安装、代码生成、格式化、静态检查、测试和 Git 操作均应针对当前工程执行。
- 每完成一个功能，用中文提交信息在本地执行一次 Git 提交。
- 远程仓库：`origin` = `https://github.com/gitzcs/TVBoxAPP.git`（公开仓库，按用户要求推送；本机实际使用 SSH 别名地址 `git@github.com.gitzcs:gitzcs/TVBoxAPP.git`，对应 `~/.ssh/config` 的 `github.com.gitzcs` 条目）。**推送前必须确认敏感信息不入库**：`keystore.properties`、`*.jks`、`local.properties` 已在 `.gitignore` 中，不得添加或提交；改动涉及这些文件时先停下来询问用户。
- 代码改动需同时兼容低版本与高版本 Android：最低支持 API 19（Android 4.4），同时保证在高版本（如 API 24+ 的 `file://` 跨应用暴露限制、API 26+ 的通知渠道与后台执行限制）上行为正确。
- 涉及版本差异时用 `Build.VERSION.SDK_INT` 分支处理，不得只在单一版本上验证后交付。
