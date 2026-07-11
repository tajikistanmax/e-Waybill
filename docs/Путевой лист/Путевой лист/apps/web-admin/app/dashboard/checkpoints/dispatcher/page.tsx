"use client";

import React, { useState } from "react";
import Link from "next/link";
import { useRouter } from "next/navigation";

export default function DispatcherCheck() {
    const router = useRouter();
    const [waybillId, setWaybillId] = useState("");
    const [status, setStatus] = useState("idle");

    const handleSearch = (e: React.FormEvent) => {
        e.preventDefault();
        if (waybillId) setStatus("found");
    };

    const handleApprove = async (e: React.FormEvent) => {
        e.preventDefault();
        if (!waybillId) return;
        try {
            const res = await fetch(`http://localhost:8080/api/waybills/${waybillId}/approve-dispatcher?signature=Dispatcher. Ivanova`, {
                method: "POST"
            });
            if (res.ok) {
                alert("Диспетчерское утверждение завершено. Лист передан водителю!");
                router.push("/dashboard");
            } else {
                alert("Ошибка: " + res.statusText);
            }
        } catch (err) {
            console.error(err);
            alert("Ошибка сети");
        }
    };

    return (
        <div className="min-h-screen bg-[#020617] text-slate-200 font-sans p-8 flex flex-col items-center">
            <div className="w-full max-w-2xl">
                <Link href="/dashboard" className="inline-flex items-center gap-2 text-slate-400 hover:text-white mb-8 transition-colors group">
                    <svg className="w-4 h-4 group-hover:-translate-x-1 transition-transform" fill="none" stroke="currentColor" viewBox="0 0 24 24"><path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M15 19l-7-7 7-7" /></svg>
                    Вернуться в панель управления
                </Link>

                <div className="bg-[#0f172a] border border-white/5 p-10 rounded-[40px] shadow-2xl relative overflow-hidden">
                    <div className="absolute top-0 right-0 w-64 h-64 bg-blue-600/5 blur-[80px] -mr-32 -mt-32"></div>

                    <div className="relative z-10">
                        <div className="flex items-center gap-4 mb-8">
                            <div className="p-3 bg-blue-600/10 rounded-2xl text-blue-400 border border-blue-500/20 shadow-lg shadow-blue-500/5">
                                <svg className="w-8 h-8" fill="none" stroke="currentColor" viewBox="0 0 24 24"><path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M9 12h6m-6 4h6m2 5H7a2 2 0 01-2-2V5a2 2 0 012-2h5.586a1 1 0 01.707.293l5.414 5.414a1 1 0 01.293.707V19a2 2 0 01-2 2z" /></svg>
                            </div>
                            <div>
                                <h1 className="text-2xl font-black text-white uppercase tracking-tight">Диспетчерский Центр</h1>
                                <p className="text-[10px] text-slate-500 font-black uppercase tracking-[0.2em]">Final Document Issuance & KEP Signing</p>
                            </div>
                        </div>

                        {status === "idle" ? (
                            <form onSubmit={handleSearch} className="space-y-6">
                                <div>
                                    <label className="block text-[10px] font-bold text-slate-500 uppercase tracking-widest mb-3">Введите ID Листа для выпуска в рейс</label>
                                    <input
                                        type="text"
                                        placeholder="Напр: WL-2025-0042"
                                        value={waybillId}
                                        onChange={(e) => setWaybillId(e.target.value)}
                                        className="w-full px-5 py-4 bg-black/40 border border-white/10 rounded-2xl text-white focus:outline-none focus:ring-2 focus:ring-blue-500/50 transition-all font-mono text-lg"
                                        required
                                    />
                                </div>
                                <button type="submit" className="w-full py-4 bg-blue-600 hover:bg-blue-500 text-white font-black uppercase tracking-widest text-xs rounded-2xl shadow-xl shadow-blue-600/20 transition-all active:scale-[0.98]">
                                    Проверить готовность (Мед/Тех)
                                </button>
                            </form>
                        ) : (
                            <form onSubmit={handleApprove} className="space-y-8 animate-in fade-in slide-in-from-bottom-4 duration-500">
                                <div className="bg-white/[0.02] border border-white/5 p-8 rounded-3xl">
                                    <h3 className="text-[10px] font-bold text-slate-500 uppercase tracking-widest mb-6 border-b border-white/5 pb-4">Статус предрейсового контроля</h3>
                                    <div className="space-y-4">
                                        <div className="flex items-center justify-between">
                                            <div className="flex items-center gap-3">
                                                <div className="w-2 h-2 bg-emerald-500 rounded-full shadow-[0_0_8px_rgba(16,185,129,0.5)]"></div>
                                                <span className="text-xs font-bold text-white uppercase">Медицинский осмотр</span>
                                            </div>
                                            <span className="text-[9px] font-black text-emerald-500 uppercase tracking-tighter">Пройден</span>
                                        </div>
                                        <div className="flex items-center justify-between">
                                            <div className="flex items-center gap-3">
                                                <div className="w-2 h-2 bg-emerald-500 rounded-full shadow-[0_0_8px_rgba(16,185,129,0.5)]"></div>
                                                <span className="text-xs font-bold text-white uppercase">Технический осмотр</span>
                                            </div>
                                            <span className="text-[9px] font-black text-emerald-500 uppercase tracking-tighter">Пройден</span>
                                        </div>
                                    </div>
                                </div>

                                <div className="bg-blue-500/[0.03] border border-blue-500/10 p-8 rounded-3xl">
                                    <p className="text-[10px] font-bold text-blue-400 uppercase tracking-[0.2em] mb-4 text-center">Юридическая финализация</p>
                                    <p className="text-xs text-slate-400 text-center leading-relaxed mb-6">Нажимая кнопку ниже, вы подтверждаете корректность данных и выпускаете электронный путевой лист в систему ЭДО с наложением КЭП диспетчера.</p>

                                    <button type="submit" className="w-full py-5 bg-blue-600 hover:bg-blue-500 text-white font-black uppercase tracking-[0.2em] text-xs rounded-[32px] shadow-2xl shadow-blue-600/20 transition-all active:scale-[0.98] outline outline-1 outline-blue-400/30">
                                        Утвердить и Подписать (КЭП)
                                    </button>
                                </div>

                                <div className="p-4 bg-white/[0.01] border border-white/5 rounded-2xl flex items-center justify-center gap-3">
                                    <span className="text-[10px] font-bold text-slate-600 uppercase">Оператор КЭП:</span>
                                    <span className="text-[10px] font-mono text-slate-500">GOST-R-34.10 // ID: DISP-84</span>
                                </div>
                            </form>
                        )}
                    </div>
                </div>
            </div>

            <style jsx global>{`
                body {
                    background-image: 
                        radial-gradient(circle at 100% 0%, rgba(37, 99, 235, 0.03) 0, transparent 50%),
                        radial-gradient(circle at 0% 100%, rgba(29, 78, 216, 0.02) 0, transparent 50%);
                }
            `}</style>
        </div>
    );
}
