# 统计助手

管理员登录后，进入“数据概览 → 管理员统计助手”。

可查询学生人数、年级分布、宿舍数、入住率、剩余房间容量、楼栋入住对比和报修统计。点击快捷问题即可查询；配置 DeepSeek 后，也可以输入“各栋宿舍分别住了多少人”这样的自然语言问题。

## 配置

在项目根目录 `.env` 增加以下内容并重启后端：

```properties
DEEPSEEK_API_KEY=你的真实密钥
DEEPSEEK_API_URL=https://api.deepseek.com/v1/chat/completions
DEEPSEEK_MODEL=deepseek-flash
```

密钥只由后端读取，不能放入 `VITE_*` 变量或提交到 Git。模型名可按自己的 DeepSeek 账号能力调整。
协议参考 [DeepSeek Chat Completions 官方文档](https://api-docs.deepseek.com/api/create-chat-completion/)。

可执行 `python scripts/check-deepseek.py` 检查配置及连通性。脚本仅向官方接口发送一个虚构问题，不读取数据库，也不输出密钥；成功时显示 `http_status: 200` 和 `intent_valid: true`。该检查会产生一次小额 API 调用。

## 查询方式

DeepSeek 仅接收用户本次提问和固定的查询类型说明，将问题映射为允许的查询类型。
后端使用固定 SQL 获取统计并格式化结果，不把数据库统计内容发送给模型，不执行模型生成的 SQL。
请勿主动在提问中填写个人信息。查询只读，仅管理员可用，受登录会话、CSRF 和每账号每分钟 10 次的限制保护。

未配置密钥、请求超时、供应商错误、返回空内容或非法类型时，显示本地模式和原因，使用关键词查询及全校统计概况。
页面提供数据时间、结果来源和统计依据。配置状态不代表供应商连接测试成功。

## 统计口径

入住率 = 房间登记入住人数 / 房间总容量；学生数包括全部在档学生。
剩余容量未扣除维修、停用和预留限制，不能直接当作可分配床位数。
当前不支持个人明细、时间趋势、日期区间和指定楼栋筛选；楼栋对比会展示全部楼栋。
多条统计查询读取期间数据可能变化，页面时间表示生成时间，不是数据库事务快照。

## 接口

`GET /api/ai/status` 查看配置状态；`POST /api/ai/chat` 提交 `{"message":"当前入住率是多少"}`。
返回 `reply`、`source`（`deepseek`/`local`）、`notice`、`generatedAt` 和 `statistics`。
