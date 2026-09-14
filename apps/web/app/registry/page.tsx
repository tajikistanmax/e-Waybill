import { redirect } from 'next/navigation';

/** /registry → раздел «Транспорт» (у каждого раздела свой адрес). */
export default function RegistryIndex() {
  redirect('/registry/vehicles');
}
