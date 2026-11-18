import { http } from "@/api/http";

export interface UserProfile {
  contactName?: string | null;
  phoneMask?: string | null;
  projectId?: string | null;
  buildingId?: string | null;
  roomId?: string | null;
  customerId?: string | null;
}

export interface UpdateUserProfile {
  contactName?: string | null;
  phone?: string | null;
  projectId?: string | null;
  buildingId?: string | null;
  roomId?: string | null;
  customerId?: string | null;
}

export async function getProfile(): Promise<UserProfile> {
  const body = (await http.get("/profile")) as any;
  if (body.code !== 0) throw new Error(body.message);
  return body.data || {};
}

export async function updateProfile(payload: UpdateUserProfile): Promise<UserProfile> {
  const body = (await http.put("/profile", payload)) as any;
  if (body.code !== 0) throw new Error(body.message);
  return body.data || {};
}
