import { createRouter, createWebHistory } from "vue-router";
import DefaultLayout from "@/layouts/DefaultLayout.vue";
import LoginView from "@/views/login/LoginView.vue";
import DashboardView from "@/views/dashboard/DashboardView.vue";
import TicketListView from "@/views/tickets/TicketListView.vue";
import ChatView from "@/views/chat/ChatView.vue";
import KnowledgeListView from "@/views/knowledge/KnowledgeListView.vue";
import { useAuthStore } from "@/stores/auth";

const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: "/login", name: "login", component: LoginView },
    {
      path: "/",
      component: DefaultLayout,
      redirect: "/dashboard",
      children: [
        { path: "dashboard", name: "dashboard", component: DashboardView },
        { path: "tickets", name: "tickets", component: TicketListView },
        { path: "chat", name: "chat", component: ChatView },
        { path: "knowledge", name: "knowledge", component: KnowledgeListView },
      ],
    },
  ],
});

router.beforeEach((to) => {
  const auth = useAuthStore();
  if (to.path !== "/login" && !auth.isAuthenticated) {
    return "/login";
  }
  if (to.path === "/login" && auth.isAuthenticated) {
    return "/dashboard";
  }
  return true;
});

export default router;
