import test from 'node:test'
import assert from 'node:assert/strict'
import {effectiveSelection,updateSelection} from '../src/resourceSelection.js'
test('include new shows effective selection and preserves sparse overrides on unchanged save',()=>{
  const known=['explicit','off','new'];const overrides={explicit:true,off:false}
  const selected=effectiveSelection(known,overrides,true)
  assert.deepEqual(selected,['explicit','new'])
  assert.deepEqual(updateSelection(known,overrides,true,selected),overrides)
})
test('explicit removal survives include-new changes and new catalog entries',()=>{
  const overrides=updateSelection(['a','b'],{},true,['a'])
  assert.deepEqual(overrides,{b:false})
  assert.deepEqual(effectiveSelection(['a','b','c'],overrides,true),['a','c'])
  assert.deepEqual(effectiveSelection(['a','b','c'],overrides,false),[])
})
test('search selection updates only visible entries and preserves invisible or unavailable overrides',()=>{
  assert.deepEqual(updateSelection(['visible'],{hidden:true,missing:false},false,['visible']),{hidden:true,missing:false,visible:true})
})
