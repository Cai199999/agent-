import { http } from "@/api/http";

export interface MasterDataOption {
  id: string;
  code: string;
  name: string;
  parentId?: string | null;
  extra?: string | null;
}

export interface MasterDataOptions {
  projects: MasterDataOption[];
  buildings: MasterDataOption[];
  rooms: MasterDataOption[];
  customers: MasterDataOption[];
  equipment: MasterDataOption[];
  serviceItems: MasterDataOption[];
}

/** 主数据只读选项；后端按表分别查询，前端按 parentId 级联过滤。 */
export async function getMasterDataOptions(): Promise<MasterDataOptions> {
  const body = (await http.get("/master-data/options")) as any;
  if (body.code !== 0) throw new Error(body.message);
  return body.data;
}
