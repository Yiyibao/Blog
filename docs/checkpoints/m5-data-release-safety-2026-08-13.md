# M5 数据与发布安全 Checkpoint

日期：2026-08-13
范围：Flyway fresh/upgrade/rollback、生产发布前置、数据库与共享存储备份恢复、staged/AI 资源生命周期

## 交付结果

- 冻结 V1–V55 共 54 个历史迁移文件的 SHA-256；CI 会同时拒绝文件集合、内容和目标版本漂移。
- 新安装仍执行完整迁移历史，但只对确认的空 schema 清除历史演示内容；版本化旧库保留全部业务数据，非空无历史库 fail closed。
- 新增 V55 expand-only 迁移，为 dish import/asset 增加 owner、TTL、字节统计与审计；历史 V51/V52 双存储行保留审计，新非法写入被约束拒绝。
- staged dish、dish import、AI upload 和 artifact 按 owner 隔离，数量/字节配额在 PostgreSQL transaction advisory lock 下串行检查；过期资源定时清理并记录成功/失败指标。
- 备份批次同时提交 PostgreSQL custom dump、共享 storage tar、逐文件 SHA-256 inventory 和批次 SHA-256 manifest；COS 仍以 manifest 最后上传作为完成标记。
- 发布前置在 symlink、权限和服务状态改变前检查最近备份、批次完整性、dump/tar 可读性、磁盘、固定 SSH host key、兼容窗口，并用待发布 JAR 实际执行只读 Flyway `validate/info`。
- V53 旧应用数据契约在 V55 下保持：旧表/列全部保留，新增引用可空或有兼容默认值；代码回滚不伪装成数据库回滚。

## 验证证据

- 定向后端：fresh、V39/V52/V53 upgrade、V55 双存储审计、旧应用回滚契约、owner/TTL/配额/清理测试通过。
- `BlogApiIntegrationTest`：历史 seed 作为显式测试 fixture 保留，生产 fresh 默认清理；全套集成断言通过。
- 恢复演练：`status=PASS`，Flyway `V55→V55`，文章 `20→20`、菜品 `20→20`，AI lifecycle 表 4/4，50 个共享存储文件逐项校验通过，query audit 通过。
- 待发布 JAR 预检：`current=V55 target=V55 pending=0`，Flyway validate/info 通过。
- 前端：71 个测试文件、822 项测试通过；Statements 62.39%、Branches 55.88%、Functions 51.20%、Lines 65.08%；生产构建与 1,469,677-byte JS budget 通过。
- 脚本：migration manifest、Bash 语法、PowerShell parser、预检缺失备份 fail-closed、`git diff --check` 均通过。
- PostgreSQL 17 是正式发布基线；本地 PostgreSQL 18.4 的 Flyway“最高正式验证到 17”提示已在支持矩阵中解释，不据此声明生产 18 支持。

## 发布与回滚约束

- `deploy/release-compatibility.env`：schema target V55、rollback application floor V53、migration mode expand-only。
- 生产切换仍必须使用 `.github/workflows/deploy.yml` 的 `production` Environment 门，服务器最近完整备份不得超过 26 小时。
- 恢复必须使用同一批次的数据库 dump 与 shared storage；只恢复 metadata 或只恢复字节都不构成有效恢复。
- 旧代码回滚只切换应用 release，数据库继续保持 V55；若当前 schema 低于 V53 或高于 V55，自动发布前置拒绝放行。

## 结论

M5 数据与发布安全门已具备可执行契约和恢复证据。Production GA 的代码门满足，但实际生产部署继续依赖生产环境审批、真实最新备份和服务器预检全部通过。
