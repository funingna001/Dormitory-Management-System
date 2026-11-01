<script setup>
import { onMounted, reactive, ref } from 'vue'
import { api, download } from '../api'
const items=ref([]),error=ref(''),busy=ref(false),editing=ref(false),editId=ref(null)
const form=reactive({name:'',gender:'男',sortMode:'asc',floors:[]})
async function load() { try { items.value=await api('/buildings') } catch(e) { error.value=e.message } }
async function open(item) {
  error.value=''; editId.value=item?.id??null
  Object.assign(form,{name:item?.name||'',gender:item?.gender||'男',sortMode:'asc',floors:[{doubleRooms:0,fourRooms:5,sixRooms:5}]})
  if(item) {
    try { const data=await api('/buildings/'+item.id); const configs=[]
      for(const room of data.dormitories) { const floor=Number(room.dormitoryNumber.slice(item.name.length+1,item.name.length+3)); if(!Number.isInteger(floor)||floor<1||floor>30)continue; while(configs.length<floor)configs.push({doubleRooms:0,fourRooms:0,sixRooms:0}); const key={2:'doubleRooms',4:'fourRooms',6:'sixRooms'}[room.capacity]; if(key)configs[floor-1][key]++ }
      if(configs.length)form.floors=configs
      form.sortMode=data.dormitories.find(d=>[4,6].includes(d.capacity))?.capacity===6?'desc':'asc'
    } catch(e) { error.value=e.message; return }
  }
  editing.value=true
}
async function save() { busy.value=true;error.value=''; try { await api('/buildings'+(editId.value?'/'+editId.value:''),{method:editId.value?'PUT':'POST',body:form});editing.value=false;await load() } catch(e) {error.value=e.message} finally{busy.value=false} }
async function remove(item) { if(!confirm(`确认删除 ${item.name} 及其空宿舍？有入住学生时将拒绝删除。`))return;try {await api('/buildings/'+item.id,{method:'DELETE'});await load()}catch(e){error.value=e.message} }
async function exportData(){try{await download('buildings')}catch(e){error.value=e.message}}
onMounted(load)
</script>
<template>
  <div class="page-heading"><div><span class="eyebrow">BUILDINGS</span><h1>楼栋管理</h1><p class="muted">配置楼层、房间类型和床位规模</p></div><div class="actions"><button @click="exportData">导出 Excel</button><button class="primary" @click="open(null)">＋ 添加楼栋</button></div></div>
  <div v-if="error" class="alert sticky" role="alert">{{error}}<button @click="error=''">关闭</button></div>
  <div class="building-grid"><article v-for="b in items" :key="b.id" class="card building-card"><span class="building-icon">▥</span><span class="badge">{{b.gender}}生楼栋</span><h2>{{b.name}}</h2><p class="muted">楼栋编号 {{b.id}}</p><div class="actions"><button @click="open(b)">编辑楼栋与房间</button><button class="danger-text" @click="remove(b)">删除</button></div></article><div v-if="!items.length" class="card empty">暂无楼栋，请添加第一栋宿舍楼。</div></div>
  <div v-if="editing" class="modal-backdrop"><section class="modal" role="dialog" aria-modal="true" aria-label="楼栋设置"><div class="modal-heading"><h2>{{editId?'编辑':'添加'}}楼栋</h2><button @click="editing=false">关闭</button></div><form @submit.prevent="save"><div class="form-grid"><label>楼栋名称<input v-model="form.name" required maxlength="40" placeholder="例如：1号楼"></label><label>性别限制<select v-model="form.gender"><option>男</option><option>女</option></select></label><label>房型排列<select v-model="form.sortMode"><option value="asc">二人间 → 四人间 → 六人间</option><option value="desc">二人间 → 六人间 → 四人间</option></select></label></div><p class="muted">每层 1–99 间，最多 30 层。系统会检查容量和入住情况后保存。</p><div class="table-scroll"><table><thead><tr><th>楼层</th><th>二人间</th><th>四人间</th><th>六人间</th></tr></thead><tbody><tr v-for="(f,i) in form.floors" :key="i"><td>{{i+1}} 层</td><td v-for="k in ['doubleRooms','fourRooms','sixRooms']" :key="k"><input v-model.number="f[k]" type="number" required min="0" max="99" :aria-label="`${i+1} 层 ${k}`"></td></tr></tbody></table></div><div class="actions floor-actions"><button type="button" :disabled="form.floors.length>=30" @click="form.floors.push({doubleRooms:0,fourRooms:5,sixRooms:5})">增加楼层</button><button type="button" :disabled="form.floors.length<=1" @click="form.floors.pop()">移除顶层</button></div><div class="form-footer"><button type="button" @click="editing=false">取消</button><button class="primary" :disabled="busy">{{busy?'保存中…':'保存楼栋'}}</button></div></form></section></div>
</template>
