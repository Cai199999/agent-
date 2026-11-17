export interface ApiResponse<T> {
  code: number;
  message: string;
  data: T;
  traceId: string;
}

export interface PageResult<T> {
  items: T[];
  total: number;
  page: number;
  pageSize: number;
}
