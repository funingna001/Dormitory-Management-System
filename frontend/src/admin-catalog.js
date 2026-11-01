import { navigationFor } from './permissions.js'

// 分类只组织入口，不授予权限；模块仍从 navigationFor 的角色白名单中筛选。
const categories = [
  { id: 'insights', title: '数据与查询', description: '住宿概览、AI 查询与数据核对', mark: '01', pages: ['overview', 'consistency'] },
  { id: 'housing', title: '宿舍与床位', description: '楼栋、房间、床位和宿舍长', mark: '02', pages: ['buildings', 'dormitories', 'beds', 'rooms', 'leaders'] },
  { id: 'students', title: '学生与入住', description: '学生档案、住宿申请与新生分配', mark: '03', pages: ['students', 'archives', 'applications', 'history', 'batch'] },
  { id: 'services', title: '校园服务', description: '维修、检查、留宿、访客与通知', mark: '04', pages: ['repairs', 'inspections', 'stays', 'visitors', 'announcements', 'notifications'] },
  { id: 'finance', title: '费用与物品', description: '费用账单、资产和领用交接', mark: '05', pages: ['bills', 'assets', 'handovers'] },
  { id: 'system', title: '系统与权限', description: '账号、楼栋权限、规则和审计', mark: '06', pages: ['accounts', 'settings', 'audit'] },
]
// 每项依次为卡片说明与搜索别名；新增模块时同步补充分类和别名。
const details = {
  overview: ['查看住宿统计，使用 DeepSeek 自然语言查询', 'AI 人工智能 统计 分析 看板 导出 deepseek'],
  consistency: ['核对住宿人数、床位和历史记录异常', '一致性 校验 异常'],
  buildings: ['维护楼栋、楼层和房间布局', '楼宇 建楼'],
  dormitories: ['查询宿舍房型、容量和入住情况', '房间 房号 空房 入住率'],
  beds: ['安排床位入住、预留及状态调整', '分配 床号 空床'],
  rooms: ['调整房间的停用、关闭及维修状态', '禁用 房间状态'],
  leaders: ['任命宿舍长并办理交接', '寝室长 任免'],
  students: ['管理学生基本资料与住宿安排', '学号 电话 姓名 查询'],
  archives: ['维护学籍、学院专业和归档信息', '毕业 休学 退学 辅导员'],
  applications: ['审批并办理入住、调宿及退宿申请', '审批 搬宿舍 换宿舍'],
  history: ['查看入住与退宿历史及经办记录', '住宿记录 入住记录'],
  batch: ['导入新生名单并预览批量床位分配', 'Excel XLSX 上传 批量 入学'],
  repairs: ['查看待派单工单、自动优先级和维修进度', '报修 派单 改派 维修 紧急 验收 工单'],
  inspections: ['登记卫生安全检查并复查整改', '评分 卫生 安全'],
  stays: ['审核留宿外宿申请并登记返校', '晚归 假期'],
  visitors: ['登记访客到访及离开', '来访 客人'],
  announcements: ['发布全校或楼栋公告', '通知 发布 消息'],
  notifications: ['查看个人消息与业务待办', '消息 通知 提醒 派单'],
  bills: ['生成账单并登记已核实的缴费与退款', '费用 欠费 付款 水费 电费 住宿费 收费'],
  assets: ['维护宿舍家具设备与钥匙', '资产 钥匙 家具 财产'],
  handovers: ['登记物品领用、归还及损坏情况', '归还 交接 丢失'],
  accounts: ['创建工作人员账号并设置负责楼栋', '用户 密码 重置 宿管 维修人员 权限'],
  settings: ['设置报修资格与退宿结清条件', '设置 配置 业务规则'],
  audit: ['查看业务操作人与变更记录', '日志 审计 操作历史'],
}
export function adminGroups(role) {
  if (role !== 'admin') return []
  const allowed = new Map(navigationFor(role))
  return categories.map(group => ({ ...group, modules: group.pages.filter(key => allowed.has(key)).map(key => ({
    key, title: allowed.get(key), description: details[key]?.[0] || '', keywords: details[key]?.[1] || '', category: group.title, categoryId: group.id,
  })) })).filter(group => group.modules.length)
}
export function searchModules(role, query) {
  // 全角字符统一后忽略大小写；多个空格分隔词须同时匹配，搜索范围覆盖全部分类。
  const terms = query.normalize('NFKC').toLocaleLowerCase().trim().split(/\s+/).filter(Boolean)
  if (!terms.length) return []
  return adminGroups(role).flatMap(group => group.modules).filter(module => {
    const text = `${module.title} ${module.description} ${module.keywords} ${module.category}`.normalize('NFKC').toLocaleLowerCase()
    return terms.every(term => text.includes(term))
  })
}
