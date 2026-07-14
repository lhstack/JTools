import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import { resolve } from 'node:path'
export default defineConfig({plugins:[vue()],base:'./',build:{outDir:resolve(__dirname,'../src/main/resources/agent-web'),emptyOutDir:true,cssCodeSplit:false,rollupOptions:{output:{entryFileNames:'app.js',assetFileNames:'app.[ext]'}}}})
