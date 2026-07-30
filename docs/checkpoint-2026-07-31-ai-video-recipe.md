# AI 视频菜谱提取检查点（2026-07-31）

## 目标

将原有“文本/网页 → AI 菜谱 → 菜品草稿”扩展为：

`视频链接 → 元数据/字幕/缩略图 → AI 结构化菜谱 → .yrecipe → 预览 → 草稿或直接发布`

直接发布的菜品可以立即被今日菜单模块通过 slug 选择；草稿仍需在菜品管理中审核并发布。

## 实现

- 新增 `VIDEO_URL` 来源类型和“视频链接”前端页签。
- 使用 `yt-dlp` 的 `--skip-download`、字幕和缩略图选项；不下载完整视频。
- 默认支持 YouTube、Bilibili、抖音和小红书的主域名及短链域名。
- 仅接受公网 HTTPS URL，拒绝 userinfo、内网、环回、链路本地、CGNAT、组播和保留地址。
- 字幕执行 VTT 时间轴/标签清理和重复行去重；简介与字幕有效内容不足 60 字时拒绝生成。
- AI 仍只接收文本，不直接获得文件、网络或工具权限。
- 视频缩略图作为导入封面；无缩略图时使用既有安全占位图。
- 暂存导入包新增下载端点，可在提交前保存标准 `.yrecipe` 文件。
- 导入提交新增 `published` 选项，缺省为 `false`，保持现有调用兼容。
- 弹窗成功后自动回填 AI slug 和匹配分类；取消时清理暂存会话。

## 运行配置

```properties
APP_RECIPE_VIDEO_ENABLED=true
APP_RECIPE_YT_DLP_PATH=/usr/local/bin/yt-dlp
APP_RECIPE_VIDEO_TIMEOUT=PT45S
APP_RECIPE_MAX_TRANSCRIPT_CHARS=25000
APP_RECIPE_VIDEO_HOSTS=youtube.com,youtu.be,bilibili.com,b23.tv,douyin.com,iesdouyin.com,xiaohongshu.com,xhslink.com
```

生产环境固定使用 PyPI wheel `yt-dlp==2026.7.4`，解压到
`/opt/yt-dlp/2026.7.4`，由仓库内 `deploy/yt-dlp` 包装器从
`/usr/local/bin/yt-dlp` 启动。升级时需同步修改版本目录并重新执行视频平台抽查。

## 验证

- 后端完整测试：655 项通过。
- 视频提取、安全 URL 和导入相关测试：100 项通过。
- 前端完整测试：49 个文件、492 项通过。
- 前端生产构建通过。

## 使用限制

- 平台要求登录、验证码或 cookies 时会返回明确失败，不绕过平台访问控制。
- 没有字幕且简介信息不足的视频不会生成猜测性菜谱。
- AI 供应商必须已经配置凭据并启用；OpenCode sidecar 进程健康不等于模型凭据可用。
- `.yrecipe` 应在提交前下载；提交后暂存封面会迁移到正式菜品资源。
