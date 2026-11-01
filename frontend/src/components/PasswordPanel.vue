<script setup>
import {reactive,ref} from 'vue'
import {api} from '../api'
const emit=defineEmits(['changed']);defineProps({required:Boolean})
const form=reactive({oldPassword:'',newPassword:'',confirm:''}),error=ref(''),busy=ref(false),success=ref('')
async function save(){error.value='';if(form.newPassword!==form.confirm){error.value='两次新密码不一致';return}busy.value=true;try{await api('/business/account/password',{method:'PUT',body:{oldPassword:form.oldPassword,newPassword:form.newPassword}});form.oldPassword='';form.newPassword='';form.confirm='';success.value='密码已更新，其他登录会话已失效。';emit('changed')}catch(e){error.value=e.message}finally{busy.value=false}}
</script>
<template><section class="card padded"><h1>{{required?'请先修改初始密码':'修改登录密码'}}</h1><p class="muted">新密码至少 8 位，包含字母与数字。修改后其他设备需要重新登录。</p><div v-if="error" class="alert" role="alert">{{error}}</div><div v-if="success" class="notice">{{success}}</div><form class="form-grid" @submit.prevent="save"><label>原密码<input v-model="form.oldPassword" type="password" required autocomplete="current-password" maxlength="72"></label><label>新密码<input v-model="form.newPassword" type="password" required minlength="8" maxlength="72" autocomplete="new-password"></label><label>确认新密码<input v-model="form.confirm" type="password" required minlength="8" maxlength="72" autocomplete="new-password"></label><div class="form-footer"><button class="primary" :disabled="busy">保存密码</button></div></form></section></template>
