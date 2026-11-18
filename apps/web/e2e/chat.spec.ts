import { expect, test } from "@playwright/test";
import { adminPassword, adminUser, login } from "./helpers";

test("AI 助手可以完成一次知识问答", async ({ page }) => {
  test.setTimeout(90_000);
  await login(page, adminUser, adminPassword);
  await page.getByRole("menuitem", { name: "AI 助手" }).click();
  await page.getByRole("button", { name: "新建" }).click();
  await page.getByPlaceholder(/描述你的问题/).fill("电梯困人怎么处理？");
  await page.getByRole("button", { name: "发送" }).click();

  const assistant = page.locator(".message.assistant .markdown").last();
  await expect(assistant).toContainText(/电梯|困人|救援/, { timeout: 60_000 });
});
