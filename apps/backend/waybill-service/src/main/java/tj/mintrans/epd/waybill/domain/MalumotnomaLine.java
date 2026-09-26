package tj.mintrans.epd.waybill.domain;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Строка маршрута справки (перенос {@code rmalumotnomas}): маршрут + признак
 * «туда и обратно» (цена ×2).
 */
@Entity
@Table(name = "malumotnoma_line")
public class MalumotnomaLine {

    @Id
    private UUID id;

    @ManyToOne(optional = false)
    @JoinColumn(name = "malumotnoma_id", nullable = false)
    @JsonIgnore
    private Malumotnoma malumotnoma;

    @ManyToOne(optional = false)
    @JoinColumn(name = "malumotnoma_route_id", nullable = false)
    private MalumotnomaRoute route;

    @Column(name = "round_trip", nullable = false)
    private boolean roundTrip;

    /** Цена строки на момент выдачи: тариф вида × 2 при «туда и обратно», до льготы. */
    private BigDecimal price;

    /** Порядок маршрута в справке (строка «самт» на бланке). */
    @Column(nullable = false)
    private short position;

    @PrePersist
    void prePersist() {
        if (id == null) {
            id = UUID.randomUUID();
        }
    }

    public UUID getId() { return id; }
    public Malumotnoma getMalumotnoma() { return malumotnoma; }
    public void setMalumotnoma(Malumotnoma malumotnoma) { this.malumotnoma = malumotnoma; }
    public MalumotnomaRoute getRoute() { return route; }
    public void setRoute(MalumotnomaRoute route) { this.route = route; }
    public boolean isRoundTrip() { return roundTrip; }
    public void setRoundTrip(boolean roundTrip) { this.roundTrip = roundTrip; }
    public BigDecimal getPrice() { return price; }
    public void setPrice(BigDecimal price) { this.price = price; }
    public short getPosition() { return position; }
    public void setPosition(short position) { this.position = position; }
}
