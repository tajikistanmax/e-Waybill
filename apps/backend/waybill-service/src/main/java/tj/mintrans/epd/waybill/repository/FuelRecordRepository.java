package tj.mintrans.epd.waybill.repository;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import tj.mintrans.epd.waybill.domain.FuelRecord;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface FuelRecordRepository extends JpaRepository<FuelRecord, UUID> {

    List<FuelRecord> findByWaybillIdOrderByCreatedAt(UUID waybillId);

    /**
     * Последние записи топлива данного вида по ТС (через путевые листы этого госномера), новые сверху.
     * Перенос legacy {@code api/parking_fuel_left} / {@code ref/remain_fuel} (MIGRATION.md §4.9):
     * «Бақияи пеш аз баромад» нового ПЛ = {@code remain_fuel_entry} предыдущего ПЛ того же ТС по тому же
     * виду топлива; «Дода шавад» — {@code be_given} предыдущего. Текущий ПЛ исключает сервис.
     */
    @Query("select f from FuelRecord f, Waybill w where f.waybillId = w.id "
            + "and w.vehicleRegNumber = :reg and f.fuelType = :fuelType "
            + "order by f.createdAt desc")
    List<FuelRecord> findLatestForVehicle(@Param("reg") String vehicleRegNumber,
                                          @Param("fuelType") short fuelType,
                                          Pageable pageable);

    // --- Агрегаты для сводок (ReportService): считает БД, записи в память не поднимаются
    //     (после Ф5 findAll() по fuel_record/waybill ронял сервис в OutOfMemoryError). ---

    /** Σ выданного топлива по ПЛ периода [from, to) — все организации; null, если строк нет. */
    @Query("select sum(f.fuelGiven) from FuelRecord f, Waybill w where f.waybillId = w.id "
            + "and w.createdAt >= :from and w.createdAt < :to")
    BigDecimal sumFuelGivenInPeriod(@Param("from") OffsetDateTime from, @Param("to") OffsetDateTime to);

    @Query("select sum(f.fuelGiven) from FuelRecord f, Waybill w where f.waybillId = w.id "
            + "and w.organizationRma in :rmas and w.createdAt >= :from and w.createdAt < :to")
    BigDecimal sumFuelGivenInPeriodForOrganizations(@Param("rmas") Collection<String> organizationRmas,
                                                    @Param("from") OffsetDateTime from,
                                                    @Param("to") OffsetDateTime to);

    /** По видам топлива: [fuelType, Σ выдано, Σ остаток при возврате] по ПЛ периода. */
    @Query("select f.fuelType, sum(f.fuelGiven), sum(f.remainEntry) from FuelRecord f, Waybill w "
            + "where f.waybillId = w.id and w.createdAt >= :from and w.createdAt < :to "
            + "group by f.fuelType order by f.fuelType")
    List<Object[]> sumByFuelTypeInPeriod(@Param("from") OffsetDateTime from, @Param("to") OffsetDateTime to);

    @Query("select f.fuelType, sum(f.fuelGiven), sum(f.remainEntry) from FuelRecord f, Waybill w "
            + "where f.waybillId = w.id and w.organizationRma in :rmas "
            + "and w.createdAt >= :from and w.createdAt < :to group by f.fuelType order by f.fuelType")
    List<Object[]> sumByFuelTypeInPeriodForOrganizations(@Param("rmas") Collection<String> organizationRmas,
                                                         @Param("from") OffsetDateTime from,
                                                         @Param("to") OffsetDateTime to);
}
