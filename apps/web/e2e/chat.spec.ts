import { expect, test } from "@playwright/test";
import { adminPassword, adminUser, login } from "./helpers";

test("AI 助手可以完成一次知识问答", async ({ page }) => {
  test.setTimeout(90_000);
  await login(page, adminUser, adminPassword);
  await page.getByRole("menuitem", { name: "AI 助手" }).click();
  await page.getByRole("button", { name: "新建" }).click();
  // 新建会话成功后组件会清空输入框，先等会话号出现再填写，避免竞态。
  await expect(page.locator(".session-no")).toContainText("SESS", { timeout: 15_000 });
  await page.getByPlaceholder(/描述你的问题/).fill("电梯困人怎么处理？");
  const send = page.getByRole("button", { name: "发送" });
  await expect(send).toBeEnabled({ timeout: 10_000 });
  await send.click();

  const assistant = page.locator(".message.assistant .markdown").last();
  await expect(assistant).toContainText(/电梯|困人|救援/, { timeout: 60_000 });
});
