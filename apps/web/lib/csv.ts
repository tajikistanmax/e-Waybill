// Выгрузка таблиц в CSV, который Excel открывает напрямую (UTF-8 BOM + разделитель «;»).

/** Экранирование ячейки CSV (разделитель «;» — как ждёт Excel в RU-локали). */
export function csvCell(v: unknown): string {
  const s = v == null ? '' : String(v);
  return /[";\n]/.test(s) ? '"' + s.replace(/"/g, '""') + '"' : s;
}

/** Скачать CSV с UTF-8 BOM — Excel открывает напрямую и корректно показывает кириллицу. */
export function downloadCsv(filename: string, rows: unknown[][]) {
  const text = rows.map(r => r.map(csvCell).join(';')).join('\r\n');
  const blob = new Blob(['﻿' + text], { type: 'text/csv;charset=utf-8;' });
  const url = URL.createObjectURL(blob);
  const a = document.createElement('a');
  a.href = url; a.download = filename; a.click();
  URL.revokeObjectURL(url);
}
