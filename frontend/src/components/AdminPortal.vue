<script setup>
import { computed, ref } from 'vue'
import { adminGroups, searchModules } from '../admin-catalog'
import { currentUser } from '../api'
const props = defineProps({ category: { type: String, default: '' } })
const emit = defineEmits(['open', 'category'])
const query = ref('')
const groups = computed(() => adminGroups(currentUser.value?.role))
const selected = computed(() => groups.value.find(group => group.id === props.category))
const searching = computed(() => !!query.value.trim())
const results = computed(() => searchModules(currentUser.value?.role, query.value))
const common = ['overview', 'students', 'applications', 'repairs', 'bills', 'accounts']
const cards = computed(() => searching.value ? results.value : selected.value?.modules || groups.value.flatMap(group => group.modules).filter(module => common.includes(module.key)))
function chooseCategory(id) { query.value = ''; emit('category', id) }
function reset() { query.value = ''; emit('category', '') }
</script>
<template>
  <section class="portal" aria-label="管理员功能入口">
    <div class="page-heading"><div><span class="eyebrow">ADMIN WORKSPACE</span><h1>今天，从这里开始</h1><p class="muted">按业务分类进入模块，也可以直接搜索要办理的事项。</p></div><span class="badge">{{ groups.reduce((sum, group) => sum + group.modules.length, 0) }} 个功能模块</span></div>
    <div class="portal-search card">
      <label for="module-search">查找功能模块</label>
      <div class="portal-search-row"><input id="module-search" v-model="query" type="search" maxlength="100" placeholder="搜索模块或事项，例如：派单、调宿、费用、AI" autocomplete="off" aria-describedby="search-help"><button v-if="query || category" @click="reset">重置</button></div>
      <p id="search-help" class="muted">搜索覆盖全部分类，支持模块名称及常用业务关键词。</p>
    </div>
    <div v-if="!searching && !selected" class="portal-categories" aria-label="业务分类">
      <button v-for="group in groups" :key="group.id" class="portal-category" :class="{ selected: category === group.id }" :aria-pressed="category === group.id" @click="chooseCategory(group.id)">
        <span class="portal-category-mark">{{ group.mark }}</span><span class="portal-category-copy"><strong>{{ group.title }}</strong><span>{{ group.description }}</span></span><span class="portal-count">{{ group.modules.length }} 项 ›</span>
      </button>
    </div>
    <div class="portal-section-heading"><h2>{{ searching ? '搜索结果' : selected?.title || '常用入口' }}</h2><span class="muted" aria-live="polite">{{ cards.length }} 个模块</span><button v-if="selected && !searching" class="text-button" @click="reset">返回全部分类</button></div>
    <div v-if="cards.length" class="portal-modules">
      <button v-for="module in cards" :key="module.key" class="portal-module" @click="emit('open', module.key)">
        <span class="portal-module-category">{{ module.category }}</span><strong>{{ module.title }} <span aria-hidden="true">↗</span></strong><span>{{ module.description }}</span><span class="portal-module-action">进入模块 →</span>
      </button>
    </div>
    <section v-else class="card padded portal-empty" role="status"><h2>没有找到相关模块</h2><p class="muted">试试“维修”“派单”“入住”“费用”或“账号”等关键词。</p><button @click="reset">返回分类入口</button></section>
  </section>
</template>
<style scoped>
.portal-search{padding:24px 28px}.portal-search label{font-weight:600;margin-bottom:10px}.portal-search-row{display:flex;gap:10px}.portal-search input{flex:1;padding:13px 16px;font-size:15px}.portal-search p{font-size:12px;margin:10px 0 0}.portal-categories{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:14px;margin-bottom:30px}.portal-category{display:flex;align-items:center;gap:14px;padding:20px;text-align:left;border-radius:11px;border:1px solid #e1e7f1;min-width:0}.portal-category.selected{border-color:#375bd2;background:#edf1ff}.portal-category-mark{display:grid;place-items:center;width:42px;height:42px;flex-shrink:0;background:#edf1ff;border-radius:10px;color:#375bd2;font-weight:650}.portal-category-copy{display:grid;gap:4px;min-width:0}.portal-category-copy strong{font-size:16px;color:#293c5a}.portal-category-copy>span{font-size:12px;color:#7f8da3}.portal-count{margin-left:auto;white-space:nowrap;color:#8a99b0;font-size:12px}.portal-section-heading{display:flex;align-items:center;gap:16px;margin-bottom:15px}.portal-section-heading h2{margin:0}.portal-section-heading>span{font-size:12px}.portal-section-heading button{margin-left:auto}.portal-modules{display:grid;grid-template-columns:repeat(3,minmax(0,1fr));gap:16px}.portal-module{display:flex;flex-direction:column;gap:12px;padding:23px;text-align:left;border-radius:11px;min-width:0}.portal-module strong{display:flex;justify-content:space-between;gap:10px;font-size:16px;color:#293c5a}.portal-module>span:not(.portal-module-action){font-size:12px;color:#7f8da3}.portal-module .portal-module-category{color:#5573c9!important;font-size:11px!important}.portal-module-action{font-size:12px;color:#375bd2;margin-top:auto;padding-top:8px}.portal-empty{text-align:center}.portal-module:hover{border-color:#a6b8ef;background:#f8faff}@media(max-width:1100px){.portal-modules{grid-template-columns:repeat(2,minmax(0,1fr))}}@media(max-width:720px){.portal-categories,.portal-modules{grid-template-columns:1fr}.portal-search{padding:20px}.portal-category{padding:16px}.portal-count{font-size:11px}}
</style>
