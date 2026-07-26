# 阶段 0 检查点 · 安全与正确性（2026-07-26）

执行依据：docs/optimization-plan-v3-2026-07-26.md（已批准）。本阶段共 10 个独立提交。

## 已完成项

| 编号 | 内容 | 提交 |
| --- | --- | --- |
| NF-1 | 登录态统一走 Pinia authStore 单一事实源，修复登录/守卫双轨导致的重定向死循环；api/admin.ts 会话函数与 axios 拦截器全部委托 store | fix: 管理端登录态统一走 authStore 单一事实源 |
| NF-3 | contentStore.currentPost 改以路由 slug 为响应式输入；ArticlePage watch slug 重拉详情，修复文章间跳转渲染旧文章 | fix: currentPost 改以路由 slug 为响应式输入 |
| NF-2 | 文章正文 v-html 渲染点前置 DOMPurify 消毒（两步走第一步，第一期 Markdown 迁移根治）；新增 dompurify 依赖与 utils/sanitizeHtml.ts | fix: 文章正文渲染前 DOMPurify 消毒 |
| ND-1 / P0-10 / P0-3(nginx) | nginx 显式转发 /sitemap.xml 与 /robots.txt；HSTS 头；登录接口 limit_req 5r/m | fix: nginx 转发 sitemap/robots、加 HSTS 与登录限速 |
| P0-1 / NB-3 | anyRequest() 改 denyAll；启动时拒绝 .env.example 占位 JWT 密钥 | fix: SecurityConfiguration 改 denyAll 兜底并拒绝占位 JWT 密钥 |
| P0-2 / P0-3 | 进程内固定窗口限流器（零新依赖）：登录 5 次/分/IP，点赞与收藏 10 次/分/IP+slug，超限 429 | feat: 登录与公开计数接口进程内限流 |
| P0-4 / P0-7 | 点赞/收藏改数据库端原子 UPDATE；收藏改纯计数语义（响应移除恒真的 isFavorite，已批准的破坏性变更；前端从未调用该接口，无兼容影响） | fix: 点赞收藏改数据库原子自增 |
| P0-6 | 附件上传 magic-byte 嗅探（PNG/JPEG/WebP/GIF），伪造 Content-Type 返回 400 | fix: 附件上传 magic-byte 嗅探 |
| P0-9 | 搜索 LIKE 通配符转义（%、_、\），GET 分组与 POST 分页两条路径 | fix: 搜索 LIKE 通配符转义 |
| P0-5 | 前端测试固化：公开笔记 markdown 含 script/onerror/javascript: 时 TipTap 只读渲染不产生可执行节点 | test: 阶段 0 集成测试覆盖与口令策略说明 |

## 验证结果

- 后端 `mvn test`：**155/155 通过**（原 139 + 新增 16），在云端以 JDK 21 + PostgreSQL 16 + 本机 .m2 离线仓库运行。
- 新增集成测试：未白名单路径 401/403、登录第 6 次 429、点赞第 11 次 429、32 线程并发点赞计数无丢失、伪造 Content-Type 上传拒绝、通配符转义。
- javac 语法检查通过；前端 vitest 因 node_modules 为 Windows 平台二进制（rolldown win32）无法在云端/VM 运行，**待本机执行**（见下）。

## 待用户本机执行

1. **前端验证**：`cd frontend && npm install && npm test`（npm install 拉取新增的 dompurify）。新增测试文件：adminSession、contentStore、sanitizeHtml、publicNotesXss。
2. **P0-8 口令轮换**（本机操作，代码不涉及）：
   - 数据库口令：`psql -U postgres -c "ALTER USER yubai_app WITH PASSWORD '<新的16位以上随机口令>';"`，同步更新 backend/.env.properties 的 DB_PASSWORD。
   - 管理员口令：AdminBootstrap 只在账号不存在时创建，改 .env 不会自动轮换；本地可执行 `DELETE FROM admin_users;` 后用新 APP_ADMIN_PASSWORD（≥16 位随机）重启后端重建，或直接 UPDATE bcrypt 哈希。
   - 生产环境按同样标准轮换，并确认 APP_JWT_SECRET 为随机值（openssl rand -base64 48）。
3. **生产部署**：更新 nginx 配置后 `nginx -t && systemctl reload nginx`，并验证：
   - `curl -I https://hxnf.top/sitemap.xml` 返回 XML（而非 index.html）
   - `curl -I https://hxnf.top` 含 Strict-Transport-Security 头

## 说明与偏差

- 原计划 P0-2 的「前端 localStorage 去重」暂缓：当前前端根本未调用点赞/收藏接口（NF-9 幽灵收藏），待前端接入真实计数时一并做。
- ArticlePage.vue 的 NF-2 与 NF-3 改动在同一文件，提交按主项归属拆分，NF-2 提交中包含该文件的 slug watch 改动。
- 集成测试新增 @BeforeEach 重置限流器，保证测试间隔离。
- 仓库工作区遗留若干仅行尾（CRLF）变化的未提交文件，为本轮之前的编辑器噪音，未纳入提交；建议后续 P2-7 时统一加 .gitattributes。
- 桌面 VM 对 .git 锁文件无删除权限，锁文件被移至 _to_delete/git-locks/，该目录可整体删除。

## 下一步

阶段 1 · 性能（P1-1~P1-8 + NB-1/NB-5/NF-5/NF-6/NF-7/NF-10）。其中 pg_trgm 扩展与 V15 迁移需要数据库操作权限（决策⑤已允许）。
