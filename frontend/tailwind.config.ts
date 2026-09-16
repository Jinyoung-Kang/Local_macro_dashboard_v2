import type { Config } from "tailwindcss";

/**
 * 구버전 Streamlit 앱의 다크 핀테크 팔레트를 그대로 옮겼습니다.
 *   배경 #0D1117 · 카드 #161B22 · 테두리 #30363D · 강조 #58A6FF
 */
const config: Config = {
  content: ["./src/**/*.{ts,tsx}"],
  theme: {
    extend: {
      colors: {
        canvas: "#0D1117",
        surface: "#161B22",
        "surface-hover": "#21262D",
        border: "#30363D",
        muted: "#8B949E",
        body: "#C9D1D9",
        bright: "#F0F6FC",
        accent: "#58A6FF",
        up: "#F85149",      // 한국 관행: 상승 = 빨강
        down: "#4493F8",    // 하락 = 파랑
        ok: "#3FB950",
        warn: "#D29922",
        danger: "#F85149",
      },
      fontFamily: {
        sans: ["Pretendard", "-apple-system", "BlinkMacSystemFont", "Segoe UI", "sans-serif"],
        mono: ["SFMono-Regular", "Menlo", "Consolas", "monospace"],
      },
    },
  },
  plugins: [],
};

export default config;
