"use client";

import React from "react";
import Link from "next/link";

import { TelemetryMap } from "../../../components/TelemetryMap";

export default function AnalyticsPage() {

    const categories = [
        { name: "Trucks", icon: "M9 17a2 2 0 11-4 0 2 2 0 014 0zM19 17a2 2 0 11-4 0 2 2 0 014 0z M13 16V6a1 1 0 00-1-1H4a1 1 0 00-1 1v10a1 1 0 001 1h1M13 16l4 2V8l-4 2", color: "blue" },
        { name: "Buses", icon: "M8 7v8a2 2 0 002 2h6M8 7V5a2 2 0 012-2h4a2 2 0 012 2v2M8 7h8m0 0v8a2 2 0 01-2 2H10a2 2 0 01-2-2", color: "indigo" },
        { name: "Taxis", icon: "M3 10h18M7 15h1m4 0h1m4 0h1 M5 19h14a2 2 0 002-2v-5a2 2 0 00-2-2H5a2 2 0 00-2 2v5a2 2 0 002 2z", color: "amber" },
        { name: "Special", icon: "M11 4a2 2 0 114 0v1a1 1 0 001 1h3a1 1 0 011 1v2a1 1 0 01-1 1h-5a1 1 0 01-1-1V5a1 1 0 01.447-.894L11 4z", color: "rose" }
    ];

    return (
        <div className="min-h-screen bg-[#020617] text-slate-300 font-sans p-8">
            <div className="max-w-[1600px] mx-auto">
                {/* Header */}
                <header className="flex items-center justify-between mb-12">
                    <div>
                        <div className="flex items-center gap-2 text-[10px] font-black text-blue-500 uppercase tracking-[0.3em] mb-2">
                            <span className="w-2 h-2 bg-blue-500 rounded-full animate-pulse"></span>
                            Live Intelligence
                        </div>
                        <h1 className="text-4xl font-black text-white tracking-tighter uppercase">Центр Аналитики</h1>
                        <p className="text-slate-500 text-sm mt-1 uppercase font-bold tracking-widest">Global Performance & Efficiency Dashboard</p>
                    </div>
                    <Link href="/dashboard" className="px-6 py-3 bg-white/5 hover:bg-white/10 border border-white/5 rounded-2xl text-xs font-black uppercase tracking-widest transition-all">
                        Вернуться
                    </Link>
                </header>

                {/* Primary Stats */}
                <div className="grid grid-cols-1 md:grid-cols-4 gap-8 mb-12">
                    {[
                        { label: "Общий пробег", value: "48,290", unit: "км", trend: "+12%", desc: "За текущий месяц" },
                        { label: "Расход ГСМ", value: "12,402", unit: "л", trend: "-4%", desc: "Оптимизация: 2.1%" },
                        { label: "Активность парка", value: "94.2", unit: "%", trend: "+1.5%", desc: "Выход на линию" },
                        { label: "Экономия затрат", value: "842,000", unit: "TJS", trend: "+8%", desc: "Digital Optimization" }
                    ].map((s, i) => (
                        <div key={i} className="bg-[#0f172a] border border-white/5 p-8 rounded-[32px] relative overflow-hidden group">
                            <div className="absolute -right-4 -bottom-4 opacity-5 group-hover:scale-110 transition-all duration-700">
                                <svg className="w-32 h-32" fill="white" viewBox="0 0 24 24"><path d="M13 10V3L4 14h7v7l9-11h-7z" /></svg>
                            </div>
                            <p className="text-[10px] font-black text-slate-500 uppercase tracking-widest mb-4">{s.label}</p>
                            <div className="flex items-baseline gap-2">
                                <span className="text-3xl font-black text-white">{s.value}</span>
                                <span className="text-xs font-bold text-slate-500">{s.unit}</span>
                            </div>
                            <div className="mt-4 flex items-center justify-between">
                                <span className="text-[10px] text-slate-400 font-bold uppercase">{s.desc}</span>
                                <span className={`text-[10px] font-black ${s.trend.startsWith('+') ? 'text-emerald-500' : 'text-rose-500'}`}>{s.trend}</span>
                            </div>
                        </div>
                    ))}
                </div>

                {/* Telemetry Map */}
                <div className="mb-12">
                    <TelemetryMap />
                </div>

                <div className="grid grid-cols-1 lg:grid-cols-12 gap-8">
                    {/* Category Breakdown */}
                    <div className="lg:col-span-8 space-y-8">
                        <section className="bg-[#0f172a] border border-white/5 rounded-[40px] p-10">
                            <h2 className="text-xl font-black text-white uppercase tracking-widest mb-10">Эффективность по категориям</h2>
                            <div className="space-y-8">
                                {categories.map((cat, i) => (
                                    <div key={i} className="group">
                                        <div className="flex items-center justify-between mb-4">
                                            <div className="flex items-center gap-4">
                                                <div className={`p-4 rounded-2xl bg-${cat.color}-500/10 text-${cat.color}-500 border border-${cat.color}-500/20`}>
                                                    <svg className="w-6 h-6" fill="none" stroke="currentColor" viewBox="0 0 24 24"><path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d={cat.icon} /></svg>
                                                </div>
                                                <div>
                                                    <h3 className="text-lg font-black text-white tracking-tight">{cat.name}</h3>
                                                    <p className="text-[10px] text-slate-500 font-bold uppercase tracking-widest">Performance Index: 9{i}.2</p>
                                                </div>
                                            </div>
                                            <div className="text-right">
                                                <p className="text-xl font-black text-white">{(i + 1) * 240} ТС</p>
                                                <p className="text-[10px] text-emerald-500 font-bold uppercase tracking-widest">Active Now</p>
                                            </div>
                                        </div>
                                        <div className="h-2 bg-white/5 rounded-full overflow-hidden">
                                            <div className={`h-full bg-${cat.color}-500 rounded-full shadow-[0_0_15px_rgba(59,130,246,0.5)] transition-all duration-1000`} style={{ width: `${80 - i * 10}%` }}></div>
                                        </div>
                                    </div>
                                ))}
                            </div>
                        </section>

                        {/* Recent Alerts / Maintenance */}
                        <section className="bg-rose-500/[0.02] border border-rose-500/10 rounded-[40px] p-10">
                            <div className="flex items-center justify-between mb-8">
                                <h3 className="text-lg font-black text-white uppercase tracking-widest flex items-center gap-3">
                                    <span className="w-2 h-2 bg-rose-500 rounded-full animate-ping"></span>
                                    Критические Уведомления
                                </h3>
                                <button className="text-[10px] font-black text-rose-500 uppercase tracking-widest">Просмотреть всё</button>
                            </div>
                            <div className="grid grid-cols-1 md:grid-cols-2 gap-6">
                                {[
                                    { msg: "Просрочен техосмотр (ТС: 0001TJ)", category: "Trucks", priority: "HIGH" },
                                    { msg: "Низкий запас топлива (ТС: 2042TJ)", category: "Buses", priority: "MED" }
                                ].map((alert, i) => (
                                    <div key={i} className="bg-black/20 border border-white/5 p-4 rounded-2xl flex items-center justify-between">
                                        <div>
                                            <p className="text-[10px] text-slate-500 font-bold uppercase mb-1">{alert.category}</p>
                                            <p className="text-xs font-bold text-white">{alert.msg}</p>
                                        </div>
                                        <span className={`text-[8px] font-black px-2 py-1 rounded bg-${alert.priority === 'HIGH' ? 'rose' : 'amber'}-500/20 text-${alert.priority === 'HIGH' ? 'rose' : 'amber'}-500`}>{alert.priority}</span>
                                    </div>
                                ))}
                            </div>
                        </section>
                    </div>

                    {/* Operational Feed / Real-time */}
                    <div className="lg:col-span-4 space-y-8">
                        <section className="bg-[#0f172a]/50 backdrop-blur-xl border border-white/5 rounded-[40px] p-8 flex flex-col h-full">
                            <h3 className="text-lg font-black text-white uppercase tracking-widest mb-8 text-center underline decoration-blue-500 decoration-4 underline-offset-8">Live Logs</h3>
                            <div className="space-y-6 flex-1">
                                {[1, 2, 3, 4, 5, 6].map(i => (
                                    <div key={i} className="flex gap-4 border-l-2 border-white/5 pl-4 hover:border-blue-500 transition-all cursor-default">
                                        <div className="text-[10px] font-mono text-slate-500">14:2{i}</div>
                                        <div>
                                            <p className="text-xs font-bold text-white leading-tight">Система: Отчет GOSM сгенерирован</p>
                                            <p className="text-[9px] text-slate-600 uppercase font-black tracking-tighter mt-1">Status: Success / Log: #4829</p>
                                        </div>
                                    </div>
                                ))}
                            </div>
                            <button className="mt-8 w-full py-4 bg-blue-600/10 hover:bg-blue-600 text-blue-500 hover:text-white rounded-2xl text-[10px] font-black uppercase tracking-[0.2em] transition-all border border-blue-500/20">
                                Export Full Analytics (PDF)
                            </button>
                        </section>
                    </div>
                </div>
            </div>
        </div>
    );
}
