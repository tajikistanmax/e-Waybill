import ReportsView from '../ReportsView';

/** Боркашонӣ — типовые отчёты по грузовым ПЛ (legacy /admin/reportwaybillcargo). */
export default function Page() {
  return <ReportsView tab="typed" kind="cargo" />;
}
