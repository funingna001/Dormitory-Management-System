import { modules } from './business-modules.js'

const basePages = {
  admin: [['home', '功能首页'], ['overview', '数据概览'], ['dormitories', '宿舍管理'], ['students', '学生管理'], ['buildings', '楼栋管理']],
  student: [['profile', '我的宿舍']],
}
const studentViews = {
  beds: ['床位查询', '查看本宿舍及可申请的同性别空床位，不提供分配或修改操作。'],
  applications: ['我的住宿申请', '仅查看和提交本人的入住、调宿或退宿申请。'],
  history: ['我的住宿历史', '仅展示本人的住宿记录。'],
  leaders: ['本宿舍宿舍长', '查看本宿舍宿舍长信息；任命和交接由管理员或宿管办理。'],
  announcements: ['通知公告', '查看全校及本人所在楼栋的公告。'],
  notifications: ['我的消息', '仅展示发送给本人的消息。'],
  stays: ['我的留宿与外宿', '提交本人的留宿、外宿、晚归登记并查看进度。'],
  visitors: ['我的访客记录', '仅查看以本人为被访人的记录；登记由宿管办理。'],
  inspections: ['本宿舍检查与整改', '查看本宿舍检查结果并提交整改。'],
  bills: ['我的费用账单', '仅查看本人的账单及缴费记录，不提供收费、退款或账单管理操作。'],
  assets: ['本宿舍物品', '查看本宿舍物品，不提供新增、分配或修改操作。'],
  handovers: ['我的物品领用', '仅查看本人的物品领用与归还记录。'],
  repairs: ['宿舍报修', '提交后先等待管理员或宿管派单，再由维修人员处理，最后由报修人或本宿舍宿舍长验收。'],
}
export function moduleForRole(kind, role) {
  const config = modules[kind]
  if (!config?.roles.includes(role)) return null
  const view = role === 'student' ? studentViews[kind] : null
  if (kind === 'repairs' && role === 'worker') return { ...config, title: '我的维修工单', description: '仅展示已派给本人的工单。学生提交后须由管理员或宿管派单；空列表也可能是尚未派单，请勿重复报修。' }
  return view ? { ...config, title: view[0], description: view[1] } : config
}
export function navigationFor(role) {
  return [...(basePages[role] || []), ...Object.keys(modules).flatMap(kind => {
    const config = moduleForRole(kind, role)
    return config ? [[kind, config.title]] : []
  })]
}
export function canAccessPage(page, role) {
  // 菜单和页面守卫复用同一白名单；接口访问及数据范围仍由后端独立校验。
  return navigationFor(role).some(([key]) => key === page)
}
