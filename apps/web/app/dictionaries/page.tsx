import { redirect } from 'next/navigation';

/** /dictionaries → «Маршруты» (у каждого справочника свой адрес). */
export default function DictionariesIndex() {
  redirect('/dictionaries/routes');
}
