<script setup>
import { computed, onMounted, ref, watch } from 'vue'
import { api, currentUser, mustChangePassword, refreshCsrf } from './api'
import { canAccessPage, navigationFor } from './permissions'
import { adminGroups } from './admin-catalog'
import AdminPortal from './components/AdminPortal.vue'
import BusinessCenter from './components/BusinessCenter.vue'
import PasswordPanel from './components/PasswordPanel.vue'
import Login from './components/Login.vue'
import Management from './components/Management.vue'
import Buildings from './components/Buildings.vue'
import Dashboard from './components/Dashboard.vue'
import Student from './components/Student.vue'
const ready = ref(false), error = ref(''), active = ref('home'), selectedCategory = ref(''), portalEpoch = ref(0)
const links = computed(() => navigationFor(currentUser.value?.role))
const groups = computed(() => adminGroups(currentUser.value?.role))
const activeCategory = computed(() => active.value === 'home' ? selectedCategory.value : groups.value.find(group => group.modules.some(module => module.key === active.value))?.id)
const pageAllowed = computed(() => canAccessPage(active.value, currentUser.value?.role))
// 回首页或从侧栏切换分类时重建入口组件，清除上一次搜索，避免搜索结果遮住所选分类。
function selectDefault() { selectedCategory.value = ''; portalEpoch.value++; active.value = links.value[0]?.[0] || 'password' }
function openModule(key) { if (!mustChangePassword.value && canAccessPage(key, currentUser.value?.role)) active.value = key }
function openCategory(id) { if (!mustChangePassword.value && groups.value.some(group => group.id === id)) { selectedCategory.value = id; portalEpoch.value++; active.value = 'home' } }
watch(() => [currentUser.value?.username, currentUser.value?.role], selectDefault, { flush: 'sync' })
async function load() {
  try {
    await refreshCsrf()
    const result=await api('/auth/me'); currentUser.value=result.user; mustChangePassword.value=!!result.mustChangePassword
    selectDefault()
  } catch (e) { if (!e.message.includes('请先登录')) error.value = e.message }
  finally { ready.value = true }
}
async function logout() {
  try { await api('/auth/logout', { method:'POST' }); currentUser.value = null; await refreshCsrf() }
  catch (e) { error.value = e.message }
}
onMounted(load)
</script>
<template>
  <main v-if="!ready" class="loading">正在连接宿舍管理系统…</main>
  <Login v-else-if="!currentUser" @logged-in="selectDefault" />
  <div v-else class="shell">
    <aside class="sidebar"><a class="brand" href="#" @click.prevent="selectDefault"><span class="brand-mark">D</span><span>宿舍管理<small>DORMITORY SYSTEM</small></span></a>
      <div class="nav-label">{{currentUser.role==='admin'?'业务分类':'工作空间'}}</div><nav aria-label="工作空间导航">
        <template v-if="currentUser.role==='admin'">
          <button :class="{active:active==='home'&&!selectedCategory}" :disabled="mustChangePassword" @click="selectDefault">功能首页<span>⌂</span></button>
          <button v-for="group in groups" :key="group.id" :class="{active:activeCategory===group.id}" :disabled="mustChangePassword" @click="openCategory(group.id)">{{group.title}}<span>{{group.modules.length}}</span></button>
        </template>
        <template v-else><button v-for="[key,label] in links" :key="key" :class="{ active:active===key }" :disabled="mustChangePassword" @click="openModule(key)">{{ label }}<span>›</span></button></template>
      </nav>
      <div class="sidebar-footer"><span class="online-dot"></span>校园住宿服务平台</div>
    </aside>
    <div class="workspace"><header class="topbar"><div class="workspace-location"><button v-if="currentUser.role==='admin'" class="text-button" :disabled="mustChangePassword" @click="selectDefault">功能首页</button><span>{{currentUser.role==='admin'?' / ':'校园管理 / '}}{{mustChangePassword?'修改密码':active==='home'?(groups.find(group=>group.id===selectedCategory)?.title||'全部分类'):links.find(x=>x[0]===active)?.[1]||'修改密码'}}</span></div><div class="user-info"><span>{{ currentUser.realName || currentUser.username }}</span><span class="badge">{{ {admin:'管理员',manager:'楼栋宿管',counselor:'辅导员',student:'学生',worker:'维修人员'}[currentUser.role] }}</span><button class="text-button" @click="active='password'">修改密码</button><button class="text-button" @click="logout">退出登录</button></div></header>
      <main :key="currentUser.username + ':' + currentUser.role" class="content"><div v-if="error" class="alert" role="alert">{{ error }}<button @click="error=''">关闭</button></div>
        <PasswordPanel v-if="mustChangePassword||active==='password'" :required="mustChangePassword" @changed="mustChangePassword=false;selectDefault()" />
        <div v-else-if="!pageAllowed" class="alert" role="alert">无权查看此页面。<button @click="selectDefault">返回我的工作空间</button></div>
        <AdminPortal v-else-if="active==='home'" :key="portalEpoch" :category="selectedCategory" @category="selectedCategory=$event" @open="openModule" />
        <Dashboard v-else-if="active==='overview'" />
        <Management v-else-if="active==='students'||active==='dormitories'" :key="active" :kind="active" />
        <Buildings v-else-if="active==='buildings'" />
        <Student v-else-if="active==='profile'" />
        <BusinessCenter v-else :key="active" :kind="active" />
      </main>
    </div>
  </div>
</template>
<style scoped>
.workspace-location{display:flex;align-items:center;gap:5px;min-width:0}.workspace-location>span{overflow:hidden;text-overflow:ellipsis;white-space:nowrap}.workspace-location>button{flex-shrink:0}.topbar{gap:16px}.user-info{flex-shrink:0}@media(max-width:720px){.workspace-location{display:none}}
</style>
