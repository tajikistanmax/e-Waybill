package tj.mintrans.epd.waybill.web;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tj.mintrans.epd.waybill.service.DashboardService;

/** Показатели главной панели — агрегаты по всей области видимости, а не по 1000 последних листов. */
@RestController
@RequestMapping("/api/v1/dashboard")
public class DashboardController {

    private final DashboardService dashboard;

    public DashboardController(DashboardService dashboard) {
        this.dashboard = dashboard;
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('DISPATCHER','ACCOUNTANT','COMPANY_ADMIN','BRANCH_ADMIN','SYSTEM_ADMIN','MINTRANS_ANALYST','INSPECTOR')")
    public DashboardService.Stats stats() {
        return dashboard.stats();
    }
}
