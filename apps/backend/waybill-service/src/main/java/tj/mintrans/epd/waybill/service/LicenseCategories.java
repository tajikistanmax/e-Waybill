package tj.mintrans.epd.waybill.service;

import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;

/**
 * Категории водительского удостоверения из текста реестра (сверка 25.09, A22). В перенесённых данных
 * категории записаны как попало: «BCD», «ВСД» (кириллица), «БсД», «В.С.Д», «В,С,Д», «ВВ1СС1», «В В1 С С1»,
 * «bc». Раньше строка делилась только по запятым/пробелам, и «BCD» или «ВСД» не содержали отдельного «D» —
 * новое правило «категория ↔ вид ТС» блокировало выписку тысячам водителей с нужной категорией.
 *
 * <p>Разбор: верхний регистр, кириллические двойники → латиница (А/В/Б→A/B, С→C, Д→D, Е→E, М→M, Т→T),
 * разделители убираются, далее буква + необязательная «1» (подкатегории A1/B1/C1/D1). Строка, которую
 * так разобрать нельзя (цифры вроде «3», посторонние буквы), — {@link Optional#empty()}: проверка
 * категорий для такого водителя не выполняется, как и при пустом поле.</p>
 */
public final class LicenseCategories {

    private LicenseCategories() {
    }

    public static Optional<Set<String>> parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        StringBuilder s = new StringBuilder();
        for (char ch : raw.toUpperCase().toCharArray()) {
            switch (ch) {
                case 'А' -> s.append('A');
                case 'В', 'Б' -> s.append('B');
                case 'С' -> s.append('C');
                case 'Д' -> s.append('D');
                case 'Е' -> s.append('E');
                case 'М' -> s.append('M');
                case 'Т' -> s.append('T');
                case ' ', ',', '.', ';', '/', '-', '\\', '\t' -> { /* разделители */ }
                default -> s.append(ch);
            }
        }
        Set<String> out = new LinkedHashSet<>();
        String t = s.toString();
        for (int i = 0; i < t.length(); i++) {
            char c = t.charAt(i);
            if ("ABCDEMT".indexOf(c) < 0) {
                return Optional.empty();   // не категория — не угадываем
            }
            if (i + 1 < t.length() && t.charAt(i + 1) == '1' && "ABCD".indexOf(c) >= 0) {
                out.add(c + "1");
                i++;
            } else {
                out.add(String.valueOf(c));
            }
        }
        return out.isEmpty() ? Optional.empty() : Optional.of(out);
    }

    /** Есть ли у водителя хотя бы одна из категорий {@code anyOf}; неразобранная строка — не проверяется (true). */
    public static boolean hasAny(String raw, Set<String> anyOf) {
        return parse(raw).map(set -> set.stream().anyMatch(anyOf::contains)).orElse(true);
    }
}
