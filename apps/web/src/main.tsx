import { StrictMode } from "react";
import { createRoot } from "react-dom/client";
import { App as NativeApp } from "@capacitor/app";
import "@basamak/ui/src/base.css";
import { applyStoredTheme } from "@basamak/ui";
import { App } from "./App";
import { isMobile } from "./platform";

applyStoredTheme();

if (isMobile) {
  // Android system back: go back inside the app; only leave the app from the first screen.
  NativeApp.addListener("backButton", ({ canGoBack }) => {
    if (canGoBack) window.history.back();
    else NativeApp.exitApp();
  });
}

createRoot(document.getElementById("root")!).render(
  <StrictMode>
    <App />
  </StrictMode>,
);
