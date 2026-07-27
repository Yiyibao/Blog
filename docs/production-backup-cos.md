# Lighthouse 生产备份同步到腾讯云 COS

## 一、环境与边界

- 主机：腾讯云轻量应用服务器 Lighthouse，Ubuntu Server 24.04 LTS 64bit。
- COS 存储桶：`prod-backup-1456294292`。
- 地域：`ap-shanghai`。
- Endpoint：`cos.ap-shanghai.myqcloud.com`。
- COSCLI 桶别名：`backup`。
- 对象前缀：`backups/prod/`。
- COSCLI：v1.0.8，使用专用 CAM 子用户的 SecretKey 模式。
- 生产配置目标：`/etc/yubai-blog/cos.yaml`，必须为 `root:root`、`0600`。

SecretId/SecretKey 只由 COSCLI 配置文件管理，不得写入 Git、备份脚本、systemd service 或 `backup.env`。CAM 策略只允许目标桶 `backups/prod/` 前缀所需的上传、读取和列举操作，不允许删除对象、删除桶或修改桶配置。

每个 UTC 批次上传到：

```text
cos://backup/backups/prod/<STAMP>/
```

批次包含 PostgreSQL custom dump、附件目录归档和 `SHA256SUMS`。缺少 `backup.env`、附件目录或启用 COS 后缺少 COSCLI 配置都会令 service 失败，避免静默退化为不完整备份。本地文件保留 14 天；COS 通过桶生命周期保留 90 天，脚本不主动删除 COS 对象。

## 二、部署文件暂存

以下命令在工作站仓库根目录执行：

```bash
scp -i ~/.ssh/id_ed25519_tencent_blog \
  deploy/yubai-blog-backup \
  deploy/yubai-blog-backup.service \
  deploy/yubai-blog-backup.timer \
  deploy/yubai-blog-backup.env.example \
  ubuntu@159.75.211.202:/home/ubuntu/
```

## 三、安全迁移 COSCLI 配置

以下命令在 Lighthouse 执行。先停止现有 timer，避免部署和验证期间自动运行：

```bash
sudo systemctl disable --now yubai-blog-backup.timer
sudo test -f /root/.cos.yaml
sudo coscli --config-path /root/.cos.yaml ls cos://backup/backups/prod/
```

保留 `/etc/yubai-blog` 的 `yubai` 组遍历权限，避免影响应用读取 `app.env`；目录所有者仍为 root。COS 配置文件自身只允许 root 读取：

```bash
sudo chown root:yubai /etc/yubai-blog
sudo chmod 0750 /etc/yubai-blog
sudo install -o root -g root -m 0600 \
  /root/.cos.yaml /etc/yubai-blog/cos.yaml
sudo stat -c '%U:%G %a %n' \
  /etc/yubai-blog /etc/yubai-blog/cos.yaml
sudo -u yubai test ! -r /etc/yubai-blog/cos.yaml
sudo coscli --config-path /etc/yubai-blog/cos.yaml \
  ls cos://backup/backups/prod/
```

预期权限：

```text
root:yubai 750 /etc/yubai-blog
root:root 600 /etc/yubai-blog/cos.yaml
```

不要运行 `coscli config show`，也不要把配置文件内容输出到终端、日志或工单。

## 四、安装但不启用 timer

```bash
sudo install -o root -g root -m 0755 \
  /home/ubuntu/yubai-blog-backup /usr/local/sbin/yubai-blog-backup
sudo install -o root -g root -m 0644 \
  /home/ubuntu/yubai-blog-backup.service /etc/systemd/system/yubai-blog-backup.service
sudo install -o root -g root -m 0644 \
  /home/ubuntu/yubai-blog-backup.timer /etc/systemd/system/yubai-blog-backup.timer
sudo install -o root -g yubai -m 0640 \
  /home/ubuntu/yubai-blog-backup.env.example /etc/yubai-blog/backup.env
sudo install -d -o yubai -g yubai -m 0750 \
  /opt/yubai-blog/data/attachments
sudo systemctl daemon-reload
sudo systemctl is-enabled yubai-blog-backup.timer || true
sudo grep '^COS_BACKUP_ENABLED=' /etc/yubai-blog/backup.env
```

此时必须看到 `COS_BACKUP_ENABLED=false`，timer 必须不是 enabled/active。

## 五、首次本地备份测试

```bash
sudo systemctl start yubai-blog-backup.service
sudo systemctl show yubai-blog-backup.service \
  --property=Result,ExecMainStatus,ExecMainStartTimestamp,ExecMainExitTimestamp \
  --no-pager
sudo journalctl -u yubai-blog-backup.service -n 50 --no-pager

STAMP="$(sudo find /var/backups/yubai-blog -maxdepth 1 \
  -type f -name 'SHA256SUMS-*' -printf '%f\n' \
  | sort | tail -n 1 | sed 's/^SHA256SUMS-//')"
printf '%s\n' "${STAMP}"

sudo test -s "/var/backups/yubai-blog/yubai_blog-${STAMP}.dump"
sudo test -s "/var/backups/yubai-blog/attachments-${STAMP}.tar.gz"
sudo test -s "/var/backups/yubai-blog/SHA256SUMS-${STAMP}"
sudo sh -c "cd /var/backups/yubai-blog && sha256sum --check SHA256SUMS-${STAMP}"
sudo -u postgres pg_restore --list \
  "/var/backups/yubai-blog/yubai_blog-${STAMP}.dump" >/dev/null
sudo tar --list --gzip \
  --file="/var/backups/yubai-blog/attachments-${STAMP}.tar.gz"
```

全部命令成功后，才进行 COS 手工往返验证。

## 六、COS 手工上传与下载校验

使用与脚本相同的服务端加密和禁止覆盖选项：

```bash
COS_DEST="cos://backup/backups/prod/${STAMP}"

sudo coscli --config-path /etc/yubai-blog/cos.yaml \
  cp "/var/backups/yubai-blog/yubai_blog-${STAMP}.dump" \
  "${COS_DEST}/yubai_blog-${STAMP}.dump" \
  --forbid-overwrite=true \
  --encryption-type SSE-COS --server-side-encryption AES256
sudo coscli --config-path /etc/yubai-blog/cos.yaml \
  cp "/var/backups/yubai-blog/attachments-${STAMP}.tar.gz" \
  "${COS_DEST}/attachments-${STAMP}.tar.gz" \
  --forbid-overwrite=true \
  --encryption-type SSE-COS --server-side-encryption AES256
sudo coscli --config-path /etc/yubai-blog/cos.yaml \
  cp "/var/backups/yubai-blog/SHA256SUMS-${STAMP}" \
  "${COS_DEST}/SHA256SUMS-${STAMP}" \
  --forbid-overwrite=true \
  --encryption-type SSE-COS --server-side-encryption AES256
sudo coscli --config-path /etc/yubai-blog/cos.yaml \
  ls "${COS_DEST}/"
```

下载到隔离目录并校验哈希：

```bash
VERIFY_DIR="/var/tmp/yubai-cos-verify-${STAMP}"
sudo install -d -o root -g postgres -m 0750 "${VERIFY_DIR}"
sudo coscli --config-path /etc/yubai-blog/cos.yaml \
  cp "${COS_DEST}/yubai_blog-${STAMP}.dump" \
  "${VERIFY_DIR}/yubai_blog-${STAMP}.dump"
sudo coscli --config-path /etc/yubai-blog/cos.yaml \
  cp "${COS_DEST}/attachments-${STAMP}.tar.gz" \
  "${VERIFY_DIR}/attachments-${STAMP}.tar.gz"
sudo coscli --config-path /etc/yubai-blog/cos.yaml \
  cp "${COS_DEST}/SHA256SUMS-${STAMP}" \
  "${VERIFY_DIR}/SHA256SUMS-${STAMP}"
sudo sh -c "cd '${VERIFY_DIR}' && sha256sum --check 'SHA256SUMS-${STAMP}'"
sudo chown postgres:postgres "${VERIFY_DIR}/yubai_blog-${STAMP}.dump"
sudo chmod 0600 "${VERIFY_DIR}/yubai_blog-${STAMP}.dump"
```

恢复到全新的隔离数据库和附件目录，禁止指向生产目标：

```bash
RESTORE_DB="yubai_blog_restore_verify_$(date -u +%Y%m%d%H%M%S)"
RESTORE_ATTACHMENTS="${VERIFY_DIR}/restored-attachments"
sudo -u postgres createdb "${RESTORE_DB}"
sudo -u postgres pg_restore --exit-on-error --no-owner \
  --dbname="${RESTORE_DB}" \
  "${VERIFY_DIR}/yubai_blog-${STAMP}.dump"
sudo install -d -o root -g root -m 0700 "${RESTORE_ATTACHMENTS}"
sudo tar --extract --gzip --no-same-owner \
  --file="${VERIFY_DIR}/attachments-${STAMP}.tar.gz" \
  --directory="${RESTORE_ATTACHMENTS}"

sudo -u postgres psql -AtX --dbname=yubai_blog \
  --command="select count(*) from pg_tables where schemaname = current_schema()"
sudo -u postgres psql -AtX --dbname="${RESTORE_DB}" \
  --command="select count(*) from pg_tables where schemaname = current_schema()"
sudo -u postgres psql -AtX --dbname="${RESTORE_DB}" \
  --command="select max(installed_rank) from flyway_schema_history where success"
sudo find "${RESTORE_ATTACHMENTS}" -type f -printf '%P\n' | sort
```

核对数据库表数、Flyway 版本、核心数据和附件清单后清理隔离资源：

```bash
sudo -u postgres dropdb "${RESTORE_DB}"
sudo rm -rf -- "${VERIFY_DIR}"
```

这里只删除本机隔离目录，不删除任何 COS 对象。

## 七、启用 COS 自动上传与 timer

只有前述上传、下载、哈希和恢复验证全部成功后，才执行：

```bash
sudo sed -i 's/^COS_BACKUP_ENABLED=false$/COS_BACKUP_ENABLED=true/' \
  /etc/yubai-blog/backup.env
sudo grep '^COS_BACKUP_ENABLED=' /etc/yubai-blog/backup.env
sudo systemctl start yubai-blog-backup.service
sudo systemctl show yubai-blog-backup.service \
  --property=Result,ExecMainStatus --no-pager
sudo journalctl -u yubai-blog-backup.service -n 50 --no-pager
sudo coscli --config-path /etc/yubai-blog/cos.yaml \
  ls cos://backup/backups/prod/
sudo systemctl enable --now yubai-blog-backup.timer
sudo systemctl list-timers yubai-blog-backup.timer --all --no-pager
```

## 八、启用前检查清单

- Lighthouse 系统、磁盘余量、PostgreSQL 和应用健康状态正常。
- `/etc/yubai-blog` 为 `root:yubai`、`0750`，应用仍可读取 `app.env`。
- `/etc/yubai-blog/cos.yaml` 为 `root:root`、`0600`，`yubai` 用户不可读。
- 仓库、脚本、service 和 `backup.env` 均不含 SecretId/SecretKey。
- `backup.env` 使用别名 `backup`、配置 `/etc/yubai-blog/cos.yaml`、前缀 `backups/prod`。
- 本地测试包含 dump、附件归档和 SHA-256 清单，且哈希与 archive 检查通过。
- COS 手工上传目标为 `cos://backup/backups/prod/<STAMP>/`。
- 从 COS 下载后的 SHA-256、数据库隔离恢复和附件隔离解包均通过。
- CAM 子用户不能删除对象、删除桶或修改桶配置。
- COS 生命周期已设置为 90 天，本地脚本保留 14 天且不删除 COS 对象。
- `COS_BACKUP_ENABLED=true` 后的手动 systemd 执行成功。
- 满足以上全部条件后才启用 timer。
