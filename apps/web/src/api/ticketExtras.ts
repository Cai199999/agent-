import { http } from "@/api/http";

export interface TicketMaterial {
  id: string;
  materialName: string;
  specification?: string | null;
  quantity?: number | null;
  unit?: string | null;
  amount?: number | null;
  remark?: string | null;
  createdAt?: string | null;
}

export interface TicketVisit {
  id: string;
  required: boolean;
  visitMethod?: string | null;
  visitorId?: string | null;
  visitorName?: string | null;
  visitedAt?: string | null;
  visitResult?: string | null;
  status: string;
  remark?: string | null;
}

export async function listMaterials(ticketId: string): Promise<TicketMaterial[]> {
  const body = (await http.get(`/tickets/${ticketId}/materials`)) as any;
  if (body.code !== 0) throw new Error(body.message);
  return body.data || [];
}

export async function addMaterial(ticketId: string, payload: Record<string, unknown>): Promise<TicketMaterial> {
  const body = (await http.post(`/tickets/${ticketId}/materials`, payload)) as any;
  if (body.code !== 0) throw new Error(body.message);
  return body.data;
}

export async function listVisits(ticketId: string): Promise<TicketVisit[]> {
  const body = (await http.get(`/tickets/${ticketId}/visits`)) as any;
  if (body.code !== 0) throw new Error(body.message);
  return body.data || [];
}

export async function addVisit(ticketId: string, payload: Record<string, unknown>): Promise<TicketVisit> {
  const body = (await http.post(`/tickets/${ticketId}/visits`, payload)) as any;
  if (body.code !== 0) throw new Error(body.message);
  return body.data;
}
