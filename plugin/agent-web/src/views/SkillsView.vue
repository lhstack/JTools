<script setup>
import {computed,onMounted,reactive,ref} from 'vue';
import {ElMessage,ElMessageBox} from 'element-plus';
import {api} from '../bridge/jcefBridge';
import PageShell from '../components/common/PageShell.vue';
const rows=ref([]),page=ref(1),size=ref(16),dialog=ref(false),mode=ref('view'),current=ref(null),tree=ref([]),file=ref(null),content=ref('');
const contextMenu=ref(null);
const createVisible=ref(false);
const creating=ref(false);
const createForm=reactive({name:'',description:''});
const importing=ref(false);
const paged=computed(()=>rows.value.slice((page.value-1)*size.value,page.value*size.value));
async function load(){rows.value=await api('skills.list')}
async function refreshTree(selectPath){
  tree.value=[await api('skill.files',{name:current.value.name})];
  if(selectPath){
    const target=findNode(tree.value[0],selectPath);
    if(target?.kind==='file') await select(target);
  }
}
function findNode(node,path){if(node.path===path)return node;return (node.children||[]).map(x=>findNode(x,path)).find(Boolean)}
async function open(x,m){current.value=x;mode.value=m;dialog.value=true;tree.value=[await api('skill.files',{name:x.name})];const root=await api('skill.read',{name:x.name,path:'SKILL.md'});file.value={name:'SKILL.md',path:root.path,kind:'file'};content.value=root.content}
async function select(x){if(x.kind!=='file')return;file.value=x;content.value=(await api('skill.read',{name:current.value.name,path:x.path})).content}
async function save(){await api('skill.save',{name:current.value.name,path:file.value.path,content:content.value});ElMessage.success('Skill 文件已保存');load()}
async function remove(x){await ElMessageBox.confirm(`确定删除 Skill“${x.name}”及其全部文件吗？`,'删除 Skill',{type:'warning'});await api('skill.delete',{name:x.name});ElMessage.success('Skill 已删除');load()}
function showContextMenu(event,node){
  event.preventDefault();
  const path=node.kind==='dir'?node.path:(node.path.split('/').slice(0,-1).join('/'));
  contextMenu.value={x:event.clientX,y:event.clientY,parentPath:path,nodePath:node.path};
}
function hideContextMenu(){contextMenu.value=null}
async function createFile(){
  const menu=contextMenu.value;if(!menu)return;hideContextMenu();
  try{const {value}=await ElMessageBox.prompt('输入文件名','新建文件',{inputPlaceholder:'例如 references/example.md'});const result=await api('skill.createFile',{name:current.value.name,parentPath:menu.parentPath,fileName:value.trim()});await refreshTree(result.path);ElMessage.success('文件已创建')}catch(error){if(error!=='cancel'&&error!=='close')ElMessage.error(`创建文件失败：${error?.message||String(error)}`)}
}
async function deleteEntry(){
 const menu=contextMenu.value;if(!menu||!menu.nodePath)return;hideContextMenu();
 const node=findNode(tree.value[0],menu.nodePath);if(!node||node.path==='')return;
 try{await ElMessageBox.confirm(`确定删除“${node.name}”${node.kind==='dir'?'及其全部内容':''}吗？`,'删除技能文件',{type:'warning',confirmButtonText:'删除',cancelButtonText:'取消'});await api('skill.deleteEntry',{name:current.value.name,path:node.path});if(file.value?.path===node.path||file.value?.path?.startsWith(`${node.path}/`)){file.value=null;content.value=''};await refreshTree();ElMessage.success('已删除')}catch(error){if(error!=='cancel'&&error!=='close')ElMessage.error(`删除失败：${error?.message||String(error)}`)}
}
async function createDirectory(){
  const menu=contextMenu.value;if(!menu)return;hideContextMenu();
  try{const {value}=await ElMessageBox.prompt('输入目录名','新建目录',{inputPlaceholder:'例如 references'});await api('skill.createDirectory',{name:current.value.name,parentPath:menu.parentPath,directoryName:value.trim()});await refreshTree();ElMessage.success('目录已创建')}catch(error){if(error!=='cancel'&&error!=='close')ElMessage.error(`创建目录失败：${error?.message||String(error)}`)}
}
function openCreate(){
  createForm.name=''
  createForm.description=''
  createVisible.value=true
}
async function createSkill(){
  const name=createForm.name.trim()
  const description=createForm.description.trim()
  if(!name){ElMessage.error('请输入技能名称');return}
  if(!description){ElMessage.error('请输入技能描述');return}
  creating.value=true
  try{
    const created=await api('skill.create',{name,description})
    createVisible.value=false
    await load()
    ElMessage.success('技能已创建')
    const record=rows.value.find(x=>x.name===created.name)
    if(record) await open(record,'edit')
  }catch(error){
    ElMessage.error(error?.message||String(error)||'创建技能失败')
  }finally{
    creating.value=false
  }
}
async function importSkills(){
  importing.value=true
  try{
    const sourcePath=await api('skill.chooseImportDir')
    if(!sourcePath) return
    const result=await api('skill.import',{sourcePath})
    const imported=result?.imported||[]
    const failed=result?.failed||[]
    if(failed.length){
      ElMessage.warning(`已导入 ${imported.length} 个技能，失败 ${failed.length} 个`)
      if(failed[0]?.error) ElMessage.error(`${failed[0].name}: ${failed[0].error}`)
    }else{
      ElMessage.success(`已导入 ${imported.length} 个技能`)
    }
    await load()
  }catch(error){
    if(error!=='cancel'&&error!=='close') ElMessage.error(error?.message||String(error)||'导入技能失败')
  }finally{
    importing.value=false
  }
}
onMounted(load)
</script>
<template><PageShell title="技能管理"><template #actions><el-button @click="load">刷新列表</el-button><el-button :loading="importing" @click="importSkills">导入技能</el-button><el-button type="primary" @click="openCreate">新增技能</el-button></template><div class="card-page"><div class="skill-cards"><article v-for="x in paged" :key="x.name" class="content-card skill-card"><header><strong>{{x.name}}</strong><el-tag size="small" :type="x.available?'success':'danger'">{{x.available?'可用':'不可用'}}</el-tag></header><div class="skill-tags"><el-tag size="small" effect="plain">SKILL.md</el-tag><el-tag size="small" effect="plain">{{x.files?.length||0}} 个文件</el-tag></div><p>{{x.description||x.failReason||'暂无描述'}}</p><small class="path">{{x.relativePath||x.name}}</small><footer><el-button size="small" @click="open(x,'view')">查看</el-button><el-button size="small" type="primary" plain @click="open(x,'edit')">编辑</el-button><el-button size="small" type="danger" plain @click="remove(x)">删除</el-button></footer></article></div><div class="card-pagination"><el-pagination v-model:current-page="page" v-model:page-size="size" :page-sizes="[8,16,20,50]" layout="total, sizes, prev, pager, next" :total="rows.length"/></div></div><el-dialog v-model="dialog" @click="hideContextMenu" :title="`${mode==='edit'?'编辑':'查看'} ${current?.name||''}`" width="980px" class="skill-dialog" :lock-scroll="false"><div class="skill-editor"><aside><strong>技能文件</strong><el-tree :data="tree" node-key="path" :props="{label:'name',children:'children'}" default-expand-all highlight-current @node-click="select"><template #default="{data}"><span class="skill-file-node" @contextmenu="showContextMenu($event,data)" :title="data.path||data.name"><span>{{data.kind==='dir'?'▾':'·'}}</span><span>{{data.name}}</span></span></template></el-tree></aside><section><div class="sub-head"><strong>文件内容</strong><span>{{file?.path||'请选择文件'}}</span></div><el-input v-if="file" v-model="content" type="textarea" resize="none" :readonly="mode!=='edit'"/></section></div><div v-if="contextMenu" class="skill-context-menu" :style="{left:contextMenu.x+'px',top:contextMenu.y+'px'}" @click.stop><button @click="createFile">新建文件</button><button @click="createDirectory">新建目录</button><button class="danger" v-if="contextMenu.nodePath" @click="deleteEntry">删除</button></div><template #footer><el-button @click="dialog=false">关闭</el-button><el-button v-if="mode==='edit'" type="primary" :disabled="!file" @click="save">保存文件</el-button></template></el-dialog>
<el-dialog v-model="createVisible" title="新增技能" width="520px" :lock-scroll="false">
  <el-form label-position="top">
    <el-form-item label="技能名称" required>
      <el-input v-model="createForm.name" placeholder="例如 my-skill" />
    </el-form-item>
    <el-form-item label="技能描述" required>
      <el-input v-model="createForm.description" type="textarea" :rows="4" placeholder="简要说明这个技能做什么" />
    </el-form-item>
  </el-form>
  <template #footer>
    <el-button @click="createVisible=false">取消</el-button>
    <el-button type="primary" :loading="creating" @click="createSkill">创建</el-button>
  </template>
</el-dialog>
</PageShell></template>
