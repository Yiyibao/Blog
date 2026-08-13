# M7 可观测性、SLO 与告警

应用只暴露低基数、可聚合的运行指标；owner、taskId、fileId、正文、token 和 provider secret 不得进入 metric label 或普通日志。

## SLO 目标

| 范围          | 目标                                                        | 数据源                                                                                    |
| ------------- | ----------------------------------------------------------- | ----------------------------------------------------------------------------------------- |
| 公开 HTTP API | 5xx < 0.5%；p95 < 300 ms                                    | `http_server_requests_seconds_*`                                                          |
| 后台 HTTP API | p95 < 500 ms                                                | `http_server_requests_seconds_*`，按 `/api/v1/admin/**` 聚合                              |
| AI 任务       | queued→terminal p95 < 60 s；永久 RUNNING = 0                | `blog_ai_tasks{status=...}` 与任务表                                                      |
| AI/下载失败   | 5xx、`AI_*_ERROR`、artifact integrity failure 可追踪        | HTTP 指标、应用安全日志、artifact 状态                                                    |
| 存储          | retained file/artifact bytes < 配额；清理失败持续时间可告警 | `blog_ai_files_bytes`、`blog_ai_artifacts_bytes`、`blog_resources_cleanup_failures_total` |
| DB pool       | pending connections = 0；active/max < 0.8                   | `hikaricp_connections_*`                                                                  |
| 发布          | release health check < 60 s；失败必须有回滚结果             | deploy workflow artifact/log                                                              |
| 备份          | 最近完整 batch ≤ 26 h；RPO ≤ 24 h                           | backup manifest + `release-preflight.sh`                                                  |

## Prometheus 规则

`deploy/prometheus-alerts.yml` 是可导入的规则模板。生产仍需由运维将通知路由到值班渠道；未配置 Alertmanager 不得被 CI 伪装成已告警。

- `BlogHighApiErrorRate`：五分钟 API 5xx 比例 > 0.5%。
- `BlogPublicApiLatencyP95`：公开 API 五分钟 p95 > 300 ms。
- `BlogAiQueueStuck`：存在 RUNNING 任务超过 10 分钟或队列状态持续增长。
- `BlogHikariPoolPressure`：连接池 pending > 0 或 active/max > 0.8。
- `BlogResourceCleanupFailures`：资源清理失败计数五分钟内增加。
- `BlogBackupFreshness`：备份 textfile exporter 的最后成功时间超过 26 小时；没有该指标时保持 unknown，不得误报为 healthy。

## CI 与恢复证据

- M7 full-stack job 使用隔离 PostgreSQL、真实本地 Spring Boot/Vite 和 deterministic fake Responses provider；失败日志、fake provider 请求和 Playwright trace 作为 artifact。
- offline smoke 仍单独运行，不共享“在线成功”结论。
- 备份/恢复和 release preflight 继续以批次 manifest、数据库 dump、storage archive、逐文件 SHA-256 和 Flyway 兼容窗口为事实源。
