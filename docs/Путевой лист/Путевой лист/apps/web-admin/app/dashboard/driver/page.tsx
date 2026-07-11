"use client";

import React, { useState } from "react";
import Link from "next/link";
import { useRouter } from "next/navigation";

export default function DriverEntry() {
    const router = useRouter();
    const [waybillNum, setWaybillNum] = useState("");

    const handleEnter = (e: React.FormEvent) => {
        e.preventDefault();
        // In a real system we'd search by number, but here we'll assume ID = Number for the mock
        if (waybillNum) {
            router.push(`/dashboard/driver/${waybillNum}`);
        }
    };

    return (
        <div className="min-h-screen bg-[#020617] text-slate-200 font-sans p-6 flex flex-col items-center justify-center">
            <div className="w-full max-w-sm">
                <div className="text-center mb-12">
                    <div className="w-20 h-20 bg-gradient-to-tr from-blue-600 to-indigo-600 rounded-[2rem] flex items-center justify-center mx-auto mb-6 shadow-2xl shadow-blue-500/20">
                        <svg className="w-10 h-10 text-white" fill="none" stroke="currentColor" viewBox="0 0 24 24"><path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2.5} d="M12 18h.01M8 21h8a2 2 0 002-2V5a2 2 0 00-2-2H8a2 2 0 00-2 2v14a2 2 0 002 2z" /></svg>
                    </div>
                    <h1 className="text-3xl font-black text-white uppercase tracking-tight mb-2">Кабинет Водителя</h1>
                    <p className="text-[10px] text-slate-500 font-black uppercase tracking-[0.2em]">Digital Waybill Portal</p>
                </div>

                <form onSubmit={handleEnter} className="space-y-6">
                    <div className="bg-[#0f172a] border border-white/5 p-8 rounded-[40px] shadow-2xl ring-1 ring-white/5">
                        <label className="block text-[10px] font-bold text-slate-500 uppercase tracking-widest mb-4 text-center">Введите номер или ID листа</label>
                        <input
                            type="text"
                            placeholder="Напр: WL-0042"
                            value={waybillNum}
                            onChange={(e) => setWaybillNum(e.target.value)}
                            className="w-full px-5 py-5 bg-black/40 border border-white/10 rounded-2xl text-white focus:outline-none focus:ring-2 focus:ring-blue-500/50 transition-all font-mono text-2xl text-center"
                            required
                        />
                        <button type="submit" className="w-full mt-8 py-5 bg-blue-600 hover:bg-blue-500 text-white font-black uppercase tracking-[0.2em] text-[10px] rounded-[24px] shadow-2xl shadow-blue-600/20 transition-all active:scale-95">
                            Открыть Путевой Лист
                        </button>
                    </div>

                    <Link href="/dashboard" className="block text-center text-xs text-slate-600 hover:text-slate-400 transition-colors uppercase font-bold tracking-widest">
                        Вернуться в систему
                    </Link>
                </form>
            </div>
        </div>
    );
}
