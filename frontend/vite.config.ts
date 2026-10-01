import react from "@vitejs/plugin-react";
import { defineConfig } from "vitest/config";

export default defineConfig({
  plugins: [react()],
  server: {
    host: "127.0.0.1",
    port: 5174,
    proxy: {
      "/api": "http://127.0.0.1:8081",
      "/actuator": "http://127.0.0.1:8081",
      "/uploads": "http://127.0.0.1:8081",
      "/product-models": "http://127.0.0.1:8081"
    }
  },
  test: {
    environment: "jsdom",
    setupFiles: "./src/test/setup.ts"
  }
});
