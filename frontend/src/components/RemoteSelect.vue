<script setup>
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { api } from '../api'
import { translate } from '../business-modules'
const props = defineProps({ source:String, modelValue:[String,Number,Array], label:String, multiple:Boolean, required:Boolean, number:Boolean, unhoused:Boolean })
const emit = defineEmits(['update:modelValue'])
const rows=ref([]), selected=ref([]), search=ref(''), page=ref(1), total=ref(0), busy=ref(false), error=ref('')
let version=0,timer,selectionVersion=0
const key=row=>props.source==='workers'?row.username:row.id
const optionLabel=row=>props.source==='students'?`${row.name} · ${row.student_id}`:props.source==='rooms'?row.dormitory_number:props.source==='beds'?`${row.dormitory_number} · ${row.bed_number} 号床（${translate(row.state)}）`:props.source==='workers'?`${row.real_name||row.username} · ${row.username}`:row.name
const ids=computed(()=>Array.isArray(props.modelValue)?props.modelValue:props.modelValue===''||props.modelValue==null?[]:[props.modelValue])
const selection=computed({get:()=>props.multiple?ids.value:props.modelValue??'',set:value=>emit('update:modelValue',value)})
const choices=computed(()=>[...new Map([...selected.value,...rows.value].map(row=>[String(key(row)),row])).values()])
async function fetchPage(){const current=++version;busy.value=true;error.value='';try{const query=new URLSearchParams({page:page.value,size:50,search:search.value,unhoused:props.unhoused});const data=await api(`/business/options/${props.source}?${query}`);if(current!==version)return;rows.value=data.items;total.value=data.total}catch(e){if(current===version)error.value=e.message}finally{if(current===version)busy.value=false}}
async function fetchSelected(){const current=++selectionVersion;if(!ids.value.length){selected.value=[];return}try{const wanted=new Set(ids.value.map(String));selected.value=choices.value.filter(row=>wanted.has(String(key(row))));const missing=ids.value.filter(id=>!selected.value.some(row=>String(key(row))===String(id)));if(!missing.length)return;let found=[];for(let offset=0;offset<missing.length;offset+=100){const query=new URLSearchParams({ids:missing.slice(offset,offset+100).join(','),size:100,unhoused:props.unhoused});const data=await api(`/business/options/${props.source}?${query}`);found.push(...data.items)}if(current===selectionVersion)selected.value=[...selected.value,...found]}catch(e){if(current===selectionVersion)error.value=e.message}}
function searchChanged(){clearTimeout(timer);version++;busy.value=true;timer=setTimeout(()=>{page.value=1;fetchPage()},300)}
function turn(delta){page.value+=delta;fetchPage()}
watch(ids,fetchSelected,{deep:true})
onMounted(()=>{fetchPage();fetchSelected()})
onBeforeUnmount(()=>{clearTimeout(timer);version++;selectionVersion++})
</script>
<template>
  <div class="remote-select">
    <input v-model="search" type="search" :aria-label="`搜索${label}`" placeholder="输入名称或编号前缀搜索" maxlength="100" @input="searchChanged">
    <select v-model="selection" :aria-label="label" :required="required" :multiple="multiple" :size="multiple?6:undefined">
      <option v-if="!multiple" value="">请选择{{required?'':'（可不填）'}}</option>
      <option v-for="row in choices" :key="key(row)" :value="key(row)">{{optionLabel(row)}}</option>
    </select>
    <div class="actions"><button type="button" :disabled="busy||page<=1" :aria-label="`${label}上一页`" @click="turn(-1)">上一页</button><small>{{page}} / {{Math.max(1,Math.ceil(total/50))}} · {{total}} 项</small><button type="button" :disabled="busy||page*50>=total" :aria-label="`${label}下一页`" @click="turn(1)">下一页</button></div>
    <small v-if="busy" role="status">正在加载选项…</small><small v-if="error" class="alert" role="alert">{{error}} <button type="button" @click="fetchPage">重试</button></small>
  </div>
</template>
<style scoped>
.remote-select{display:grid;gap:.45rem;min-width:0}.remote-select select{width:100%}.remote-select .actions{justify-content:space-between}.remote-select .actions button{padding:.25rem .5rem;font-size:.8rem}
</style>
