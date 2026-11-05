# 发布说明

## 构建

提交前运行后端测试和前端构建，推送后查看 GitHub Actions 的结果。命令见 [项目首页](../README.md#构建与验证)。使用新演示库检查启动步骤；页面有变化时更新 [截图](SHOWCASE.md)。

## 确认提交内容

在项目根目录检查待提交文件：

```powershell
git status --short
git diff --cached --stat
```

提交源码、文档和演示截图。`.env`、数据库、附件、备份、构建产物、缓存及日志留在本机；`.gitignore` 已列出对应规则，但对已经跟踪的文件不生效，误加入时需要从索引移除。

`artifacts/`、`artifacts-*.json` 和本机迁移记录 `docs/MYSQL-MIGRATION.md` 也不参与发布。展示页使用相对路径，提交时需包含 `docs/images/` 中的图片。

## 配置与数据

仓库只提供 [.env.example](../.env.example)，初始化密码由部署者设置。截图使用虚构数据，不附带真实学生资料。密钥如果曾经公开，先撤销并更换，再处理仓库记录。

最后核对文档链接和许可证。需要接受私密漏洞反馈时，在 GitHub 仓库设置中开启 Private vulnerability reporting，说明见 [安全反馈](../SECURITY.md)。
