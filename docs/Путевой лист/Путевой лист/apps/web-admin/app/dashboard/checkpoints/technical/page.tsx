"use client";

import React, { useState } from "react";
import Link from "next/link";
import { useRouter } from "next/navigation";

export default function TechnicalCheck() {
  const router = useRouter();
  const [waybillId, setWaybillId] = useState("");
  const [status, setStatus] = useState("idle");
  const [currentTime] = useState(new Date());

  const [techData, setTechData] = useState({
    mileage: "45230",
    fuelLevel: "75",
    tiresStatus: "OK",
    lightingOk: true,
    brakesOk: true,
    overallStatus: "READY",
  });

  const handleSearch = (e: React.FormEvent) => {
    e.preventDefault();
    if (waybillId) setStatus("found");
  };

  const handleApprove = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!waybillId) return;
    try {
      const res = await fetch(`http://localhost:8080/api/waybills/${waybillId}/approve-technical?signature=Mech. Volkov&seal=TECH_READY`, {
        method: "POST"
      });
      if (res.ok) {
        alert("Технический контроль пройден. ТС допущено к рейсу!");
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
          Вернуться в систему
        </Link>

        <div className="bg-[#0f172a] border border-white/5 p-10 rounded-[40px] shadow-2xl relative overflow-hidden">
          <div className="absolute top-0 right-0 w-64 h-64 bg-emerald-600/5 blur-[80px] -mr-32 -mt-32"></div>

          <div className="relative z-10">
            <div className="flex items-center gap-4 mb-8">
              <div className="p-3 bg-emerald-600/10 rounded-2xl text-emerald-400 border border-emerald-500/20">
                <svg className="w-8 h-8" fill="none" stroke="currentColor" viewBox="0 0 24 24"><path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M10.325 4.317c.426-1.756 2.924-1.756 3.35 0a1.724 1.724 0 002.573 1.066c1.543-.94 3.31.826 2.37 2.37a1.724 1.724 0 001.065 2.572c1.756.426 1.756 2.924 0 3.35a1.724 1.724 0 00-1.066 2.573c.94 1.543-.826 3.31-2.37 2.37a1.724 1.724 0 00-2.572 1.065c-.426 1.756-2.924 1.756-3.35 0a1.724 1.724 0 00-2.573-1.066-1.543.94-3.31-.826-2.37-2.37a1.724 1.724 0 00-1.065-2.572c-1.756-.426-1.756-2.924 0-3.35a1.724 1.724 0 001.066-2.573c-.94-1.543.826-3.31 2.37-2.37.996.608 2.296.07 2.572-1.065z" /></svg>
              </div>
              <div>
                <h1 className="text-2xl font-black text-white uppercase tracking-tight">Технический Контроль</h1>
                <p className="text-[10px] text-slate-500 font-black uppercase tracking-[0.2em]">Vehicle Readiness & Safety Pool</p>
              </div>
            </div>

            {status === "idle" ? (
              <form onSubmit={handleSearch} className="space-y-6">
                <div>
                  <label className="block text-[10px] font-bold text-slate-500 uppercase tracking-widest mb-3">Введите ID Листа для допуска ТС</label>
                  <input
                    type="text"
                    placeholder="Напр: WL-2025-4502"
                    value={waybillId}
                    onChange={(e) => setWaybillId(e.target.value)}
                    className="w-full px-5 py-4 bg-black/40 border border-white/10 rounded-2xl text-white focus:outline-none focus:ring-2 focus:ring-emerald-500/50 transition-all font-mono text-lg"
                    required
                  />
                </div>
                <button type="submit" className="w-full py-4 bg-emerald-600 hover:bg-emerald-500 text-white font-black uppercase tracking-widest text-xs rounded-2xl shadow-xl shadow-emerald-600/20 transition-all active:scale-[0.98]">
                  Идентифицировать Транспорт
                </button>
              </form>
            ) : (
              <form onSubmit={handleApprove} className="space-y-8 animate-in fade-in slide-in-from-bottom-4 duration-500">
                <div className="grid grid-cols-2 gap-6">
                  <div className="bg-white/[0.02] border border-white/5 p-6 rounded-3xl">
                    <p className="text-[10px] font-bold text-slate-500 uppercase tracking-widest mb-3">Одометр (км)</p>
                    <input
                      type="number"
                      value={techData.mileage}
                      onChange={(e) => setTechData({ ...techData, mileage: e.target.value })}
                      className="w-full bg-transparent text-2xl font-black text-white focus:outline-none font-mono"
                    />
                  </div>
                  <div className="bg-white/[0.02] border border-white/5 p-6 rounded-3xl">
                    <p className="text-[10px] font-bold text-slate-500 uppercase tracking-widest mb-3">Топливо (%)</p>
                    <input
                      type="number"
                      value={techData.fuelLevel}
                      onChange={(e) => setTechData({ ...techData, fuelLevel: e.target.value })}
                      className="w-full bg-transparent text-2xl font-black text-white focus:outline-none font-mono"
                    />
                  </div>
                </div>

                <div className="grid grid-cols-2 gap-6">
                  <div className="bg-black/20 border border-white/5 p-4 rounded-2xl flex items-center justify-between">
                    <div className="flex items-center gap-3">
                      <div className={`w-2 h-2 rounded-full ${techData.lightingOk ? 'bg-emerald-500 shadow-[0_0_8px_rgba(16,185,129,0.5)]' : 'bg-rose-500'}`}></div>
                      <span className="text-[10px] font-bold text-slate-400 uppercase">Освещение</span>
                    </div>
                    <input type="checkbox" checked={techData.lightingOk} onChange={(e) => setTechData({ ...techData, lightingOk: e.target.checked })} className="w-4 h-4 rounded-md border-white/10 bg-black/40 text-emerald-500 focus:ring-0" />
                  </div>
                  <div className="bg-black/20 border border-white/5 p-4 rounded-2xl flex items-center justify-between">
                    <div className="flex items-center gap-3">
                      <div className={`w-2 h-2 rounded-full ${techData.brakesOk ? 'bg-emerald-500 shadow-[0_0_8px_rgba(16,185,129,0.5)]' : 'bg-rose-500'}`}></div>
                      <span className="text-[10px] font-bold text-slate-400 uppercase">Тормоза</span>
                    </div>
                    <input type="checkbox" checked={techData.brakesOk} onChange={(e) => setTechData({ ...techData, brakesOk: e.target.checked })} className="w-4 h-4 rounded-md border-white/10 bg-black/40 text-emerald-500 focus:ring-0" />
                  </div>
                </div>

                <button type="submit" className="w-full py-5 bg-blue-600 hover:bg-blue-500 text-white font-black uppercase tracking-[0.2em] text-xs rounded-[32px] shadow-2xl shadow-blue-600/20 transition-all active:scale-[0.98]">
                  Заверить Техсостояние
                </button>

                <div className="p-4 bg-white/[0.01] border border-white/5 rounded-2xl flex items-center justify-center gap-3">
                  <span className="text-[10px] font-bold text-slate-600 uppercase">Logs:</span>
                  <span className="text-[10px] font-mono text-slate-500">MECHANIC ID: #842 // {currentTime.toLocaleDateString()}</span>
                </div>
              </form>
            )}
          </div>
        </div>
      </div>

      <style jsx global>{`
                body {
                    background-image: 
                        radial-gradient(circle at 0% 0%, rgba(16, 185, 129, 0.03) 0, transparent 50%),
                        radial-gradient(circle at 100% 100%, rgba(59, 130, 246, 0.03) 0, transparent 50%);
                }
            `}</style>
    </div>
  );
}
