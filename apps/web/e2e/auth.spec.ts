import { expect, test } from "@playwright/test";
import { adminPassword, adminUser, login } from "./helpers";

test("admin 可以登录并进入工单列表", async ({ page }) => {
  await login(page, adminUser, adminPassword);
  await expect(page).toHaveURL(/\/tickets/);
  await expect(page.getByRole("heading", { name: "工单管理" })).toBeVisible();
  await expect(page.getByRole("button", { name: "导出" })).toBeVisible();
});
