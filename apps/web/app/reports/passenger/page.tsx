import ReportsView from '../ReportsView';

/** Мусофирбарӣ — типовые отчёты по пассажирским ПЛ (legacy /admin/report). */
export default function Page() {
  return <ReportsView tab="typed" kind="passenger" />;
}
