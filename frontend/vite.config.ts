import { defineConfig } from 'vite';
import vue from '@vitejs/plugin-vue';
export default defineConfig({plugins:[vue()],server:{host:'0.0.0.0',port:5173,strictPort:true,proxy:{'/api':{target:'http://localhost:8080',changeOrigin:false}}},build:{target:'es2022'}});
