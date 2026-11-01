// Run with the Vite app on UI_TEST_URL (default :5176). API responses are isolated fixtures.
const {chromium}=require(process.env.PLAYWRIGHT_MODULE||'../../artifacts/browser/node_modules/playwright')
const assert=require('node:assert/strict')
const fs=require('node:fs')
const completed=[]
async function session(browser,role){
 const context=await browser.newContext({viewport:{width:1440,height:1000}}),calls=[],errors=[]
 await context.route('**/api/**',async route=>{
  const url=new URL(route.request().url()),path=url.pathname
  calls.push({path,query:Object.fromEntries(url.searchParams),method:route.request().method(),body:route.request().postDataJSON?.()})
  let json={}
  if(path==='/api/auth/csrf')json={headerName:'X-CSRF-TOKEN',token:'test'}
  else if(path==='/api/auth/me')json={user:{username:role,role},mustChangePassword:false}
  else if(path==='/api/data/statistics')json={totalStudents:1,totalDormitories:1,gradeStats:[],genderStats:[],buildingStats:[],roomTypeStats:[]}
  else if(path==='/api/me')json={student:null,dormitory:null}
  else if(path.startsWith('/api/business/options/')){
   const source=path.split('/').pop(),page=Number(url.searchParams.get('page')||1),search=url.searchParams.get('search')||'',ids=url.searchParams.get('ids')?.split(',')
   const row=id=>source==='students'?{id,name:`测试学生${id}`,student_id:`2026${String(id).padStart(4,'0')}`,lifecycle:'ENROLLED',dormitory_number:null}:source==='buildings'?{id,name:`${id}号楼`,gender:'男'}:source==='beds'?{id,dormitory_number:'1号楼-0101',bed_number:id,state:'AVAILABLE'}:source==='workers'?{username:'worker',real_name:'维修员'}:{id,dormitory_number:`1号楼-${id}`}
   const total=source==='students'?60:2
   const items=ids?ids.map(id=>row(Number(id))):search?[row(search==='new'?59:58)]:Array.from({length:Math.min(50,Math.max(0,total-(page-1)*50))},(_,i)=>row((page-1)*50+i+1))
   json={items,total:ids?ids.length:search?1:total,page,size:50}
   if(search==='old')await new Promise(resolve=>setTimeout(resolve,650))
  }
  else if(path.startsWith('/api/business/lists/')){
   const kind=path.split('/').pop(),page=Number(url.searchParams.get('page')||1),search=url.searchParams.get('search')
   const items=kind==='repairs'?[{id:1,status:'待处理',description:'测试报修',assignee:null}]:kind==='beds'?[{id:60,dormitory_number:'1号楼-0101',bed_number:60,state:'AVAILABLE',room_state:'ACTIVE'}]:kind==='notifications'?Array.from({length:page===3?5:20},(_,i)=>({id:(page-1)*20+i+1,title:`消息${(page-1)*20+i+1}`,is_read:0})):[]
   json={items:search?[{id:99,title:search}]:items,total:search?1:kind==='notifications'?45:items.length,page,size:20}
  }
  else if(route.request().method()!=='GET')json={success:true,id:1,ids:[1]}
  await route.fulfill({status:200,contentType:'application/json',body:JSON.stringify(json)})
 })
 const page=await context.newPage();page.on('pageerror',error=>errors.push(error.message));await page.goto(process.env.UI_TEST_URL||'http://127.0.0.1:5176')
 async function nav(label){await page.locator('nav button').filter({hasText:label}).click();await page.getByRole('heading',{name:label,exact:true}).waitFor();await page.waitForFunction(()=>!document.querySelector('.page-heading button')?.disabled)}
 return{context,page,calls,errors,nav}
}
;(async()=>{
 const browser=await chromium.launch({executablePath:process.env.CHROME_PATH||'C:/Program Files/Google/Chrome/Application/chrome.exe',headless:true})
 try{
  const admin=await session(browser,'admin'),{page,calls,nav}=admin
  for(const label of ['住宿数据核对','床位管理','入住 / 调宿 / 退宿','住宿历史','房间停用与维修','学籍与住宿档案','宿舍长任命与交接','账号与楼栋权限','通知公告','消息与待办','留宿 / 外宿 / 晚归','访客登记','卫生与安全检查','住宿费用台账','家具设备与钥匙','领用与退宿交接','业务操作记录','住宿业务规则','报修工单'])await nav(label)
  assert.equal(calls.filter(c=>c.path.startsWith('/api/business/options')).length,0)
  completed.push('All lists and the consistency report load without full option downloads')
  await nav('住宿费用台账');await page.getByLabel('筛选状态').selectOption('VOID');await page.getByLabel('筛选楼栋').fill('1号楼');await page.getByLabel('筛选楼栋').dispatchEvent('change');await page.getByLabel('筛选开始日期').fill('2026-10-01');await page.getByLabel('筛选结束日期').fill('2026-10-31')
  await page.waitForTimeout(450)
  const filtered=calls.filter(c=>c.path.endsWith('/lists/bills')).at(-1).query
  assert.equal(filtered.state,'VOID');assert.equal(filtered.building,'1号楼');assert.equal(filtered.from,'2026-10-01');assert.equal(filtered.to,'2026-10-31')
  await page.getByRole('button',{name:'清除筛选',exact:true}).click();await page.waitForTimeout(450)
  assert.equal(calls.filter(c=>c.path.endsWith('/lists/bills')).at(-1).query.state,'')
  completed.push('Exact state, building and date filters reach the server and can be cleared')
  await page.locator('.page-heading button.primary').click();const dialog=page.getByRole('dialog');await dialog.waitFor()
  await dialog.getByLabel('学生',{exact:true}).locator('option[value="1"]').waitFor({state:'attached'})
  assert.equal(await dialog.getByLabel('学生',{exact:true}).locator('option').count(),51)
  await dialog.getByLabel('学生',{exact:true}).selectOption('1')
  await dialog.getByRole('button',{name:'学生下一页',exact:true}).click()
  await dialog.getByLabel('学生',{exact:true}).locator('option[value="60"]').waitFor({state:'attached'})
  assert.equal(await dialog.getByLabel('学生',{exact:true}).inputValue(),'1')
  assert.equal(await dialog.getByLabel('学生',{exact:true}).locator('option').count(),12)
  completed.push('Remote choices are paged and preserve the selected student across pages')
  await dialog.getByLabel('搜索学生',{exact:true}).fill('old');await page.waitForTimeout(350);await dialog.getByLabel('搜索学生',{exact:true}).fill('new');await dialog.getByLabel('学生',{exact:true}).locator('option[value="59"]').waitFor({state:'attached'});await page.waitForTimeout(750)
  assert.equal(await dialog.getByLabel('学生',{exact:true}).locator('option[value="58"]').count(),0)
  completed.push('Delayed option search responses cannot overwrite newer results')
  await dialog.getByLabel('学生',{exact:true}).selectOption('59');await dialog.getByLabel('费用类型').selectOption('WATER');await dialog.getByLabel('所属期间').fill('2026-10');await dialog.getByLabel('总金额（元）').fill('10.00');await dialog.getByLabel('缴费期限').fill('2026-12-01');await dialog.getByLabel('收费项目说明').fill('选择框回归测试');await dialog.getByRole('button',{name:'确认提交',exact:true}).click();await dialog.waitFor({state:'hidden'})
  assert.equal(calls.filter(c=>c.path==='/api/business/bills'&&c.method==='POST').at(-1).body.studentId,59)
  completed.push('Remote numeric selections produce the correct form payload')
  await nav('床位管理');await page.getByRole('button',{name:'安排入住',exact:true}).click();await dialog.getByLabel('目标床位',{exact:true}).locator('option[value="60"]').waitFor({state:'attached'});assert.equal(await dialog.getByLabel('目标床位',{exact:true}).inputValue(),'60');await dialog.getByRole('button',{name:'关闭',exact:true}).click()
  assert(calls.some(c=>c.path==='/api/business/options/beds'&&c.query.ids==='60'))
  completed.push('Default selections outside the first option page are fetched by ID')
  await nav('新生导入与批量分配');const students=page.getByLabel('选择未入住学生',{exact:true});await students.locator('option[value="1"]').waitFor({state:'attached'});await students.selectOption(['1','2']);await page.getByRole('button',{name:'选择未入住学生下一页',exact:true}).click();await students.locator('option[value="60"]').waitFor({state:'attached'});await students.selectOption(['1','2','60']);assert.deepEqual(await students.evaluate(el=>[...el.selectedOptions].map(o=>o.value)),['1','2','60'])
  assert(calls.filter(c=>c.path==='/api/business/options/students'&&c.query.unhoused==='true').length>=2)
  completed.push('Batch multi-select keeps selections across pages and requests only unhoused students')
  await nav('消息与待办');await page.getByRole('button',{name:'下一页',exact:true}).click();await page.waitForFunction(()=>!document.querySelector('.page-heading button')?.disabled);await page.getByText('2 / 3',{exact:true}).waitFor();await page.getByRole('button',{name:'下一页',exact:true}).click();await page.waitForFunction(()=>!document.querySelector('.page-heading button')?.disabled);assert.equal(await page.locator('tbody tr').count(),5)
  completed.push('Business list pagination still respects the server total')
  const student=await session(browser,'student');await student.nav('入住 / 调宿 / 退宿');await student.page.locator('.page-heading button.primary').click();await student.page.getByLabel('目标床位',{exact:true}).locator('option[value="1"]').waitFor({state:'attached'});assert.deepEqual([...new Set(student.calls.filter(c=>c.path.startsWith('/api/business/options/')).map(c=>c.path.split('/').pop()))],['beds'])
  const manager=await session(browser,'manager');await manager.nav('报修工单');await manager.page.getByRole('button',{name:'派单 / 改派',exact:true}).click();await manager.page.getByLabel('维修人员',{exact:true}).locator('option[value="worker"]').waitFor({state:'attached'});assert.deepEqual([...new Set(manager.calls.filter(c=>c.path.startsWith('/api/business/options/')).map(c=>c.path.split('/').pop()))],['workers'])
  completed.push('Student applications and manager dispatch load only their required option source')
  assert.deepEqual([...admin.errors,...student.errors,...manager.errors],[])
  const result={success:true,completed};fs.writeFileSync('artifacts/all-optimizations-browser-result.json',JSON.stringify(result,null,2));console.log(JSON.stringify(result,null,2))
 }finally{await browser.close()}
})().catch(error=>{console.error(error);process.exitCode=1})
