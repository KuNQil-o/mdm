import { defineConfig } from "vite";
import vue from "@vitejs/plugin-vue";
export default defineConfig({
  plugins: [vue()],
  server: {
    port: 5173,
    proxy: {
      "/erp": "http://localhost:9092",
      "/api": "http://localhost:8080",
      "/actuator": "http://localhost:8080",
    },
  },
});
