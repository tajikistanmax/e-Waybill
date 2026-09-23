'use client';

import { useEffect, useRef } from 'react';
import L from 'leaflet';
import 'leaflet/dist/leaflet.css';
import type { LivePosition } from '@/lib/api';
import tjBorder from './tajikistan-border.geo.json';

/**
 * Настоящая карта (Leaflet) для GPS-мониторинга. Источник тайлов КОНФИГУРИРУЕМ через env:
 *   NEXT_PUBLIC_MAP_TILES_URL  — шаблон тайлов ({z}/{x}/{y}); в проде — тайлы госЦОД РТ, карта
 *                                ГП «Мониторинг» Минтранса, либо самостоятельно поднятый офлайн
 *                                тайл-сервер в закрытом контуре (например TileServer GL).
 *   NEXT_PUBLIC_MAP_ATTRIBUTION — подпись источника тайлов (если он задан).
 * По умолчанию (переменная НЕ задана) внешние тайлы НЕ запрашиваются — платформа работает в
 * закрытом гос-контуре без интернета (см. changelog/2026-09-23-локальные-шрифты-и-карты.md).
 * Вместо растровых тайлов — офлайн-подложка: однотонный фон + контур границы Таджикистана
 * (GeoJSON, вшит в сборку из tajikistan-border.geo.json, упрощённые координаты) — точки ТС и их
 * взаимное расположение видны и без интернет-карты. Если NEXT_PUBLIC_MAP_TILES_URL задан —
 * используются обычные растровые тайлы с указанного (в т.ч. локального) сервера.
 */
const TILES = process.env.NEXT_PUBLIC_MAP_TILES_URL || '';
const ATTR = process.env.NEXT_PUBLIC_MAP_ATTRIBUTION || '';
const DUSHANBE: [number, number] = [38.5598, 68.7870];
// Грубые границы Таджикистана с запасом — чтобы без тайловой подложки нельзя было ускакать в
// пустой океан карты (офлайн-режим).
const TJ_BOUNDS = L.latLngBounds([36.0, 66.3], [41.6, 75.5]);

function agoSec(iso: string | null): number | null {
  if (!iso) return null;
  return Math.max(0, Math.round((Date.now() - new Date(iso).getTime()) / 1000));
}
function freshColor(sec: number | null): string {
  return sec == null ? '#94a3b8' : sec < 120 ? '#16a34a' : sec < 900 ? '#ea9615' : '#dc2626';
}

type Props = {
  rows: LivePosition[];
  t: (k: string) => string;
  tStatus: (s: string) => string;
  /** Высота карты (px). По умолчанию 560 — полноэкранный монитор; в карточках задают меньше. */
  height?: number;
};

export default function LeafletMap({ rows, t, tStatus, height = 560 }: Props) {
  const boxRef = useRef<HTMLDivElement>(null);
  const mapRef = useRef<L.Map | null>(null);
  const layerRef = useRef<L.LayerGroup | null>(null);
  const fittedRef = useRef(false);

  // Инициализация карты (один раз).
  useEffect(() => {
    if (!boxRef.current || mapRef.current) return;
    const map = L.map(boxRef.current, { center: DUSHANBE, zoom: 12, zoomControl: true, attributionControl: true });
    if (TILES) {
      // Настроенный тайл-сервер (свой/локальный) — обычные растровые тайлы.
      L.tileLayer(TILES, { attribution: ATTR, maxZoom: 19 }).addTo(map);
    } else {
      // Офлайн: без внешних тайлов. Однотонный фон (см. .leaflet-offline в globals.css) + контур
      // границы Таджикистана, чтобы точки ТС были видны на местности без интернета.
      boxRef.current.classList.add('leaflet-offline');
      L.geoJSON(tjBorder as GeoJSON.GeoJsonObject, {
        style: { color: '#2563eb', weight: 1.5, fillColor: '#dbe7fe', fillOpacity: 0.5 },
      }).addTo(map);
      map.attributionControl.setPrefix(false);
      map.attributionControl.addAttribution('Контур границы РТ — упрощённая офлайн-подложка');
      map.setMaxBounds(TJ_BOUNDS.pad(0.15));
      map.setMinZoom(6);
    }
    layerRef.current = L.layerGroup().addTo(map);
    mapRef.current = map;
    setTimeout(() => map.invalidateSize(), 120); // корректный размер после раскладки
    return () => { map.remove(); mapRef.current = null; layerRef.current = null; fittedRef.current = false; };
  }, []);

  // Обновление меток при изменении данных (живое обновление).
  useEffect(() => {
    const map = mapRef.current, layer = layerRef.current;
    if (!map || !layer) return;
    layer.clearLayers();
    const pts: [number, number][] = [];
    const ago = (sec: number | null) => sec == null ? t('mon.nosignal')
      : sec < 60 ? `${sec} ${t('mon.sec')}` : sec < 3600 ? `${Math.floor(sec / 60)} ${t('mon.min')}` : `${Math.floor(sec / 3600)} ${t('mon.hour')}`;

    rows.filter(r => r.lat != null && r.lon != null).forEach(r => {
      const lat = Number(r.lat), lon = Number(r.lon);
      const sec = agoSec(r.recordedAt);
      const col = freshColor(sec);
      const moving = (r.speedKmh ?? 0) > 3;
      pts.push([lat, lon]);

      // iconSize задаёт кликабельную область (точку); чип с госномером выходит за неё (визуально).
      const icon = L.divIcon({
        className: '', iconSize: [18, 18], iconAnchor: [9, 9], popupAnchor: [0, -10],
        html:
          `<div style="position:relative;width:18px;height:18px">
             <span style="position:absolute;inset:0;border-radius:50%;background:${col};border:2.5px solid #fff;box-shadow:0 1px 3px rgba(0,0,0,.45);cursor:pointer"></span>
             ${moving ? '<span style="position:absolute;left:7px;top:7px;width:4px;height:4px;border-radius:50%;background:#fff;pointer-events:none"></span>' : ''}
             <span style="position:absolute;left:24px;top:0;white-space:nowrap;background:#fff;border:1px solid #e2e8f0;border-radius:9px;padding:1px 7px;font:600 11px/1.5 ui-monospace,Consolas,monospace;color:#0f172a;box-shadow:0 1px 2px rgba(0,0,0,.18);pointer-events:none">${r.vehicleRegNumber}</span>
           </div>`,
      });

      const popup =
        `<div style="min-width:200px;font:13px/1.5 system-ui,sans-serif">
           <div style="font:700 15px/1.3 ui-monospace,Consolas,monospace">${r.vehicleRegNumber}</div>
           <div style="color:#64748b;font-size:12px">${r.number ?? '—'}</div>
           <div style="margin-top:6px">${r.driver}</div>
           <div style="margin-top:6px">
             <span style="background:#eff4ff;color:#2563eb;border-radius:6px;padding:1px 8px;font-size:11.5px">${tStatus(r.status)}</span>
             <span style="background:${moving ? '#eafaf0' : '#f1f5f9'};color:${moving ? '#16a34a' : '#64748b'};border-radius:6px;padding:1px 8px;font-size:11.5px">${moving ? t('mon.state.moving') : t('mon.state.parked')}</span>
           </div>
           <div style="margin-top:8px;font-family:ui-monospace,Consolas,monospace;font-size:12px">${r.speedKmh ?? 0} ${t('mon.kmh')} · ${lat.toFixed(5)}, ${lon.toFixed(5)}</div>
           <div style="margin-top:2px;color:#64748b;font-size:12px">${t('mon.col.lastseen')}: ${ago(sec)}</div>
         </div>`;

      L.marker([lat, lon], { icon }).bindPopup(popup).addTo(layer);
    });

    // Один раз подгоняем вид под метки (потом не дёргаем — пользователь может панорамировать).
    if (pts.length && !fittedRef.current) {
      map.fitBounds(pts, { padding: [50, 50], maxZoom: 14 });
      fittedRef.current = true;
    }
  }, [rows, t, tStatus]);

  return <div ref={boxRef} style={{ height, width: '100%', borderRadius: 14, overflow: 'hidden', border: '1px solid var(--line)', zIndex: 0 }} />;
}
