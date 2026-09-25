import { redirect } from 'next/navigation';

/** Старый адрес «Разрезы Роҳхат» — типовые отчёты разделены на Мусофирбарӣ и Боркашонӣ. */
export default function Page() {
  redirect('/reports/passenger');
}
