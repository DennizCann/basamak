import { useEffect, useState } from "react";

export type Theme = "system" | "light" | "dark";
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

/** Theme preference (system / light / dark), persisted per browser; follows the OS setting by default. */
export function useThemePreference(): [Theme, (t: Theme) => void] {
  const [theme, setTheme] = useState<Theme>(read);
  useEffect(() => {
    applyStoredTheme(theme);
    try { localStorage.setItem(KEY, theme); } catch { /* storage may be blocked */ }
  }, [theme]);
  return [theme, setTheme];
}

/** Compact select used by the CMS header. */
export function ThemeToggle() {
  const [theme, setTheme] = useThemePreference();
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
