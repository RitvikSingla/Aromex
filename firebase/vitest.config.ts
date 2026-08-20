import { defineConfig } from "vitest/config";

// Rules tests only. The functions/ codebase has its own vitest run.
export default defineConfig({
  test: {
    include: ["tests/**/*.test.ts"],
    testTimeout: 15000,
    hookTimeout: 15000,
  },
});
