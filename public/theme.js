// Light/dark theme, independent of the Windows setting. Default is "system" (follow Windows, via the
// prefers-color-scheme media query in style.css); choosing Light or Dark here overrides that.
const THEME_KEY = 'gf_theme';
const THEMES = ['system', 'light', 'dark'];

export function getTheme() {
  try {
    const v = localStorage.getItem(THEME_KEY);
    return THEMES.includes(v) ? v : 'system';
  } catch {
    return 'system';
  }
}

export function applyTheme(theme = getTheme()) {
  if (theme === 'system') document.documentElement.removeAttribute('data-theme');
  else document.documentElement.setAttribute('data-theme', theme);
}

export function setTheme(theme) {
  try {
    localStorage.setItem(THEME_KEY, theme);
  } catch {
    // private window or blocked storage - the choice just won't stick between visits
  }
  applyTheme(theme);
}

/** Cycles system -> light -> dark -> system, and applies+persists the new one. */
export function cycleTheme() {
  const next = THEMES[(THEMES.indexOf(getTheme()) + 1) % THEMES.length];
  setTheme(next);
  return next;
}
