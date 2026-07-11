import Link from "next/link";

export default function Home() {
  return (
    <div className="flex min-h-screen items-center justify-center bg-[#0f172a] font-sans text-white text-center">
      <main className="flex flex-col items-center gap-8 p-12 bg-white/5 backdrop-blur-lg border border-white/10 rounded-3xl">
        <h1 className="text-4xl font-bold tracking-tight">Добро пожаловать в Систему &quot;Путевой Лист&quot;</h1>
        <p className="text-slate-400 max-w-md">Автоматизация и контроль цифровых документов для вашего автопарка.</p>
        <Link
          href="/login"
          className="px-8 py-3 bg-blue-600 hover:bg-blue-500 rounded-xl font-bold transition-all shadow-lg shadow-blue-600/20 active:scale-95"
        >
          Войти в кабинет
        </Link>
      </main>
    </div>
  );
}
