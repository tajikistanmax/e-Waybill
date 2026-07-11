"use client";

import React, { useState, useEffect } from "react";
import Link from "next/link";

interface Waybill {
    id: number;
    number: string;
    status: string;
    vehicle: { plateNumber: string, model: string };
    odometerArrival: number;
    fuelArrival: number;
}

export default function PostTripHub() {
    const [waybills, setWaybills] = useState<Waybill[]>([]);
    const [loading, setLoading] = useState(true);

    useEffect(() => {
        fetch('http://localhost:8080/api/waybills')
            .then(res => res.json())
            .then(json => {
                const filtered = json.filter((w: Waybill) =>
                    w.status === 'PENDING_RETURN_TECH' || w.status === 'PENDING_RETURN_MED'
                );
                setWaybills(filtered);
                setLoading(false);
            })
            .catch(err => {
                console.error(err);
                setLoading(false);
            });
    }, []);

    const handleApprove = async (id: number, type: 'technical' | 'medical') => {
        const endpoint = type === 'technical' ? 'approve-return-technical' : 'approve-return-medical';
        const signature = type === 'technical' ? 'MECH_ARRIVAL_SIG' : 'DOCTOR_ARRIVAL_SIG';

        try {
            const res = await fetch(`http://localhost:8080/api/waybills/${id}/${endpoint}?signature=${signature}`, {
                method: 'POST'
            });
            if (res.ok) {
                setWaybills(prev => prev.filter(w => w.id !== id || (w.status === 'PENDING_RETURN_TECH' && type === 'technical')));
                // Re-fetch to update status in the list if it moved from TECH to MED
                const updated = await res.json();
                if (updated.status === 'PENDING_RETURN_MED' || updated.status === 'COMPLETED') {
                    setWaybills(prev => {
                        const others = prev.filter(w => w.id !== id);
                        if (updated.status === 'PENDING_RETURN_MED') return [...others, updated];
                        return others;
                    });
                }
            }
        } catch (e) {
            console.error(e);
        }
    };

    if (loading) return <div className="min-h-screen bg-[#020617] flex items-center justify-center"><div className="w-12 h-12 border-4 border-rose-600 border-t-transparent rounded-full animate-spin"></div></div>;

    return (
        <div className="min-h-screen bg-[#020617] text-slate-300 p-8">
            <div className="max-w-6xl mx-auto space-y-12">
                <header className="flex justify-between items-end">
                    <div>
                        <h1 className="text-4xl font-black text-white tracking-tighter uppercase mb-2">Post-Trip Hub</h1>
                        <p className="text-slate-500 font-bold uppercase text-[10px] tracking-[0.3em]">Обработка Титулов 4 и 5 (Возврат ТС)</p>
                    </div>
                    <Link href="/dashboard" className="px-6 py-2 bg-white/5 border border-white/10 rounded-xl text-xs font-bold hover:bg-white/10 transition-all">В главное меню</Link>
                </header>

                <div className="grid grid-cols-1 lg:grid-cols-2 gap-12">
                    {/* Technical Check Section (T4) */}
                    <section className="space-y-6">
                        <div className="flex items-center gap-3">
                            <div className="w-10 h-10 bg-rose-500/10 border border-rose-500/20 rounded-xl flex items-center justify-center text-rose-500">
                                <svg className="w-6 h-6" fill="none" stroke="currentColor" viewBox="0 0 24 24"><path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M19 21V5a2 2 0 00-2-2H7a2 2 0 00-2 2v16m14 0h2m-2 0h-5m-9 0H3m2 0h5M9 7h1m-1 4h1m4-4h1m-1 4h1m-5 10v-5a1 1 0 011-1h2a1 1 0 011 1v5m-4 0h4" /></svg>
                            </div>
                            <h2 className="text-xl font-black text-white uppercase italic tracking-tight">Титул 4: Техконтроль Заезда</h2>
                        </div>

                        <div className="space-y-4">
                            {waybills.filter(w => w.status === 'PENDING_RETURN_TECH').map(w => (
                                <div key={w.id} className="p-6 bg-[#0f172a] border border-white/5 rounded-3xl group hover:border-rose-500/30 transition-all duration-500">
                                    <div className="flex justify-between items-start mb-6">
                                        <div>
                                            <p className="text-sm font-bold text-white mb-1">{w.vehicle.plateNumber}</p>
                                            <p className="text-[10px] text-slate-500 font-bold uppercase">{w.number}</p>
                                        </div>
                                        <div className="text-right">
                                            <p className="text-[10px] text-slate-500 uppercase mb-1">Одометр</p>
                                            <p className="text-sm font-mono text-rose-400 font-bold">{w.odometerArrival} км</p>
                                        </div>
                                    </div>
                                    <button
                                        onClick={() => handleApprove(w.id, 'technical')}
                                        className="w-full py-3 bg-rose-600 hover:bg-rose-500 text-white font-black uppercase tracking-widest text-[10px] rounded-xl shadow-lg shadow-rose-500/20 transition-all active:scale-95"
                                    >
                                        Подписать заезд (КЭП)
                                    </button>
                                </div>
                            ))}
                            {waybills.filter(w => w.status === 'PENDING_RETURN_TECH').length === 0 && (
                                <p className="text-slate-600 text-sm font-bold italic py-12 text-center border-2 border-dashed border-white/5 rounded-3xl">Нет ТС на осмотр заезда</p>
                            )}
                        </div>
                    </section>

                    {/* Medical Check Section (T5) */}
                    <section className="space-y-6">
                        <div className="flex items-center gap-3">
                            <div className="w-10 h-10 bg-pink-500/10 border border-pink-500/20 rounded-xl flex items-center justify-center text-pink-500">
                                <svg className="w-6 h-6" fill="none" stroke="currentColor" viewBox="0 0 24 24"><path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M4.318 6.318a4.5 4.5 0 000 6.364L12 20.364l7.682-7.682a4.5 4.5 0 00-6.364-6.364L12 7.636l-1.318-1.318a4.5 4.5 0 00-6.364 0z" /></svg>
                            </div>
                            <h2 className="text-xl font-black text-white uppercase italic tracking-tight">Титул 5: Медосмотр (Возврат)</h2>
                        </div>

                        <div className="space-y-4">
                            {waybills.filter(w => w.status === 'PENDING_RETURN_MED').map(w => (
                                <div key={w.id} className="p-6 bg-[#0f172a] border border-white/5 rounded-3xl group hover:border-pink-500/30 transition-all duration-500">
                                    <div className="flex justify-between items-start mb-6">
                                        <div>
                                            <p className="text-sm font-bold text-white mb-1">{w.vehicle.plateNumber}</p>
                                            <p className="text-[10px] text-slate-500 font-bold uppercase">{w.number}</p>
                                        </div>
                                        <div className="px-3 py-1 bg-pink-500/10 text-pink-500 rounded-lg text-[10px] font-black tracking-widest uppercase">
                                            Медосмотр Т5
                                        </div>
                                    </div>
                                    <button
                                        onClick={() => handleApprove(w.id, 'medical')}
                                        className="w-full py-3 bg-pink-600 hover:bg-pink-500 text-white font-black uppercase tracking-widest text-[10px] rounded-xl shadow-lg shadow-pink-500/20 transition-all active:scale-95"
                                    >
                                        Допуск после рейса (КЭП)
                                    </button>
                                </div>
                            ))}
                            {waybills.filter(w => w.status === 'PENDING_RETURN_MED').length === 0 && (
                                <p className="text-slate-600 text-sm font-bold italic py-12 text-center border-2 border-dashed border-white/5 rounded-3xl">Нет водителей на послерейсовый контроль</p>
                            )}
                        </div>
                    </section>
                </div>
            </div>
        </div>
    );
}
