import type { Page } from "@playwright/test";
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "../../..");

function envValue(key: string): string {
  const envPath = path.join(root, ".env");
  if (!fs.existsSync(envPath)) return "";
  for (const line of fs.readFileSync(envPath, "utf8").split(/\r?\n/)) {
    const trimmed = line.trim();
    if (!trimmed || trimmed.startsWith("#") || !trimmed.includes("=")) continue;
    const [name, ...rest] = trimmed.split("=");
    if (name.trim() === key) return rest.join("=").trim().replace(/^["']|["']$/g, "");
  }
  return "";
}

export const adminPassword = process.env.E2E_ADMIN_PASSWORD || envValue("DEV_ADMIN_PASSWORD");
export const adminUser = process.env.E2E_ADMIN_USER || envValue("DEV_ADMIN_USERNAME") || "admin";
export const reporterPassword = process.env.E2E_REPORTER_PASSWORD || adminPassword;

export async function login(page: Page, username: string, password: string): Promise<void> {
  await page.goto("/login");
  await page.getByLabel("账号").fill(username);
  await page.getByLabel("密码").fill(password);
  await page.getByRole("button", { name: "登录" }).click();
  await page.waitForURL("**/tickets");
}
