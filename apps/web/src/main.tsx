import { StrictMode } from "react";
import { createRoot } from "react-dom/client";
import "@basamak/ui/src/base.css";
import { MediaProvider, applyStoredTheme } from "@basamak/ui";
import { apiBase } from "./platform";
import { App } from "./App";

applyStoredTheme();

createRoot(document.getElementById("root")!).render(
  <StrictMode>
    <MediaProvider resolve={(id) => `${apiBase}/api/public/media/${encodeURIComponent(id)}`}>
      <App />
    </MediaProvider>
  </StrictMode>,
);
