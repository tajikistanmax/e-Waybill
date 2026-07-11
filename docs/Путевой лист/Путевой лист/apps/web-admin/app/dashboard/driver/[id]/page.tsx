"use client";

import React, { useState, useEffect } from "react";
import Link from "next/link";
import { useParams } from "next/navigation";

interface Waybill {
    id: number;
    number: string;
    status: string;
    vehicle: { plateNumber: string, model: string };
    departureLocation: string;
    arrivalLocation: string;
    odometerDeparture: number;
}

export default function DriverCockpit() {
    const params = useParams();
    const [waybill, setWaybill] = useState<Waybill | null>(null);
    const [loading, setLoading] = useState(true);
    const [odometer, setOdometer] = useState("");
    const [fuel, setFuel] = useState("");
    const [statusText, setStatusText] = useState("");

    useEffect(() => {
        fetch(`http://localhost:8080/api/waybills/${params.id}`)
            .then(res => res.json())
            .then(json => {
                setWaybill(json);
                setLoading(false);
            })
            .catch(err => {
                console.error(err);
                setLoading(false);
            });
    }, [params.id]);

    const handleConfirmReceipt = async () => {
        setStatusText("Подписание...");
        try {
            const res = await fetch(`http://localhost:8080/api/waybills/${params.id}/confirm-driver?signature=DRIVER_KEP_${params.id}`, {
                method: 'POST'
            });
            if (res.ok) {
                const updated = await res.json();
                setWaybill(updated);
            }
        } catch (e) {
            console.error(e);
        } finally {
            setStatusText("");
        }
    };

    const handleReportArrival = async (e: React.FormEvent) => {
        e.preventDefault();
        setStatusText("Отправка данных...");
        try {
            const res = await fetch(`http://localhost:8080/api/waybills/${params.id}/report-arrival?odometer=${odometer}&fuel=${fuel}`, {
                method: 'POST'
            });
            if (res.ok) {
                const updated = await res.json();
                setWaybill(updated);
            }
        } catch (e) {
            console.error(e);
        } finally {
            setStatusText("");
        }
    };

    if (loading) return <div className="min-h-screen bg-[#020617] flex items-center justify-center"><div className="w-8 h-8 border-4 border-blue-600 border-t-transparent rounded-full animate-spin"></div></div>;

    if (!waybill) return <div className="min-h-screen bg-[#020617] text-white p-8">Лист не найден</div>;

    return (
        <div className="min-h-screen bg-[#020617] text-slate-200 font-sans p-4 safe-top">
            <div className="max-w-md mx-auto space-y-6">
                {/* Header */}
                <div className="flex items-center justify-between">
                    <Link href="/dashboard/driver" className="p-3 bg-white/5 rounded-2xl">
                        <svg className="w-6 h-6" fill="none" stroke="currentColor" viewBox="0 0 24 24"><path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M15 19l-7-7 7-7" /></svg>
                    </Link>
                    <div className="text-right">
                        <p className="text-[10px] font-black text-slate-500 uppercase tracking-widest">Путевой Лист</p>
                        <p className="text-sm font-bold text-white font-mono">{waybill.number}</p>
                    </div>
                </div>

                {/* Status Card */}
                <div className={`p-6 rounded-[2.5rem] border ${waybill.status === 'ACTIVE' ? 'bg-emerald-500/10 border-emerald-500/20' :
                    waybill.status === 'PENDING_DRIVER' ? 'bg-blue-500/10 border-blue-500/20' : 'bg-white/5 border-white/10'
                    }`}>
                    <div className="flex items-center gap-4 mb-4">
                        <div className={`w-3 h-3 rounded-full ${waybill.status === 'ACTIVE' ? 'bg-emerald-500 animate-pulse' : 'bg-blue-500'}`}></div>
                        <span className="text-xs font-black uppercase tracking-[0.2em]">{waybill.status.replace('_', ' ')}</span>
                    </div>

                    {waybill.status === 'PENDING_DRIVER' && (
                        <div className="space-y-4">
                            <h2 className="text-2xl font-black text-white leading-tight">Подтвердите получение документа</h2>
                            <p className="text-xs text-slate-400">Проверьте данные ТС и маршрута перед выездом.</p>
                            <button onClick={handleConfirmReceipt} className="w-full py-5 bg-blue-600 hover:bg-blue-500 text-white font-black uppercase tracking-widest text-xs rounded-3xl shadow-xl shadow-blue-500/20">
                                {statusText || "Подписать и Выехать"}
                            </button>
                        </div>
                    )}

                    {waybill.status === 'ACTIVE' && (
                        <div className="text-center">
                            <h2 className="text-2xl font-black text-white mb-6">Рейс Активен</h2>
                            <div className="bg-white p-4 rounded-3xl mb-6 aspect-square max-w-[200px] mx-auto shadow-2xl">
                                <svg className="w-full h-full text-slate-900" fill="currentColor" viewBox="0 0 24 24">
                                    <path d="M3 3h8v8H3V3zm2 2v4h4V5H5zm8-2h8v8h-8V3zm2 2v4h4V5h-4zM3 13h8v8H3v-8zm2 2v4h4v-4H5zm13-2h3v2h-3v-2zm-3 0h2v2h-2v-2zm3 3h3v2h-3v-2zm-3 3h2v2h-2v-2zm3-3h3v2h-3v-2zm-3 3h2v2h-2v-2zm3 3h3v2h-3v-2zm-3 0h2v2h-2v-2z" />
                                </svg>
                            </div>
                            <p className="text-[10px] font-bold text-slate-500 uppercase tracking-widest mb-8">QR-код для проверки ГИБДД</p>
                        </div>
                    )}
                </div>

                {/* Details */}
                <div className="grid grid-cols-2 gap-4">
                    <div className="p-5 bg-white/5 border border-white/10 rounded-3xl">
                        <p className="text-[9px] font-bold text-slate-500 uppercase mb-2">ТС</p>
                        <p className="text-sm font-bold text-white">{waybill.vehicle?.plateNumber}</p>
                        <p className="text-[10px] text-slate-500">{waybill.vehicle?.model}</p>
                    </div>
                    <div className="p-5 bg-white/5 border border-white/10 rounded-3xl">
                        <p className="text-[9px] font-bold text-slate-500 uppercase mb-2">Одометр</p>
                        <p className="text-sm font-bold text-white">{waybill.odometerDeparture} км</p>
                        <p className="text-[10px] text-slate-500">При выезде</p>
                    </div>
                </div>

                {/* Return Form */}
                {waybill.status === 'ACTIVE' && (
                    <form onSubmit={handleReportArrival} className="bg-[#0f172a] border border-white/5 p-8 rounded-[3rem] space-y-6">
                        <h3 className="text-lg font-black text-white uppercase tracking-tight text-center">Завершение рейса</h3>
                        <div className="space-y-4">
                            <div>
                                <label className="block text-[10px] font-bold text-slate-500 uppercase mb-2 ml-4">Текущий одометр</label>
                                <input
                                    type="number"
                                    value={odometer}
                                    onChange={e => setOdometer(e.target.value)}
                                    className="w-full bg-black/40 border border-white/10 rounded-2xl p-4 text-white font-mono"
                                    placeholder="0.0"
                                    required
                                />
                            </div>
                            <div>
                                <label className="block text-[10px] font-bold text-slate-500 uppercase mb-2 ml-4">Остаток топлива (L)</label>
                                <input
                                    type="number"
                                    value={fuel}
                                    onChange={e => setFuel(e.target.value)}
                                    className="w-full bg-black/40 border border-white/10 rounded-2xl p-4 text-white font-mono"
                                    placeholder="0"
                                    required
                                />
                            </div>
                        </div>
                        <button type="submit" className="w-full py-5 bg-white text-black font-black uppercase text-xs tracking-widest rounded-3xl shadow-xl transition-transform active:scale-95">
                            {statusText || "Сдать смену (Заезд)"}
                        </button>
                    </form>
                )}

                {waybill.status.startsWith('PENDING_RETURN') && (
                    <div className="p-8 bg-blue-500/10 border border-blue-500/20 rounded-[3rem] text-center">
                        <div className="w-12 h-12 bg-blue-500 rounded-full flex items-center justify-center mx-auto mb-4 animate-bounce">
                            <svg className="w-6 h-6 text-white" fill="none" stroke="currentColor" viewBox="0 0 24 24"><path strokeLinecap="round" strokeLinejoin="round" strokeWidth={3} d="M5 13l4 4L19 7" /></svg>
                        </div>
                        <h3 className="text-xl font-black text-white mb-2">Данные переданы</h3>
                        <p className="text-xs text-slate-400">Ожидайте проверки ТС и здоровья для завершения Титулов 4 и 5.</p>
                    </div>
                )}
            </div>
        </div>
    );
}
