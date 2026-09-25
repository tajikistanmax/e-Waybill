import ReportsView from '../ReportsView';

/** Умумӣ → «Иҷроиши ҳамлу нақл»: план/факт перевозок (legacy reportwaybillgeneral, transportation). */
export default function Page() {
  return <ReportsView tab="regional" mode="trans" />;
}
