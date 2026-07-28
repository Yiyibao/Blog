# 2026-07-28 项目问题修复检查点

## 范围

本轮根据项目代码审查结果完成 16 组安全性、数据一致性、并发、前端行为和运维可靠性修复。实现与修正过程未连接生产 COS、未修改生产服务器，也未改动本地密钥配置。

## 已完成修复

1. 认证搜索、私有笔记和笔记附件响应改为私有禁缓存，并正确设置 `Vary: Authorization`。
2. 保持已发布的 Flyway V8 不变，在迁移前归档 rank-4 生产库中的 `projects` 和 `project_stack` 数据；元数据异常时阻止迁移。
3. refresh token 轮换前校验账号启用状态和 `sessions_valid_from`，禁用账号不能继续换取令牌。
4. 删除整篇笔记后仅在事务提交成功时清理对应附件文件，事务回滚不会删除文件。
5. 前端 refresh 流程增加认证 generation guard，旧 refresh 结果不能覆盖退出登录或新登录。
6. `/login` 与 `/admin/login` 统一加载登录样式。
7. 文章保存与 revision 快照纳入同一事务边界。
8. access token 不再持久化到 localStorage；跨会话保持登录依赖 HttpOnly refresh cookie，并清理旧 localStorage 键。
9. 合集更新在 flush 后返回数据库实际版本。
10. 菜单、合集和标签页面增加请求竞态保护，并修复菜单跨午夜跟随今日的状态判断。
11. 本地附件存储使用独立临时文件、读写锁和受限的 Windows 原子替换重试；失败时保留旧文件并清理临时文件。
12. 动态 SEO 页面补充运行时 metadata/noindex，并要求生产部署构建显式提供动态预渲染 API。
13. 归档页加载服务端报告的全部分页，不再静默截断；部分分页失败时保留已成功内容并显示提示。
14. 图片上传增加总像素限制，JPEG 解码或优化失败时拒绝文件，不再静默保存未优化原图。
15. 部署统一为 `releases/<release>`、`current` 和 `shared/attachments` 目录约定；systemd、nginx、CI artifact、健康检查、回滚及版本清理使用同一约定。
16. COS 备份先上传 dump 和附件归档，最后上传 `SHA256SUMS` 作为完整批次标记；上传失败仍执行本地过期清理，但显式保留当前失败批次并返回失败状态。

## 验证结果

- 后端完整测试：`487/487` 通过。
- `LocalFileStorageTest` 并发压力测试：连续 50 轮通过。
- 前端完整测试：`442/442` 通过。
- 前端 TypeScript typecheck：通过。
- 前端生产 build、PWA 和 prerender：通过。
- `.github/workflows/deploy.yml` YAML 解析与结构检查：通过。
- `deploy/bootstrap-server.sh`、`deploy/yubai-blog-backup` Bash 语法检查：通过。
- `git diff --check`：通过。
- 旧迁移类名、旧附件目录、旧 nginx/systemd 路径、无效 COSCLI 命令和 localStorage token 说明扫描：无残留。

## 生产边界

- 本检查点记录的是仓库代码状态；生产服务器尚未部署本轮修复。
- 生产数据库当前仍停留在 Flyway rank 4，首次部署前必须按 `docs/migration-preflight.md` 执行备份和迁移预检。
- GitHub Deploy workflow 需要配置非敏感变量 `PRERENDER_API_BASE_URL`；缺失时生产构建会按设计失败，避免发布缺少动态 SEO 快照的版本。
- 服务器需要按新约定安装 `deploy/yubai-blog.service`、`deploy/hxnf.top.nginx`，并确保部署用户具备 release 写入和受限的 service restart 权限。
- 附件持久目录统一为 `/opt/yubai-blog/shared/attachments`，应用和备份环境配置必须同时更新。
- 数据库 dump 与附件 tar 依次生成，不是严格的跨资源同一时刻快照；严格一致恢复点应在停止应用写入后生成备份，并在完成后重启和检查健康状态。

## 后续操作

1. 推送本检查点及全部修复代码。
2. 配置 GitHub Environment、Secrets 和 `PRERENDER_API_BASE_URL`。
3. 在生产低峰窗口重新确认 COS 备份和恢复点。
4. 按迁移预检、部署、健康检查和回滚验证顺序上线。
