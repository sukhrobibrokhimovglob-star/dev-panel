import { createApp } from "vue";
import { createPinia } from "pinia";
import ElementPlus from "element-plus";
import "element-plus/dist/index.css";
import "element-plus/theme-chalk/dark/css-vars.css";
import "./style.css";
import { i18n } from "./i18n";
import App from "./App.vue";

createApp(App).use(createPinia()).use(i18n).use(ElementPlus).mount("#app");
