<script setup>defineProps({title:String,loading:Boolean})</script>
<template><main class="page-shell" v-loading="loading"><header><h2>{{title}}</h2><slot name="actions"/></header><section><slot/></section></main></template>
