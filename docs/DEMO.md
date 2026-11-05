# 演示数据

需要 Python 3。在项目根目录执行下面的命令，创建 `demo.db` 并以 SQLite 模式启动后端：

```powershell
python scripts/create-demo.py
$env:ADMIN_INITIAL_PASSWORD = '请自行设置至少12字符的独立密码'
$env:SQLITE_DB_URL = 'jdbc:sqlite:./demo.db?busy_timeout=10000&foreign_keys=on'
powershell -ExecutionPolicy Bypass -File scripts/start-backend.ps1 -Database sqlite
```

前端启动和登录方法见 [项目首页](../README.md#快速体验独立-sqlite-演示库)。

脚本包含两栋楼、三间宿舍、三名学生和一条报修，均为虚构数据。`demo.db` 已存在时会拒绝覆盖。

首次启动后端时会建好业务表、床位和住宿历史，并创建管理员账号。演示学生只有档案，没有登录账号；需要体验学生端时，由管理员在“学籍与住宿档案”中开通。

可以先看住宿概览、学生管理和宿舍管理，再试试统计助手。截图请使用演示库，不要使用真实学生资料。
