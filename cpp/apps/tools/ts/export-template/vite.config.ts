import { defineConfig } from "vitest/config";

export default defineConfig({
  // Exported Director movies bundle all assets locally; keep the dev server simple.
  server: {
    port: 5173,
    open: true,
  },
  build: {
    target: "es2022",
    sourcemap: true,
  },
  test: {
    include: ["test/**/*.test.ts"],
    environment: "node",
  },
});