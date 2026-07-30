# 2026-07-31 项目审查、性能优化与部署记录

## 审查结论

- 修复 `V38__rename_blog_quote_author.sql` 使用错误表名导致全新数据库迁移失败的问题；目标表由不存在的 `quotes` 修正为 `sys_quote`。
- 路由组件统一改为 Vue Router 原生动态导入，消除 `defineAsyncComponent` 用作路由组件的运行时警告。
- 全站搜索和后台 AI 停靠栏改为真正按需加载，未打开功能时不下载对应组件。
- 文章 HTML、Markdown 正文、后台菜品列表及附件列表中的非首屏图片统一添加原生 `loading="lazy"` 与 `decoding="async"`。
- 首页背景从固定 public URL 改为 Vite 内容哈希资源，并以高优先级图片元素加载，避免 Service Worker 长缓存导致换图后仍显示旧图。
- 首页首屏以下区块启用 `content-visibility: auto`，减少初始布局与绘制开销。
- nginx 启用 HTTP/2、gzip、哈希资源一年 immutable 缓存、普通图片七天缓存，并要求 HTML 与 Service Worker 每次重新验证。
- Caffeine 缓存启用统计采集，使 Micrometer 能正常记录命中、未命中和驱逐指标。
- 通过 npm overrides 将受安全公告影响的 `brace-expansion` 统一提升至 5.0.9；生产与完整依赖审计均为 0 漏洞。

## 验证结果

- 前端 Vitest：49 个测试文件、499 项测试全部通过。
- 前端 TypeScript：`vue-tsc --noEmit` 通过。
- 前端生产构建、PWA 生成、8 条路由预渲染通过。
- 后端 Maven：658 项测试全部通过。
- npm audit（含开发依赖）：0 vulnerabilities。
- 浏览器验收：背景图 1671×941 完整加载，资源文件名带内容哈希；标题无横向溢出；搜索按需加载后可正常打开。
- `git diff --check`：通过。

## 部署记录

- 部署前生产备份：`Result=success`、`ExecMainStatus=0`。
- 首次发布版本：`release-20260731-1c8c79c`，由上一版 `release-20260731-515b107` 原子切换。
- Flyway：成功从 v37 升级至 v38。
- 应用：`/actuator/health` 返回 `UP`；知识图谱公开接口返回 200。
- nginx：配置校验通过并 reload；服务器本机验证 HTTP/2、gzip、HTML/SW `no-cache`、哈希资源一年 immutable 缓存。
- 公网复核：因备案期间公网 443 已关闭，从开发机访问 `https://hxnf.top` 失败；服务器本机 HTTPS 验证正常。
