import { consumeMessageTask, replaceMessageTasks } from '../realtime/messageTaskStore.js'
import { reactive } from 'vue'
const INITIAL_SESSION_KEY='jtools:selected-session:v1'
const MESSAGE_CACHE_PREFIX='jtools:chat-messages:v1:'
function initialChatCache(){try{const sessionId=Number(localStorage.getItem(INITIAL_SESSION_KEY));if(!Number.isFinite(sessionId)||sessionId<=0)return{sessionId:null,messages:[]};const messages=JSON.parse(localStorage.getItem(`${MESSAGE_CACHE_PREFIX}${sessionId}`)||'[]');return{sessionId,messages:Array.isArray(messages)?messages.filter(item=>item?.id&&item.persisted===true):[]}}catch{return{sessionId:null,messages:[]}}}
const initialChat=initialChatCache()
export const hostState=reactive({dark:false,theme:{},sessions:[],agents:[],messages:initialChat.messages,events:[],tasks:[],historyRevision:0,queue:[],drafts:[],inputRestore:null,currentSessionId:initialChat.sessionId,currentAgentId:null,currentSession:null,providers:[],prompts:[],context:null,skills:[]})
let bridgeReady
const ready=new Promise(resolve=>bridgeReady=resolve)
window.addEventListener('jtools-ready',()=>bridgeReady())
if(window.jtoolsInvoke) bridgeReady()
export async function invoke(type,payload={}){await ready;return window.jtoolsInvoke(JSON.stringify({type,payload}))}
export async function api(type,payload={}){const raw=await invoke(type,payload);const result=raw?JSON.parse(raw):null;if(!result?.ok)throw new Error(result?.error||'操作失败');return result.data}
export function installHostState(){window.jtoolsAgent={replace(next){const incoming=next&&typeof next==='object'?next:{};Object.assign(hostState,incoming,{events:Array.isArray(incoming.events)?incoming.events:[]});if(Array.isArray(incoming.drafts)) hostState.drafts=incoming.drafts;applyTheme(incoming)},patch(payload){applyHostPatch(payload)}};installPopupStateBridge();invoke('ui.ready')}
function eventRevision(event){return Number(event?.revision||0)}
function shouldReplaceEvent(current,incoming){
  if(!current) return true
  if(eventRevision(incoming)>eventRevision(current)) return true
  if(eventRevision(incoming)<eventRevision(current)) return false
  const currentUpdated=Date.parse(String(current.updated_at||current.created_at||'').replace(' ','T'))||0
  const incomingUpdated=Date.parse(String(incoming.updated_at||incoming.created_at||'').replace(' ','T'))||0
  if(incomingUpdated!==currentUpdated) return incomingUpdated>=currentUpdated
  return (incoming.summary||'')!==(current.summary||'') || (incoming.status||'')!==(current.status||'')
}
function mergeEvent(current,incoming){
  if(!incoming) return current
  if(!shouldReplaceEvent(current,incoming)) return current
  if(current && incoming.context==null && current.context!=null) return Object.assign({},current,incoming,{context:current.context})
  return current ? Object.assign({},current,incoming) : incoming
}
function applyHostPatch(payload){
  if(!payload||typeof payload!=='object') return
  if(Array.isArray(payload.events)){
    const events=Array.isArray(hostState.events)?hostState.events.slice():[]
    payload.events.forEach(incoming=>{
      const id=Number(incoming?.id)
      if(!Number.isFinite(id)) return
      const index=events.findIndex(item=>Number(item.id)===id)
      if(index>=0) events.splice(index,1,mergeEvent(events[index],incoming))
      else events.push(incoming)
    })
    events.sort((left,right)=>Number(left.id)-Number(right.id))
    hostState.events=events
  }
  const taskEvents=[]
  if(Array.isArray(payload.message_tasks)) taskEvents.push(...payload.message_tasks)
  else if(payload.message_task) taskEvents.push(payload.message_task)
  const consumedTasks=taskEvents.map(consumeMessageTask).filter(Boolean)
  consumedTasks.filter((task)=>task.deleted).forEach((task)=>{
    if(task.turn_id) hostState.events=(hostState.events||[]).filter((event)=>String(event.turn_id)!==String(task.turn_id))
    if(Number(task.session_id)===Number(hostState.currentSessionId)){
      hostState.inputRestore={sequence:Date.now(),text:task.content||'',attachments:task.attachment_items||[]}
    }
  })
  if(Array.isArray(payload.tasks)) replaceMessageTasks(payload.session_id || hostState.currentSessionId, payload.tasks)
  if(Array.isArray(payload.drafts)) hostState.drafts=payload.drafts
  if(payload.context) hostState.context=payload.context
  if(payload.historyRevision!=null) hostState.historyRevision=payload.historyRevision
}

function installPopupStateBridge(){let reported=null,scheduled=false;const isVisible=element=>{const style=getComputedStyle(element);return element.getClientRects().length>0&&style.display!=='none'&&style.visibility!=='hidden'};const hasOpenPopup=()=>[...document.querySelectorAll('.el-overlay')].some(overlay=>isVisible(overlay)&&overlay.querySelector('.el-dialog,.el-drawer,.el-message-box'))||[...document.querySelectorAll('.el-popper')].some(isVisible);const report=()=>{scheduled=false;const open=hasOpenPopup();if(open===reported)return;reported=open;invoke('ui.popupState',{open}).catch(()=>{})};const schedule=()=>{if(scheduled)return;scheduled=true;queueMicrotask(report)};new MutationObserver(schedule).observe(document.body,{subtree:true,childList:true,attributes:true,attributeFilter:['class','style']});report()}
export function applyTheme(state){const t=state?.theme||{};document.documentElement.classList.toggle('dark',!!state?.dark);const style=document.documentElement.style;const vars={'--jb-bg':t.background,'--jb-panel':t.panel,'--jb-input':t.input,'--jb-text':t.text,'--jb-muted':t.muted,'--jb-border':t.border,'--jb-accent':t.accent,'--jb-font':t.fontFamily,'--el-bg-color':t.panel,'--el-bg-color-page':t.background,'--el-bg-color-overlay':t.panel,'--el-fill-color-blank':t.panel,'--el-fill-color-light':t.input,'--el-text-color-primary':t.text,'--el-text-color-regular':t.text,'--el-text-color-secondary':t.muted,'--el-border-color':t.border,'--el-border-color-light':t.border,'--el-color-primary':t.accent};Object.entries(vars).forEach(([k,v])=>v&&style.setProperty(k,v))}
