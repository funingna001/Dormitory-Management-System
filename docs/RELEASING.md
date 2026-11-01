# GitHub 发布检查

1. 执行后端测试及前端构建，确认 Actions 通过。
2. 使用新演示库验证 README，截图只使用虚构数据。
3. 通过 Git 提交源码，不要将整个本地工作目录拖到 GitHub。
4. 查看 `git status --short` 和 `git diff --cached --stat`，禁止提交 `.env`、`*.db`、`backups/`、`uploads/`、构建产物、缓存及日志。
5. 本机审计产物 `artifacts-*.json` 和本机迁移记录 `docs/MYSQL-MIGRATION.md` 不参与发布。
6. 已跟踪文件不受新增忽略规则影响，须从索引移除。若密钥曾公开，先撤销并更换，再处理 Git 历史。
7. 核对 LICENSE、仓库简介及文档链接，可在 GitHub 启用私密漏洞报告。

不附带真实业务数据、管理员固定密码或 API Key。部署者自行设置初始化密码。
