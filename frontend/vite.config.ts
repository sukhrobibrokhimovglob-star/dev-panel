import path from "path";
import vue from "@vitejs/plugin-vue";
import { defineConfig } from "vite";

// Build backend jar'iga kiradi va /<base-path>/ ostida beriladi, shuning uchun hamma yo'llar nisbiy
export default defineConfig({
  base: "./",
  plugins: [vue()],
  resolve: {
    alias: { "@": path.resolve(__dirname, "./src") },
  },
  server: {
    port: 5180,
    // `npm run dev`: http://localhost:5180 dagi panel API'ni lokal backend'dan oladi
    proxy: {
      "/api": {
        target: process.env.DEV_PANEL_BACKEND || "http://localhost:8080",
        changeOrigin: true,
        rewrite: (p) => p.replace(/^\/api/, "/dev-panel/api"),
        // Backend cookie'ni /dev-panel yo'liga qo'yadi, dev server esa / da ishlaydi
        cookiePathRewrite: { "/dev-panel": "/" },
      },
    },
  },
  build: {
    // Committed, so the jar builds without Node (see backend/pom.xml, profile "ui")
    outDir: "../backend/src/main/resources/META-INF/devpanel-ui",
    emptyOutDir: true,
    sourcemap: false,
    chunkSizeWarningLimit: 2000,
  },
});
