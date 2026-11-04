# TVBoxAPP

基于开源项目 [q215613905/TVBoxOS](https://github.com/q215613905/TVBoxOS) 二次开发的安卓电视盒子应用，聚焦「**视频源全自动获取与本地合并**」：不再依赖任何随包内置源或固定订阅，启动后自动发现、验证、合并可用的视频源。

> 本项目仅供学习研究，请勿用于商业用途；所有视频源内容均来自第三方公开配置，与本项目无关。

## 功能特性

- **自动找源**：启动即用本地缓存，后台定时从 bdnb.cn 索引站 / GitHub / GitLab / Gitee 发现配置并逐个验证，可用源自动写入配置列表、失效源自动移除
- **可替换索引站**：设置页可新增、编辑、启停多个兼容 GitHub 仓库搜索 JSON 的索引站，索引站失效时仍自动回退 GitHub 搜索
- **本地合并源**：找源成功后把所有可用配置的视频站点去重合并成一份 `local://merged_sources.json`，作为默认配置并排在配置列表第一位（也可在「设置 → 配置地址管理」手动管理）
- **主页源随机**：每次打开应用随机选一个直连采集站作为主页源；功能栏新增「刷新」按钮，点击随机换源，选中的源无内容时自动换下一个
- **搜索去重**：聚合搜索、快速搜索、详情页换源均按「后端」去重，同一采集站不会重复请求
- **应用内自动更新**：更新源地址可配置（推荐 GitHub Releases），检查 → 下载（多地址镜像兜底）→ SHA-256 校验 → 调起系统安装
- **兼容性**：最低支持 Android 4.4（API 19），HTTP 模块带 TLS 1.2 兼容层与 DoH

## 编译

```bash
# 调试包（flavor: java）
./gradlew.bat :app:assembleJavaDebug

# 正式包（需要签名配置）
./gradlew.bat :app:assembleJavaRelease
```

- 签名：项目根目录放 `keystore.properties`（含 `RELEASE_STORE_FILE / RELEASE_KEY_ALIAS / RELEASE_STORE_PASSWORD / RELEASE_KEY_PASSWORD` 四项），CI 或无签名环境产出未签名包
- 要求 JDK 8+ 与 Android Gradle Plugin 7.x（见 `build.gradle`）

## 应用内更新托管

任何能提供 HTTPS GET 的地方均可，推荐 GitHub Releases：

1. 仓库里放一份 `update.json`：

   ```json
   {
     "versionCode": 2,
     "versionName": "1.1.0",
     "changelog": "本次更新内容",
     "sha256": "apk 的 SHA-256（可选）",
     "apk": ["https://github.com/you/repo/releases/download/v1.1.0/app.apk",
             "https://gh-proxy.com/https://github.com/you/repo/releases/download/v1.1.0/app.apk"]
   }
   ```

2. Release 附件传签名 APK
3. 应用内「设置 → 检查更新（长按）」填入 `update.json` 的地址

## 配置格式参考

<details>
<summary>json 接口示例（点开查看）</summary>

```jsonc
//searchable:搜索开关      0:关闭 1:启用
//filterable:首页可选      0:否 1:是
//playerType:播放器类型    0:系统 1:IJK 2:EXO
//采集接口类型             0:xml 1:json 3:jar 4:remote
//parses解析类型           0:嗅探,自带播放器 1:解析,返回直链
//直播参数说明             ua:用户自定义ua epg:节目网址 logo:台标网址

{
  "spider": "./your.jar",
  "wallpaper": "./api/img",
  "sites": [],
  "parses": [],
  "lives": [],
  "rules": [],
  "doh": [
    { "name": "腾讯", "url": "https://doh.pub/dns-query" },
    { "name": "阿里", "url": "https://dns.alidns.com/dns-query" },
    { "name": "360",  "url": "https://doh.360.cn/dns-query" }
  ]
}
```

</details>

## 致谢

- [q215613905/TVBoxOS](https://github.com/q215613905/TVBoxOS) 及其上游
- [CatVodOpen](https://github.com/CatVodOpen) 的爬虫生态
- [LINUX DO](https://linux.do/) 

## License

本项目基于上游 [q215613905/TVBoxOS](https://github.com/q215613905/TVBoxOS)（AGPL-3.0）二次开发，继续以 [AGPL-3.0](LICENSE) 协议开源。
