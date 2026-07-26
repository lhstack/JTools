<script setup>
import {computed,onMounted,reactive,ref,watch} from 'vue'
import {ElMessage,ElMessageBox} from 'element-plus'
import {api} from '../bridge/jcefBridge'
import PageShell from '../components/common/PageShell.vue'
const groups=ref([]),selectedId=ref(null),dialog=ref(false),sections=ref(['provider','models']),provider=reactive(emptyProvider()),model=reactive(emptyModel()),params=reactive(defaultParams()),editing=ref(false),remoteModels=ref([]),remoteLoading=ref(false),remoteQuery=ref('')
const selected=computed(()=>groups.value.find(x=>x.provider.id===selectedId.value));const models=computed(()=>selected.value?.models||[]);const isCompatible=computed(()=>provider.kind==='openai'&&provider.providerConfig?.openai_provider_type==='compatible');const effectiveApi=computed(()=>provider.kind==='anthropic'?'anthropic':(model.api||provider.api||'completions'));const filteredRemoteModels=computed(()=>remoteModels.value.filter(x=>x.id.toLowerCase().includes(remoteQuery.value.toLowerCase())));const isAnthropic=computed(()=>effectiveApi.value==='anthropic');const isResponses=computed(()=>effectiveApi.value==='responses')
function emptyProvider(){return{id:null,name:'',kind:'openai',apiKey:'',baseUrl:'',api:'completions',anthropicVersion:'',providerConfig:{openai_provider_type:'official',proxy_url:'',custom_headers:[]},enabled:1}}
function emptyModel(){return{id:null,providerId:null,alias:'',modelId:'',displayName:'',api:'',contextWindow:32000,modalities:['text'],additionalParams:'',enabled:1}}
function defaultParams(){return{maxTokens:4096,reasoning:'',thinkingType:'disabled',thinkingBudget:null,outputEffort:'',outputFormat:'',outputSchema:'{}',parallelTools:true,stream:true,maxToolRounds:30,maxRetries:0}}
function assign(target,value){Object.keys(target).forEach(k=>delete target[k]);Object.assign(target,value)}
function object(text){try{return text?JSON.parse(text):{}}catch{return{}}}function nested(o,path){return path.split('.').reduce((v,k)=>v?.[k],o)}
function hydrateParams(x){
  const mp=object(x.modelParams),ep=object(x.executionParams)
  const compatible=isCompatible.value
  const anthropic=isAnthropic.value
  const responses=isResponses.value
  assign(params,{
    ...defaultParams(),
    maxTokens:mp.max_tokens??mp.max_output_tokens??mp.max_completion_tokens??4096,
    reasoning:responses?nested(mp,'reasoning.effort')??'':mp.reasoning_effort??'',
    thinkingType:nested(mp,'thinking.type')??'disabled',
    thinkingBudget:nested(mp,'thinking.budget_tokens')??null,
    outputEffort:nested(mp,'output_config.effort')??'',
    outputFormat:compatible?'':responses?nested(mp,'text.format.type')??'':anthropic?nested(mp,'output_config.format.type')??'':nested(mp,'response_format.type')??'',
    outputSchema:JSON.stringify(responses?nested(mp,'text.format.schema')||{}:anthropic?nested(mp,'output_config.format.schema')||{}:nested(mp,'response_format.json_schema.schema')||{},null,2),
    parallelTools:anthropic?!nested(mp,'tool_choice.disable_parallel_tool_use'):(mp.parallel_tool_calls??true),
    stream:mp.stream??true,
    maxToolRounds:ep.max_tool_call_rounds??30,
    maxRetries:ep.max_retries??0,
  })
}
function schemaFormat(){
  const schema=object(params.outputSchema)
  if(params.outputFormat!=='json_schema')return null
  return {type:'json_schema',name:'response',schema,strict:true}
}
function chatResponseFormat(){
  const format=schemaFormat()
  return format?{type:'json_schema',json_schema:{name:format.name,schema:format.schema,strict:format.strict}}:null
}
function modelPayload(){
  const mp={stream:params.stream}
  if(isAnthropic.value){
    mp.max_tokens=params.maxTokens
    mp.thinking={type:params.thinkingType}
    if(params.thinkingType==='enabled'&&params.thinkingBudget)mp.thinking.budget_tokens=params.thinkingBudget
    mp.tool_choice={type:'auto',disable_parallel_tool_use:!params.parallelTools}
    if(params.outputEffort)mp.output_config={effort:params.outputEffort}
    const format=schemaFormat();if(format)mp.output_config={...(mp.output_config||{}),format}
  }else{
    if(isResponses.value){
      mp.max_output_tokens=params.maxTokens
      if(params.reasoning)mp.reasoning={effort:params.reasoning}
      const format=schemaFormat();if(format)mp.text={format}
    }else{
      mp.max_completion_tokens=params.maxTokens
      if(params.reasoning)mp.reasoning_effort=params.reasoning
      const format=chatResponseFormat();if(format)mp.response_format=format
    }
    mp.parallel_tool_calls=params.parallelTools
    if(isCompatible.value){
      mp.thinking={type:params.thinkingType}
      if(params.outputEffort)mp.output_config={effort:params.outputEffort}
    }
  }
  return{...model,providerId:provider.id,enabled:!!model.enabled,modelParams:JSON.stringify(mp,null,2),executionParams:JSON.stringify({max_tool_call_rounds:params.maxToolRounds,max_retries:params.maxRetries},null,2),additionalParams:model.additionalParams||'{}'}
}
async function load(){const data=await api('catalog.get');groups.value=data.providers||[];if(selectedId.value&&!groups.value.some(x=>x.provider.id===selectedId.value))selectedId.value=null}
function openGroup(g){remoteModels.value=[];remoteQuery.value='';selectedId.value=g.provider.id;assign(provider,{...emptyProvider(),...g.provider});normalizeProviderHeaders(provider);const first=g.models[0];assign(model,first?hydrate(first):{...emptyModel(),providerId:g.provider.id});editing.value=!!first;if(first)hydrateParams(first);else assign(params,defaultParams());sections.value=['provider','models'];dialog.value=true}
function newProvider(){remoteModels.value=[];remoteQuery.value='';selectedId.value=null;assign(provider,emptyProvider());normalizeProviderHeaders(provider);assign(model,emptyModel());assign(params,defaultParams());editing.value=false;sections.value=['provider','models'];dialog.value=true}
function hydrate(x){return{...emptyModel(),...x,modalities:parseArray(x.modalities),enabled:x.enabled??1}}
function parseArray(x){if(Array.isArray(x))return x;try{return JSON.parse(x||'[]')}catch{return['text']}}
function normalizeProviderHeaders(p){
  const cfg=p.providerConfig&&typeof p.providerConfig==='object'?p.providerConfig:{}
  const headers=Array.isArray(cfg.custom_headers)?cfg.custom_headers:[]
  p.providerConfig={...emptyProvider().providerConfig,...cfg,custom_headers:headers.map(h=>({name:h?.name??'',value:h?.value??''}))}
}
function addCustomHeader(){
  normalizeProviderHeaders(provider)
  provider.providerConfig.custom_headers.push({name:'',value:''})
}
function removeCustomHeader(index){
  normalizeProviderHeaders(provider)
  provider.providerConfig.custom_headers.splice(index,1)
}
async function saveProvider(){
  normalizeProviderHeaders(provider)
  const headers=provider.providerConfig.custom_headers||[]
  for(let i=0;i<headers.length;i++){
    const name=String(headers[i]?.name||'').trim()
    if(!name){ElMessage.warning(`自定义请求头第 ${i+1} 行名称不能为空`);return}
    if(/^authorization$/i.test(name)){ElMessage.warning('自定义请求头不支持 Authorization，请使用上方 API Key');return}
  }
  provider.providerConfig.custom_headers=headers.map(h=>({name:String(h.name||'').trim(),value:String(h.value??'')}))
  const proxyUrl=String(provider.providerConfig.proxy_url||'').trim()
  if(proxyUrl)provider.providerConfig.proxy_url=proxyUrl
  else delete provider.providerConfig.proxy_url
  const saved=await api('provider.save',{...provider,enabled:!!provider.enabled});ElMessage.success('供应商已保存');await load();selectedId.value=saved.id;const g=groups.value.find(x=>x.provider.id===saved.id);if(g){assign(provider,{...emptyProvider(),...g.provider});normalizeProviderHeaders(provider)}}
async function loadRemoteModels(){
  if(!provider.id){ElMessage.warning('请先保存供应商');return}
  remoteLoading.value=true
  try{
    const {jobId}=await api('provider.remoteModels.start',{id:provider.id})
    const result=await waitRemoteModels(jobId)
    if(result.status==='failed')throw new Error(result.error||'获取远端模型失败')
    remoteModels.value=result.models||[]
    if(!remoteModels.value.length)ElMessage.warning('供应商未返回任何模型')
    else ElMessage.success(`已获取 ${remoteModels.value.length} 个模型`)
  }catch(error){
    remoteModels.value=[]
    ElMessage.error(`获取远端模型失败：${error?.message||String(error)}`)
  }finally{remoteLoading.value=false}
}
async function waitRemoteModels(jobId){
  while(true){
    const result=await api('provider.remoteModels.poll',{jobId})
    if(result.status!=='running')return result
    await new Promise(resolve=>setTimeout(resolve,250))
  }
}
function useRemoteModel(x){newModel();Object.assign(model,{alias:x.id,modelId:x.id,displayName:x.displayName||x.id})}
async function removeProvider(g){await ElMessageBox.confirm(`删除供应商 ${g.provider.name}？所属模型也会删除。`,'删除供应商',{type:'warning'});await api('provider.delete',{id:g.provider.id});dialog.value=false;selectedId.value=null;load()}
function editModel(x){assign(model,hydrate(x));hydrateParams(x);editing.value=true;sections.value=['provider','models']}function newModel(){assign(model,{...emptyModel(),providerId:provider.id});assign(params,defaultParams());editing.value=true;sections.value=['provider','models']}
async function saveModel(){await api('model.save',modelPayload());ElMessage.success('模型已保存');await load();const g=groups.value.find(x=>x.provider.id===provider.id);const saved=g?.models.find(x=>x.id===model.id)||g?.models.find(x=>x.alias===model.alias);if(saved){assign(model,hydrate(saved));hydrateParams(saved)}}
async function removeModel(x){await ElMessageBox.confirm(`删除模型 ${x.alias}？`,'删除模型',{type:'warning'});await api('model.delete',{id:x.id});await load();const g=groups.value.find(x=>x.provider.id===provider.id);if(g?.models[0])editModel(g.models[0]);else{assign(model,{...emptyModel(),providerId:provider.id});editing.value=false}}
watch(()=>provider.kind,kind=>{if(kind==='anthropic')provider.api=''});onMounted(load)
</script>
<template>
<PageShell title="供应商与模型">
  <template #actions><el-button type="primary" @click="newProvider">新增供应商</el-button></template>
  <div class="provider-grid">
    <article v-for="g in groups" :key="g.provider.id" class="provider-card" @click="openGroup(g)">
      <div class="provider-head"><strong>{{g.provider.name}}</strong><el-tag size="small" effect="plain">{{g.provider.kind}}</el-tag></div>
      <div class="provider-stats"><div><span>本地模型</span><b>{{g.models.length}}</b></div><div><span>接口模式</span><b>{{g.provider.kind==='anthropic'?'Messages':g.provider.api||'Chat'}}</b></div></div>
      <div class="provider-url"><span>接口地址</span><p>{{g.provider.baseUrl||'-'}}</p></div>
      <footer><el-tag :type="g.provider.enabled?'success':'info'" size="small">{{g.provider.enabled?'启用':'禁用'}}</el-tag><el-button link type="danger" @click.stop="removeProvider(g)">删除</el-button></footer>
    </article>
  </div>
  <el-dialog v-model="dialog" :title="provider.name||'新增供应商'" width="min(1080px, calc(100vw - 30px))" class="catalog-dialog" :lock-scroll="false">
    <el-scrollbar class="dialog-scroll">
      <el-collapse v-model="sections" class="config-collapse">
        <el-collapse-item name="provider">
          <template #title><span class="collapse-title">供应商配置</span></template>
          <el-form label-position="top" class="section-form">
            <div class="form-three">
              <el-form-item label="名称"><el-input v-model="provider.name"/></el-form-item>
              <el-form-item label="协议"><el-select v-model="provider.kind"><el-option label="OpenAI" value="openai"/><el-option label="Anthropic" value="anthropic"/></el-select></el-form-item>
              <el-form-item label="API Key"><el-input v-model="provider.apiKey" type="password" show-password/></el-form-item>
              <el-form-item v-if="provider.kind==='openai'" label="OpenAI 类型"><el-select v-model="provider.providerConfig.openai_provider_type"><el-option label="官方 OpenAI" value="official"/><el-option label="三方兼容 OpenAI" value="compatible"/></el-select></el-form-item>
              <el-form-item label="Base URL"><el-input v-model="provider.baseUrl"/></el-form-item>
              <el-form-item v-if="provider.kind==='openai'" label="OpenAI API"><el-select v-model="provider.api"><el-option label="Chat Completions" value="completions"/><el-option label="Responses" value="responses"/></el-select></el-form-item>
              <el-form-item v-else label="Anthropic Version"><el-input v-model="provider.anthropicVersion" placeholder="2023-06-01"/></el-form-item>
              <el-form-item label="代理地址"><el-input v-model="provider.providerConfig.proxy_url" clearable placeholder="http://127.0.0.1:7890 或 socks5://127.0.0.1:1080"/></el-form-item>
            </div>
            <div class="provider-headers">
              <div class="provider-headers-head">
                <div>
                  <strong>自定义请求头</strong>
                  <span>随供应商请求发送；Authorization 请使用上方 API Key</span>
                </div>
                <el-button size="small" @click="addCustomHeader">添加请求头</el-button>
              </div>
              <div class="provider-headers-body">
                <div v-if="!(provider.providerConfig?.custom_headers||[]).length" class="provider-headers-empty">暂无自定义请求头</div>
                <div v-for="(header,index) in (provider.providerConfig?.custom_headers||[])" :key="index" class="provider-header-row">
                  <el-input v-model="header.name" placeholder="Header 名，如 X-App-Id"/>
                  <el-input v-model="header.value" placeholder="Header 值"/>
                  <el-button link type="danger" @click="removeCustomHeader(index)">删除</el-button>
                </div>
              </div>
            </div>
            <div class="save-row"><div class="switch-label"><el-switch v-model="provider.enabled" :active-value="1" :inactive-value="0"/><span>启用供应商</span></div><el-button type="primary" @click="saveProvider">保存供应商</el-button></div>
          </el-form>
        </el-collapse-item>
        <el-collapse-item name="models" :disabled="!provider.id">
          <template #title><span class="collapse-title">模型管理 <small>{{models.length}} 个本地模型</small></span></template>
          <div class="model-management">
            <aside class="model-sources"><section class="model-picker"><div class="sub-head"><strong>本地模型</strong><el-button size="small" @click="newModel">新增模型</el-button></div><el-scrollbar height="215px"><button v-for="x in models" :key="x.id" class="model-item" :class="{active:model.id===x.id}" @click="editModel(x)"><span><strong>{{x.displayName||x.alias}}</strong><small>{{x.modelId}}</small></span><el-button link type="danger" @click.stop="removeModel(x)">删除</el-button></button></el-scrollbar></section><section class="model-picker remote-picker"><div class="sub-head"><strong>供应商模型</strong><el-button size="small" :loading="remoteLoading" @click="loadRemoteModels">获取</el-button></div><el-input v-model="remoteQuery" size="small" clearable placeholder="搜索远端模型"/><el-scrollbar height="190px"><button v-for="x in filteredRemoteModels" :key="x.id" class="model-item" @click="useRemoteModel(x)"><span><strong>{{x.displayName||x.id}}</strong><small>{{x.id}}</small></span><el-button link type="primary">添加</el-button></button><el-empty v-if="!remoteModels.length" description="点击获取供应商模型" :image-size="35"/></el-scrollbar></section></aside>
            <section class="model-form-panel">
              <div class="sub-head"><strong>模型参数</strong><el-tag v-if="editing" size="small" effect="plain">{{isAnthropic?'Anthropic':isResponses?'OpenAI Responses':'OpenAI Chat'}}</el-tag></div>
              <el-empty v-if="!editing" description="请选择或新增模型"/>
              <el-scrollbar v-else height="500px">
                <el-form label-position="top" class="section-form model-form">
                  <div class="form-three">
                    <el-form-item label="别名"><el-input v-model="model.alias"/></el-form-item><el-form-item label="模型 ID"><el-input v-model="model.modelId"/></el-form-item><el-form-item label="显示名称"><el-input v-model="model.displayName"/></el-form-item>
                    <el-form-item v-if="provider.kind==='openai'" label="API"><el-select v-model="model.api" clearable placeholder="继承供应商"><el-option label="Chat Completions" value="completions"/><el-option label="Responses" value="responses"/></el-select></el-form-item>
                    <el-form-item label="上下文窗口"><el-input-number v-model="model.contextWindow" :min="1"/></el-form-item><el-form-item label="多模态能力"><el-select v-model="model.modalities" multiple><el-option v-for="x in ['text','image','audio','video','file']" :key="x" :value="x"/></el-select></el-form-item>
                  </div>
                  <div class="section-caption"><strong>生成与推理</strong><span>根据 API 自动映射参数字段</span></div>
                  <div class="form-four">
                    <el-form-item label="最大输出 Token"><el-input-number v-model="params.maxTokens" :min="1"/></el-form-item>
                    <template v-if="isAnthropic"><el-form-item label="Thinking"><el-select v-model="params.thinkingType"><el-option label="关闭" value="disabled"/><el-option label="启用" value="enabled"/><el-option label="Adaptive" value="adaptive"/></el-select></el-form-item><el-form-item v-if="params.thinkingType==='enabled'" label="Thinking Budget"><el-input-number v-model="params.thinkingBudget" :min="1024"/></el-form-item><el-form-item label="输出强度"><el-select v-model="params.outputEffort" clearable><el-option v-for="x in ['low','medium','high','xhigh','max']" :key="x" :value="x"/></el-select></el-form-item></template>
                    <template v-else-if="isCompatible"><el-form-item label="推理强度"><el-select v-model="params.reasoning" clearable><el-option v-for="x in ['无','最小','低','中','高','极高']" :key="x" :label="x" :value="{'无':'none','最小':'minimal','低':'low','中':'medium','高':'high','极高':'xhigh'}[x]"/></el-select></el-form-item><el-form-item label="兼容推理"><el-select v-model="params.thinkingType"><el-option label="启用" value="enabled"/><el-option label="关闭" value="disabled"/></el-select></el-form-item><el-form-item label="输出强度"><el-select v-model="params.outputEffort" clearable><el-option v-for="x in ['low','medium','high','xhigh','max']" :key="x" :value="x"/></el-select></el-form-item></template>
                    <el-form-item v-else label="推理强度"><el-select v-model="params.reasoning" clearable><el-option v-for="x in ['无','最小','低','中','高','极高']" :key="x" :label="x" :value="{'无':'none','最小':'minimal','低':'low','中':'medium','高':'high','极高':'xhigh'}[x]"/></el-select></el-form-item>
                    <el-form-item v-if="!isCompatible&&!isAnthropic" label="结构化输出格式"><el-select v-model="params.outputFormat" clearable><el-option label="JSON Schema" value="json_schema"/></el-select></el-form-item>
                    <el-form-item label="流式输出"><el-switch v-model="params.stream"/></el-form-item>
                  </div>
                  <el-form-item v-if="params.outputFormat==='json_schema'" label="输出结构 JSON Schema"><el-input v-model="params.outputSchema" type="textarea" :rows="4"/></el-form-item><div class="section-caption"><strong>工具执行</strong><span>工具调用类型固定为自动</span></div>
                  <div class="form-four"><el-form-item label="并发工具调用"><el-switch v-model="params.parallelTools"/></el-form-item><el-form-item label="工具调用类型"><el-input model-value="auto" disabled/></el-form-item><el-form-item label="最大工具调用轮次"><el-input-number v-model="params.maxToolRounds" :min="1"/></el-form-item><el-form-item label="失败重试次数"><el-input-number v-model="params.maxRetries" :min="0"/></el-form-item></div>
                  <div class="section-caption"><strong>附加参数</strong><span>仅填写未被表单覆盖的 JSON 参数</span></div><el-form-item><el-input v-model="model.additionalParams" type="textarea" :rows="4" placeholder="{}"/></el-form-item>
                  <div class="save-row"><div class="switch-label"><el-switch v-model="model.enabled" :active-value="1" :inactive-value="0"/><span>启用模型</span></div><el-button type="primary" @click="saveModel">保存模型</el-button></div>
                </el-form>
              </el-scrollbar>
            </section>
          </div>
        </el-collapse-item>
      </el-collapse>
    </el-scrollbar>
  </el-dialog>
</PageShell>
</template>
