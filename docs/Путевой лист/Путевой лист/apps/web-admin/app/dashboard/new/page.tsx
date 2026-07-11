"use client";

import React, { useState, useEffect } from "react";
import { useRouter } from "next/navigation";
import Link from "next/link";

interface WaybillType {
    id: number;
    name: string;
    code: string;
    fields: { name: string, label: string, fieldType: string, isRequired: boolean }[];
}

interface Vehicle {
    id: number;
    plateNumber: string;
    model: string;
    category: string;
}

export default function NewWaybill() {
    const router = useRouter();
    const [types, setTypes] = useState<WaybillType[]>([]);
    const [vehicles, setVehicles] = useState<Vehicle[]>([]);
    const [selectedType, setSelectedType] = useState<WaybillType | null>(null);
    const [formData, setFormData] = useState({
        number: "",
        vehicleId: "",
        driverId: "",
        typeId: "",
        departureLocation: "",
        arrivalLocation: "",
        estimatedFuel: "",
        messageType: "URBAN",
        odometerDeparture: "",
        issuerName: "DTS CORE OPERATOR",
        category: "TRUCK", // Current category context
        fieldValues: {} as Record<string, string>,
    });

    useEffect(() => {
        const fetchData = async () => {
            const [tRes, vRes] = await Promise.all([
                fetch("http://localhost:8080/api/management/types"),
                fetch("http://localhost:8080/api/management/vehicles")
            ]);
            if (tRes.ok) setTypes(await tRes.json());
            if (vRes.ok) setVehicles(await vRes.json());

            setFormData(prev => ({
                ...prev,
                number: `WL-${new Date().getFullYear()}${String(new Date().getMonth() + 1).padStart(2, '0')}-${Math.floor(1000 + Math.random() * 9000)}`
            }));
        };
        fetchData();
    }, []);

    const handleTypeChange = (id: string) => {
        const type = types.find(t => t.id === parseInt(id)) || null;
        setSelectedType(type);
        setFormData({ ...formData, typeId: id, fieldValues: {} });
    };

    const handleSubmit = async (e: React.FormEvent) => {
        e.preventDefault();
        try {
            const response = await fetch("http://localhost:8080/api/waybills", {
                method: "POST",
                headers: { "Content-Type": "application/json" },
                body: JSON.stringify({
                    number: formData.number,
                    driverId: parseInt(formData.driverId),
                    vehicleId: parseInt(formData.vehicleId),
                    typeId: parseInt(formData.typeId),
                    departureLocation: formData.departureLocation,
                    arrivalLocation: formData.arrivalLocation,
                    estimatedFuel: parseFloat(formData.estimatedFuel),
                    messageType: formData.messageType,
                    odometerDeparture: parseFloat(formData.odometerDeparture),
                    issuerName: formData.issuerName,
                    fieldValues: formData.fieldValues
                })
            });

            if (response.ok) {
                alert("Путевой лист успешно создан!");
                router.push("/dashboard");
            } else {
                alert("Ошибка: " + response.statusText);
            }
        } catch (error) {
            console.error(error);
            alert("Ошибка сети");
        }
    };

    return (
        <div className="min-h-screen bg-[#020617] text-slate-200 p-8 flex flex-col items-center">
            <div className="w-full max-w-3xl">
                <Link href="/dashboard" className="inline-flex items-center gap-2 text-slate-400 hover:text-white mb-8 transition-colors group">
                    <svg className="w-4 h-4 group-hover:-translate-x-1 transition-transform" fill="none" stroke="currentColor" viewBox="0 0 24 24"><path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M15 19l-7-7 7-7" /></svg>
                    Вернуться в Центр управления
                </Link>

                <div className="bg-[#0f172a] border border-white/5 p-10 rounded-[32px] shadow-2xl relative overflow-hidden">
                    <div className="absolute top-0 right-0 w-64 h-64 bg-blue-600/5 blur-[80px] -mr-32 -mt-32"></div>

                    <div className="relative z-10">
                        <h1 className="text-3xl font-bold text-white mb-2">Новая Операция (ЭПЛ)</h1>
                        <p className="text-slate-500 text-sm mb-10">Формирование электронного путевого листа (Титул 1)</p>

                        <form onSubmit={handleSubmit} className="space-y-8">
                            {/* Primary Info Header */}
                            <div className="grid grid-cols-2 gap-8 p-6 bg-white/[0.02] border border-white/5 rounded-2xl">
                                <div>
                                    <label className="block text-[10px] font-bold text-slate-500 uppercase tracking-widest mb-2">Номер документа</label>
                                    <input type="text" value={formData.number} disabled className="w-full px-4 py-3 bg-black/20 border border-white/5 rounded-xl text-blue-400 font-mono font-bold" />
                                </div>
                                <div>
                                    <label className="block text-[10px] font-bold text-slate-500 uppercase tracking-widest mb-2">Табельный № Водителя</label>
                                    <input type="number" required placeholder="Напр: 4502" onChange={(e) => setFormData({ ...formData, driverId: e.target.value })} className="w-full px-4 py-3 bg-white/5 border border-white/10 rounded-xl text-white focus:ring-1 ring-blue-500 transition-all font-mono" />
                                </div>
                            </div>

                            {/* EPL Compliance Fields (Titul 1) */}
                            <div className="grid grid-cols-2 gap-8 p-6 bg-indigo-500/[0.02] border border-indigo-500/10 rounded-2xl">
                                <div>
                                    <label className="block text-[10px] font-bold text-indigo-500/50 uppercase tracking-widest mb-2">Вид Сообщения</label>
                                    <select required value={formData.messageType} onChange={(e) => setFormData({ ...formData, messageType: e.target.value })} className="w-full px-4 py-3 bg-[#1e293b] border border-white/10 rounded-xl text-white text-xs">
                                        <option value="URBAN">Городское</option>
                                        <option value="INTERURBAN">Междугородное</option>
                                        <option value="SUBURBAN">Пригородное</option>
                                        <option value="INTERNATIONAL">Международное</option>
                                    </select>
                                </div>
                                <div>
                                    <label className="block text-[10px] font-bold text-indigo-500/50 uppercase tracking-widest mb-2">Показания одометра (км)</label>
                                    <input type="number" required placeholder="0.0" value={formData.odometerDeparture} onChange={(e) => setFormData({ ...formData, odometerDeparture: e.target.value })} className="w-full px-4 py-3 bg-white/5 border border-white/10 rounded-xl text-white font-mono" />
                                </div>
                                <div className="col-span-2">
                                    <label className="block text-[10px] font-bold text-indigo-500/50 uppercase tracking-widest mb-2">Лицо, оформившее лист (ФИО)</label>
                                    <input type="text" required value={formData.issuerName} onChange={(e) => setFormData({ ...formData, issuerName: e.target.value })} className="w-full px-4 py-3 bg-white/5 border border-white/10 rounded-xl text-white text-sm" />
                                </div>
                            </div>

                            {/* Selection Row */}
                            <div className="grid grid-cols-2 gap-8">
                                <div>
                                    <label className="block text-[10px] font-bold text-slate-500 uppercase tracking-widest mb-2">Режим перевозки</label>
                                    <select required onChange={(e) => handleTypeChange(e.target.value)} className="w-full px-4 py-3 bg-[#1e293b] border border-white/10 rounded-xl text-white focus:ring-1 ring-blue-500 appearance-none">
                                        <option value="">Выберите тип</option>
                                        {types.map(t => <option key={t.id} value={t.id}>{t.name}</option>)}
                                    </select>
                                </div>
                                <div>
                                    <label className="block text-[10px] font-bold text-slate-500 uppercase tracking-widest mb-2">Транспортное средство</label>
                                    <select
                                        required
                                        onChange={(e) => {
                                            const v = vehicles.find(veh => veh.id === parseInt(e.target.value));
                                            setFormData({ ...formData, vehicleId: e.target.value, category: v?.category || 'TRUCK' });
                                        }}
                                        className="w-full px-4 py-3 bg-[#1e293b] border border-white/10 rounded-xl text-white focus:ring-1 ring-blue-500 appearance-none"
                                    >
                                        <option value="">Выберите ТС</option>
                                        {vehicles.map(v => <option key={v.id} value={v.id}>{v.plateNumber} · {v.model} ({v.category})</option>)}
                                    </select>
                                </div>
                            </div>

                            {/* Dynamic Category Specifics */}
                            {formData.category === 'TRUCK' && (
                                <div className="p-6 bg-amber-500/[0.05] border border-amber-500/10 rounded-2xl animate-in slide-in-from-left duration-500">
                                    <h4 className="text-[10px] font-black text-amber-500 uppercase tracking-widest mb-4 flex items-center gap-2">
                                        <svg className="w-4 h-4" fill="none" stroke="currentColor" viewBox="0 0 24 24"><path d="M13 16V6a1 1 0 00-1-1H4a1 1 0 00-1 1v10a1 1 0 001 1h8a1 1 0 001-1z" /></svg>
                                        Спецификация Грузоперевозки
                                    </h4>
                                    <div className="grid grid-cols-2 gap-6">
                                        <div>
                                            <label className="block text-[9px] font-bold text-slate-400 uppercase mb-2">Вес груза (т)</label>
                                            <input type="number" step="0.1" placeholder="12.5" className="w-full bg-black/20 border border-white/5 rounded-lg px-4 py-2 text-xs" />
                                        </div>
                                        <div>
                                            <label className="block text-[9px] font-bold text-slate-400 uppercase mb-2">Тип прицепа</label>
                                            <select className="w-full bg-black/20 border border-white/5 rounded-lg px-4 py-2 text-xs">
                                                <option>Тент</option>
                                                <option>Рефрижератор</option>
                                                <option>Цистерна</option>
                                            </select>
                                        </div>
                                    </div>
                                </div>
                            )}

                            {formData.category === 'BUS' && (
                                <div className="p-6 bg-indigo-500/[0.05] border border-indigo-500/10 rounded-2xl animate-in slide-in-from-left duration-500">
                                    <h4 className="text-[10px] font-black text-indigo-500 uppercase tracking-widest mb-4 flex items-center gap-2">
                                        <svg className="w-4 h-4" fill="none" stroke="currentColor" viewBox="0 0 24 24"><path d="M17 20h5v-2a3 3 0 00-5.356-1.857M17 20H7m10 0v-2c0-.656-.126-1.283-.356-1.857M7 20H2v-2a3 3 0 015.356-1.857M7 20v-2c0-.656.126-1.283.356-1.857m0 0a5.002 5.002 0 019.288 0M15 7a3 3 0 11-6 0 3 3 0 016 0zm6 3a2 2 0 11-4 0 2 2 0 014 0zM7 10a2 2 0 11-4 0 2 2 0 014 0z" /></svg>
                                        Параметры Пассажирской перевозки
                                    </h4>
                                    <div className="grid grid-cols-2 gap-6">
                                        <div>
                                            <label className="block text-[9px] font-bold text-slate-400 uppercase mb-2">Вместимость (чел)</label>
                                            <input type="number" placeholder="45" className="w-full bg-black/20 border border-white/5 rounded-lg px-4 py-2 text-xs" />
                                        </div>
                                        <div>
                                            <label className="block text-[9px] font-bold text-slate-400 uppercase mb-2">Номер маршрута</label>
                                            <input type="text" placeholder="104к" className="w-full bg-black/20 border border-white/5 rounded-lg px-4 py-2 text-xs" />
                                        </div>
                                    </div>
                                </div>
                            )}

                            {/* Operational Route Logic */}
                            <div className="space-y-4">
                                <h3 className="text-[10px] font-bold text-blue-500 uppercase tracking-[0.2em]">Логистика и Маршрут</h3>
                                <div className="grid grid-cols-2 gap-8">
                                    <div>
                                        <label className="block text-[10px] font-bold text-slate-500 uppercase tracking-widest mb-2">Пункт отправления</label>
                                        <input type="text" required placeholder="Напр: База №1" value={formData.departureLocation} onChange={(e) => setFormData({ ...formData, departureLocation: e.target.value })} className="w-full px-4 py-3 bg-white/5 border border-white/10 rounded-xl text-white" />
                                    </div>
                                    <div>
                                        <label className="block text-[10px] font-bold text-slate-500 uppercase tracking-widest mb-2">Пункт назначения</label>
                                        <input type="text" required placeholder="Напр: Объект 'Запад'" value={formData.arrivalLocation} onChange={(e) => setFormData({ ...formData, arrivalLocation: e.target.value })} className="w-full px-4 py-3 bg-white/5 border border-white/10 rounded-xl text-white" />
                                    </div>
                                </div>
                                <div>
                                    <label className="block text-[10px] font-bold text-slate-500 uppercase tracking-widest mb-2">Плановый расход топлива (литров)</label>
                                    <input type="number" step="0.1" required placeholder="45.5" value={formData.estimatedFuel} onChange={(e) => setFormData({ ...formData, estimatedFuel: e.target.value })} className="w-48 px-4 py-3 bg-white/5 border border-white/10 rounded-xl text-white" />
                                </div>
                            </div>

                            {/* Dynamic Fields Section */}
                            {selectedType && selectedType.fields?.length > 0 && (
                                <div className="pt-6 border-t border-white/5 space-y-6">
                                    <h3 className="text-[10px] font-bold text-blue-500 uppercase tracking-[0.2em]">Дополнительные параметры ({selectedType.name})</h3>
                                    <div className="grid grid-cols-2 gap-x-8 gap-y-6">
                                        {selectedType.fields.map(field => (
                                            <div key={field.name}>
                                                <label className="block text-[10px] font-bold text-slate-500 uppercase tracking-widest mb-2">{field.label}</label>
                                                <input
                                                    type={field.fieldType === 'NUMBER' ? 'number' : 'text'}
                                                    required={field.isRequired}
                                                    onChange={(e) => setFormData({
                                                        ...formData,
                                                        fieldValues: { ...formData.fieldValues, [field.name]: e.target.value }
                                                    })}
                                                    className="w-full px-4 py-3 bg-white/5 border border-white/10 rounded-xl text-white transition-all focus:ring-1 ring-white/20"
                                                />
                                            </div>
                                        ))}
                                    </div>
                                </div>
                            )}

                            <button type="submit" className="w-full py-5 bg-blue-600 hover:bg-blue-500 text-white font-bold rounded-2xl shadow-xl shadow-blue-600/20 transition-all active:scale-[0.98] uppercase tracking-widest text-xs">
                                Подтвердить и создать ЭПЛ
                            </button>
                        </form>
                    </div>
                </div>
            </div>
        </div>
    );
}
