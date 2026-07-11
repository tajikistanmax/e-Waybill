/* Единый набор линейных иконок (наследуют currentColor). */
export const P = {
  home: 'M3 10.5 12 3l9 7.5M5 9.5V20a1 1 0 0 0 1 1h4v-6h4v6h4a1 1 0 0 0 1-1V9.5',
  doc: 'M7 3h7l5 5v11a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2zM14 3v5h5M9 13h6M9 17h6',
  docActive: 'M8 3h6l4 4v12a2 2 0 0 1-2 2H8a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2zM13 3v5h5',
  med: 'M12 3a4 4 0 0 0-4 4v3a4 4 0 0 0 8 0V7a4 4 0 0 0-4-4zM6 13v1a6 6 0 0 0 12 0v-1M12 5v3M10.5 6.5h3',
  wrench: 'M15.5 5.5a4 4 0 0 0-5.3 5.3l-6 6 2 2 6-6a4 4 0 0 0 5.3-5.3l-2.6 2.6-2-2 2.6-2.6z',
  chart: 'M4 20V4M4 20h16M8 16V11M12 16V7M16 16v-3M20 16V9',
  building: 'M4 21h16M6 21V4a1 1 0 0 1 1-1h6a1 1 0 0 1 1 1v17M14 8h3a1 1 0 0 1 1 1v12M9 7h1M9 11h1M9 15h1',
  book: 'M5 4a1 1 0 0 1 1-1h12v18H6a1 1 0 0 0-1 1V4zM9 7h6M9 11h4',
  car: 'M5 11l1.5-4.5A2 2 0 0 1 8.4 5h7.2a2 2 0 0 1 1.9 1.5L19 11M4 11h16v5a1 1 0 0 1-1 1h-1a1 1 0 0 1-1-1v-1H7v1a1 1 0 0 1-1 1H5a1 1 0 0 1-1-1v-5zM7 14h.5M16.5 14h.5',
  bell: 'M18 8a6 6 0 0 0-12 0c0 7-3 9-3 9h18s-3-2-3-9M13.7 21a2 2 0 0 1-3.4 0',
  mail: 'M4 5h16a1 1 0 0 1 1 1v12a1 1 0 0 1-1 1H4a1 1 0 0 1-1-1V6a1 1 0 0 1 1-1zM3.5 6.5 12 12l8.5-5.5',
  help: 'M12 21a9 9 0 1 0 0-18 9 9 0 0 0 0 18zM9.5 9a2.5 2.5 0 0 1 4.5 1.5c0 1.5-2 2-2 3.5M12 17h.01',
  expand: 'M8 3H5a2 2 0 0 0-2 2v3M16 3h3a2 2 0 0 1 2 2v3M8 21H5a2 2 0 0 1-2-2v-3M16 21h3a2 2 0 0 0 2-2v-3',
  chevron: 'M9 6l6 6-6 6',
  collapse: 'M15 6l-6 6 6 6',
  shield: 'M12 3l8 3v6c0 5-3.5 8-8 9-4.5-1-8-4-8-9V6l8-3zM9 12l2 2 4-4',
  check: 'M20 6 9 17l-5-5',
  alert: 'M12 3l10 18H2L12 3zM12 9v5M12 17h.01',
  user: 'M12 12a4 4 0 1 0 0-8 4 4 0 0 0 0 8zM4 21a8 8 0 0 1 16 0',
  users: 'M9 11a4 4 0 1 0 0-8 4 4 0 0 0 0 8zM2 21a7 7 0 0 1 14 0M17 3.5a4 4 0 0 1 0 7.5M22 21a7 7 0 0 0-4-6.3',
  eye: 'M2 12s4-7 10-7 10 7 10 7-4 7-10 7-10-7-10-7zM12 15a3 3 0 1 0 0-6 3 3 0 0 0 0 6z',
  eyeOff: 'M3 3l18 18M10.6 10.6a3 3 0 0 0 4.2 4.2M9.4 5.2A10.5 10.5 0 0 1 12 5c6 0 10 7 10 7a17 17 0 0 1-2.2 3M6.2 6.2A17 17 0 0 0 2 12s4 7 10 7a10 10 0 0 0 2.8-.4',
  login: 'M15 3h4a2 2 0 0 1 2 2v14a2 2 0 0 1-2 2h-4M10 17l5-5-5-5M15 12H3',
  route: 'M6 19a2 2 0 1 0 0-4 2 2 0 0 0 0 4zM18 9a2 2 0 1 0 0-4 2 2 0 0 0 0 4zM8 17h6a4 4 0 0 0 0-8H10a4 4 0 0 1 0-8h4',
  globe: 'M12 21a9 9 0 1 0 0-18 9 9 0 0 0 0 18zM3.5 9h17M3.5 15h17M12 3a13 13 0 0 1 0 18M12 3a13 13 0 0 0 0 18',
  settings: 'M4 21v-7M4 10V3M12 21v-9M12 8V3M20 21v-5M20 12V3M1 14h6M9 8h6M17 16h6',
};

export function Icon({ d, cls, style }: { d: string; cls?: string; style?: React.CSSProperties }) {
  return (
    <svg className={cls ?? 'ic'} width={18} height={18} style={style} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.7" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
      {d.split('M').filter(Boolean).map((seg, i) => <path key={i} d={'M' + seg} />)}
    </svg>
  );
}
