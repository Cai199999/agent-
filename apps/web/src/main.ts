import { createApp } from "vue";
import { createPinia } from "pinia";
import ElementPlus from "element-plus";
import "element-plus/dist/index.css";
import App from "./App.vue";
import router from "./router";
import { useAuthStore } from "./stores/auth";
import "./styles/index.scss";

async function bootstrap(): Promise<void> {
  const app = createApp(App);
  app.use(createPinia());
  const auth = useAuthStore();
  if (auth.accessToken) {
    try {
      await auth.restore();
    } catch {
      auth.logout();
    }
  }
  app.use(router);
  app.use(ElementPlus);
  app.mount("#app");
}

void bootstrap();
