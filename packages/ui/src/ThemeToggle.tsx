import { useEffect, useState } from "react";

type Theme = "system" | "light" | "dark";
const KEY = "basamak-theme";

function read(): Theme {
  try {
    const v = localStorage.getItem(KEY);
    return v === "light" || v === "dark" ? v : "system";
  } catch {
    return "system";
  }
}

export function applyStoredTheme(theme: Theme = read()) {
  const el = document.documentElement;
  if (theme === "system") el.removeAttribute("data-theme");
  else el.setAttribute("data-theme", theme);
}

/** Follows the OS setting by default; the member can override it. */
export function ThemeToggle() {
  const [theme, setTheme] = useState<Theme>(read);
  useEffect(() => {
    applyStoredTheme(theme);
    try { localStorage.setItem(KEY, theme); } catch { /* storage may be blocked */ }
  }, [theme]);
  return (
    <label className="field" style={{ flexDirection: "row", alignItems: "center", gap: "var(--space-2)" }}>
      <span>Tema</span>
      <select value={theme} onChange={(e) => setTheme(e.target.value as Theme)} aria-label="Tema seçimi">
        <option value="system">Sistem</option>
        <option value="light">Açık</option>
        <option value="dark">Koyu</option>
      </select>
    </label>
  );
}
