const {chromium}=require(process.env.PLAYWRIGHT_MODULE||'../../artifacts/browser/node_modules/playwright')
const assert=require('node:assert/strict')
;(async()=>{
 const browser=await chromium.launch({executablePath:process.env.CHROME_PATH||'C:/Program Files/Google/Chrome/Application/chrome.exe',headless:true})
 try{
  const page=await browser.newPage();let expired=false,token='first',logins=0,csrfRequests=0,breakAfterLogin=false,failNextCsrf=false
  await page.route('**/api/**',async route=>{
   const path=new URL(route.request().url()).pathname
   if(path==='/api/auth/captcha')return route.fulfill({status:200,contentType:'image/png',body:Buffer.from('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+j3c4AAAAASUVORK5CYII=','base64')})
   let status=200,json={}
   if(path==='/api/auth/csrf'){csrfRequests++;if(failNextCsrf){failNextCsrf=false;status=503;json={message:'simulated outage'}}else json={headerName:'X-CSRF-TOKEN',token}}
   else if(path==='/api/auth/me'){if(expired){status=401;json={message:'请先登录'}}else json={user:{username:'admin',role:'admin'},mustChangePassword:false}}
   else if(path==='/api/auth/logout'&&expired){status=403;json={message:'安全令牌已失效'}}
   else if(path==='/api/data/statistics'){
    if(expired){status=401;json={message:'请先登录'}}else json={totalStudents:1,totalDormitories:1,buildingStats:{},gradeStats:{},genderStats:{}}
   }else if(path==='/api/auth/login'){
    assert.equal(route.request().headers()['x-csrf-token'],token);logins++;expired=false;token='after-login';failNextCsrf=breakAfterLogin;json={user:{username:'admin',role:'admin'},mustChangePassword:false}
   }
   await route.fulfill({status,contentType:'application/json',body:JSON.stringify(json)})
  })
  await page.goto('http://127.0.0.1:5176');await page.getByRole('heading',{name:'住宿数据概览'}).waitFor()
  expired=true;token='new-session';await page.getByRole('button',{name:'刷新数据',exact:true}).click();await page.getByRole('heading',{name:'欢迎登录'}).waitFor()
  assert.equal(await page.getByRole('button',{name:'学生自助注册',exact:true}).count(),0)
  await page.getByPlaceholder('请输入账号').fill('admin');await page.getByPlaceholder('请输入密码').fill('Password123!');await page.getByPlaceholder('四位验证码').fill('1234');await page.getByRole('button',{name:'登录系统',exact:true}).click();await page.getByRole('heading',{name:'住宿数据概览'}).waitFor()
  assert.equal(logins,1);assert(csrfRequests>=3)
  expired=true;token='third-session';await page.getByRole('button',{name:'退出登录',exact:true}).click();await page.getByRole('heading',{name:'欢迎登录'}).waitFor()
  breakAfterLogin=true
  await page.getByPlaceholder('请输入账号').fill('admin');await page.getByPlaceholder('请输入密码').fill('Password123!');await page.getByPlaceholder('四位验证码').fill('1234');await page.getByRole('button',{name:'登录系统',exact:true}).click()
  await page.getByText('登录已完成，状态同步暂时失败，请重新同步。',{exact:true}).waitFor()
  assert(await page.getByRole('button',{name:'登录系统',exact:true}).isDisabled());assert.equal(logins,2)
  await page.getByRole('button',{name:'重新同步登录状态',exact:true}).click();await page.getByRole('heading',{name:'住宿数据概览'}).waitFor()
  assert.equal(logins,2)
  console.log('PASS: session expiry recovery and post-login CSRF outage resynchronization; login POST is not replayed')
 }finally{await browser.close()}
})().catch(error=>{console.error(error);process.exitCode=1})
