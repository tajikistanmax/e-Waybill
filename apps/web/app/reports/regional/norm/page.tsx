import ReportsView from '../../ReportsView';

/** Умумӣ → норматив выдачи ПЛ на стоянку (legacy count_waybills_type2, must_give). */
export default function Page() {
  return <ReportsView tab="regional" mode="norm" />;
}
