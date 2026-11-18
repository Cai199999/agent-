import { expect, test } from "@playwright/test";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { adminPassword, adminUser, login } from "./helpers";

const fixture = path.join(path.dirname(fileURLToPath(import.meta.url)), "fixtures/onsite.png");

test("简化报修表单可以创建带图片的室内工单", async ({ page }) => {
  await login(page, adminUser, adminPassword);
  await page.getByRole("button", { name: "新建工单" }).click();
  await expect(page.getByRole("dialog")).toContainText("报修 / 报事");

  const description = `E2E 浏览器建单 ${Date.now()}：A栋2号梯异响`;
  await page.getByPlaceholder(/请描述故障现象/).fill(description);

  const buildingItem = page.locator(".el-form-item").filter({ hasText: "楼宇" });
  await buildingItem.locator(".el-select").click();
  await page.locator(".el-select-dropdown__item").filter({ hasText: "A 栋" }).first().click();

  const roomItem = page.locator(".el-form-item").filter({ hasText: "房间" });
  await roomItem.locator(".el-select").click();
  await page.locator(".el-select-dropdown__item").filter({ hasText: "1单元 101" }).first().click();

  await page.getByPlaceholder("如 A栋2号梯 / 东门地下车库").fill("A栋2号梯");
  await page.locator(".el-dialog input[type=file]").setInputFiles(fixture);
  await page.getByRole("button", { name: "提交" }).click();

  await expect(page.getByText(/工单已创建/)).toBeVisible({ timeout: 15_000 });
});
