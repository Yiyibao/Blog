# ADR-001：网站级 AI 多模态平台

- 状态：Accepted（Internal Alpha）
- 日期：2026-08-10
- 范围：M1 AI 多模态、真实记忆与生成物闭环

## 决策

1. 新 AI 核心位于 `com.yubai.blog.ai`，旧 `com.yubai.blog.admin.ai` 保持兼容并作为 provider/生图适配来源。
2. PostgreSQL 是 session、task、part、event、memory、file metadata 和 artifact metadata 的事实源；应用受控存储保存文件字节。
3. API 只接受不可猜测的 `fileId`、`taskId`、`memoryId`、`artifactId`，不接受本地路径、服务端路径或 `storageKey`。
4. 长期记忆由用户直接创建或确认后才进入 `ACTIVE`；模型建议一律先进入 `PROPOSED`。
5. provider 能力按 provider 类型显式声明，不依据模型名称猜测。附件在能力不足时请求前失败，不能静默降级为纯文本。
6. artifact 在返回给浏览器前转存到应用存储，并记录 owner、task、MIME、size、SHA-256、状态和有效期；下载始终重新鉴权。
7. 网站 AI 不读取或操控浏览器所在电脑的任意文件。浏览器只上传用户通过文件选择器主动选择的字节。
8. 生产 feature flag 默认关闭。M1 完成只代表 Internal Alpha 就绪，不代表获得生产开放或部署授权。

## 安全边界

- 允许：图片、PDF、DOCX、TXT、Markdown、CSV、JSON 的受限上传与解析；受控生成物下载。
- 拒绝：可执行文件、脚本、压缩包、宏文档、加密文档、路径输入、任意 URL 抓取、任意 shell/SQL/文件系统工具。
- 所有读取、删除、下载、任务回放和记忆操作都按 JWT subject 重新校验 owner。
- 上传正文永远视为不可信数据，不得改变系统策略、能力矩阵或权限。

## 兼容与回滚

- V46/V47 及旧聊天/生图 API 不修改；V53 仅新增表、索引和缺失外键。
- 关闭 `APP_AI_PLATFORM_*` 后，新 API 返回不可用，旧文本聊天继续工作。
- 代码回滚不删除 V53 表；后续版本可继续读取或清理数据，避免破坏性数据库回滚。
