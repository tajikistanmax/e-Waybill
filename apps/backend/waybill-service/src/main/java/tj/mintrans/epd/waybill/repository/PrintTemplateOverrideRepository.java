package tj.mintrans.epd.waybill.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import tj.mintrans.epd.waybill.domain.PrintTemplateOverride;

public interface PrintTemplateOverrideRepository extends JpaRepository<PrintTemplateOverride, String> {
}
