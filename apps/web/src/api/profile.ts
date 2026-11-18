import { http } from "@/api/http";

export interface UserProfile {
  contactName?: string | null;
  phoneMask?: string | null;
  projectId?: string | null;
  buildingId?: string | null;
  roomId?: string | null;
  customerId?: string | null;
  reviewStatus?: "UNSET" | "PENDING" | "APPROVED" | "REJECTED" | string | null;
  reviewRemark?: string | null;
}

export interface ProfileReview {
  userId: string;
  username?: string | null;
  displayName?: string | null;
  contactName?: string | null;
  phoneMask?: string | null;
  projectId?: string | null;
  buildingId?: string | null;
  roomId?: string | null;
  customerId?: string | null;
  reviewStatus: string;
  createdAt?: string | null;
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

export async function listPendingProfiles(): Promise<ProfileReview[]> {
  const body = (await http.get("/profile/reviews")) as any;
  if (body.code !== 0) throw new Error(body.message);
  return body.data || [];
}

export async function reviewProfile(
  userId: string,
  approved: boolean,
  remark?: string,
): Promise<void> {
  const body = (await http.post(`/profile/reviews/${userId}`, { approved, remark })) as any;
  if (body.code !== 0) throw new Error(body.message);
}
