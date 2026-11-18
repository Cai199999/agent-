import { defineConfig } from "@playwright/test";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";

const cachedChromium = process.env.PLAYWRIGHT_CHROMIUM_PATH
  || path.join(os.homedir(), ".cache/ms-playwright/chromium-1254/chrome-linux64/chrome");
const launchOptions = fs.existsSync(cachedChromium)
  ? { executablePath: cachedChromium, args: ["--no-sandbox"] }
  : { args: ["--no-sandbox"] };

export default defineConfig({
  testDir: "./e2e",
  timeout: 60_000,
  fullyParallel: false,
  workers: 1,
  reporter: [["list"]],
  use: {
    baseURL: "http://127.0.0.1:5180",
    headless: true,
    trace: "retain-on-failure",
    screenshot: "only-on-failure",
    launchOptions,
  },
  webServer: {
    command: "npm run dev",
    url: "http://127.0.0.1:5180",
    reuseExistingServer: true,
    timeout: 60_000,
  },
});
