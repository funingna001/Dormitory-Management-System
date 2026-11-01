# 虚构演示数据

需要 Python 3。在首次启动演示库前执行：

```powershell
python scripts/create-demo.py
$env:ADMIN_INITIAL_PASSWORD = '请自行设置至少12字符的独立密码'
$env:SQLITE_DB_URL = 'jdbc:sqlite:./demo.db?busy_timeout=10000&foreign_keys=on'
powershell -ExecutionPolicy Bypass -File scripts/start-backend.ps1 -Database sqlite
```

脚本只创建根目录 `demo.db`，已存在则拒绝覆盖。示例使用虚构楼栋、宿舍、学生和报修，不包含真实电话号码或密码。
应用启动后创建管理员、业务表、床位和住宿历史。演示学生没有登录账号，可由管理员按业务流程开通。
推荐体验顺序：管理员概览 → 学生档案 → 楼栋宿舍 → 统计助手快捷提问。
截图请使用此演示库，不使用本机真实业务数据。
