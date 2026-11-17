import { http } from "@/api/http";

export interface NotificationItem {
  id: string;
  typeCode: string;
  title: string;
  content?: string | null;
  bizType?: string | null;
  bizId?: string | null;
  readAt?: string | null;
  createdAt?: string | null;
}

export async function listNotifications(params: {
  unread?: boolean;
  page?: number;
  pageSize?: number;
}): Promise<{ items: NotificationItem[]; total: number }> {
  const body = (await http.get("/notifications", {
    params: { unread: params.unread ?? false, page: params.page ?? 1, pageSize: params.pageSize ?? 20 },
  })) as any;
  if (body.code !== 0) throw new Error(body.message);
  return { items: body.data.items ?? [], total: body.data.total ?? 0 };
}

export async function fetchUnreadCount(): Promise<number> {
  const body = (await http.get("/notifications/unread-count")) as any;
  if (body.code !== 0) throw new Error(body.message);
  return body.data.count ?? 0;
}

export async function markNotificationRead(id: string): Promise<void> {
  const body = (await http.put(`/notifications/${id}/read`)) as any;
  if (body.code !== 0) throw new Error(body.message);
}

export async function markAllNotificationsRead(): Promise<void> {
  const body = (await http.put("/notifications/read-all")) as any;
  if (body.code !== 0) throw new Error(body.message);
}
