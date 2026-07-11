"use client";

import React, { useState } from "react";
import Image from "next/image";
import { useRouter } from "next/navigation";

export default function LoginPage() {
    const [email, setEmail] = useState("");
    const [password, setPassword] = useState("");
    const router = useRouter();

    const handleLogin = (e: React.FormEvent) => {
        e.preventDefault();
        console.log("Logging in with:", email, password);
        // Simple mock login redirect
        router.push("/dashboard");
    };

    const handleSSO = () => {
        console.log("Redirecting to SSO...");
    };

    return (
        <div className="min-h-screen flex items-center justify-center bg-[#0f172a] relative overflow-hidden font-sans">
            {/* Background Decorative Elements */}
            <div className="absolute top-[-10%] left-[-10%] w-[40%] h-[40%] bg-blue-600 rounded-full blur-[120px] opacity-20 animate-pulse"></div>
            <div className="absolute bottom-[-10%] right-[-10%] w-[40%] h-[40%] bg-indigo-600 rounded-full blur-[120px] opacity-20 animate-pulse delay-700"></div>

            <div className="relative z-10 w-full max-w-md p-8 sm:p-12">
                {/* Glassmorphism Card */}
                <div className="bg-white/10 backdrop-blur-xl border border-white/20 rounded-3xl shadow-2xl p-8 transition-all hover:border-white/30">

                    {/* Header */}
                    <div className="text-center mb-10">
                        <h1 className="text-3xl font-bold text-white tracking-tight mb-2">Путевой Лист</h1>
                        <p className="text-slate-400 text-sm">Система управления цифровыми документами</p>
                    </div>

                    {/* Local Login Form */}
                    <form onSubmit={handleLogin} className="space-y-6">
                        <div>
                            <label className="block text-xs font-semibold text-slate-300 uppercase tracking-wider mb-2" htmlFor="email">
                                Email / Логин
                            </label>
                            <input
                                id="email"
                                type="text"
                                value={email}
                                onChange={(e) => setEmail(e.target.value)}
                                className="w-full px-4 py-3 bg-white/5 border border-white/10 rounded-xl text-white focus:outline-none focus:ring-2 focus:ring-blue-500/50 focus:border-blue-500/50 transition-all placeholder:text-slate-500"
                                placeholder="admin@company.com"
                                required
                            />
                        </div>

                        <div>
                            <div className="flex justify-between mb-2">
                                <label className="text-xs font-semibold text-slate-300 uppercase tracking-wider" htmlFor="password">
                                    Пароль
                                </label>
                                <a href="#" className="text-xs text-blue-400 hover:text-blue-300 transition-colors">Забыли?</a>
                            </div>
                            <input
                                id="password"
                                type="password"
                                value={password}
                                onChange={(e) => setPassword(e.target.value)}
                                className="w-full px-4 py-3 bg-white/5 border border-white/10 rounded-xl text-white focus:outline-none focus:ring-2 focus:ring-blue-500/50 focus:border-blue-500/50 transition-all placeholder:text-slate-500"
                                placeholder="••••••••"
                                required
                            />
                        </div>

                        <button
                            type="submit"
                            className="w-full py-3.5 px-4 bg-blue-600 hover:bg-blue-500 text-white font-bold rounded-xl shadow-lg shadow-blue-600/20 active:scale-[0.98] transition-all"
                        >
                            Войти в систему
                        </button>
                    </form>

                    {/* SSO Section */}
                    <div className="mt-10">
                        <div className="relative flex items-center justify-center mb-8">
                            <div className="w-full border-t border-white/10"></div>
                            <span className="absolute bg-[#1a2133] px-4 text-xs font-medium text-slate-500 uppercase tracking-widest leading-none">или</span>
                        </div>

                        <button
                            onClick={handleSSO}
                            className="w-full flex items-center justify-center gap-3 py-3 px-4 bg-white hover:bg-slate-100 text-slate-900 font-bold rounded-xl transition-all active:scale-[0.98]"
                        >
                            <svg className="w-5 h-5" viewBox="0 0 24 24">
                                <path d="M12.48 10.92v3.28h7.84c-.24 1.84-.9 3.22-1.9 4.22-1.2 1.2-3.04 2.4-6.04 2.4-4.8 0-8.68-3.88-8.68-8.68s3.88-8.68 8.68-8.68c2.6 0 4.5 1.02 5.9 2.32l2.32-2.32C18.6 1.3 15.82 0 12.48 0 5.58 0 0 5.58 0 12.48s5.58 12.48 12.48 12.48c3.7 0 6.48-1.2 8.62-3.44 2.22-2.22 2.92-5.32 2.92-7.82 0-.74-.06-1.44-.18-2.12H12.48z" fill="currentColor" />
                            </svg>
                            Войти через Единый Вход (SSO)
                        </button>
                    </div>

                    {/* Footer Metadata */}
                    <div className="mt-8 text-center">
                        <p className="text-xs text-slate-500">
                            © 2025 Waybill Platform. Все права защищены.
                        </p>
                    </div>
                </div>
            </div>
        </div>
    );
}
