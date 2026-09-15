package tj.mintrans.epd.waybill.web;

import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tj.mintrans.epd.waybill.calc.WaybillCalcEngine;
import tj.mintrans.epd.waybill.calc.model.CargoCalcInput;
import tj.mintrans.epd.waybill.calc.model.CargoCalcResult;
import tj.mintrans.epd.waybill.calc.model.PassengerCalcInput;
import tj.mintrans.epd.waybill.calc.model.PassengerCalcResult;

/**
 * Движок расчёта путевого листа (перенос движка ИС «Роҳхат», пакет calc).
 *
 * <p>Пока — «калькулятор»: принимает самодостаточный вход и возвращает полное
 * разложение (коэффициенты, нормативный расход по видам топлива, остатки,
 * заработок водителя, пассажирские показатели, тариф маршрута). Привязка к
 * конкретному ПЛ по id — следующая итерация (Phase 3b).</p>
 */
@RestController
@RequestMapping("/api/v1/calc")
public class WaybillCalcController {

    private final WaybillCalcEngine engine;

    public WaybillCalcController(WaybillCalcEngine engine) {
        this.engine = engine;
    }

    /** Расчёт пассажирского путевого листа (формы 1-А, 1-АД, 1-АДЕ, 3-С). */
    @PostMapping("/passenger")
    @PreAuthorize("hasAnyRole('DISPATCHER','COMPANY_ADMIN','ACCOUNTANT','SYSTEM_ADMIN')")
    public PassengerCalcResult passenger(@Valid @RequestBody PassengerCalcInput input) {
        return engine.passenger(input);
    }

    /** Расчёт грузового путевого листа (формы 2-Б, 5Б-БМ). */
    @PostMapping("/cargo")
    @PreAuthorize("hasAnyRole('DISPATCHER','COMPANY_ADMIN','ACCOUNTANT','SYSTEM_ADMIN')")
    public CargoCalcResult cargo(@Valid @RequestBody CargoCalcInput input) {
        return engine.cargo(input);
    }
}
