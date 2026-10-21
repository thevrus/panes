import { defineConfig } from "vitest/config";
import { cloudflare } from "@cloudflare/vite-plugin";

// Dev, build and preview run the Worker in workerd via the Cloudflare plugin (reads wrangler.jsonc).
// Tests are plain vitest on pure functions plus an in-memory KV fake and a stubbed fetch,
// so the plugin (and the Workers pool) stays out of the test run.
export default defineConfig({
  plugins: process.env.VITEST ? [] : [cloudflare()],
  test: { include: ["test/**/*.test.ts"] },
});
