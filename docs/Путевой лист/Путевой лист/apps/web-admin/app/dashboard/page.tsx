"use client";

import React, { useState, useEffect } from "react";
import Link from "next/link";

interface Waybill {
    id: number;
    number: string;
    status: string;
    driverId: number;
    vehicle: {
        plateNumber: string;
        model: string;
    };
    departureLocation?: string;
    arrivalLocation?: string;
    createdAt: string;
}

export default function Dashboard() {
    const [waybills, setWaybills] = useState<Waybill[]>([]);
    const [loading, setLoading] = useState(true);
    const [currentTime, setCurrentTime] = useState(new Date());

    useEffect(() => {
        const timer = setInterval(() => setCurrentTime(new Date()), 1000);
        const fetchWaybills = async () => {
            try {
                const response = await fetch("http://localhost:8080/api/waybills");
                if (response.ok) {
                    const data = await response.json();
                    setWaybills(data);
                }
            } catch (error) {
                console.error("Failed to fetch waybills:", error);
            } finally {
                setLoading(false);
            }
        };
        fetchWaybills();
        return () => clearInterval(timer);
    }, []);

    const getStatusStyle = (status: string) => {
        switch (status) {
            case "ACTIVE": return "bg-emerald-500 text-white shadow-emerald-500/20";
            case "PENDING_TECH": return "bg-amber-500 text-white shadow-amber-500/20";
            case "PENDING_MED": return "bg-indigo-500 text-white shadow-indigo-500/20";
            case "DRAFT": return "bg-slate-500 text-white shadow-slate-500/20";
            default: return "bg-slate-600 text-white";
        }
    };

    return (
        <div className="flex h-screen bg-[#020617] text-slate-300 font-sans overflow-hidden">
            {/* Professional Sidebar Navigation */}
            <aside className="w-64 bg-[#0f172a] border-r border-white/5 flex flex-col">
                <div className="p-6">
                    <div className="flex items-center gap-3 mb-8">
                        <div className="w-10 h-10 bg-blue-600 rounded-xl flex items-center justify-center shadow-lg shadow-blue-600/30">
                            <svg className="w-6 h-6 text-white" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                                <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2.5} d="M13 10V3L4 14h7v7l9-11h-7z" />
                            </svg>
                        </div>
                        <h2 className="text-xl font-bold text-white tracking-tight">DTS Core</h2>
                    </div>

                    <nav className="space-y-1">
                        {[
                            { label: "Рабочий стол", icon: "M3 12l2-2m0 0l7-7 7 7M5 10v10a1 1 0 001 1h3m10-11l2 2m-2-2v10a1 1 0 01-1 1h-3m-6 0a1 1 0 001-1v-4a1 1 0 011-1h2a1 1 0 011 1v4a1 1 0 001 1m-6 0h6", active: true, href: "/dashboard" },
                            { label: "Путевые листы", icon: "M9 12h6m-6 4h6m2 5H7a2 2 0 01-2-2V5a2 2 0 012-2h5.586a1 1 0 01.707.293l5.414 5.414a1 1 0 01.293.707V19a2 2 0 01-2 2z", href: "/dashboard" },
                            { label: "Отчеты и Выгрузки", icon: "M7 21h10a2 2 0 002-2V9.414a1 1 0 00-.293-.707l-5.414-5.414A1 1 0 0012.586 3H7a2 2 0 00-2 2v14a2 2 0 002 2z", href: "/dashboard/reports" },
                            { label: "Аналитика", icon: "M9 19v-6a2 2 0 00-2-2H5a2 2 0 00-2 2v6a2 2 0 002 2h2a2 2 0 002-2zm0 0V9a2 2 0 012-2h2a2 2 0 012 2v10m-6 0a2 2 0 002 2h2a2 2 0 002-2m0 0V5a2 2 0 012-2h2a2 2 0 012 2v14a2 2 0 01-2 2h-2a2 2 0 01-2-2z", href: "/dashboard/analytics" },
                        ].map((item, i) => (
                            <Link key={i} href={item.href} className={`flex items-center gap-3 px-4 py-3 rounded-xl transition-all ${item.active ? 'bg-blue-600/10 text-blue-400 border border-blue-500/20' : 'hover:bg-white/5 text-slate-400 hover:text-slate-200'}`}>
                                <svg className="w-5 h-5" fill="none" stroke="currentColor" viewBox="0 0 24 24"><path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d={item.icon} /></svg>
                                <span className="text-sm font-medium">{item.label}</span>
                            </Link>
                        ))}
                    </nav>

                    <div className="mt-8">
                        <p className="px-4 text-[10px] font-bold text-slate-500 uppercase tracking-[0.2em] mb-4">Управление парком</p>
                        <nav className="space-y-1">
                            {[
                                { label: "Грузовой (Truck)", href: "/dashboard/management?cat=TRUCK" },
                                { label: "Пассажирский (Bus)", href: "/dashboard/management?cat=BUS" },
                                { label: "Такси (Taxi)", href: "/dashboard/management?cat=TAXI" },
                                { label: "Спецтехника", href: "/dashboard/management?cat=SPECIAL" },
                            ].map((item, i) => (
                                <Link key={i} href={item.href} className="flex items-center gap-3 px-4 py-2 text-xs text-slate-400 hover:text-white hover:bg-white/5 rounded-lg transition-all">
                                    <div className="w-1.5 h-1.5 rounded-full bg-slate-600 group-hover:bg-blue-500"></div>
                                    {item.label}
                                </Link>
                            ))}
                        </nav>
                    </div>

                    <div className="mt-8">
                        <p className="px-4 text-[10px] font-bold text-slate-500 uppercase tracking-[0.2em] mb-4">КПП и Титулы (TS v2.0)</p>
                        <nav className="space-y-1">
                            {[
                                { label: "Медик (Титул 2)", href: "/dashboard/checkpoints/medical", color: "text-indigo-400" },
                                { label: "Техник (Титул 3)", href: "/dashboard/checkpoints/technical", color: "text-emerald-400" },
                                { label: "Диспетчер (Титул 1)", href: "/dashboard/checkpoints/dispatcher", color: "text-blue-400" },
                                { label: "Возврат (Т4/Т5)", href: "/dashboard/checkpoints/post-trip", color: "text-rose-400" },
                                { label: "Водитель (QR)", href: "/dashboard/driver", color: "text-violet-400" },
                            ].map((item, i) => (
                                <Link key={i} href={item.href} className="flex items-center justify-between px-4 py-2 text-xs text-slate-400 hover:text-white hover:bg-white/5 rounded-lg transition-all group">
                                    <div className="flex items-center gap-3">
                                        <div className={`w-1 h-1 rounded-full bg-slate-600 group-hover:scale-150 transition-transform ${item.color.replace('text', 'bg')}`}></div>
                                        {item.label}
                                    </div>
                                    <svg className="w-3 h-3 opacity-0 group-hover:opacity-100 transition-opacity" fill="none" stroke="currentColor" viewBox="0 0 24 24"><path strokeLinecap="round" strokeLinejoin="round" strokeWidth={3} d="M9 5l7 7-7 7" /></svg>
                                </Link>
                            ))}
                        </nav>
                    </div>
                </div>

                <div className="mt-auto p-6 border-t border-white/5">
                    <div className="flex items-center gap-3">
                        <div className="w-8 h-8 rounded-full bg-gradient-to-tr from-blue-600 to-indigo-600 flex items-center justify-center text-[10px] font-bold text-white">AD</div>
                        <div>
                            <p className="text-xs font-bold text-white">Administrator</p>
                            <p className="text-[10px] text-slate-500">Super Admin Mode</p>
                        </div>
                    </div>
                </div>
            </aside>

            {/* Main Content Area */}
            <main className="flex-1 flex flex-col overflow-hidden relative">
                {/* Header */}
                <header className="h-16 bg-[#0f172a]/50 backdrop-blur-xl border-b border-white/5 flex items-center justify-between px-8 z-20">
                    <div className="flex items-center gap-4">
                        <div className="text-xs font-mono text-slate-500 bg-white/5 px-2 py-1 rounded border border-white/5">
                            UTC: {currentTime.toLocaleTimeString()}
                        </div>
                        <div className="flex items-center gap-3 px-3 py-1 bg-emerald-500/[0.05] border border-emerald-500/10 rounded-full">
                            <div className="w-1.5 h-1.5 bg-emerald-500 rounded-full animate-pulse"></div>
                            <span className="text-[9px] font-black text-emerald-400 uppercase tracking-tighter">GIS EPD: ONLINE</span>
                        </div>
                        <div className="flex items-center gap-3 px-3 py-1 bg-blue-500/[0.05] border border-blue-500/10 rounded-full">
                            <div className="w-1.5 h-1.5 bg-blue-500 rounded-full"></div>
                            <span className="text-[9px] font-black text-blue-400 uppercase tracking-tighter">SSO: ACTIVE</span>
                        </div>
                    </div>
                    <div className="flex items-center gap-4">
                        <button className="p-2 text-slate-400 hover:text-white transition-colors relative">
                            <svg className="w-5 h-5" fill="none" stroke="currentColor" viewBox="0 0 24 24"><path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M15 17h5l-1.405-1.405A2.032 2.032 0 0118 14.158V11a6.002 6.002 0 00-4-5.659V5a2 2 0 10-4 0v.341C7.67 6.165 6 8.388 6 11v3.159c0 .538-.214 1.055-.595 1.436L4 17h5m6 0v1a3 3 0 11-6 0v-1m6 0H9" /></svg>
                            <span className="absolute top-1.5 right-1.5 w-2 h-2 bg-red-500 rounded-full border-2 border-[#0f172a]"></span>
                        </button>
                        <Link href="/dashboard/new" className="bg-blue-600 hover:bg-blue-500 text-white text-xs font-bold px-4 py-2 rounded-lg transition-all shadow-lg shadow-blue-600/20 active:scale-95">
                            Создать лист
                        </Link>
                    </div>
                </header>

                {/* Dashboard Scrollable Body */}
                <div className="flex-1 overflow-y-auto p-8 custom-scrollbar">
                    {/* KPI High-Density Row */}
                    <div className="grid grid-cols-1 md:grid-cols-4 gap-6 mb-8">
                        {[
                            { label: "Активный парк", value: "84%", sub: "128 ТС онлайн", trend: "+2.4%" },
                            { label: "Топливная эффективность", value: "92.1", sub: "л/100км ср.", trend: "-0.8%" },
                            { label: "Ср. время закрытия", value: "18.4", sub: "минуты", trend: "+5.1%" },
                            { label: "Инциденты безопасности", value: "0", sub: "за 24 часа", trend: "OK" },
                        ].map((stat, i) => (
                            <div key={i} className="bg-white/5 border border-white/10 p-5 rounded-2xl relative overflow-hidden group hover:border-blue-500/30 transition-all">
                                <div className="absolute -right-4 -bottom-4 opacity-[0.03] group-hover:scale-110 transition-all">
                                    <svg className="w-24 h-24" fill="currentColor" viewBox="0 0 24 24"><path d="M13 10V3L4 14h7v7l9-11h-7z" /></svg>
                                </div>
                                <p className="text-[10px] font-bold text-slate-500 uppercase tracking-widest">{stat.label}</p>
                                <div className="flex items-baseline gap-2 mt-2">
                                    <span className="text-2xl font-bold text-white tracking-tight">{stat.value}</span>
                                    <span className={`text-[10px] font-bold ${stat.trend.startsWith('+') ? 'text-emerald-400' : stat.trend === 'OK' ? 'text-blue-400' : 'text-rose-400'}`}>{stat.trend}</span>
                                </div>
                                <p className="text-[10px] text-slate-400 mt-1">{stat.sub}</p>
                            </div>
                        ))}
                    </div>

                    {/* Operational View Split */}
                    <div className="grid grid-cols-1 lg:grid-cols-12 gap-8">
                        {/* Map Preview / Tracking (Left) */}
                        <div className="lg:col-span-8 space-y-8">
                            <div className="bg-white/5 border border-white/10 rounded-2xl p-1 overflow-hidden h-[400px] relative">
                                <div className="absolute inset-0 bg-[url('https://api.mapbox.com/styles/v1/mapbox/dark-v10/static/44.38,38.62,10,0/800x400?access_token=pk.eyJ1IjoiYm90IiwiYSI6ImNrYmtwd3RzdzBiazYyc3Bla3Z4ZzZ6ZnoifQ.x-Z1_Z-x-Z1_Z-x-Z1_Z')] bg-cover bg-center brightness-[0.7] contrast-[1.2]"></div>
                                <div className="absolute inset-0 bg-blue-900/10 mix-blend-overlay"></div>
                                <div className="absolute top-6 left-6 z-10">
                                    <div className="bg-[#0f172a]/80 backdrop-blur-md border border-white/10 px-4 py-3 rounded-xl shadow-2xl">
                                        <p className="text-[10px] font-bold text-slate-400 uppercase">Активниый мониторинг</p>
                                        <div className="flex items-center gap-2 mt-1">
                                            <span className="w-2 h-2 bg-emerald-500 rounded-full animate-ping"></span>
                                            <span className="text-sm font-bold text-white">12 ТС на линии</span>
                                        </div>
                                    </div>
                                </div>
                                {/* Mock Vehicle Pulsating Dots */}
                                <div className="absolute top-[40%] left-[30%] w-3 h-3 bg-blue-500 rounded-full border-2 border-white shadow-[0_0_15px_rgba(59,130,246,0.8)] animate-pulse"></div>
                                <div className="absolute top-[60%] left-[70%] w-3 h-3 bg-emerald-500 rounded-full border-2 border-white shadow-[0_0_15px_rgba(16,185,129,0.8)] animate-bounce"></div>
                            </div>

                            {/* Data Grid Section */}
                            <div className="bg-white/5 border border-white/10 rounded-2xl overflow-hidden shadow-2xl">
                                <div className="px-6 py-4 border-b border-white/10 flex items-center justify-between">
                                    <h3 className="font-bold text-white">Последние путевые листы</h3>
                                    <div className="flex gap-2">
                                        <input placeholder="Поиск..." className="bg-white/5 border border-white/5 rounded-lg px-3 py-1.5 text-xs focus:ring-1 ring-blue-500 focus:outline-none" />
                                        <button className="bg-white/5 p-1.5 rounded-lg border border-white/5 text-slate-400 hover:text-white transition-colors">
                                            <svg className="w-4 h-4" fill="none" stroke="currentColor" viewBox="0 0 24 24"><path d="M3 4a1 1 0 011-1h16a1 1 0 011 1v2.586a1 1 0 01-.293.707l-6.414 6.414a1 1 0 00-.293.707V17l-4 4v-6.586a1 1 0 00-.293-.707L3.293 7.293A1 1 0 013 6.586V4z" /></svg>
                                        </button>
                                    </div>
                                </div>
                                <div className="overflow-x-auto overflow-y-auto max-h-[500px]">
                                    <table className="w-full text-left border-collapse">
                                        <thead>
                                            <tr className="bg-white/[0.02]">
                                                <th className="px-6 py-3 text-[10px] font-bold text-slate-500 uppercase tracking-wider">№ Документа</th>
                                                <th className="px-6 py-3 text-[10px] font-bold text-slate-500 uppercase tracking-wider">Маршрут</th>
                                                <th className="px-6 py-3 text-[10px] font-bold text-slate-500 uppercase tracking-wider">ТС / Модель</th>
                                                <th className="px-6 py-3 text-[10px] font-bold text-slate-500 uppercase tracking-wider">Статус</th>
                                                <th className="px-6 py-3 text-[10px] font-bold text-slate-500 uppercase tracking-wider text-right">Детали</th>
                                            </tr>
                                        </thead>
                                        <tbody className="divide-y divide-white/[0.05]">
                                            {loading ? (
                                                [1, 2, 3, 4].map(i => <tr key={i} className="animate-pulse px-6 py-8 h-16 bg-white/5"></tr>)
                                            ) : (
                                                waybills.map(wb => (
                                                    <tr key={wb.id} className="hover:bg-white/[0.03] transition-all group">
                                                        <td className="px-6 py-4">
                                                            <Link href={`/dashboard/waybills/${wb.id}`} className="text-white text-sm font-bold hover:text-blue-400 transition-colors">
                                                                {wb.number}
                                                            </Link>
                                                            <div className="text-[10px] text-slate-500 mt-0.5">{new Date(wb.createdAt).toLocaleDateString()}</div>
                                                        </td>
                                                        <td className="px-6 py-4">
                                                            <div className="flex items-center gap-2 max-w-[150px]">
                                                                <span className="text-xs text-slate-300 truncate">{wb.departureLocation || '—'}</span>
                                                                <svg className="w-3 h-3 text-slate-600 flex-shrink-0" fill="currentColor" viewBox="0 0 20 20"><path fillRule="evenodd" d="M12.293 5.293a1 1 0 011.414 0l4 4a1 1 0 010 1.414l-4 4a1 1 0 01-1.414-1.414L14.586 11H3a1 1 0 110-2h11.586l-2.293-2.293a1 1 0 010-1.414z" clipRule="evenodd" /></svg>
                                                                <span className="text-xs text-slate-300 truncate">{wb.arrivalLocation || '—'}</span>
                                                            </div>
                                                        </td>
                                                        <td className="px-6 py-4">
                                                            <div className="flex flex-col">
                                                                <span className="text-white text-xs font-bold font-mono uppercase">{wb.vehicle.plateNumber}</span>
                                                                <span className="text-slate-500 text-[10px]">{wb.vehicle.model}</span>
                                                            </div>
                                                        </td>
                                                        <td className="px-6 py-4">
                                                            <span className={`px-2 py-0.5 text-[9px] font-bold rounded-md uppercase tracking-tighter ${getStatusStyle(wb.status)}`}>
                                                                {wb.status.replace('_', ' ')}
                                                            </span>
                                                        </td>
                                                        <td className="px-6 py-4 text-right">
                                                            <Link href={`/dashboard/waybills/${wb.id}`} className="bg-white/5 hover:bg-white/10 p-2 rounded-lg transition-all inline-block">
                                                                <svg className="w-4 h-4" fill="none" stroke="currentColor" viewBox="0 0 24 24"><path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M9 5l7 7-7 7" /></svg>
                                                            </Link>
                                                        </td>
                                                    </tr>
                                                ))
                                            )}
                                        </tbody>
                                    </table>
                                </div>
                            </div>
                        </div>

                        {/* Operational Event Feed (Right) */}
                        <div className="lg:col-span-4 flex flex-col gap-6">
                            <div className="bg-white/5 border border-white/10 rounded-2xl flex-1 flex flex-col overflow-hidden max-h-[100%] shadow-xl">
                                <div className="p-6 border-b border-white/10">
                                    <div className="flex items-center justify-between">
                                        <h4 className="font-bold text-white text-sm">Оперативный поток</h4>
                                        <span className="bg-blue-600/20 text-blue-400 text-[9px] font-bold px-2 py-0.5 rounded-full uppercase tracking-widest">Live</span>
                                    </div>
                                </div>
                                <div className="flex-1 overflow-y-auto p-6 space-y-6">
                                    {/* Mock Events */}
                                    {[
                                        { title: "Лист WL-0023 Завершен", desc: "Водитель: Сариков А. - Маршрут выполнен", time: "12:45", type: "SUCCESS" },
                                        { title: "Новый запрос (WL-0025)", desc: "Водитель: Махмудов Д. ожидает техконтроля", time: "12:40", type: "WAIT" },
                                        { title: "Техконтроль пройден", desc: "ТС (0001TJ01) допущено механиком", time: "11:30", type: "INFO" },
                                        { title: "Медосмотр просрочен", desc: "Внимание: Водитель Холов У. требуется осмотр", time: "10:15", type: "WARN" },
                                        { title: "Автопарк: Новая единица", desc: "Добавлена Toyota Land Cruiser 300", time: "Вчера", type: "INFO" }
                                    ].map((event, i) => (
                                        <div key={i} className="flex gap-4 group">
                                            <div className="relative">
                                                <div className={`w-3 h-3 rounded-full mt-1.5 ${event.type === 'SUCCESS' ? 'bg-emerald-500' : event.type === 'WARN' ? 'bg-rose-500' : 'bg-blue-500'} relative z-10`}></div>
                                                {i !== 4 && <div className="absolute top-4 left-1.5 bottom-[-24px] w-[1px] bg-white/5 group-hover:bg-white/10 transition-all"></div>}
                                            </div>
                                            <div className="flex-1">
                                                <div className="flex justify-between items-start">
                                                    <p className="text-xs font-bold text-white leading-tight">{event.title}</p>
                                                    <span className="text-[10px] text-slate-500 font-mono">{event.time}</span>
                                                </div>
                                                <p className="text-[10px] text-slate-500 mt-1 leading-normal">{event.desc}</p>
                                            </div>
                                        </div>
                                    ))}
                                </div>
                                <div className="p-4 border-t border-white/10 bg-white/[0.01]">
                                    <button className="w-full text-[10px] font-bold text-blue-400 hover:text-blue-300 text-center uppercase tracking-widest">
                                        Просмотреть весь лог
                                    </button>
                                </div>
                            </div>

                            <div className="bg-gradient-to-br from-blue-600 to-indigo-700 p-6 rounded-2xl shadow-lg relative overflow-hidden group">
                                <div className="absolute top-0 right-0 p-4 opacity-10 group-hover:scale-125 transition-all">
                                    <svg className="w-20 h-20" fill="white" viewBox="0 0 24 24"><path d="M12 2C6.48 2 2 6.48 2 12s4.48 10 10 10 10-4.48 10-10S17.52 2 12 2zM13 17h-2v-6h2v6zm0-8h-2V7h2v2z" /></svg>
                                </div>
                                <h5 className="text-white font-bold text-sm mb-2">Техподдержка DTS</h5>
                                <p className="text-white/70 text-[10px] mb-4 leading-relaxed">Возникли сложности с цифровой подписью? Свяжитесь с центром мониторинга.</p>
                                <button className="w-full py-2 bg-white text-blue-600 text-[10px] font-bold rounded-lg hover:shadow-xl transition-all uppercase tracking-widest">
                                    Связаться
                                </button>
                            </div>
                        </div>
                    </div>
                </div>
            </main>

            <style jsx global>{`
                .custom-scrollbar::-webkit-scrollbar {
                    width: 6px;
                }
                .custom-scrollbar::-webkit-scrollbar-track {
                    background: transparent;
                }
                .custom-scrollbar::-webkit-scrollbar-thumb {
                    background: rgba(255, 255, 255, 0.05);
                    border-radius: 10px;
                }
                .custom-scrollbar::-webkit-scrollbar-thumb:hover {
                    background: rgba(255, 255, 255, 0.1);
                }
            `}</style>
        </div>
    );
}
