"use client";

import React, { useState, useEffect, useCallback } from "react";
import Link from "next/link";

interface WaybillType {
    id: number;
    name: string;
    code: string;
    fields?: { id: number, label: string, name: string, fieldType: string }[];
}

interface Vehicle {
    id: number;
    plateNumber: string;
    model: string;
}

interface Organization {
    id: number;
    name: string;
    taxId: string;
}

export default function ManagementPage() {
    const [types, setTypes] = useState<WaybillType[]>([]);
    const [vehicles, setVehicles] = useState<Vehicle[]>([]);
    const [orgs, setOrgs] = useState<Organization[]>([]);
    const [isLoading, setIsLoading] = useState(true);

    const [newType, setNewType] = useState({ name: "", code: "" });
    const [newVehicle, setNewVehicle] = useState({ plateNumber: "", model: "" });
    const [newOrg, setNewOrg] = useState({ name: "", taxId: "" });
    const [addingFieldTo, setAddingFieldTo] = useState<number | null>(null);
    const [newField, setNewField] = useState({ label: "", name: "", fieldType: "TEXT" });

    const fetchData = useCallback(async () => {
        try {
            const [typesRes, vehiclesRes, orgsRes] = await Promise.all([
                fetch("http://localhost:8080/api/management/types"),
                fetch("http://localhost:8080/api/management/vehicles"),
                fetch("http://localhost:8080/api/management/organizations")
            ]);
            if (typesRes.ok) setTypes(await typesRes.json());
            if (vehiclesRes.ok) setVehicles(await vehiclesRes.json());
            if (orgsRes.ok) setOrgs(await orgsRes.json());
        } catch (err) {
            console.error("Failed to fetch management data", err);
        } finally {
            setIsLoading(false);
        }
    }, []);

    useEffect(() => {
        fetchData();
    }, [fetchData]);

    const handleAddType = async () => {
        if (!newType.name) return;
        await fetch("http://localhost:8080/api/management/types", {
            method: "POST",
            headers: { "Content-Type": "application/json" },
            body: JSON.stringify(newType)
        });
        setNewType({ name: "", code: "" });
        fetchData();
    };

    const handleAddVehicle = async () => {
        if (!newVehicle.plateNumber) return;
        await fetch("http://localhost:8080/api/management/vehicles", {
            method: "POST",
            headers: { "Content-Type": "application/json" },
            body: JSON.stringify({ ...newVehicle, vin: "DEFAULT", category: "B", currentMileage: 0 })
        });
        setNewVehicle({ plateNumber: "", model: "" });
        fetchData();
    };

    const handleAddOrg = async () => {
        if (!newOrg.name) return;
        await fetch("http://localhost:8080/api/management/organizations", {
            method: "POST",
            headers: { "Content-Type": "application/json" },
            body: JSON.stringify(newOrg)
        });
        setNewOrg({ name: "", taxId: "" });
        fetchData();
    };

    const handleAddField = async (typeId: number) => {
        if (!newField.name) return;
        await fetch(`http://localhost:8080/api/management/types/${typeId}/fields`, {
            method: "POST",
            headers: { "Content-Type": "application/json" },
            body: JSON.stringify({ ...newField, isRequired: true })
        });
        setAddingFieldTo(null);
        setNewField({ label: "", name: "", fieldType: "TEXT" });
        fetchData();
    };

    if (isLoading) return (
        <div className="min-h-screen bg-[#020617] flex items-center justify-center">
            <div className="w-10 h-10 border-2 border-blue-600/20 border-t-blue-600 rounded-full animate-spin"></div>
        </div>
    );

    return (
        <div className="min-h-screen bg-[#020617] text-slate-300 font-sans selection:bg-blue-500/30">
            {/* Command Bar */}
            <header className="h-20 bg-[#0f172a]/80 backdrop-blur-xl border-b border-white/5 flex items-center justify-between px-10 sticky top-0 z-50">
                <div className="flex items-center gap-4">
                    <div className="w-10 h-10 bg-blue-600 rounded-xl flex items-center justify-center shadow-lg shadow-blue-600/20">
                        <svg className="w-6 h-6 text-white" fill="none" stroke="currentColor" viewBox="0 0 24 24"><path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M10.325 4.317c.426-1.756 2.924-1.756 3.35 0a1.724 1.724 0 002.573 1.066c1.543-.94 3.31.826 2.37 2.37a1.724 1.724 0 001.065 2.572c1.756.426 1.756 2.924 0 3.35a1.724 1.724 0 00-1.066 2.573c.94 1.543-.826 3.31-2.37 2.37a1.724 1.724 0 00-2.572 1.065c-.426 1.756-2.924 1.756-3.35 0a1.724 1.724 0 00-2.573-1.066-1.543.94-3.31-.826-2.37-2.37a1.724 1.724 0 00-1.065-2.572c-1.756-.426-1.756-2.924 0-3.35a1.724 1.724 0 001.066-2.573c-.94-1.543.826-3.31 2.37-2.37.996.608 2.296.07 2.572-1.065z" /></svg>
                    </div>
                    <div>
                        <h1 className="text-lg font-bold text-white tracking-tight">System Configuration</h1>
                        <p className="text-[10px] font-bold text-slate-500 uppercase tracking-widest">Platform Core & EPL Management</p>
                    </div>
                </div>
                <Link href="/dashboard" className="flex items-center gap-2 px-6 py-2.5 bg-white/5 hover:bg-white/10 border border-white/5 rounded-xl text-sm font-bold transition-all group">
                    <svg className="w-4 h-4 group-hover:-translate-x-1 transition-transform" fill="none" stroke="currentColor" viewBox="0 0 24 24"><path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M15 19l-7-7 7-7" /></svg>
                    Control Center
                </Link>
            </header>

            <main className="p-10 max-w-[1600px] mx-auto grid grid-cols-1 xl:grid-cols-12 gap-10">

                {/* Organizational Infrastructure */}
                <div className="xl:col-span-4 space-y-10">
                    <section className="bg-[#0f172a] border border-white/5 rounded-[32px] overflow-hidden shadow-2xl">
                        <div className="p-8 border-b border-white/5 bg-gradient-to-r from-blue-600/10 to-transparent">
                            <h2 className="text-sm font-black text-white uppercase tracking-[0.2em] mb-1">Organizations</h2>
                            <p className="text-[10px] text-slate-500 font-bold uppercase">Legal entities & tax registration</p>
                        </div>
                        <div className="p-8 space-y-6">
                            <div className="flex gap-3">
                                <input placeholder="Legal Name" value={newOrg.name} onChange={e => setNewOrg({ ...newOrg, name: e.target.value })} className="flex-1 bg-white/5 border border-white/10 rounded-xl px-4 py-3 text-sm focus:ring-1 ring-blue-500 transition-all" />
                                <input placeholder="Tax ID" value={newOrg.taxId} onChange={e => setNewOrg({ ...newOrg, taxId: e.target.value })} className="w-32 bg-white/5 border border-white/10 rounded-xl px-4 py-3 text-sm font-mono" />
                                <button onClick={handleAddOrg} className="bg-blue-600 hover:bg-blue-500 p-3 rounded-xl transition-all shadow-lg shadow-blue-600/20 active:scale-95 text-white">
                                    <svg className="w-5 h-5" fill="none" stroke="currentColor" viewBox="0 0 24 24"><path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M12 4v16m8-8H4" /></svg>
                                </button>
                            </div>
                            <div className="space-y-3 max-h-[400px] overflow-y-auto pr-2 custom-scrollbar">
                                {orgs.map(o => (
                                    <div key={o.id} className="group p-5 bg-white/[0.02] hover:bg-white/[0.04] border border-white/5 rounded-2xl transition-all flex items-center justify-between">
                                        <div className="flex items-center gap-4">
                                            <div className="w-10 h-10 bg-blue-500/10 rounded-lg flex items-center justify-center text-blue-500 font-bold text-xs uppercase">
                                                {o.name.substring(0, 2)}
                                            </div>
                                            <div>
                                                <p className="text-sm font-bold text-white group-hover:text-blue-400 transition-colors">{o.name}</p>
                                                <p className="text-[10px] text-slate-500 font-mono tracking-wider">TAX_ID: {o.taxId}</p>
                                            </div>
                                        </div>
                                        <button className="opacity-0 group-hover:opacity-100 p-2 hover:bg-red-500/10 text-slate-600 hover:text-red-400 rounded-lg transition-all">
                                            <svg className="w-4 h-4" fill="none" stroke="currentColor" viewBox="0 0 24 24"><path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M19 7l-.867 12.142A2 2 0 0116.138 21H7.862a2 2 0 01-1.995-1.858L5 7m5 4v6m4-4v6m1-10V4a1 1 0 00-1-1h-4a1 1 0 00-1 1v3M4 7h16" /></svg>
                                        </button>
                                    </div>
                                ))}
                            </div>
                        </div>
                    </section>

                    <section className="bg-[#0f172a] border border-white/5 rounded-[32px] overflow-hidden shadow-2xl">
                        <div className="p-8 border-b border-white/5 bg-gradient-to-r from-emerald-600/10 to-transparent">
                            <h2 className="text-sm font-black text-white uppercase tracking-[0.2em] mb-1">Fleet Management</h2>
                            <p className="text-[10px] text-slate-500 font-bold uppercase">Active vehicles & technical state</p>
                        </div>
                        <div className="p-8 space-y-6">
                            <div className="flex gap-3">
                                <input placeholder="Plate №" value={newVehicle.plateNumber} onChange={e => setNewVehicle({ ...newVehicle, plateNumber: e.target.value })} className="flex-1 bg-white/5 border border-white/10 rounded-xl px-4 py-3 text-sm font-mono uppercase focus:ring-1 ring-emerald-500 transition-all" />
                                <input placeholder="Model" value={newVehicle.model} onChange={e => setNewVehicle({ ...newVehicle, model: e.target.value })} className="flex-1 bg-white/5 border border-white/10 rounded-xl px-4 py-3 text-sm" />
                                <button onClick={handleAddVehicle} className="bg-emerald-600 hover:bg-emerald-500 p-3 rounded-xl transition-all shadow-lg shadow-emerald-600/20 active:scale-95 text-white">
                                    <svg className="w-5 h-5" fill="none" stroke="currentColor" viewBox="0 0 24 24"><path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M12 4v16m8-8H4" /></svg>
                                </button>
                            </div>
                            <div className="grid grid-cols-1 gap-3 max-h-[400px] overflow-y-auto pr-2 custom-scrollbar">
                                {vehicles.map(v => (
                                    <div key={v.id} className="p-4 bg-white/[0.02] border border-white/5 rounded-xl flex items-center justify-between group hover:border-emerald-500/30 transition-all">
                                        <div className="flex items-center gap-4">
                                            <div className="text-emerald-500 bg-emerald-500/5 p-2 rounded-lg">
                                                <svg className="w-4 h-4" fill="none" stroke="currentColor" viewBox="0 0 24 24"><path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M13 16V6a1 1 0 00-1-1H4a1 1 0 00-1 1v10a1 1 0 001 1h8a1 1 0 001-1z" /><path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M13 16l4 2V8l-4 2m4 6v-8" /></svg>
                                            </div>
                                            <div>
                                                <p className="text-xs font-black text-white font-mono">{v.plateNumber}</p>
                                                <p className="text-[10px] text-slate-500 uppercase font-bold">{v.model}</p>
                                            </div>
                                        </div>
                                        <span className="text-[9px] font-black uppercase text-emerald-500/50 bg-emerald-500/5 px-2 py-0.5 rounded border border-emerald-500/20">Active</span>
                                    </div>
                                ))}
                            </div>
                        </div>
                    </section>
                </div>

                {/* Document Type Builder (Central Logic) */}
                <div className="xl:col-span-8">
                    <section className="bg-[#0f172a] border border-white/5 rounded-[40px] shadow-2xl overflow-hidden min-h-[800px] flex flex-col">
                        <div className="p-10 border-b border-white/5 bg-gradient-to-r from-indigo-600/10 via-transparent to-transparent flex items-center justify-between">
                            <div>
                                <h2 className="text-xl font-black text-white uppercase tracking-[0.3em] mb-2">EPL Schema Builder</h2>
                                <p className="text-xs text-slate-500 font-bold uppercase tracking-widest">Electronic Waybill Tituls & Validation Rules</p>
                            </div>
                            <div className="flex gap-4 p-2 bg-black/20 rounded-2xl border border-white/5">
                                <input placeholder="Type Name" value={newType.name} onChange={e => setNewType({ ...newType, name: e.target.value })} className="bg-white/5 border border-white/10 rounded-xl px-4 py-2 text-xs focus:ring-1 ring-indigo-500 transition-all" />
                                <input placeholder="CODE" value={newType.code} onChange={e => setNewType({ ...newType, code: e.target.value })} className="w-20 bg-white/5 border border-white/10 rounded-xl px-4 py-2 text-xs font-mono uppercase" />
                                <button onClick={handleAddType} className="bg-indigo-600 hover:bg-indigo-500 px-6 py-2 rounded-xl text-xs font-black uppercase tracking-widest text-white transition-all shadow-lg shadow-indigo-600/20">
                                    Initialize
                                </button>
                            </div>
                        </div>

                        <div className="flex-1 p-10 grid grid-cols-1 md:grid-cols-2 gap-10 overflow-y-auto">
                            {types.map(t => (
                                <div key={t.id} className="flex flex-col bg-white/[0.02] border border-white/5 rounded-[32px] p-8 hover:border-indigo-500/30 transition-all group relative overflow-hidden">
                                    <div className="absolute top-0 right-0 p-4 opacity-5 group-hover:opacity-10 transition-opacity">
                                        <svg className="w-24 h-24" fill="currentColor" viewBox="0 0 24 24"><path d="M7 2a2 2 0 00-2 2v1h14V4a2 2 0 00-2-2H7zM5 19a2 2 0 002 2h10a2 2 0 002-2V7H5v12zm3-8h8v2H8v-2z" /></svg>
                                    </div>

                                    <div className="flex justify-between items-start mb-10 relative z-10">
                                        <div>
                                            <h3 className="text-lg font-black text-white tracking-tight leading-none mb-2">{t.name}</h3>
                                            <code className="text-[10px] text-indigo-400 font-black bg-indigo-500/10 px-2 py-0.5 rounded border border-indigo-500/20 uppercase">{t.code}</code>
                                        </div>
                                        <button
                                            onClick={() => setAddingFieldTo(addingFieldTo === t.id ? null : t.id)}
                                            className="px-4 py-1.5 bg-indigo-600/10 hover:bg-indigo-600 text-indigo-400 hover:text-white rounded-lg text-[10px] font-black uppercase tracking-widest transition-all border border-indigo-600/20"
                                        >
                                            {addingFieldTo === t.id ? "Cancel" : "Add Field"}
                                        </button>
                                    </div>

                                    {addingFieldTo === t.id && (
                                        <div className="mb-8 p-6 bg-indigo-600/5 rounded-2xl border border-indigo-500/20 space-y-4 animate-in fade-in slide-in-from-top-4 duration-300">
                                            <div className="grid grid-cols-2 gap-4">
                                                <input placeholder="Label (e.g. Fuel Level)" value={newField.label} onChange={e => setNewField({ ...newField, label: e.target.value })} className="bg-black/40 border border-white/10 rounded-xl px-4 py-2.5 text-xs text-white" />
                                                <input placeholder="Key (e.g. fuel_lvl)" value={newField.name} onChange={e => setNewField({ ...newField, name: e.target.value })} className="bg-black/40 border border-white/10 rounded-xl px-4 py-2.5 text-xs font-mono text-indigo-300" />
                                            </div>
                                            <div className="flex gap-4">
                                                <select value={newField.fieldType} onChange={e => setNewField({ ...newField, fieldType: e.target.value })} className="flex-1 bg-black/40 border border-white/10 rounded-xl px-4 py-2.5 text-xs text-slate-400 appearance-none">
                                                    <option value="TEXT">Regular Text Input</option>
                                                    <option value="NUMBER">Digital / Numeric Only</option>
                                                    <option value="BOOLEAN">Toggle / Boolean</option>
                                                </select>
                                                <button onClick={() => handleAddField(t.id)} className="bg-indigo-600 hover:bg-indigo-500 px-6 rounded-xl text-xs font-bold text-white shadow-lg">Confirm</button>
                                            </div>
                                        </div>
                                    )}

                                    <div className="space-y-3 relative z-10 flex-1">
                                        <p className="text-[10px] font-bold text-slate-500 uppercase tracking-widest border-b border-white/5 pb-2 mb-4">Mandatory Runtime Fields</p>
                                        <div className="flex flex-wrap gap-2">
                                            {t.fields?.map(f => (
                                                <div key={f.id} className="flex items-center gap-2 bg-white/5 border border-white/5 rounded-lg px-3 py-1.5 group/field hover:bg-white/10 transition-colors">
                                                    <span className="text-[10px] font-bold text-slate-300">{f.label}</span>
                                                    <span className="text-[8px] font-black text-slate-600 uppercase tracking-tighter">{f.fieldType}</span>
                                                </div>
                                            ))}
                                            {(!t.fields || t.fields.length === 0) && (
                                                <p className="text-[10px] text-slate-600 italic">No custom fields defined for this type.</p>
                                            )}
                                        </div>
                                    </div>

                                    <div className="mt-8 pt-6 border-t border-white/5 flex items-center justify-between">
                                        <div className="flex -space-x-2">
                                            <div className="w-6 h-6 rounded-full bg-slate-800 border-2 border-[#1e293b]" title="Validator: Med"></div>
                                            <div className="w-6 h-6 rounded-full bg-slate-800 border-2 border-[#1e293b]" title="Validator: Tech"></div>
                                        </div>
                                        <span className="text-[9px] font-black uppercase text-indigo-500/50">Titul 1-5 Ready</span>
                                    </div>
                                </div>
                            ))}
                        </div>
                    </section>
                </div>
            </main>

            <style jsx global>{`
                .custom-scrollbar::-webkit-scrollbar { width: 4px; }
                .custom-scrollbar::-webkit-scrollbar-track { background: transparent; }
                .custom-scrollbar::-webkit-scrollbar-thumb { background: rgba(255,255,255,0.05); border-radius: 10px; }
                .custom-scrollbar::-webkit-scrollbar-thumb:hover { background: rgba(255,255,255,0.1); }
            `}</style>
        </div>
    );
}
