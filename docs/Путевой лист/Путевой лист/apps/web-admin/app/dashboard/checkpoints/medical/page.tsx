"use client";

import React, { useState } from "react";
import Link from "next/link";
import { useRouter } from "next/navigation";

export default function MedicalCheck() {
  const router = useRouter();
  const [waybillId, setWaybillId] = useState("");
  const [status, setStatus] = useState("idle");
  const [currentTime] = useState(new Date());

  const [medData, setMedData] = useState({
    temperature: "36.6",
    pressure: "120/80",
    isAlcoholOk: true,
    overallStatus: "FIT",
  });

  const handleSearch = (e: React.FormEvent) => {
    e.preventDefault();
    if (waybillId) setStatus("found");
  };

  const handleApprove = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!waybillId) return;
    try {
      const res = await fetch(`http://localhost:8080/api/waybills/${waybillId}/approve-medical?signature=Dr. Smirnov&seal=MED_PASSED`, {
        method: "POST"
      });
      if (res.ok) {
        alert("Медицинский осмотр пройден успешно!");
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
          <div className="absolute top-0 right-0 w-64 h-64 bg-indigo-600/5 blur-[80px] -mr-32 -mt-32"></div>

          <div className="relative z-10">
            <div className="flex items-center gap-4 mb-8">
              <div className="p-3 bg-indigo-600/10 rounded-2xl text-indigo-400 border border-indigo-500/20">
                <svg className="w-8 h-8" fill="none" stroke="currentColor" viewBox="0 0 24 24"><path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M9 12l2 2 4-4m5.618-4.016A11.955 11.955 0 0112 2.944a11.955 11.955 0 01-8.618 3.04A12.02 12.02 0 003 9c0 5.591 3.824 10.29 9 11.622 5.176-1.332 9-6.03 9-11.622 0-1.042-.133-2.052-.382-3.016z" /></svg>
              </div>
              <div>
                <h1 className="text-2xl font-black text-white uppercase tracking-tight">Медицинский Контроль</h1>
                <p className="text-[10px] text-slate-500 font-black uppercase tracking-[0.2em]">Health & Safety Verification Pool</p>
              </div>
            </div>

            {status === "idle" ? (
              <form onSubmit={handleSearch} className="space-y-6">
                <div>
                  <label className="block text-[10px] font-bold text-slate-500 uppercase tracking-widest mb-3">Введите ID Листа для проверки</label>
                  <input
                    type="text"
                    placeholder="Напр: 4502"
                    value={waybillId}
                    onChange={(e) => setWaybillId(e.target.value)}
                    className="w-full px-5 py-4 bg-black/40 border border-white/10 rounded-2xl text-white focus:outline-none focus:ring-2 focus:ring-indigo-500/50 transition-all font-mono text-lg"
                    required
                  />
                </div>
                <button type="submit" className="w-full py-4 bg-indigo-600 hover:bg-indigo-500 text-white font-black uppercase tracking-widest text-xs rounded-2xl shadow-xl shadow-indigo-600/20 transition-all active:scale-[0.98]">
                  Идентифицировать Водителя
                </button>
              </form>
            ) : (
              <form onSubmit={handleApprove} className="space-y-8 animate-in fade-in slide-in-from-bottom-4 duration-500">
                <div className="grid grid-cols-2 gap-6">
                  <div className="bg-white/[0.02] border border-white/5 p-6 rounded-3xl">
                    <p className="text-[10px] font-bold text-slate-500 uppercase tracking-widest mb-3">Температура (°C)</p>
                    <input
                      type="text"
                      value={medData.temperature}
                      onChange={(e) => setMedData({ ...medData, temperature: e.target.value })}
                      className="w-full bg-transparent text-2xl font-black text-white focus:outline-none font-mono"
                    />
                  </div>
                  <div className="bg-white/[0.02] border border-white/5 p-6 rounded-3xl">
                    <p className="text-[10px] font-bold text-slate-500 uppercase tracking-widest mb-3">Давление (SYS/DIA)</p>
                    <input
                      type="text"
                      value={medData.pressure}
                      onChange={(e) => setMedData({ ...medData, pressure: e.target.value })}
                      className="w-full bg-transparent text-2xl font-black text-white focus:outline-none font-mono"
                    />
                  </div>
                </div>

                <div className="bg-white/[0.02] border border-white/5 p-8 rounded-3xl flex items-center justify-between">
                  <div className="flex items-center gap-4">
                    <div className={`w-12 h-12 rounded-full flex items-center justify-center transition-all ${medData.isAlcoholOk ? 'bg-emerald-500/20 text-emerald-500' : 'bg-rose-500/20 text-rose-500'}`}>
                      <svg className="w-6 h-6" fill="none" stroke="currentColor" viewBox="0 0 24 24"><path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M19.428 15.428a2 2 0 00-1.022-.547l-2.387-.477a6 6 0 00-3.86.517l-.318.158a6 6 0 01-3.86.517L6.05 15.21a2 2 0 00-1.022.547l-2.387.477a2 2 0 00-1.543 2.507A2 2 0 003.543 20h16.914a2 2 0 001.943-1.543 2 2 0 00-1.543-2.507l-2.387-.477z" /></svg>
                    </div>
                    <div>
                      <p className="text-[10px] font-bold text-slate-500 uppercase tracking-widest">Контроль Трезвости</p>
                      <p className="text-sm font-bold text-white uppercase">{medData.isAlcoholOk ? 'Тест Пройден' : 'Требуется проверка'}</p>
                    </div>
                  </div>
                  <input
                    type="checkbox"
                    checked={medData.isAlcoholOk}
                    onChange={(e) => setMedData({ ...medData, isAlcoholOk: e.target.checked })}
                    className="w-6 h-6 rounded-xl border-white/10 bg-black/40 text-emerald-500 focus:ring-0"
                  />
                </div>

                <button type="submit" className="w-full py-5 bg-emerald-600 hover:bg-emerald-500 text-white font-black uppercase tracking-[0.2em] text-xs rounded-[32px] shadow-2xl shadow-emerald-500/20 transition-all active:scale-[0.98]">
                  Заверить Электронной Подписью
                </button>

                <div className="p-4 bg-white/[0.01] border border-white/5 rounded-2xl flex items-center justify-center gap-3">
                  <span className="text-[10px] font-bold text-slate-600 uppercase">Логирование:</span>
                  <span className="text-[10px] font-mono text-slate-500">TIMESTAMP: {currentTime.toISOString()}</span>
                </div>
              </form>
            )}
          </div>
        </div>
      </div>

      <style jsx global>{`
                body {
                    background-image: 
                        radial-gradient(circle at 0% 0%, rgba(99, 102, 241, 0.03) 0, transparent 50%),
                        radial-gradient(circle at 100% 100%, rgba(16, 185, 129, 0.03) 0, transparent 50%);
                }
            `}</style>
    </div>
  );
}
