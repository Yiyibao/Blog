# 可观测性与 OpenCode 侧车检查点（2026-07-31）

## pg_stat_statements

- 维护前即时备份：`20260730T173317Z`，数据库 dump、附件归档与 SHA-256
  清单均通过。
- PostgreSQL 版本：16.14。
- `shared_preload_libraries=pg_stat_statements`。
- `compute_query_id=auto`。
- `yubai_blog` 已创建 `pg_stat_statements` 1.10 扩展。
- PostgreSQL 与博客服务重启后均为 active，健康检查返回 UP。
- 首次验证已采集 31 条统计记录，可以按调用次数、总执行时间和平均执行时间分析 SQL。
- 慢查询日志继续保持 `log_min_duration_statement=500ms`。

第一次验证查询误连默认 `postgres` 数据库，保护脚本随即撤销预加载并恢复服务；
随后全部查询明确指定 `yubai_blog`，重新启用并验证成功。数据未受影响。

## OpenCode 侧车

- 安装版本：OpenCode 1.18.10，Linux x64。
- 二进制来源：官方 npm 包 `opencode-linux-x64@1.18.10`。
- 上传前后 SHA-512：
  `82499941f2358751c989a02e77ccde1081a9a9522820d71ef41e1b3f8fb854d86bae4ab6a8bef758873620e2dd3bfedd193d6d6d2a0e8eb1f5e0424f1239054e`。
- 安装路径：`/usr/bin/opencode`。
- 独立用户：`opencode`，无登录 shell。
- 服务：`yubai-blog-opencode.service` 已 enabled 且 active。
- 网络：仅监听 `127.0.0.1:4096`。
- 匿名访问 `/provider` 返回 401；使用内部 Basic Auth 返回成功。
- `--pure` 已启用，外部插件不加载；工具权限全局及 `blog-ai` agent 均为 deny。
- `/etc/yubai-blog-opencode` 为 `root:opencode 0750`，环境和配置文件为 0640。
- 博客与侧车使用同一组内部 Basic Auth 凭据，博客重启后健康检查为 UP。
- 后台已登记 `OpenCode Sidecar`，类型为 `OPENCODE_SERVER`，当前保持 disabled。

## 待用户凭据

当前 `opencode auth list` 为 0 credentials，`/provider` 的 `connected` 数组为空。
OpenCode Go 需要用户提供订阅/API key，因此尚不能调用
`opencode-go/deepseek-v4-flash`。

取得 key 后执行交互式认证：

```bash
sudo -u opencode HOME=/var/lib/opencode \
  OPENCODE_CONFIG=/etc/yubai-blog-opencode/opencode.json \
  /usr/bin/opencode auth login --provider opencode-go
```

认证完成后重启侧车，确认 `/provider` 的 `connected` 包含 `opencode-go`，再在博客
后台测试连接并启用 `OpenCode Sidecar`。API key 不写入仓库或博客数据库。

## 最终验收

- `pg_stat_statements` 最终采集 54 条统计记录。
- 首页、`/api/v1/posts`、`/rss.xml`、`/sitemap.xml` 经生产 Nginx 均返回 200。
- 应用 liveness/readiness 健康检查为 UP。
- 配置后备份批次：`20260730T175155Z`。
- 数据库 dump 与附件归档均通过本地 SHA-256 校验；COS 中 dump、附件和最后上传的
  `SHA256SUMS` 提交标记共 3 个对象齐全。
- 生产与本地安装临时包均已清理。
