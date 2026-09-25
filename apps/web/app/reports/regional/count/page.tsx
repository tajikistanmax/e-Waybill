import ReportsView from '../../ReportsView';

/** Умумӣ → «Шумораи варақаи роҳхатҳо ва автомобилҳо» + борхатҳо (legacy count_waybills, count_cargowaybills_*). */
export default function Page() {
  return <ReportsView tab="regional" mode="count" />;
}
