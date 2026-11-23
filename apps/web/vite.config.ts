import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";

// Dev: /api is proxied to the backend so cookies stay same-origin.
// API_TARGET lets the isolated e2e stack point at its own backend (:8081).
export default defineConfig({
  plugins: [react()],
  server: {
    proxy: { "/api": process.env.API_TARGET || "http://localhost:8080" },
  },
});
