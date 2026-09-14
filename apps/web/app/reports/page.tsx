import { redirect } from 'next/navigation';

/** /reports → «Сводка» (у каждого отчёта свой адрес). */
export default function ReportsIndex() {
  redirect('/reports/summary');
}
