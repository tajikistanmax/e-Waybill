/**
 * Конфигурируемые колонки отчётов (MIGRATION.md 7.4 / 10.5): набор и подписи колонок типовых и сводных отчётов
 * берутся из настроек платформы категории «reports» (Настройки → Отчёты), а не из кода.
 *
 * - `<family>_columns` — ключи видимых колонок через запятую; пусто/неизвестные ключи → все колонки по умолчанию
 *   (порядок — как в настройке, чтобы можно было переставлять);
 * - `<family>_labels` — JSON {"ключ": "подпись"}; невалидный JSON или не-строки игнорируются.
 */
export type ColumnDef<K extends string> = { key: K; label: string };

/** Ключи из настройки «видимые колонки»: trim, без пустых, без дублей. */
export function parseVisible(csv: string | null | undefined): string[] {
  if (!csv) return [];
  const out: string[] = [];
  for (const raw of csv.split(',')) {
    const k = raw.trim();
    if (k && !out.includes(k)) out.push(k);
  }
  return out;
}

/** Подписи из настройки-JSON; невалидный JSON, не-объект или не-строковые значения → отбрасываются. */
export function parseLabels(json: string | null | undefined): Record<string, string> {
  if (!json || !json.trim()) return {};
  try {
    const v = JSON.parse(json) as unknown;
    if (!v || typeof v !== 'object' || Array.isArray(v)) return {};
    const out: Record<string, string> = {};
    for (const [k, val] of Object.entries(v as Record<string, unknown>)) {
      if (typeof val === 'string' && val.trim()) out[k] = val.trim();
    }
    return out;
  } catch {
    return {};
  }
}

/**
 * Применить настройки к колонкам по умолчанию: оставить только видимые (в порядке настройки; если ни один ключ
 * не распознан — все по умолчанию) и подменить подписи. Колонка `keep` (напр. «label») остаётся всегда первой.
 */
export function applyColumnConfig<K extends string>(
  defaults: ColumnDef<K>[], visibleCsv: string | null | undefined, labelsJson: string | null | undefined, keep?: K,
): ColumnDef<K>[] {
  const labels = parseLabels(labelsJson);
  const byKey = new Map(defaults.map(c => [c.key as string, c]));
  const wanted = parseVisible(visibleCsv).filter(k => byKey.has(k));
  let cols: ColumnDef<K>[] = wanted.length === 0 ? defaults : wanted.map(k => byKey.get(k)!);
  if (keep && !cols.some(c => c.key === keep) && byKey.has(keep)) cols = [byKey.get(keep)!, ...cols];
  return cols.map(c => (labels[c.key] ? { ...c, label: labels[c.key] } : c));
}
