import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";

// Dev: /api is proxied to the Spring Boot backend so cookies stay same-origin.
export default defineConfig({
  plugins: [react()],
  server: {
    proxy: { "/api": "http://localhost:8080" },
  },
});
