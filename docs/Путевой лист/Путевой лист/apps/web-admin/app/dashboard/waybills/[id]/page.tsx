"use client";

import React, { useState, useEffect } from "react";
import Link from "next/link";
import { useParams } from "next/navigation";

interface Waybill {
    id: number;
    number: string;
    status: string;
    driverId: number;
    vehicle: { plateNumber: string, model: string };
    type: { name: string };
    departureLocation?: string;
    arrivalLocation?: string;
    estimatedFuel?: number;
    messageType?: string;
    odometerDeparture?: number;
    odometerArrival?: number;
    issuerName?: string;
    fieldValues: { field: { label: string }, value: string }[];
    mechanicSignature?: string;
    mechanicSeal?: string;
    technicalControlAt?: string;
    doctorId?: number;
    doctorSignature?: string;
    doctorSeal?: string;
    medicalControlAt?: string;
    dispatcherSignature?: string;
    dispatcherApprovalAt?: string;
    driverSignature?: string;
    driverReceiptAt?: string;
    mechanicArrivalSignature?: string;
    doctorArrivalSignature?: string;
    postTripTechnicalControlAt?: string;
    postTripMedicalControlAt?: string;
    fuelArrival?: number;
    issuedAt?: string;
    closedAt?: string;
    createdAt: string;
}

export default function WaybillDetailPage() {
    const params = useParams();
    const [waybill, setWaybill] = useState<Waybill | null>(null);
    const [loading, setLoading] = useState(true);
    const [violations, setViolations] = useState<string[]>([]);

    useEffect(() => {
        const loadData = async () => {
            try {
                const [wRes, vRes] = await Promise.all([
                    fetch(`http://localhost:8080/api/waybills/${params.id}`),
                    fetch(`http://localhost:8080/api/waybills/${params.id}/validate`)
                ]);
                const wData = await wRes.json();
                const vData = await vRes.json();
                setWaybill(wData);
                setViolations(vData);
            } catch (err) {
                console.error(err);
            } finally {
                setLoading(false);
            }
        };
        loadData();
    }, [params.id]);

    const handlePrint = () => window.print();

    const getStatusStyle = (status: string) => {
        switch (status) {
            case "ACTIVE": return "bg-emerald-500 text-white";
            case "PENDING_TECH": return "bg-amber-500 text-white";
            case "PENDING_MED": return "bg-indigo-500 text-white";
            case "PENDING_DISPATCHER": return "bg-blue-600 text-white";
            case "PENDING_DRIVER": return "bg-violet-600 text-white";
            case "PENDING_RETURN_TECH": return "bg-rose-500 text-white";
            case "PENDING_RETURN_MED": return "bg-pink-500 text-white";
            case "COMPLETED": return "bg-slate-700 text-slate-300";
            default: return "bg-slate-500 text-white";
        }
    };

    const getTitleStatus = (titleNum: number) => {
        const statuses = [
            ["PENDING_MED", "PENDING_TECH", "PENDING_DISPATCHER", "PENDING_DRIVER", "ACTIVE", "PENDING_RETURN_TECH", "PENDING_RETURN_MED", "COMPLETED"], // T1
            ["PENDING_TECH", "PENDING_DISPATCHER", "PENDING_DRIVER", "ACTIVE", "PENDING_RETURN_TECH", "PENDING_RETURN_MED", "COMPLETED"], // T2
            ["PENDING_DISPATCHER", "PENDING_DRIVER", "ACTIVE", "PENDING_RETURN_TECH", "PENDING_RETURN_MED", "COMPLETED"], // T3
            ["PENDING_RETURN_MED", "COMPLETED"], // T4
            ["COMPLETED"] // T5
        ];

        const currentIdx = statuses[titleNum - 1].indexOf(waybill?.status || "");
        if (currentIdx > 0) return "completed";
        if (currentIdx === 0) return "active";
        return "pending";
    };

    if (loading) return (
        <div className="min-h-screen bg-[#020617] flex items-center justify-center">
            <div className="w-12 h-12 border-4 border-blue-600 border-t-transparent rounded-full animate-spin"></div>
        </div>
    );

    if (!waybill) return (
        <div className="min-h-screen bg-[#020617] flex flex-col items-center justify-center text-slate-400">
            <svg className="w-16 h-16 mb-4 opacity-20" fill="none" stroke="currentColor" viewBox="0 0 24 24"><path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M9.172 9.172a4 4 0 015.656 0M9 10h.01M15 10h.01M21 12a9 9 0 11-18 0 9 9 0 0118 0z" /></svg>
            <p>Документ не обнаружен в системе</p>
            <Link href="/dashboard" className="mt-4 text-blue-500 hover:underline">Вернуться в систему</Link>
        </div>
    );

    return (
        <div className="min-h-screen bg-[#020617] text-slate-300 p-8 print:bg-white print:p-0 print:text-black">
            <div className="max-w-5xl mx-auto flex flex-col gap-8">
                {/* Navigation Bar */}
                <div className="flex items-center justify-between print:hidden">
                    <Link href="/dashboard" className="inline-flex items-center gap-2 text-slate-400 hover:text-white transition-colors group">
                        <svg className="w-4 h-4 group-hover:-translate-x-1 transition-transform" fill="none" stroke="currentColor" viewBox="0 0 24 24"><path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M15 19l-7-7 7-7" /></svg>
                        Вернуться
                    </Link>
                    <div className="flex gap-3">
                        <button onClick={handlePrint} className="px-6 py-2.5 bg-white/5 hover:bg-white/10 border border-white/10 rounded-xl text-sm font-bold transition-all active:scale-95 flex items-center gap-2 text-white">
                            <svg className="w-4 h-4" fill="none" stroke="currentColor" viewBox="0 0 24 24"><path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M17 17h2a2 2 0 002-2v-4a2 2 0 00-2-2H5a2 2 0 00-2 2v4a2 2 0 002 2h2m2 4h6a2 2 0 002-2v-4a2 2 0 00-2-2H9a2 2 0 00-2 2v4a2 2 0 002 2zm8-12V5a2 2 0 00-2-2H9a2 2 0 00-2 2v4h10z" /></svg>
                            Печать документа
                        </button>
                    </div>
                </div>

                {/* Main Document Card */}
                <div className="bg-[#0f172a] border border-white/5 rounded-[40px] shadow-2xl relative overflow-hidden print:border-none print:shadow-none print:rounded-none">
                    {/* Header Strip */}
                    <div className="h-2 w-full bg-gradient-to-r from-blue-600 via-indigo-600 to-blue-600 print:hidden"></div>

                    {/* FNS Title Tracker */}
                    <div className="bg-black/20 border-b border-white/5 p-6 flex items-center justify-between print:hidden">
                        {[1, 2, 3, 4, 5].map((t) => (
                            <div key={t} className="flex flex-col items-center gap-2 flex-1 relative group">
                                <div className={`w-10 h-10 rounded-full flex items-center justify-center border-2 transition-all duration-500 ${getTitleStatus(t) === 'completed' ? 'bg-blue-600 border-blue-600 text-white shadow-lg shadow-blue-500/20' :
                                    getTitleStatus(t) === 'active' ? 'bg-blue-500/20 border-blue-500 text-blue-400 animate-pulse' :
                                        'bg-transparent border-white/10 text-slate-600'
                                    }`}>
                                    {getTitleStatus(t) === 'completed' ? (
                                        <svg className="w-5 h-5" fill="none" stroke="currentColor" viewBox="0 0 24 24"><path strokeLinecap="round" strokeLinejoin="round" strokeWidth={3} d="M5 13l4 4L19 7" /></svg>
                                    ) : (
                                        <span className="text-sm font-black">{t}</span>
                                    )}
                                </div>
                                <span className={`text-[9px] font-bold uppercase tracking-widest ${getTitleStatus(t) === 'pending' ? 'text-slate-600' : 'text-slate-400'
                                    }`}>Титул {t}</span>
                                {t < 5 && (
                                    <div className="absolute top-5 -right-1/2 w-full h-[1px] bg-white/5 -z-10 group-last:hidden"></div>
                                )}
                            </div>
                        ))}
                    </div>

                    <div className="p-12">
                        {/* Title & Status Block */}
                        <div className="flex flex-col md:flex-row justify-between items-start gap-8 mb-16 border-b border-white/5 pb-12 print:border-black">
                            <div>
                                <div className="flex items-center gap-3 mb-4">
                                    <div className="w-8 h-8 bg-blue-600 rounded-lg flex items-center justify-center print:hidden">
                                        <svg className="w-5 h-5 text-white" fill="none" stroke="currentColor" viewBox="0 0 24 24"><path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2.5} d="M13 10V3L4 14h7v7l9-11h-7z" /></svg>
                                    </div>
                                    <h1 className="text-4xl font-black text-white tracking-tighter print:text-black uppercase">Путевой Лист</h1>
                                </div>
                                <div className="flex items-center gap-4 text-slate-500 font-mono">
                                    <span className="text-blue-500 font-bold tracking-widest">{waybill.number}</span>
                                    <span className="opacity-20">/</span>
                                    <span>Создано: {new Date(waybill.createdAt).toLocaleString()}</span>
                                </div>
                            </div>
                            <div className="flex flex-col items-end gap-2">
                                <span className={`px-4 py-1.5 rounded-lg text-xs font-black uppercase tracking-widest ${getStatusStyle(waybill.status)} shadow-lg`}>
                                    {waybill.status.replace('_', ' ')}
                                </span>
                                <p className="text-[10px] text-slate-500 font-bold uppercase tracking-widest">Цифровой статус документа</p>
                            </div>
                        </div>

                        {/* Route Highlights */}
                        <div className="grid grid-cols-1 md:grid-cols-3 gap-8 mb-16">
                            <div className="bg-white/[0.02] border border-white/5 p-6 rounded-2xl relative overflow-hidden group">
                                <p className="text-[10px] font-bold text-slate-500 uppercase tracking-widest mb-4">Автотранспорт</p>
                                <p className="text-xl font-bold text-white mb-1 print:text-black">{waybill.vehicle?.plateNumber}</p>
                                <p className="text-xs text-slate-500">{waybill.vehicle?.model}</p>
                            </div>
                            <div className="bg-white/[0.02] border border-white/5 p-6 rounded-2xl relative md:col-span-2">
                                <div className="flex items-center justify-between gap-4 h-full">
                                    <div className="flex-1">
                                        <p className="text-[10px] font-bold text-slate-500 uppercase tracking-widest mb-4">Пункт Отправления</p>
                                        <p className="text-lg font-bold text-white print:text-black">{waybill.departureLocation || '—'}</p>
                                    </div>
                                    <div className="flex flex-col items-center px-4">
                                        <div className="w-10 h-10 rounded-full border border-blue-500/30 flex items-center justify-center text-blue-500">
                                            <svg className="w-5 h-5" fill="none" stroke="currentColor" viewBox="0 0 24 24"><path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M17 8l4 4m0 0l-4 4m4-4H3" /></svg>
                                        </div>
                                    </div>
                                    <div className="flex-1 text-right">
                                        <p className="text-[10px] font-bold text-slate-500 uppercase tracking-widest mb-4">Пункт Назначения</p>
                                        <p className="text-lg font-bold text-white print:text-black">{waybill.arrivalLocation || '—'}</p>
                                    </div>
                                </div>
                            </div>
                            <div className="bg-indigo-500/[0.05] border border-indigo-500/10 p-6 rounded-2xl">
                                <p className="text-[10px] font-bold text-indigo-400 uppercase tracking-widest mb-4">Данные Титула 1 (ЭПЛ)</p>
                                <div className="space-y-3">
                                    <div className="flex justify-between">
                                        <span className="text-[10px] text-slate-500 uppercase">Одометр (Выезд)</span>
                                        <span className="text-xs font-mono text-white">{waybill.odometerDeparture || '0.0'} км</span>
                                    </div>
                                    <div className="flex justify-between">
                                        <span className="text-[10px] text-slate-500 uppercase">Вид сообщения</span>
                                        <span className="text-xs font-bold text-indigo-300 uppercase italic">{waybill.messageType || 'URBAN'}</span>
                                    </div>
                                    <div className="pt-2 border-t border-white/5">
                                        <p className="text-[8px] text-slate-600 uppercase mb-1">Оформил:</p>
                                        <p className="text-[10px] text-slate-300 font-bold">{waybill.issuerName || 'SYSTERM OPERATOR'}</p>
                                    </div>
                                </div>
                            </div>
                        </div>

                        {/* Global Compliance Guard (Synthesized from 10+ TS) */}
                        <div className="mb-16">
                            <div className="flex items-center gap-4 mb-6">
                                <div className="p-2 bg-blue-600 rounded-lg shadow-lg shadow-blue-600/20">
                                    <svg className="w-5 h-5 text-white" fill="none" stroke="currentColor" viewBox="0 0 24 24"><path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M9 12l2 2 4-4m5.618-4.016A11.955 11.955 0 0112 2.944a11.955 11.955 0 01-8.618 3.04A12.02 12.02 0 003 9c0 5.591 3.824 10.29 9 11.622 5.176-1.332 9-6.03 9-11.622 0-1.042-.133-2.052-.382-3.016z" /></svg>
                                </div>
                                <div>
                                    <h3 className="text-xl font-black text-white uppercase tracking-tighter italic">Vanguard Compliance Guard</h3>
                                    <p className="text-[10px] text-slate-500 font-bold uppercase tracking-[0.2em]">Проверка по 10+ Техническим Условиям (vUltimate)</p>
                                </div>
                            </div>

                            {violations.length > 0 ? (
                                <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
                                    {violations.map((v, i) => (
                                        <div key={i} className="flex items-start gap-4 p-5 bg-rose-500/[0.03] border border-rose-500/20 rounded-3xl relative overflow-hidden">
                                            <div className="w-1.5 h-1.5 rounded-full bg-rose-500 mt-1.5 animate-pulse"></div>
                                            <p className="text-xs font-bold text-rose-200/80 leading-relaxed">{v}</p>
                                        </div>
                                    ))}
                                </div>
                            ) : (
                                <div className="p-8 bg-emerald-500/[0.03] border border-emerald-500/10 rounded-[3rem] text-center">
                                    <div className="inline-flex p-3 bg-emerald-500/20 rounded-2xl mb-4">
                                        <svg className="w-6 h-6 text-emerald-500" fill="none" stroke="currentColor" viewBox="0 0 24 24"><path strokeLinecap="round" strokeLinejoin="round" strokeWidth={3} d="M5 13l4 4L19 7" /></svg>
                                    </div>
                                    <h4 className="text-lg font-black text-emerald-400 uppercase">Compliance Green Light</h4>
                                    <p className="text-[10px] text-slate-500 font-bold uppercase mt-1">Документ полностью соответствует всем нормам РФ и ТЗ</p>
                                </div>
                            )}
                        </div>
                        <div className="grid grid-cols-1 lg:grid-cols-2 gap-12 mb-16">
                            <section>
                                <div className="flex items-center gap-3 mb-6">
                                    <div className="w-1.5 h-6 bg-blue-600 rounded-full"></div>
                                    <h3 className="text-lg font-bold text-white uppercase tracking-tight print:text-black">Технические параметры</h3>
                                </div>
                                <div className="space-y-4">
                                    <div className="flex justify-between p-4 bg-white/[0.01] rounded-xl border border-white/5 print:border-black/10">
                                        <span className="text-slate-400 text-sm">Табельный номер водителя</span>
                                        <span className="text-white font-mono font-bold print:text-black">#{waybill.driverId}</span>
                                    </div>
                                    <div className="flex justify-between p-4 bg-white/[0.01] rounded-xl border border-white/5 print:border-black/10">
                                        <span className="text-slate-400 text-sm">Планируемое топливо</span>
                                        <span className="text-white font-bold print:text-black font-mono">{waybill.estimatedFuel || '—'} L</span>
                                    </div>
                                    <div className="flex justify-between p-4 bg-emerald-500/[0.02] border border-emerald-500/10 rounded-xl">
                                        <span className="text-slate-400 text-sm">Режим перевозки</span>
                                        <span className="text-emerald-400 font-bold print:text-black">{waybill.type?.name || '—'}</span>
                                    </div>
                                    {waybill.status === "COMPLETED" && (
                                        <>
                                            <div className="flex justify-between p-4 bg-blue-500/[0.05] border border-blue-500/10 rounded-xl">
                                                <span className="text-slate-400 text-sm">Одометр (Приезд)</span>
                                                <span className="text-white font-bold font-mono">{waybill.odometerArrival || '—'} км</span>
                                            </div>
                                            <div className="flex justify-between p-4 bg-blue-500/[0.05] border border-blue-500/10 rounded-xl">
                                                <span className="text-slate-400 text-sm">Остаток топлива (Закрытие)</span>
                                                <span className="text-white font-bold font-mono">{waybill.fuelArrival || '—'} L</span>
                                            </div>
                                        </>
                                    )}
                                </div>
                            </section>

                            <section>
                                <div className="flex items-center gap-3 mb-6">
                                    <div className="w-1.5 h-6 bg-indigo-600 rounded-full"></div>
                                    <h3 className="text-lg font-bold text-white uppercase tracking-tight print:text-black">Спецификация и требования</h3>
                                </div>
                                <div className="grid grid-cols-1 gap-3">
                                    {waybill.fieldValues.map((fv, i) => (
                                        <div key={i} className="flex justify-between p-4 bg-white/[0.01] rounded-xl border border-white/5 print:border-black/10">
                                            <span className="text-slate-400 text-sm">{fv.field.label}</span>
                                            <span className="text-white font-bold print:text-black">{fv.value}</span>
                                        </div>
                                    ))}
                                    {waybill.fieldValues.length === 0 && (
                                        <p className="text-slate-500 text-sm italic py-4">Дополнительные параметры не указаны</p>
                                    )}
                                </div>
                            </section>
                        </div>

                        {/* Official Signatures Row (Professional Stamps) */}
                        <div className="space-y-12 pt-16 border-t border-white/5 print:border-black">
                            {/* Pre-Trip Strip */}
                            <div>
                                <h4 className="text-[10px] font-black text-slate-500 uppercase tracking-[0.2em] mb-8 flex items-center gap-4">
                                    <span className="w-12 h-[1px] bg-slate-800"></span>
                                    Выезд (Титулы 2-3)
                                    <span className="flex-1 h-[1px] bg-slate-800"></span>
                                </h4>
                                <div className="grid grid-cols-1 md:grid-cols-3 gap-6">
                                    {/* Medical Control (T2) */}
                                    <div className="relative p-6 bg-blue-500/[0.02] border border-blue-500/10 rounded-3xl overflow-hidden group">
                                        <h5 className="text-[9px] font-bold text-blue-500 uppercase tracking-widest mb-6">Медик (Титул 2)</h5>
                                        {waybill.doctorSignature ? (
                                            <div className="relative">
                                                <div className="mb-4">
                                                    <p className="text-[10px] text-slate-500 mb-1">ФИО / Подпись:</p>
                                                    <p className="text-white font-bold text-sm italic tracking-widest print:text-black">{waybill.doctorSignature}</p>
                                                </div>
                                                <div className="absolute -right-2 -bottom-2 w-24 h-24 border-[2px] border-blue-500/30 rounded-full flex flex-col items-center justify-center rotate-[-15deg] group-hover:rotate-0 transition-all duration-700 print:border-blue-700">
                                                    <p className="text-[8px] font-bold text-blue-500/50 uppercase print:text-blue-700">ГОДЕН</p>
                                                    <p className="text-[6px] font-black text-blue-500/30 uppercase print:text-blue-700">МЕДОСМОТР</p>
                                                </div>
                                                {waybill.medicalControlAt && (
                                                    <p className="mt-4 text-[8px] font-mono text-blue-500/50">{new Date(waybill.medicalControlAt).toLocaleString()}</p>
                                                )}
                                            </div>
                                        ) : (
                                            <div className="py-8 text-center text-slate-700 border-2 border-dashed border-white/5 rounded-2xl">
                                                <p className="text-[10px] font-black uppercase tracking-widest">Ожидает</p>
                                            </div>
                                        )}
                                    </div>

                                    {/* Technical Control (T3) */}
                                    <div className="relative p-6 bg-emerald-500/[0.02] border border-emerald-500/10 rounded-3xl overflow-hidden group">
                                        <h5 className="text-[9px] font-bold text-emerald-500 uppercase tracking-widest mb-6">Механик (Титул 3)</h5>
                                        {waybill.mechanicSignature ? (
                                            <div className="relative">
                                                <div className="mb-4">
                                                    <p className="text-[10px] text-slate-500 mb-1">ФИО / Подпись:</p>
                                                    <p className="text-white font-bold text-sm italic tracking-widest print:text-black">{waybill.mechanicSignature}</p>
                                                </div>
                                                <div className="absolute -right-2 -bottom-2 w-24 h-24 border-[2px] border-emerald-500/30 rounded-full flex flex-col items-center justify-center rotate-[10deg] group-hover:rotate-0 transition-all duration-700 print:border-emerald-700">
                                                    <p className="text-[8px] font-bold text-emerald-500/50 uppercase print:text-emerald-700">ИСПРАВНО</p>
                                                    <p className="text-[6px] font-black text-emerald-500/30 uppercase print:text-emerald-700">ВЫЕЗД РАЗР.</p>
                                                </div>
                                                {waybill.technicalControlAt && (
                                                    <p className="mt-4 text-[8px] font-mono text-emerald-500/50">{new Date(waybill.technicalControlAt).toLocaleString()}</p>
                                                )}
                                            </div>
                                        ) : (
                                            <div className="py-8 text-center text-slate-700 border-2 border-dashed border-white/5 rounded-2xl">
                                                <p className="text-[10px] font-black uppercase tracking-widest">Ожидает</p>
                                            </div>
                                        )}
                                    </div>

                                    {/* Dispatcher (T1/Issue) */}
                                    <div className="relative p-6 bg-amber-500/[0.02] border border-amber-500/10 rounded-3xl overflow-hidden group">
                                        <h5 className="text-[9px] font-bold text-amber-500 uppercase tracking-widest mb-6">Диспетчер (Выпуск)</h5>
                                        {waybill.dispatcherSignature ? (
                                            <div className="relative">
                                                <div className="mb-4">
                                                    <p className="text-[10px] text-slate-500 mb-1">ФИО / Подпись:</p>
                                                    <p className="text-white font-bold text-sm italic tracking-widest print:text-black">{waybill.dispatcherSignature}</p>
                                                </div>
                                                <div className="absolute -right-2 -bottom-2 w-24 h-24 border-[2px] border-amber-500/30 rounded-full flex flex-col items-center justify-center rotate-[-5deg] group-hover:rotate-0 transition-all duration-700 print:border-amber-700">
                                                    <p className="text-[8px] font-bold text-amber-500/50 uppercase print:text-amber-700">ПОДПИСАНО</p>
                                                    <p className="text-[6px] font-black text-amber-500/30 uppercase print:text-amber-700">КЭП: ВАЛИДЕН</p>
                                                </div>
                                                {waybill.dispatcherApprovalAt && (
                                                    <p className="mt-4 text-[8px] font-mono text-amber-500/50">{new Date(waybill.dispatcherApprovalAt).toLocaleString()}</p>
                                                )}
                                            </div>
                                        ) : (
                                            <div className="py-8 text-center text-slate-700 border-2 border-dashed border-white/5 rounded-2xl">
                                                <p className="text-[10px] font-black uppercase tracking-widest">Ожидает</p>
                                            </div>
                                        )}
                                    </div>
                                </div>
                            </div>

                            {/* Post-Trip Strip */}
                            <div>
                                <h4 className="text-[10px] font-black text-slate-500 uppercase tracking-[0.2em] mb-8 flex items-center gap-4">
                                    <span className="w-12 h-[1px] bg-slate-800"></span>
                                    Возврат (Титулы 4-5)
                                    <span className="flex-1 h-[1px] bg-slate-800"></span>
                                </h4>
                                <div className="grid grid-cols-1 md:grid-cols-2 gap-6 max-w-2xl">
                                    {/* Technical Return (T4) */}
                                    <div className="relative p-6 bg-rose-500/[0.02] border border-rose-500/10 rounded-3xl overflow-hidden group">
                                        <h5 className="text-[9px] font-bold text-rose-500 uppercase tracking-widest mb-6">Механик (Титул 4)</h5>
                                        {waybill.mechanicArrivalSignature ? (
                                            <div className="relative">
                                                <div className="mb-4">
                                                    <p className="text-[10px] text-slate-500 mb-1">ФИО / Подпись:</p>
                                                    <p className="text-white font-bold text-sm italic tracking-widest print:text-black">{waybill.mechanicArrivalSignature}</p>
                                                </div>
                                                <div className="absolute -right-2 -bottom-2 w-24 h-24 border-[2px] border-rose-500/30 rounded-full flex flex-col items-center justify-center rotate-[15deg] group-hover:rotate-0 transition-all duration-700 print:border-rose-700">
                                                    <p className="text-[8px] font-bold text-rose-500/50 uppercase print:text-rose-700">ПРИНЯТО</p>
                                                    <p className="text-[6px] font-black text-rose-500/30 uppercase print:text-rose-700">ЗАЕЗД ТС</p>
                                                </div>
                                                {waybill.postTripTechnicalControlAt && (
                                                    <p className="mt-4 text-[8px] font-mono text-rose-500/50">{new Date(waybill.postTripTechnicalControlAt).toLocaleString()}</p>
                                                )}
                                            </div>
                                        ) : (
                                            <div className="py-8 text-center text-slate-800/30 border-2 border-dashed border-white/5 rounded-2xl">
                                                <p className="text-[10px] font-black uppercase tracking-widest font-mono">WAITING_ARRIVAL</p>
                                            </div>
                                        )}
                                    </div>

                                    {/* Medical Post-Trip (T5) */}
                                    <div className="relative p-6 bg-pink-500/[0.02] border border-pink-500/10 rounded-3xl overflow-hidden group">
                                        <h5 className="text-[9px] font-bold text-pink-500 uppercase tracking-widest mb-6">Медик (Титул 5)</h5>
                                        {waybill.doctorArrivalSignature ? (
                                            <div className="relative">
                                                <div className="mb-4">
                                                    <p className="text-[10px] text-slate-500 mb-1">ФИО / Подпись:</p>
                                                    <p className="text-white font-bold text-sm italic tracking-widest print:text-black">{waybill.doctorArrivalSignature}</p>
                                                </div>
                                                <div className="absolute -right-2 -bottom-2 w-24 h-24 border-[2px] border-pink-500/30 rounded-full flex flex-col items-center justify-center rotate-[-10deg] group-hover:rotate-0 transition-all duration-700 print:border-pink-700">
                                                    <p className="text-[8px] font-bold text-pink-500/50 uppercase print:text-pink-700">ГОДЕН</p>
                                                    <p className="text-[6px] font-black text-pink-500/30 uppercase print:text-pink-700">ПОСЛЕ РЕЙСА</p>
                                                </div>
                                                {waybill.postTripMedicalControlAt && (
                                                    <p className="mt-4 text-[8px] font-mono text-pink-500/50">{new Date(waybill.postTripMedicalControlAt).toLocaleString()}</p>
                                                )}
                                            </div>
                                        ) : (
                                            <div className="py-8 text-center text-slate-800/30 border-2 border-dashed border-white/5 rounded-2xl">
                                                <p className="text-[10px] font-black uppercase tracking-widest font-mono">WAITING_T5</p>
                                            </div>
                                        )}
                                    </div>
                                </div>
                            </div>
                        </div>

                        {/* Footer Logo/Brand */}
                        <div className="mt-20 flex justify-center opacity-10 print:opacity-30">
                            <div className="flex items-center gap-2 grayscale brightness-200 print:grayscale-0 print:brightness-0">
                                <div className="w-5 h-5 bg-white rounded-md flex items-center justify-center">
                                    <div className="w-2 h-2 bg-black rounded-full"></div>
                                </div>
                                <span className="text-[10px] font-bold tracking-[0.5em] uppercase text-white">DTS Core Platform</span>
                            </div>
                        </div>
                    </div>
                </div>
            </div>

            <style jsx global>{`
                @media print {
                    @page { margin: 20mm; }
                    body { -webkit-print-color-adjust: exact; }
                    .print-hidden { display: none !important; }
                }
            `}</style>
        </div>
    );
}
