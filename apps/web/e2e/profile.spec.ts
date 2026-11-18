import { expect, test } from "@playwright/test";
import { adminPassword, adminUser, login } from "./helpers";

test("个人中心可打开并展示资料表单", async ({ page }) => {
  await login(page, adminUser, adminPassword);
  await page.getByRole("button", { name: /系统管理员|admin/ }).click();
  await expect(page).toHaveURL(/\/profile/);
  await expect(page.getByRole("heading", { name: "个人中心" })).toBeVisible();
  await expect(page.getByLabel("联系人")).toBeVisible();
  await expect(page.getByRole("button", { name: "保存" })).toBeVisible();
});
