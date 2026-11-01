<script setup>
import { computed } from 'vue'
import { currentUser } from '../api'
import { translate } from '../business-modules'
const props=defineProps({kind:String,filters:Object})
const emit=defineEmits(['change'])
const states=computed(()=>({beds:['AVAILABLE','RESERVED','OCCUPIED','DISABLED'],applications:['PENDING','APPROVED','REJECTED','COMPLETED','CANCELLED'],rooms:['ACTIVE','MAINTENANCE','CLOSED'],archives:['ENROLLED','GRADUATED','SUSPENDED','WITHDRAWN'],stays:['PENDING','APPROVED','REJECTED','RETURNED'],inspections:['OPEN','SUBMITTED','CLOSED'],bills:['ACTIVE','VOID'],assets:['AVAILABLE','ISSUED','REPAIR','LOST','RETIRED'],repairs:[...(currentUser.value?.role==='worker'?[]:['WAITING_ASSIGNMENT']),'待处理','处理中','待验收','已完成']}[props.kind]||[]))
const building=computed(()=>['beds','applications','history','rooms','archives','leaders','stays','visitors','inspections','bills','assets','handovers','repairs'].includes(props.kind))
const dates=computed(()=>['applications','history','archives','leaders','audit','announcements','notifications','stays','visitors','inspections','bills','handovers','repairs'].includes(props.kind))
const set=(field,event)=>emit('change',{[field]:event.target.value})
</script>
<template>
  <div v-if="states.length||building||dates" class="business-filters">
    <label v-if="states.length">状态<select :value="filters.state" aria-label="筛选状态" @change="set('state',$event)"><option value="">全部状态</option><option v-for="state in states" :key="state" :value="state">{{translate(state)}}</option></select></label>
    <label v-if="building">楼栋<input :value="filters.building" aria-label="筛选楼栋" placeholder="填写完整楼栋名称" maxlength="50" @change="set('building',$event)"></label>
    <template v-if="dates"><label>{{kind==='archives'?'归档开始日期':'开始日期'}}<input type="date" :value="filters.from" aria-label="筛选开始日期" @change="set('from',$event)"></label><label>{{kind==='archives'?'归档结束日期':'结束日期'}}<input type="date" :value="filters.to" aria-label="筛选结束日期" @change="set('to',$event)"></label></template>
    <button type="button" @click="emit('change',{state:'',building:'',from:'',to:''})">清除筛选</button>
  </div>
</template>
<style scoped>
.business-filters{display:flex;flex-wrap:wrap;gap:.75rem;align-items:end;padding:0 1.25rem 1rem}.business-filters label{display:grid;gap:.3rem;font-size:.85rem}.business-filters input,.business-filters select{max-width:13rem}
</style>
