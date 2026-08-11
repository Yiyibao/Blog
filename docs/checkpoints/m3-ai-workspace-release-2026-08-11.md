# M3 AI 统一工作台发布记录

日期：2026-08-11
范围：AI 统一对话、多模态输入、项目/会话、记忆、工具调用与产物生成工作台

## 本次交付

- 将管理端 AI 页面重构为图二所示的两栏工作台：项目与会话侧栏、连续对话主栏、供应商/模型/推理强度控制、附件菜单和底部输入区。
- 支持消息内文件卡片、封面图、PDF/DOCX/XLSX 产物卡片及受控下载。
- 后端新增项目、会话连续消息、provider/model capability 路由、Responses 多模态输入、工具调用、记忆和文档产物闭环。
- 新增 Flyway V54；OpenAPI 重新导出并同步前端生成 API 类型。
- `/admin/ai-preview` 仅在开发环境且显式设置 `VITE_AI_WORKSPACE_PREVIEW=true` 时可用，仅用于视觉验收，不调用真实供应商。

## 验收记录

- 前端测试：68 个测试文件、814 个测试通过。
- 前端 typecheck、lint、format check 通过。
- 前端生产构建通过：42 个 JS 文件，1,458,605 bytes；仅有 code-highlight chunk 超过 Vite 默认提示阈值的非失败警告。
- 后端 `mvn verify` 通过：760 个测试，Failures/Errors/Skipped 均为 0；Spotless 通过。
- Flyway V54 在集成验证中成功校验/应用。
- 浏览器视觉验收覆盖 1568×1000 设计稿尺寸和 390×844 移动尺寸；移动尺寸无横向溢出，并验证溢出菜单、记忆抽屉、上传菜单和消息输入区。
- 本地服务器健康检查返回 200；未认证的 provider 管理接口返回 401，符合权限边界。

## 生产发布约定

- 生产部署事实来源：`.github/workflows/deploy.yml`、`deploy/yubai-blog.service`、`deploy/hxnf.top.nginx`。
- 正常流程：构建前端 `dist` 与后端 canonical jar → 上传至 `/opt/yubai-blog/releases/<release>` → 原子切换 `current` → 重启 `yubai-blog` → 检查 `/actuator/health` → 失败自动回滚。
- 发布触发：推送 `v*` 标签或手动触发 workflow；production Environment 审批门必须保留。
- 部署所需的 `DEPLOY_HOST`、`DEPLOY_USER`、`DEPLOY_SSH_KEY`、`DEPLOY_PATH` 仅从 GitHub Secrets 读取；服务器 AI 密钥仍只保留在 `/etc/yubai-blog/app.env`，本记录不保存任何凭据值。
- 生产发布完成后，应使用已授权的管理账号在隔离测试数据上补做真实供应商 B1（Responses）、B2（文件）、B3（生图）、B4（产物生成/下载）验证；日志不得记录密钥、文件原文或完整响应正文。

## 当前状态

代码、文档和测试已准备进入发布流程；真实供应商调用是否可用取决于生产服务器现有 `app.env` 的开关、密钥、模型和上游网络状态，不能由开发环境视觉夹具代替。
