import { StrictMode } from "react";
import { createRoot } from "react-dom/client";
import "@basamak/ui/src/base.css";
import { MediaProvider, applyStoredTheme } from "@basamak/ui";
import { OfflineMediaProvider } from "./offlineMedia";
import { apiBase, isMobile } from "./platform";
import { App } from "./App";

applyStoredTheme();

createRoot(document.getElementById("root")!).render(
  <StrictMode>
    {isMobile ? (
      <OfflineMediaProvider><App /></OfflineMediaProvider>
    ) : (
      <MediaProvider resolve={(id) => `${apiBase}/api/public/media/${encodeURIComponent(id)}`}>
        <App />
      </MediaProvider>
    )}
  </StrictMode>,
);
