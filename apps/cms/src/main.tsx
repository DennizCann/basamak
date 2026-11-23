import { StrictMode } from "react";
import { createRoot } from "react-dom/client";
import "@basamak/ui/src/base.css";
import { applyStoredTheme } from "@basamak/ui";
import { App } from "./App";

applyStoredTheme();
createRoot(document.getElementById("root")!).render(
  <StrictMode>
    <App />
  </StrictMode>,
);
