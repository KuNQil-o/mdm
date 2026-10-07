declare const process: { env: Record<string, string | undefined> };
import { defineConfig } from '@playwright/test';
export default defineConfig({testDir:'./e2e',timeout:60000,workers:1,reporter:[['list'],['json',{outputFile:'../.runtime/browser-results.json'}]],use:{baseURL:'http://localhost:5173',viewport:{width:1440,height:1000},timezoneId:'Asia/Shanghai',launchOptions:{executablePath:process.env.CHROMIUM_PATH||'/usr/bin/chromium',args:['--no-sandbox']},trace:'retain-on-failure'},outputDir:'../.runtime/browser-artifacts'});
