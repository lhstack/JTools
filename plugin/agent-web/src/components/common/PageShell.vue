<script setup>defineProps({title:String,loading:Boolean})</script>
<template><main class="page-shell" v-loading="loading"><header><h2>{{title}}</h2><div class="page-actions"><slot name="actions"/></div></header><section><slot/></section></main></template>
