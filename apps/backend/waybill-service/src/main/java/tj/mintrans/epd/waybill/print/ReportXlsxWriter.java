package tj.mintrans.epd.waybill.print;

import org.apache.poi.ss.usermodel.BorderStyle;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.HorizontalAlignment;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Component;
import tj.mintrans.epd.waybill.calc.report.RegionalCountReport;
import tj.mintrans.epd.waybill.calc.report.RegionalReport;
import tj.mintrans.epd.waybill.calc.report.ReportRow;
import tj.mintrans.epd.waybill.calc.report.WaybillReport;
import tj.mintrans.epd.waybill.service.InspectionJournalService;
import tj.mintrans.epd.waybill.service.MalumotnomaService;
import tj.mintrans.epd.waybill.web.error.ApiErrors.UnprocessableException;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * Выгрузка типового отчёта ({@link WaybillReport}) в XLSX (Apache POI).
 *
 * <p>Тот же набор показателей, что и в JSON-ответе {@code /api/v1/reports/*} —
 * лист с шапкой (тип разреза, период, организация), строками группировки и строкой «ИТОГО».</p>
 */
@Component
public class ReportXlsxWriter {

    private static final DateTimeFormatter D = DateTimeFormatter.ofPattern("dd.MM.yyyy");

    private static final String[] HEADERS = {
            "Группа", "Наименование", "ПЛ", "Рейсы", "Пробег, км", "Пробег по маршруту, км",
            "Пассажирооборот, пасс-км", "Пассажиры", "Норма топлива, л", "Выдано топлива, л",
            "Фарқият (норма − выдано), л", "Выручка", "Касса", "Заработок водителей",
            "Рабочие дни", "Часы", "Грузооборот P, т·км", "Ездки Z",
            // Топливо по видам legacy «меъёр / асл / фарқият Б/С/Г» (сверка 25.09, D6).
            "Норма Б, л", "Норма С, л", "Норма Г, л", "Выдано Б, л", "Выдано С, л", "Выдано Г, л",
            "Фарқият Б, л", "Фарқият С, л", "Фарқият Г, л"
    };

    public byte[] write(WaybillReport report) {
        try (Workbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = wb.createSheet("Отчёт");

            CellStyle titleStyle = wb.createCellStyle();
            Font titleFont = wb.createFont();
            titleFont.setBold(true);
            titleFont.setFontHeightInPoints((short) 12);
            titleStyle.setFont(titleFont);

            CellStyle headStyle = wb.createCellStyle();
            Font headFont = wb.createFont();
            headFont.setBold(true);
            headStyle.setFont(headFont);
            headStyle.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex());
            headStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            headStyle.setBorderBottom(BorderStyle.THIN);
            headStyle.setAlignment(HorizontalAlignment.CENTER);
            headStyle.setWrapText(true);

            CellStyle totalStyle = wb.createCellStyle();
            Font totalFont = wb.createFont();
            totalFont.setBold(true);
            totalStyle.setFont(totalFont);
            totalStyle.setBorderTop(BorderStyle.DOUBLE);

            int r = 0;
            Row t0 = sheet.createRow(r++);
            cell(t0, 0, "Отчёт: " + report.typeLabel(), titleStyle);
            Row t1 = sheet.createRow(r++);
            cell(t1, 0, "Период: " + D.format(report.from()) + " — " + D.format(report.to())
                    + (report.organizationRma() != null ? "   Организация (РМА): " + report.organizationRma() : "   По всем организациям"), null);
            r++; // пустая строка

            Row head = sheet.createRow(r++);
            for (int c = 0; c < HEADERS.length; c++) {
                cell(head, c, HEADERS[c], headStyle);
            }

            for (ReportRow row : report.rows()) {
                writeRow(sheet.createRow(r++), row, null);
            }
            if (report.totals() != null) {
                Row totals = sheet.createRow(r++);
                writeRow(totals, report.totals(), totalStyle);
                totals.getCell(1).setCellValue("ИТОГО");
            }

            for (int c = 0; c < HEADERS.length; c++) {
                sheet.autoSizeColumn(c);
            }
            sheet.setColumnWidth(1, Math.max(sheet.getColumnWidth(1), 7000));
            sheet.createFreezePane(0, 4);
            if (HEADERS.length > 1) {
                sheet.addMergedRegion(new CellRangeAddress(0, 0, 0, HEADERS.length - 1));
                sheet.addMergedRegion(new CellRangeAddress(1, 1, 0, HEADERS.length - 1));
            }

            wb.write(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UnprocessableException("Не удалось сформировать XLSX отчёта");
        }
    }

    private static String[] regionalHeaders(boolean cargo) {
        String vol = cargo ? "тыс. тонн" : "тыс. пасс.";
        String rot = cargo ? "млн т-км" : "млн пасс-км";
        return new String[]{
                "Уровень", "Наименование",
                "План объём тек. (" + vol + ")", "Факт объём тек.", "План объём пред.", "Факт объём пред.",
                "% объём тек.", "% объём пред.",
                "План оборот тек. (" + rot + ")", "Факт оборот тек.", "План оборот пред.", "Факт оборот пред.",
                "% оборот тек.", "% оборот пред."
        };
    }

    /** Сводный региональный отчёт → XLSX: регион / город / предприятие с отступом в колонке «Уровень». */
    public byte[] writeRegional(RegionalReport report) {
        try (Workbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = wb.createSheet("Сводный отчёт");

            CellStyle headStyle = wb.createCellStyle();
            Font headFont = wb.createFont();
            headFont.setBold(true);
            headStyle.setFont(headFont);
            headStyle.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex());
            headStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            headStyle.setAlignment(HorizontalAlignment.CENTER);
            headStyle.setWrapText(true);

            CellStyle regionStyle = wb.createCellStyle();
            Font regionFont = wb.createFont();
            regionFont.setBold(true);
            regionStyle.setFont(regionFont);
            regionStyle.setFillForegroundColor(IndexedColors.LEMON_CHIFFON.getIndex());
            regionStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);

            CellStyle cityStyle = wb.createCellStyle();
            Font cityFont = wb.createFont();
            cityFont.setItalic(true);
            cityStyle.setFont(cityFont);

            CellStyle grandStyle = wb.createCellStyle();
            Font grandFont = wb.createFont();
            grandFont.setBold(true);
            grandStyle.setFont(grandFont);
            grandStyle.setBorderTop(BorderStyle.DOUBLE);

            boolean cargo = "CARGO".equalsIgnoreCase(report.billKind());
            String[] headers = regionalHeaders(cargo);
            int r = 0;
            Row t0 = sheet.createRow(r++);
            cell(t0, 0, "Сводный отчёт по перевозкам (transportation) · "
                    + (cargo ? "грузовые" : "пассажирские") + " · "
                    + D.format(report.from()) + " — " + D.format(report.to())
                    + "   год " + report.year() + " / " + report.prevYear(), null);
            r++;

            Row head = sheet.createRow(r++);
            for (int c = 0; c < headers.length; c++) {
                cell(head, c, headers[c], headStyle);
            }

            for (RegionalReport.Region region : report.regions()) {
                writeInd(sheet.createRow(r++), "Регион", region.title(), region.totals(), regionStyle);
                for (RegionalReport.City city : region.cities()) {
                    writeInd(sheet.createRow(r++), "  Город", city.title(), city.totals(), cityStyle);
                    for (RegionalReport.Company company : city.companies()) {
                        writeInd(sheet.createRow(r++), "    Предприятие", company.title(), company.totals(), null);
                    }
                }
            }
            Row grand = sheet.createRow(r++);
            writeInd(grand, "ИТОГО", "Республика Таджикистан", report.totals(), grandStyle);

            for (int c = 0; c < headers.length; c++) {
                sheet.autoSizeColumn(c);
            }
            sheet.createFreezePane(2, 3);
            return finish(wb, out);
        } catch (IOException e) {
            throw new UnprocessableException("Не удалось сформировать XLSX сводного отчёта");
        }
    }

    private static void writeInd(Row row, String level, String name, RegionalReport.Indicators d, CellStyle style) {
        cell(row, 0, level, style);
        cell(row, 1, name, style);
        double[] v = {
                d.planVolumeCur(), d.factVolumeCur(), d.planVolumePrev(), d.factVolumePrev(),
                d.volumeDoneCurPct(), d.volumeDonePrevPct(),
                d.planRotationCur(), d.factRotationCur(), d.planRotationPrev(), d.factRotationPrev(),
                d.rotationDoneCurPct(), d.rotationDonePrevPct()
        };
        for (int i = 0; i < v.length; i++) {
            num(row, 2 + i, v[i], style);
        }
    }

    private static byte[] finish(Workbook wb, ByteArrayOutputStream out) throws IOException {
        wb.write(out);
        return out.toByteArray();
    }

    private static final String[] MLM_HEADERS = {
            "Кассир / справка", "Ф.И.О.", "Вид транспорта", "Льготная", "Дата выдачи", "Маршруты", "Стоимость"
    };

    /** Отчёт по справкам (маълумотнома) → XLSX: группировка по кассирам + строки справок. */
    public byte[] writeMalumotnoma(MalumotnomaService.Report report) {
        try (Workbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = wb.createSheet("Справки");

            CellStyle headStyle = wb.createCellStyle();
            Font headFont = wb.createFont();
            headFont.setBold(true);
            headStyle.setFont(headFont);
            headStyle.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex());
            headStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);

            CellStyle groupStyle = wb.createCellStyle();
            Font groupFont = wb.createFont();
            groupFont.setBold(true);
            groupStyle.setFont(groupFont);
            groupStyle.setFillForegroundColor(IndexedColors.LEMON_CHIFFON.getIndex());
            groupStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);

            CellStyle grandStyle = wb.createCellStyle();
            Font grandFont = wb.createFont();
            grandFont.setBold(true);
            grandStyle.setFont(grandFont);
            grandStyle.setBorderTop(BorderStyle.DOUBLE);

            int r = 0;
            cell(sheet.createRow(r++), 0, "Отчёт по справкам · " + D.format(report.from()) + " — "
                    + D.format(report.to()) + "   справок: " + report.count()
                    + "   на сумму: " + report.total(), null);
            r++;
            Row head = sheet.createRow(r++);
            for (int c = 0; c < MLM_HEADERS.length; c++) {
                cell(head, c, MLM_HEADERS[c], headStyle);
            }

            for (var g : report.groups()) {
                Row gr = sheet.createRow(r++);
                cell(gr, 0, "Кассир: " + (g.issuerName() != null ? g.issuerName() : g.issuerRma()), groupStyle);
                cell(gr, 1, "справок: " + g.count(), groupStyle);
                num(gr, 6, g.amount() == null ? 0 : g.amount().doubleValue(), groupStyle);
                for (var it : g.items()) {
                    Row row = sheet.createRow(r++);
                    cell(row, 0, "  " + it.id().toString().substring(0, 8), null);
                    cell(row, 1, it.fio(), null);
                    cell(row, 2, it.transportType(), null);
                    cell(row, 3, it.privileged() ? "да" : "", null);
                    cell(row, 4, it.issuedAt() == null ? "" : PrintZone.dateTime(it.issuedAt()), null);
                    cell(row, 5, it.routes(), null);
                    num(row, 6, it.price() == null ? 0 : it.price().doubleValue(), null);
                }
            }
            Row grand = sheet.createRow(r++);
            cell(grand, 0, "ИТОГО", grandStyle);
            cell(grand, 1, "справок: " + report.count(), grandStyle);
            num(grand, 6, report.total() == null ? 0 : report.total().doubleValue(), grandStyle);

            for (int c = 0; c < MLM_HEADERS.length; c++) {
                sheet.autoSizeColumn(c);
            }
            return finish(wb, out);
        } catch (IOException e) {
            throw new UnprocessableException("Не удалось сформировать XLSX отчёта по справкам");
        }
    }

    /** Журнал предрейсового техконтроля (тип 13) → XLSX. */
    public byte[] writeMechanicJournal(InspectionJournalService.MechanicJournal j) {
        List<String[]> rows = new ArrayList<>();
        for (InspectionJournalService.MechanicRow r : j.rows()) {
            rows.add(new String[]{
                    r.date(), r.number(), r.vehicle(), r.driver(),
                    r.odometerExit() == null ? "" : String.valueOf(r.odometerExit()),
                    r.control().verdict(), r.control().employeeName(), r.control().employeeRma(),
                    PrintZone.isoToLocal(r.control().signedAt()), r.control().fingerprint(), r.control().details()
            });
        }
        return simpleSheet("Журнал механика",
                "Дафтари қайди механик · " + D2(j.from()) + " — " + D2(j.to()),
                new String[]{"Дата", "№ ПЛ", "ТС", "Водитель", "Одометр выезд",
                        "Заключение", "Механик", "РМА", "Подписано", "Отпечаток ЭП", "Показатели"},
                rows);
    }

    /** Журнал медосмотра (тип 14) → XLSX: предрейсовый (Т2) и послерейсовый (Т6). */
    public byte[] writeDoctorJournal(InspectionJournalService.DoctorJournal j) {
        List<String[]> rows = new ArrayList<>();
        for (InspectionJournalService.DoctorRow r : j.rows()) {
            rows.add(new String[]{
                    r.date(), r.number(), r.vehicle(), r.driver(),
                    mark(r.preTrip()), mark(r.postTrip())
            });
        }
        return simpleSheet("Журнал врача",
                "Дафтари қайди духтӯр · " + D2(j.from()) + " — " + D2(j.to()),
                new String[]{"Дата", "№ ПЛ", "ТС", "Водитель",
                        "Предрейсовый осмотр (Т2)", "Послерейсовый осмотр (Т6)"},
                rows);
    }

    private static String mark(InspectionJournalService.Mark m) {
        if (m == null) {
            return "—";
        }
        StringBuilder s = new StringBuilder(m.verdict());
        if (m.employeeName() != null && !m.employeeName().isBlank()) {
            s.append(" · ").append(m.employeeName());
        }
        if (!"—".equals(m.signedAt())) {
            s.append(" · ").append(PrintZone.isoToLocal(m.signedAt()));
        }
        if (m.details() != null && !m.details().isBlank()) {
            s.append(" · ").append(m.details());
        }
        return s.toString();
    }

    private byte[] simpleSheet(String sheetName, String title, String[] headers, List<String[]> rows) {
        try (Workbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = wb.createSheet(sheetName);
            CellStyle headStyle = wb.createCellStyle();
            Font headFont = wb.createFont();
            headFont.setBold(true);
            headStyle.setFont(headFont);
            headStyle.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex());
            headStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);

            int r = 0;
            cell(sheet.createRow(r++), 0, title + "   (строк: " + rows.size() + ")", null);
            r++;
            Row head = sheet.createRow(r++);
            for (int c = 0; c < headers.length; c++) {
                cell(head, c, headers[c], headStyle);
            }
            for (String[] row : rows) {
                Row xr = sheet.createRow(r++);
                for (int c = 0; c < row.length; c++) {
                    cell(xr, c, row[c], null);
                }
            }
            for (int c = 0; c < headers.length; c++) {
                sheet.autoSizeColumn(c);
            }
            sheet.createFreezePane(0, 3);
            return finish(wb, out);
        } catch (IOException e) {
            throw new UnprocessableException("Не удалось сформировать XLSX журнала");
        }
    }

    private static String D2(java.time.LocalDate d) {
        return D.format(d);
    }

    private static final String[] COUNT_HEADERS = {
            "Уровень", "Наименование",
            "1 выд. месяц", "2 выд. пр.мес", "3 Δ", "4 выд. с нач.года", "5 выд. пр.год", "6 Δ",
            "7 обр. месяц", "8 обр. пр.мес", "9 Δ", "10 обр. с нач.года", "11 обр. пр.год", "12 Δ",
            "13 необраб.", "14 ТС год", "15 ТС месяц", "16 ТС пр.мес", "17 ТС мес.пр.года", "18 Δ", "19 Δг/г",
            "20 груз. ПЛ", "21 борхатов"
    };

    /** Сводный отчёт «Количество путевых листов» (§6.3) → XLSX. */
    public byte[] writeRegionalCount(RegionalCountReport report) {
        try (Workbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = wb.createSheet("Количество ПЛ");
            CellStyle headStyle = wb.createCellStyle();
            Font headFont = wb.createFont();
            headFont.setBold(true);
            headStyle.setFont(headFont);
            headStyle.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex());
            headStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            headStyle.setWrapText(true);
            CellStyle regionStyle = wb.createCellStyle();
            Font rf = wb.createFont();
            rf.setBold(true);
            regionStyle.setFont(rf);
            regionStyle.setFillForegroundColor(IndexedColors.LEMON_CHIFFON.getIndex());
            regionStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            CellStyle grandStyle = wb.createCellStyle();
            Font gf = wb.createFont();
            gf.setBold(true);
            grandStyle.setFont(gf);
            grandStyle.setBorderTop(BorderStyle.DOUBLE);

            int r = 0;
            cell(sheet.createRow(r++), 0, "Количество путевых листов · " + report.billKind() + " · "
                    + D.format(report.from()) + " — " + D.format(report.to())
                    + "   год " + report.year() + " / " + report.prevYear(), null);
            r++;
            Row head = sheet.createRow(r++);
            for (int c = 0; c < COUNT_HEADERS.length; c++) {
                cell(head, c, COUNT_HEADERS[c], headStyle);
            }
            for (RegionalCountReport.Region region : report.regions()) {
                writeCounts(sheet.createRow(r++), "Регион", region.title(), region.totals(), regionStyle);
                for (RegionalCountReport.City city : region.cities()) {
                    writeCounts(sheet.createRow(r++), "  Город", city.title(), city.totals(), null);
                    for (RegionalCountReport.Company co : city.companies()) {
                        writeCounts(sheet.createRow(r++), "    Предприятие", co.title(), co.totals(), null);
                    }
                }
            }
            writeCounts(sheet.createRow(r++), "ИТОГО", "Республика Таджикистан", report.totals(), grandStyle);
            for (int c = 0; c < COUNT_HEADERS.length; c++) {
                sheet.autoSizeColumn(c);
            }
            sheet.createFreezePane(2, 3);
            return finish(wb, out);
        } catch (IOException e) {
            throw new UnprocessableException("Не удалось сформировать XLSX отчёта о количестве ПЛ");
        }
    }

    private static void writeCounts(Row row, String level, String name, RegionalCountReport.Counts d, CellStyle style) {
        cell(row, 0, level, style);
        cell(row, 1, name, style);
        long[] v = {
                d.issuedMonth(), d.issuedPrevMonth(), d.issuedMonthDelta(),
                d.issuedYtd(), d.issuedYtdPrev(), d.issuedYtdDelta(),
                d.processedMonth(), d.processedPrevMonth(), d.processedMonthDelta(),
                d.processedYtd(), d.processedYtdPrev(), d.processedYtdDelta(),
                d.unprocessedYtd(), d.vehiclesYtd(), d.vehiclesMonth(), d.vehiclesPrevMonth(),
                d.vehiclesMonthPrevYear(), d.vehiclesMonthDelta(), d.vehiclesYoYDelta(),
                d.cargoWaybillsTotal(), d.cargoWaybillsWithConsignment()
        };
        for (int i = 0; i < v.length; i++) {
            num(row, 2 + i, v[i], style);
        }
    }

    /** Норматив выдачи путевых листов (§6.4) → XLSX. */
    public byte[] writeWaybillNorm(tj.mintrans.epd.waybill.calc.report.WaybillNormReport report) {
        try (Workbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = wb.createSheet("Норматив выдачи");
            CellStyle head = wb.createCellStyle();
            Font hf = wb.createFont();
            hf.setBold(true);
            head.setFont(hf);
            head.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex());
            head.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            CellStyle grand = wb.createCellStyle();
            Font gf = wb.createFont();
            gf.setBold(true);
            grand.setFont(gf);
            grand.setBorderTop(BorderStyle.DOUBLE);

            int r = 0;
            cell(sheet.createRow(r++), 0, "Норматив выдачи ПЛ · " + report.waybillTypeLabel()
                    + " · норма на стоянку: " + report.mustGivePerParking()
                    + " · " + D.format(report.from()) + " — " + D.format(report.to()), null);
            r++;
            String[] hs = {"Организация", "РМА", "Регион", "Выдано (1)", "Стоянок ТС (2)",
                    "Выдано/стоянку (3)", "Норматив (4)", "Отклонение (5)"};
            Row h = sheet.createRow(r++);
            for (int c = 0; c < hs.length; c++) {
                cell(h, c, hs[c], head);
            }
            for (var row : report.rows()) {
                Row xr = sheet.createRow(r++);
                cell(xr, 0, row.organizationName(), null);
                cell(xr, 1, row.organizationRma(), null);
                cell(xr, 2, row.regionTitle(), null);
                num(xr, 3, row.issued(), null);
                num(xr, 4, row.parkings(), null);
                num(xr, 5, row.perParking(), null);
                num(xr, 6, row.mustGive(), null);
                num(xr, 7, row.deviation(), null);
            }
            Row tr = sheet.createRow(r++);
            cell(tr, 0, "ИТОГО", grand);
            num(tr, 3, report.totals().issued(), grand);
            num(tr, 4, report.totals().parkings(), grand);
            num(tr, 5, report.totals().perParking(), grand);
            num(tr, 6, report.totals().mustGive(), grand);
            num(tr, 7, report.totals().deviation(), grand);
            for (int c = 0; c < hs.length; c++) {
                sheet.autoSizeColumn(c);
            }
            return finish(wb, out);
        } catch (IOException e) {
            throw new UnprocessableException("Не удалось сформировать XLSX норматива выдачи");
        }
    }

    public static String fileName(WaybillReport report) {
        return "report-" + report.type().name().toLowerCase()
                + "-" + report.from() + "_" + report.to() + ".xlsx";
    }

    private static void writeRow(Row row, ReportRow d, CellStyle style) {
        cell(row, 0, d.key(), style);
        cell(row, 1, d.label(), style);
        num(row, 2, d.waybills(), style);
        num(row, 3, d.laps(), style);
        num(row, 4, round(d.distanceKm()), style);
        num(row, 5, round(d.routeDistanceKm()), style);
        num(row, 6, round(d.passengerTurnover()), style);
        num(row, 7, round(d.passengerCount()), style);
        num(row, 8, round(d.fuelNormLiters()), style);
        num(row, 9, round(d.fuelGivenLiters()), style);
        num(row, 10, round(d.fuelDeviationLiters()), style);
        num(row, 11, bd(d.revenue()), style);
        num(row, 12, bd(d.kassa()), style);
        num(row, 13, bd(d.driverSalary()), style);
        num(row, 14, d.workDays(), style);
        num(row, 15, round(d.workHours()), style);
        num(row, 16, round(d.transportWork()), style);
        num(row, 17, round(d.trips()), style);
        num(row, 18, round(d.fuelNormPetrol()), style);
        num(row, 19, round(d.fuelNormDiesel()), style);
        num(row, 20, round(d.fuelNormGas()), style);
        num(row, 21, round(d.fuelGivenPetrol()), style);
        num(row, 22, round(d.fuelGivenDiesel()), style);
        num(row, 23, round(d.fuelGivenGas()), style);
        num(row, 24, round(d.fuelNormPetrol() - d.fuelGivenPetrol()), style);
        num(row, 25, round(d.fuelNormDiesel() - d.fuelGivenDiesel()), style);
        num(row, 26, round(d.fuelNormGas() - d.fuelGivenGas()), style);
    }

    private static double round(double v) {
        return Math.round(v * 100.0) / 100.0;
    }

    private static double bd(BigDecimal v) {
        return v == null ? 0d : v.doubleValue();
    }

    private static void cell(Row row, int col, String value, CellStyle style) {
        Cell c = row.createCell(col);
        c.setCellValue(value == null ? "" : value);
        if (style != null) {
            c.setCellStyle(style);
        }
    }

    private static void num(Row row, int col, double value, CellStyle style) {
        Cell c = row.createCell(col);
        c.setCellValue(value);
        if (style != null) {
            c.setCellStyle(style);
        }
    }
}
