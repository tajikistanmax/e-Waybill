package tj.mintrans.epd.waybill.domain;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

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
}
