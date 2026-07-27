# 生产备份恢复演练 Checkpoint（2026-07-28）

## 范围

- 对生产主机、应用服务、PostgreSQL、备份定时器和磁盘空间做只读盘点。
- 手动触发一次现有 systemd 数据库备份服务。
- 将新备份恢复到同机隔离临时库，核对结构、迁移版本和核心数据行数。
- 下载一份短期异机副本到本机临时目录并核对 SHA-256；备份文件未加入仓库。

## 生产盘点

- `yubai-blog.service`：`active/running`，工作目录 `/opt/yubai-blog`，演练前启动时间为 2026-07-21 14:45 CST。
- PostgreSQL：16.14；`yubai_blog` 约 8.3 MB。
- 根分区：40 GB，总使用约 6 GB，剩余约 32 GB。
- `yubai-blog-backup.timer`：每日执行，最近一次计划任务成功；服务器原有 7 份本地 dump。
- 生产库当前 Flyway `installed_rank` 为 4，低于仓库当前 V29；后续发布需连续执行 V5 至 V29。
- `note_attachments` 当前为 0 行，且生产尚未部署 V27，因此没有现存附件文件需要纳入本次备份。

## 即时备份

- 通过 `systemctl start yubai-blog-backup.service` 触发，服务结果为 `success`、退出码 0。
- 产物：`yubai_blog-20260727T174616Z.dump`，大小 22,474 bytes。
- SHA-256：`ae28c71eb3616783b6f6d4927fbb38fe092b88139d64998eb1ddfac80005ecf5`。
- 本机临时副本哈希与服务器源文件一致；远端中转文件随后删除。

## 隔离恢复验证

- 创建临时库 `yubai_blog_restore_verify_20260728_0146`。
- 使用 `pg_restore --exit-on-error --no-owner` 完整恢复，无错误退出。
- 生产库与恢复库均包含 9 张 public 表，表名完全一致。
- 两边 Flyway `installed_rank` 均为 4。
- 核心行数一致：`posts=5`、`learning_notes=0`、`projects=3`、`note_attachments=0`。
- 验证后已删除临时库；应用健康检查仍返回 `UP`。

## 未完成项

- 生产应用仍停留在 Flyway rank 4；V27 上线后需再次确认真实附件文件随批次归档。
- 后续每季度应从 COS 随机选择一个历史批次重复隔离恢复抽查。

## COS 接入准备

- 生产主机确认为腾讯云 Lighthouse（Ubuntu Server 24.04 LTS 64bit），不支持 CVM 实例角色。
- COS 使用仅开启编程访问的专用 CAM 子用户和最小权限 SecretKey 模式；权限仅覆盖 `prod-backup-1456294292` 桶的 `backups/prod/` 前缀，且不允许删除对象、删除桶或修改桶配置。
- COSCLI v1.0.8 已安装，别名 `backup` 与 `/root/.cos.yaml` 配置的列举测试成功；密钥不进入仓库、脚本、service 或 `backup.env`。
- 备份脚本候选版本已增加附件归档、SHA-256 清单和可选 COS 上传；COS 上传失败会令 systemd 任务失败并保留本地文件。
- 候选脚本已在生产 Ubuntu 主机通过 `bash -n`，并以关闭 COS 的模式生成可由 `pg_restore --list` 读取的新 dump；附件测试目录也已归档且内容可列出，清单中的两个哈希均与文件实算哈希一致。
- 候选脚本只从用户目录临时执行，未覆盖当前生产脚本；验证后已删除远端候选文件。

## COS 部署与往返恢复

- `/root/.cos.yaml` 已安全复制到 `/etc/yubai-blog/cos.yaml`，后者为 `root:root`、`0600`；`yubai` 用户不可读，应用仍可读取同目录的 `app.env`。
- 新备份脚本、service、timer 和 `backup.env` 已安装；首次执行保持 `COS_BACKUP_ENABLED=false`。
- 本地批次 `20260727T194025Z` 包含 dump、空附件目录归档和 SHA-256 清单；dump 可解析，两个文件哈希均一致。
- 该批次已手工上传到 `cos://backup/backups/prod/20260727T194025Z/`，随后重新下载；下载文件哈希一致。
- 首次隔离恢复因下载目录仅允许 root 访问而在读取 dump 前失败，未写入任何表；将目录修正为 `root:postgres`、`0750`，dump 修正为 `postgres:postgres`、`0600` 后完整恢复成功，部署文档已同步修正。
- 生产库与 COS 恢复库均为 9 张 public 表、Flyway rank 4；核心行数一致：`posts=5`、`learning_notes=0`、`projects=3`、`note_attachments=0`。
- 自动上传开关随后启用；systemd 批次 `20260727T194430Z` 的 dump、附件归档和清单均成功上传 COS，service 返回 success。
- `yubai-blog-backup.timer` 已启用并处于 active；下一次计划执行为 2026-07-29 03:28 CST。
- COS 生命周期 90 天及 CAM 禁止删除/改桶权限已由用户确认；脚本只上传，不调用 COS 删除命令。
- 隔离数据库、下载目录和服务器用户目录中的部署暂存文件均已清理；应用健康检查保持 `UP`。
