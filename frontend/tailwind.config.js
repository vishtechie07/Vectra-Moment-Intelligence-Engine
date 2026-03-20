/** @type {import('tailwindcss').Config} */
export default {
  content: [
    "./index.html",
    "./src/**/*.{vue,js,ts,jsx,tsx}",
  ],
  theme: {
    extend: {
      fontFamily: {
        sans: ['Plus Jakarta Sans', 'system-ui', 'sans-serif'],
      },
      colors: {
        brand: {
          50: '#f5f3ff',
          100: '#ede9fe',
          200: '#ddd6fe',
          300: '#c4b5fd',
          400: '#a78bfa',
          500: '#8b5cf6',
          600: '#7c3aed',
          700: '#6d28d9',
          800: '#5b21b6',
          900: '#4c1d95',
        },
        accent: {
          coral: '#f43f5e',
          emerald: '#10b981',
          amber: '#f59e0b',
        },
      },
      boxShadow: {
        'glow': '0 0 40px -12px rgba(139, 92, 246, 0.4)',
        'card': '0 4px 24px -4px rgba(0,0,0,0.08), 0 8px 16px -6px rgba(0,0,0,0.04)',
        'card-hover': '0 12px 40px -8px rgba(0,0,0,0.12), 0 4px 16px -4px rgba(0,0,0,0.06)',
      },
      backgroundImage: {
        'gradient-brand': 'linear-gradient(135deg, #8b5cf6 0%, #6366f1 50%, #4f46e5 100%)',
        'gradient-mesh': 'radial-gradient(at 40% 20%, rgba(139, 92, 246, 0.15) 0px, transparent 50%), radial-gradient(at 80% 0%, rgba(99, 102, 241, 0.12) 0px, transparent 50%), radial-gradient(at 0% 50%, rgba(236, 72, 153, 0.08) 0px, transparent 50%)',
      },
    },
  },
  plugins: [],
}
