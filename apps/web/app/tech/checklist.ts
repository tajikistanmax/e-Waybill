/**
 * Чек-лист предрейсового техконтроля (титул Т3) — единый источник для АРМ механика
 * и журнала техконтроля: осмотр пишет ключи в data титула, журнал их читает и
 * расшифровывает теми же подписями. Расхождение списков означало бы, что в журнале
 * пункт показан как «неизвестный ключ», поэтому список ровно один.
 */

export type CheckItem = { key: string; label: string; desc: string };

/** label — строка пункта, desc — расшифровка (подсказка при наведении). */
export const CHECK_ITEMS: CheckItem[] = [
  { key: 'brakes', label: 'tech.chk.brakes', desc: 'tech.chk.brakes.d' },
  { key: 'lights', label: 'tech.chk.lights', desc: 'tech.chk.lights.d' },
  { key: 'tires', label: 'tech.chk.tires', desc: 'tech.chk.tires.d' },
  { key: 'steering', label: 'tech.chk.steering', desc: 'tech.chk.steering.d' },
  { key: 'fluids', label: 'tech.chk.fluids', desc: 'tech.chk.fluids.d' },
  { key: 'mirrors', label: 'tech.chk.mirrors', desc: 'tech.chk.mirrors.d' },
  { key: 'firstaid', label: 'tech.chk.firstaid', desc: 'tech.chk.firstaid.d' },
  { key: 'extinguisher', label: 'tech.chk.extinguisher', desc: 'tech.chk.extinguisher.d' },
  { key: 'documents', label: 'tech.chk.documents', desc: 'tech.chk.documents.d' },
  { key: 'general', label: 'tech.chk.general', desc: 'tech.chk.general.d' },
];

/** Группировка по узлам — для читаемости при осмотре (порядок = приоритет проверки). */
export const CHECK_GROUPS: { title: string; keys: string[] }[] = [
  { title: 'tech.grp.control', keys: ['brakes', 'steering', 'lights', 'tires', 'mirrors'] },
  { title: 'tech.grp.units', keys: ['fluids', 'general'] },
  { title: 'tech.grp.kit', keys: ['firstaid', 'extinguisher', 'documents'] },
];

/** Замеры, которые механик снимает у машины. Ключи те же, что пишет АРМ в data титула Т3. */
export const METER_FIELDS: { key: string; label: string }[] = [
  { key: 'odometer', label: 'tech.m.odometer' },
  { key: 'fuelPercent', label: 'tech.m.fuel' },
  { key: 'tirePressureBar', label: 'tech.m.tires' },
];

/** Служебные ключи data титула Т3 — не пункты чек-листа и не замеры. */
export const TITLE_META_KEYS = new Set(['verdict', 'employeeName', 'employeeRma', 'dispatcher', 'notes']);
