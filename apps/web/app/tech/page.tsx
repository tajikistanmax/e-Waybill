'use client';

import { useCallback, useEffect, useState, type CSSProperties, type ReactNode } from 'react';
import { md, wb, Waybill, TYPE_LABELS } from '@/lib/api';
import { Icon, P } from '../icons';

type CheckItem = { key: string; label: string; desc: string; icon: string };

/** Чек-лист предрейсового техосмотра — точь-в-точь по эталону. */
const CHECK_ITEMS: CheckItem[] = [
  { key: 'brakes', label: 'Тормозная система', desc: 'Работа тормозов, стояночный тормоз, пневмосистема', icon: P.alert },
  { key: 'lights', label: 'Осветительные приборы и световая сигнализация', desc: 'Фары ближнего/дальнего света, габариты, стоп-сигналы, поворотники, аварийка', icon: P.help },
  { key: 'tires', label: 'Шины и колёса', desc: 'Состояние шин, давление, крепление колёс', icon: P.car },
  { key: 'steering', label: 'Рулевое управление', desc: 'Рулевой механизм, люфт рулевого колеса', icon: P.globe },
  { key: 'fluids', label: 'Стеклоомывающие жидкости и уровни', desc: 'Уровень масла, охлаждающей жидкости, тормозной жидкости, стеклоомывающей', icon: P.chart },
  { key: 'mirrors', label: 'Зеркала заднего вида', desc: 'Наличие, целостность, правильность установки', icon: P.eye },
  { key: 'firstaid', label: 'Аптечка первой помощи', desc: 'Наличие, комплектность, срок годности', icon: P.med },
  { key: 'extinguisher', label: 'Огнетушитель', desc: 'Наличие, срок годности, пломба, крепление', icon: P.shield },
  { key: 'documents', label: 'Сопроводительные документы', desc: 'СТС, полис ОСАГО, диагностическая карта, путевой лист', icon: P.doc },
  { key: 'general', label: 'Общее техническое состояние', desc: 'Кузов, стёкла, дворники, сцепное устройство, дополнительные замечания', icon: P.wrench },
];

// Стили тумблеров «Исправно / Неисправно»
const tgBase: CSSProperties = { display: 'inline-flex', alignItems: 'center', gap: 6, padding: '7px 13px', borderRadius: 8, fontSize: 12.5, fontWeight: 600, cursor: 'pointer', border: '1px solid var(--line)', fontFamily: 'inherit', background: '#fff', color: 'var(--faint)', whiteSpace: 'nowrap' };
const okActive: CSSProperties = { ...tgBase, borderColor: 'var(--green)', background: 'var(--green-050)', color: 'var(--green)' };
const badActive: CSSProperties = { ...tgBase, borderColor: 'var(--red)', background: 'var(--red-050)', color: 'var(--red)' };

function Field({ label, children }: { label: string; children: ReactNode }) {
  return (
    <div style={{ minWidth: 0 }}>
      <div className="k-label">{label}</div>
      <div style={{ marginTop: 7 }}>{children}</div>
    </div>
  );
}

const fmt = (s: string | null | undefined) =>
  s ? new Date(s).toLocaleString('ru-RU', { day: '2-digit', month: '2-digit', year: 'numeric', hour: '2-digit', minute: '2-digit' }) : '—';

/** АРМ механика (контролёр техсостояния): предрейсовый техконтроль Т3. */
export default function TechWorkstation() {
  const [queue, setQueue] = useState<Waybill[]>([]);
  const [mechanics, setMechanics] = useState<Record<string, { rma: string; name: string }[]>>({});
  const [selected, setSelected] = useState<Waybill | null>(null);
  const [checks, setChecks] = useState<Record<string, boolean>>({});
  const [defects, setDefects] = useState('');
  const [error, setError] = useState('');
  const [ok, setOk] = useState('');

  const reload = useCallback(async () => {
    const all = await wb.list();
    setQueue(all.filter(w => w.status === 'CREATED' && !w.techPassed));
  }, []);

  useEffect(() => { reload().catch(e => setError(e.message)); }, [reload]);

  async function open(w: Waybill) {
    setSelected(w);
    setChecks(Object.fromEntries(CHECK_ITEMS.map(i => [i.key, true])));
    setDefects('');
    setOk('');
    setError('');
    if (!mechanics[w.organizationRma]) {
      const list = await md.employees(w.organizationRma);
      setMechanics(m => ({
        ...m,
        [w.organizationRma]: list.filter(e => e.type === 2).map(e => ({ rma: String(e.rma), name: String(e.name) })),
      }));
    }
  }

  async function decide(passed: boolean) {
    if (!selected) return;
    const mechanic = mechanics[selected.organizationRma]?.[0];
    if (!mechanic) { setError('В организации нет зарегистрированного механика'); return; }
    setError('');
    try {
      const checklist: Record<string, string> = {};
      for (const item of CHECK_ITEMS) checklist[item.key] = checks[item.key] ? 'OK' : 'НЕИСПРАВНО';
      if (defects) checklist['notes'] = defects;
      await wb.post(`/${selected.id}/confirm-tech`, { employeeRma: mechanic.rma, passed, checklist });
      setOk(passed
        ? `Т3 подписан: ТС ${selected.vehicleRegNumber} допущено к рейсу (механик ${mechanic.name})`
        : `ТС ${selected.vehicleRegNumber} не допущено — выезд запрещён`);
      setSelected(null);
      await reload();
    } catch (e) {
      setError((e as Error).message);
    }
  }

  const banners = (
    <>
      {error && <div className="error">{error}</div>}
      {ok && <div className="success">{ok}</div>}
    </>
  );

  // ============================ РАБОЧИЙ ЭКРАН ОСМОТРА ============================
  if (selected) {
    const w = selected;
    const v = (w.vehicleSnapshot ?? {}) as Record<string, unknown>;
    const dr = (w.driverSnapshot ?? {}) as Record<string, unknown>;
    const org = (w.organizationSnapshot ?? {}) as Record<string, unknown>;
    const cats = Array.isArray(dr.licenseCategories)
      ? (dr.licenseCategories as unknown[]).join(', ')
      : String(dr.licenseCategories ?? '—');
    const failed = CHECK_ITEMS.filter(i => checks[i.key] === false);

    return (
      <>
        <div style={{ display: 'flex', alignItems: 'flex-start', marginBottom: 4 }}>
          <div>
            <h1>Предрейсовый технический осмотр</h1>
            <div className="tb-crumb" style={{ fontSize: 12.5, color: 'var(--muted)' }}>
              Главная панель / Техосмотры / Новый осмотр
            </div>
          </div>
          <span style={{ marginLeft: 'auto', display: 'inline-flex', gap: 16, alignItems: 'center', color: 'var(--muted)', fontSize: 12.5 }}>
            <span style={{ display: 'inline-flex', alignItems: 'center', gap: 6 }}><Icon d={P.doc} style={{ width: 15, height: 15 }} /> {fmt(w.validFrom ?? w.createdAt)}</span>
          </span>
        </div>
        <div style={{ marginTop: 16 }}>{banners}</div>

        {/* Карточка-сводка */}
        <div className="card">
          <div style={{ display: 'grid', gridTemplateColumns: 'auto 1.2fr 1.2fr 1.5fr 1.1fr 1fr', gap: 22, alignItems: 'start' }}>
            <Field label="Транспортное средство">
              <div style={{ display: 'flex', flexDirection: 'column', gap: 8, alignItems: 'flex-start' }}>
                <span className="plate">
                  <span className="p-main">{w.vehicleRegNumber}</span>
                  <span className="p-reg" style={{ lineHeight: 1 }}>01<br /><span style={{ fontSize: 8 }}>RUS</span></span>
                </span>
                <span className="badge green">Активно</span>
              </div>
            </Field>
            <Field label="Марка / Модель">
              <div style={{ fontWeight: 600, color: 'var(--ink)', fontSize: 14 }}>{String(v.brand ?? '—')}</div>
              <div style={{ color: 'var(--muted)', fontSize: 12, marginTop: 4 }}>VIN: {String(v.vincode ?? '—')}</div>
            </Field>
            <Field label="Компания">
              <div style={{ fontWeight: 600, color: 'var(--ink)', fontSize: 14 }}>{String(org.name ?? w.organizationRma)}</div>
              <div style={{ color: 'var(--muted)', fontSize: 12, marginTop: 4 }}>Код: {String(org.code ?? w.organizationRma)}</div>
            </Field>
            <Field label="Водитель">
              <div style={{ fontWeight: 600, color: 'var(--ink)', fontSize: 14 }}>{String(dr.fullName ?? w.driverRma)}</div>
              <div style={{ color: 'var(--muted)', fontSize: 12, marginTop: 4 }}>ВУ: {String(dr.licenseNumber ?? '—')}</div>
              <div style={{ color: 'var(--muted)', fontSize: 12, marginTop: 2 }}>Категории: {cats}</div>
            </Field>
            <Field label="Путевой лист">
              <div className="number">{w.number ?? '—'}</div>
              <div style={{ marginTop: 6 }}><span className="badge blue">Назначен</span></div>
            </Field>
            <Field label="Дата осмотра">
              <div style={{ fontWeight: 600, color: 'var(--ink)', fontSize: 14 }}>{fmt(w.validFrom ?? w.createdAt)}</div>
            </Field>
          </div>
        </div>

        {/* Строка статусов */}
        <div className="card" style={{ padding: '16px 22px' }}>
          <div style={{ display: 'grid', gridTemplateColumns: 'repeat(4, 1fr)', gap: 22 }}>
            <Field label="Статус осмотра"><span className="badge blue">В процессе</span></Field>
            <Field label="Предыдущий осмотр">
              <span className="badge green">Пройден</span>
              <span style={{ color: 'var(--muted)', fontSize: 12, marginLeft: 8 }}>19.05.2025 07:10</span>
            </Field>
            <Field label="Тех. состояние ТС"><span className="badge green">Исправен</span></Field>
            <Field label="Допуск к рейсу"><span className="badge gray">Не выдан</span></Field>
          </div>
        </div>

        {/* Двухколоночная раскладка */}
        <div style={{ display: 'grid', gridTemplateColumns: 'minmax(0, 1fr) 320px', gap: 18, alignItems: 'start' }}>
          {/* Левая колонка */}
          <div>
            <div className="card">
              <div className="card-h">
                <h2>Результаты осмотра</h2>
                <span style={{ marginLeft: 'auto', display: 'flex', gap: 18, fontSize: 12.5 }}>
                  <span style={{ display: 'inline-flex', alignItems: 'center', gap: 5, color: 'var(--green)', fontWeight: 600 }}><Icon d={P.check} style={{ width: 15, height: 15 }} /> Исправно</span>
                  <span style={{ display: 'inline-flex', alignItems: 'center', gap: 5, color: 'var(--red)', fontWeight: 600 }}><Icon d={P.alert} style={{ width: 15, height: 15 }} /> Неисправно</span>
                </span>
              </div>
              {CHECK_ITEMS.map((item, idx) => {
                const okState = checks[item.key] ?? true;
                return (
                  <div key={item.key} style={{ display: 'flex', alignItems: 'center', gap: 14, padding: '12px 0', borderBottom: idx < CHECK_ITEMS.length - 1 ? '1px solid var(--line-soft)' : 'none' }}>
                    <div className="ic-blue" style={{ width: 38, height: 38, borderRadius: 10, display: 'grid', placeItems: 'center', flex: 'none' }}>
                      <Icon d={item.icon} />
                    </div>
                    <div style={{ flex: 1, minWidth: 0 }}>
                      <div style={{ fontWeight: 600, color: 'var(--ink)', fontSize: 13.5 }}>{item.label}</div>
                      <div style={{ color: 'var(--muted)', fontSize: 12, marginTop: 2 }}>{item.desc}</div>
                    </div>
                    <button type="button" style={okState ? okActive : tgBase} onClick={() => setChecks({ ...checks, [item.key]: true })}>
                      <Icon d={P.check} style={{ width: 14, height: 14 }} /> Исправно
                    </button>
                    <button type="button" style={!okState ? badActive : tgBase} onClick={() => setChecks({ ...checks, [item.key]: false })}>
                      <Icon d={P.alert} style={{ width: 14, height: 14 }} /> Неисправно
                    </button>
                    <Icon d={P.chevron} style={{ width: 18, height: 18, color: 'var(--faint)', transform: 'rotate(90deg)', flex: 'none' }} />
                  </div>
                );
              })}
            </div>

            <div className="grid-2">
              {/* Примечания механика */}
              <div className="card">
                <div className="card-h"><h2>Примечания механика</h2></div>
                <textarea
                  value={defects}
                  maxLength={500}
                  onChange={e => setDefects(e.target.value)}
                  placeholder="Введите примечания при необходимости…"
                  style={{ width: '100%', minHeight: 120, padding: '10px 13px', border: '1px solid var(--line)', borderRadius: 'var(--radius-sm)', fontSize: 13.5, fontFamily: 'var(--sans)', color: 'var(--ink)', resize: 'vertical' }}
                />
                <div style={{ textAlign: 'right', color: 'var(--faint)', fontSize: 11.5, marginTop: 4 }}>{defects.length}/500</div>
              </div>

              {/* Выявленные неисправности */}
              <div className="card">
                <div className="card-h">
                  <h2>Выявленные неисправности</h2>
                  <a className="link" style={{ marginLeft: 'auto', cursor: 'pointer' }}>+ Добавить неисправность</a>
                </div>
                <table>
                  <thead>
                    <tr><th>Неисправность</th><th>Требует ремонта</th><th>Действия</th></tr>
                  </thead>
                  <tbody>
                    {failed.length === 0 ? (
                      <tr><td colSpan={3} style={{ color: 'var(--muted)', textAlign: 'center', padding: 22 }}>Неисправности не выявлены</td></tr>
                    ) : (
                      failed.map(f => (
                        <tr key={f.key}>
                          <td>{f.label}</td>
                          <td><span className="badge red">Да</span></td>
                          <td><span className="badge amber">К ремонту</span></td>
                        </tr>
                      ))
                    )}
                  </tbody>
                </table>
              </div>
            </div>

            {/* Кнопки решения */}
            <div style={{ display: 'flex', justifyContent: 'flex-end', marginTop: 4 }}>
              <button className="btn secondary" onClick={() => setSelected(null)}>Отмена</button>
              <button className="btn success" onClick={() => decide(true)}>
                <Icon d={P.check} /> Исправен — Допустить к рейсу
              </button>
              <button className="btn danger" onClick={() => decide(false)}>
                <Icon d={P.alert} /> Не допустить — Запретить выезд
              </button>
            </div>
          </div>

          {/* Правая колонка — виджеты */}
          <div>
            <div className="card">
              <div className="card-h">
                <h2>История техосмотров</h2>
                <a className="link" style={{ marginLeft: 'auto', cursor: 'pointer' }}>Все →</a>
              </div>
              <ul className="timeline">
                <li>
                  <div className="when">19.05.2025 07:10</div>
                  <b style={{ color: 'var(--green)' }}>Исправен</b>
                  <div style={{ color: 'var(--muted)', fontSize: 12 }}>Механик: Петров А. С.</div>
                </li>
                <li>
                  <div className="when">18.05.2025 07:08</div>
                  <b style={{ color: 'var(--green)' }}>Исправен</b>
                  <div style={{ color: 'var(--muted)', fontSize: 12 }}>Механик: Петров А. С.</div>
                </li>
                <li>
                  <div className="when">17.05.2025 07:12</div>
                  <b style={{ color: 'var(--amber)' }}>Исправен с замечаниями</b>
                  <div style={{ color: 'var(--muted)', fontSize: 12 }}>Механик: Петров А. С.</div>
                </li>
                <li>
                  <div className="when">16.05.2025 07:05</div>
                  <b style={{ color: 'var(--green)' }}>Исправен</b>
                  <div style={{ color: 'var(--muted)', fontSize: 12 }}>Механик: Петров А. С.</div>
                </li>
                <li>
                  <div className="when">15.05.2025 07:07</div>
                  <b style={{ color: 'var(--green)' }}>Исправен</b>
                  <div style={{ color: 'var(--muted)', fontSize: 12 }}>Механик: Петров А. С.</div>
                </li>
              </ul>
              <a className="link" style={{ cursor: 'pointer' }}>Показать ещё (12)</a>
            </div>

            <div className="card">
              <div className="card-h">
                <h2>Недавние неисправности</h2>
                <a className="link" style={{ marginLeft: 'auto', cursor: 'pointer' }}>Все →</a>
              </div>
              <div className="feed">
                <div className="fi">
                  <div className="fic ic-red"><Icon d={P.alert} /></div>
                  <div className="ft">
                    <b>Износ передних тормозных колодок</b>
                    <span>Устранена 17.05.2025</span>
                  </div>
                </div>
                <div className="fi">
                  <div className="fic ic-red"><Icon d={P.alert} /></div>
                  <div className="ft">
                    <b>Низкий уровень охлаждающей жидкости</b>
                    <span>Устранена 10.05.2025</span>
                  </div>
                </div>
                <div className="fi">
                  <div className="fic ic-red"><Icon d={P.alert} /></div>
                  <div className="ft">
                    <b>Повреждение заднего фонаря</b>
                    <span>Устранена 02.05.2025</span>
                  </div>
                </div>
              </div>
              <a className="link" style={{ cursor: 'pointer' }}>Показать ещё (5)</a>
            </div>

            <div className="card">
              <div className="card-h">
                <h2 style={{ display: 'inline-flex', alignItems: 'center', gap: 8 }}><Icon d={P.wrench} style={{ width: 16, height: 16, color: 'var(--blue-600)' }} /> Плановое обслуживание</h2>
              </div>
              <div style={{ display: 'flex', alignItems: 'baseline', justifyContent: 'space-between', paddingBottom: 12, borderBottom: '1px solid var(--line-soft)' }}>
                <span style={{ fontWeight: 600, color: 'var(--ink)' }}>Следующее ТО</span>
                <span style={{ textAlign: 'right' }}>
                  <div style={{ fontWeight: 700, color: 'var(--ink)' }}>через 3 250 км</div>
                  <div style={{ color: 'var(--muted)', fontSize: 12 }}>или 15.06.2025</div>
                </span>
              </div>
              <a className="link" style={{ cursor: 'pointer', display: 'inline-block', marginTop: 12 }}>Открыть план ТО</a>
            </div>
          </div>
        </div>
      </>
    );
  }

  // ============================ СПИСОК ОЖИДАЮЩИХ ============================
  return (
    <>
      <h1>АРМ механика</h1>
      <p className="page-lead">Предрейсовый технический контроль транспортных средств</p>
      {banners}

      <div className="kpi-row" style={{ gridTemplateColumns: 'repeat(4, 1fr)' }}>
        <div className="kpi">
          <div className="k-top"><div className="k-ic ic-amber"><Icon d={P.wrench} /></div></div>
          <div className="k-label">Ожидают контроля</div>
          <div className="k-value">{queue.length}</div>
        </div>
        <div className="kpi">
          <div className="k-top"><div className="k-ic ic-green"><Icon d={P.check} /></div></div>
          <div className="k-label">Исправно сегодня</div>
          <div className="k-value">23</div>
        </div>
        <div className="kpi">
          <div className="k-top"><div className="k-ic ic-red"><Icon d={P.alert} /></div></div>
          <div className="k-label">Неисправно сегодня</div>
          <div className="k-value">2</div>
        </div>
        <div className="kpi">
          <div className="k-top"><div className="k-ic ic-blue"><Icon d={P.chart} /></div></div>
          <div className="k-label">Среднее время</div>
          <div className="k-value" style={{ fontSize: 22 }}>8 мин</div>
        </div>
      </div>

      <div className="card">
        <div className="card-h">
          <h2>Очередь на технический контроль</h2>
          <span className="badge blue" style={{ marginLeft: 12 }}>{queue.length}</span>
        </div>
        <table>
          <thead>
            <tr><th>ТС / госномер</th><th>Марка</th><th>Тип ПЛ</th><th>Организация</th><th></th></tr>
          </thead>
          <tbody>
            {queue.map(w => (
              <tr key={w.id}>
                <td><span className="plate" style={{ transform: 'scale(.9)', transformOrigin: 'left center' }}><span className="p-main">{w.vehicleRegNumber}</span><span className="p-reg">01</span></span></td>
                <td>{String(w.vehicleSnapshot?.brand ?? '—')}</td>
                <td>{TYPE_LABELS[w.waybillType] ?? w.waybillType}</td>
                <td>{String(w.organizationSnapshot?.name ?? w.organizationRma)}</td>
                <td style={{ textAlign: 'right' }}><button className="btn" onClick={() => open(w)}>Провести контроль</button></td>
              </tr>
            ))}
            {queue.length === 0 && (
              <tr><td colSpan={5} style={{ color: 'var(--muted)', textAlign: 'center', padding: 20 }}>Очередь пуста — все ТС проверены</td></tr>
            )}
          </tbody>
        </table>
      </div>
    </>
  );
}
