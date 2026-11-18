import { describe, expect, it } from "vitest";
import { httpStatusMessage, toErrorMessage } from "./http";

describe("toErrorMessage", () => {
  it("keeps backend Chinese message and appends traceId", () => {
    const error: any = {
      response: { status: 500, data: { message: "系统内部错误", traceId: "trace-1" } },
    };
    expect(toErrorMessage(error)).toBe("系统内部错误（追踪号：trace-1）");
  });

  it("maps bare 500 to a friendly message", () => {
    const error: any = { response: { status: 500, data: {} } };
    expect(toErrorMessage(error)).toBe("系统繁忙，请稍后重试");
  });

  it("maps 403 to a permission message", () => {
    const error: any = { response: { status: 403, data: {} } };
    expect(toErrorMessage(error)).toBe("您没有权限执行该操作");
  });

  it("hides technical English 500 text", () => {
    const error: any = {
      response: { status: 500, data: { message: "Internal Server Error" } },
    };
    expect(toErrorMessage(error)).toBe("系统繁忙，请稍后重试");
  });

  it("maps network failures", () => {
    const error: any = { message: "Network Error" };
    expect(toErrorMessage(error)).toBe("网络连接异常，请检查网络后重试");
  });

  it("maps timeout failures", () => {
    const error: any = { message: "timeout of 10000ms exceeded" };
    expect(toErrorMessage(error)).toBe("请求超时，请稍后重试");
  });

  it("has friendly status messages", () => {
    expect(httpStatusMessage(404)).toBe("请求的资源不存在或已被删除");
    expect(httpStatusMessage(429)).toBe("操作过于频繁，请稍后再试");
  });
});
