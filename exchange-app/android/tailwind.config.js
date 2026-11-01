// Mirrors the inline `tailwind.config` in desktop/app-source/index.html, which the
// web/desktop builds hand to the Tailwind Play CDN at runtime. The offline APK
// can't load the CDN, so the same theme is compiled to static CSS at build time.
module.exports = {
  content: ['../desktop/app-source/index.html'],
  theme: {
    extend: {
      colors: {
        ink: { 950: '#0B0F14', 900: '#10151C', 850: '#161D26', 800: '#1D2531', 700: '#2A3441', 600: '#3B4756', 500: '#57667A' },
        paper: { DEFAULT: '#F3EEE2', dim: '#B9B2A2' },
        brass: { DEFAULT: '#D6A24C', bright: '#F0C374', dim: '#8A6B2E' },
        teal: { DEFAULT: '#3E9C8D', bright: '#5FCBB9', dim: '#295C54' },
        rose: { DEFAULT: '#E2574C', bright: '#F1857C' },
      },
      fontFamily: {
        display: ['Fraunces', 'serif'],
        sans: ['Inter', 'sans-serif'],
        mono: ['"IBM Plex Mono"', 'monospace'],
      },
    },
  },
};
