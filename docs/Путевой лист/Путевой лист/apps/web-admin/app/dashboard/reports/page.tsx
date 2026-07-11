"use client";

import React, { useState } from "react";
import Link from "next/link";

export default function ReportsPage() {
    const [generating, setGenerating] = useState<string | null>(null);

    const reportTypes = [
        { id: "gosm", name: "Отчет ГСМ (Fuel Accounting)", desc: "Контроль расхода топлива, лимиты и остатки по всему парку.", color: "blue", icon: "M13 10V3L4 14h7v7l9-11h-7z" },
        { id: "compliance", name: "Compliance XML (Минтранс)", desc: "Экспорт путевых листов в формате XML согласно Приказу №390.", color: "emerald", icon: "M10 20l4-16m4 4l4 4-4 4M6 16l-4-4 4-4" },
        { id: "mileage", name: "Сводка Пробега (Monthly)", desc: "Детализация пробега по категориям: Грузовой, Автобус, Такси.", color: "indigo", icon: "M9 19v-6a2 2 0 00-2-2H5a2 2 0 00-2 2v6a2 2 0 002 2h2a2 2 0 002-2m6 0v-6a2 2 0 00-2-2h-2a2 2 0 00-2 2v6a2 2 0 002 2h2a2 2 0 002-2" },
        { id: "drivers", name: "Табель Водителей", desc: "Учет рабочего времени и выполненных рейсов.", color: "amber", icon: "M12 4.354a4 4 0 110 5.292M15 21H3v-1a6 6 0 0112 0v1zm0 0h6v-1a6 6 0 00-9-5.197M13 7a4 4 0 11-8 0 4 4 0 018 0z" }
    ];

    const handleGenerate = (id: string) => {
        setGenerating(id);
        setTimeout(() => setGenerating(null), 2000);
    };

    return (
        <div className="min-h-screen bg-[#020617] text-slate-300 p-8 flex flex-col items-center">
            <div className="w-full max-w-5xl">
                {/* Header Section */}
                <div className="flex items-center justify-between mb-12">
                    <div>
                        <h1 className="text-4xl font-black text-white tracking-tighter uppercase mb-1">Центр Отчетности</h1>
                        <p className="text-slate-500 text-xs font-bold uppercase tracking-[0.2em]">Reporting & Compliance Engine v2.0</p>
                    </div>
                    <Link href="/dashboard" className="px-6 py-3 bg-white/5 hover:bg-white/10 border border-white/5 rounded-2xl text-xs font-black uppercase tracking-widest transition-all">
                        Вернуться
                    </Link>
                </div>

                {/* Report Grid */}
                <div className="grid grid-cols-1 md:grid-cols-2 gap-8 mb-16">
                    {reportTypes.map((report) => (
                        <div key={report.id} className="bg-[#0f172a] border border-white/5 rounded-[32px] p-8 group hover:border-blue-500/30 transition-all relative overflow-hidden">
                            <div className="absolute top-0 right-0 p-6 opacity-[0.03] group-hover:scale-125 transition-transform duration-700">
                                <svg className="w-32 h-32" fill="white" viewBox="0 0 24 24"><path d={report.icon} /></svg>
                            </div>

                            <div className="flex gap-6 mb-8 relative z-10">
                                <div className={`w-14 h-14 bg-${report.color}-500/10 rounded-2xl flex items-center justify-center text-${report.color}-500 border border-${report.color}-500/20 shadow-lg shadow-${report.color}-500/10`}>
                                    <svg className="w-8 h-8" fill="none" stroke="currentColor" viewBox="0 0 24 24"><path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d={report.icon} /></svg>
                                </div>
                                <div className="flex-1">
                                    <h3 className="text-xl font-black text-white tracking-tight mb-2 uppercase">{report.name}</h3>
                                    <p className="text-xs text-slate-500 leading-relaxed max-w-[280px] font-medium">{report.desc}</p>
                                </div>
                            </div>

                            <div className="flex items-center gap-4 relative z-10">
                                <button
                                    onClick={() => handleGenerate(report.id)}
                                    disabled={generating !== null}
                                    className={`flex-1 py-4 rounded-2xl text-[10px] font-black uppercase tracking-widest transition-all flex items-center justify-center gap-3 ${generating === report.id ? 'bg-emerald-500 text-white' : 'bg-blue-600 hover:bg-blue-500 text-white shadow-lg shadow-blue-600/20'}`}
                                >
                                    {generating === report.id ? (
                                        <>
                                            <div className="w-4 h-4 border-2 border-white/30 border-t-white rounded-full animate-spin"></div>
                                            Генерация...
                                        </>
                                    ) : (
                                        <>
                                            <svg className="w-4 h-4" fill="none" stroke="currentColor" viewBox="0 0 24 24"><path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M4 16v1a3 3 0 003 3h10a3 3 0 003-3v-1m-4-4l-4 4m0 0l-4-4m4 4V4" /></svg>
                                            Сформировать PDF
                                        </>
                                    )}
                                </button>
                                <button className="p-4 bg-white/5 hover:bg-white/10 rounded-2xl border border-white/10 text-slate-400 transition-all active:scale-95">
                                    <svg className="w-5 h-5" fill="none" stroke="currentColor" viewBox="0 0 24 24"><path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M10.325 4.317c.426-1.756 2.924-1.756 3.35 0a1.724 1.724 0 002.573 1.066c1.543-.94 3.31.826 2.37 2.37a1.724 1.724 0 001.065 2.572c1.756.426 1.756 2.924 0 3.35a1.724 1.724 0 00-1.066 2.573c.94 1.543-.826 3.31-2.37 2.37a1.724 1.724 0 00-2.572 1.065c-.426 1.756-2.924 1.756-3.35 0a1.724 1.724 0 00-2.573-1.066-1.543.94-3.31-.826-2.37-2.37a1.724 1.724 0 00-1.065-2.572c-1.756-.426-1.756-2.924 0-3.35a1.724 1.724 0 001.066-2.573c-.94-1.543.826-3.31 2.37-2.37.996.608 2.296.07 2.572-1.065z" /></svg>
                                </button>
                            </div>
                        </div>
                    ))}
                </div>

                {/* Archive / History */}
                <div className="bg-[#0f172a] border border-white/5 rounded-[40px] overflow-hidden shadow-2xl">
                    <div className="px-10 py-8 border-b border-white/5 flex items-center justify-between bg-white/[0.02]">
                        <div>
                            <h2 className="text-xl font-black text-white uppercase tracking-tight">Архив Выгрузок</h2>
                            <p className="text-[10px] text-slate-500 font-bold uppercase tracking-widest mt-1">Recently generated compliance files</p>
                        </div>
                        <input placeholder="Search archives..." className="bg-black/40 border border-white/5 rounded-xl px-4 py-2 text-xs text-white focus:outline-none focus:ring-1 ring-blue-500" />
                    </div>
                    <div className="p-10">
                        <table className="w-full text-left">
                            <thead>
                                <tr className="text-[10px] font-black text-slate-500 uppercase tracking-[0.2em]">
                                    <th className="pb-6">Тип файла</th>
                                    <th className="pb-6">Дата создания</th>
                                    <th className="pb-6">Категория</th>
                                    <th className="pb-6 text-right">Размер</th>
                                </tr>
                            </thead>
                            <tbody className="divide-y divide-white/[0.03]">
                                {[
                                    { name: "REPT-GOSM-2025-01.pdf", date: "Сегодня, 14:15", cat: "Fuel Control", size: "2.4 MB" },
                                    { name: "Compliance_Export_390.xml", date: "Вчера, 09:20", cat: "Regulatory", size: "128 KB" },
                                    { name: "Fleet_Efficiency_Jan.xlsx", date: "24.12.2024", cat: "Analytics", size: "1.1 MB" }
                                ].map((file, i) => (
                                    <tr key={i} className="group hover:bg-white/[0.01] transition-all">
                                        <td className="py-5">
                                            <div className="flex items-center gap-3">
                                                <div className="w-8 h-8 rounded-lg bg-blue-500/10 flex items-center justify-center text-blue-500 group-hover:bg-blue-600 group-hover:text-white transition-all">
                                                    <svg className="w-4 h-4" fill="none" stroke="currentColor" viewBox="0 0 24 24"><path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M7 21h10a2 2 0 002-2V9.414a1 1 0 00-.293-.707l-5.414-5.414A1 1 0 0012.586 3H7a2 2 0 00-2 2v14a2 2 0 002 2z" /></svg>
                                                </div>
                                                <span className="text-xs font-bold text-white uppercase tracking-tight">{file.name}</span>
                                            </div>
                                        </td>
                                        <td className="py-5 text-xs text-slate-500 font-medium uppercase">{file.date}</td>
                                        <td className="py-5">
                                            <span className="text-[9px] font-black px-2 py-0.5 rounded bg-white/5 border border-white/10 text-slate-400 uppercase">{file.cat}</span>
                                        </td>
                                        <td className="py-5 text-right font-mono text-[10px] text-blue-500">{file.size}</td>
                                    </tr>
                                ))}
                            </tbody>
                        </table>
                    </div>
                </div>
            </div>

            <style jsx global>{`
                body {
                    background-image: 
                        radial-gradient(circle at 0% 0%, rgba(29, 78, 216, 0.03) 0, transparent 50%),
                        radial-gradient(circle at 100% 100%, rgba(79, 70, 229, 0.03) 0, transparent 50%);
                }
            `}</style>
        </div>
    );
}
