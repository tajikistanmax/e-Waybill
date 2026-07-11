package tj.mintrans.epd.waybill.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import tj.mintrans.epd.waybill.domain.Notification;

import java.util.List;
import java.util.UUID;

public interface NotificationRepository extends JpaRepository<Notification, UUID> {

    List<Notification> findTop100ByRecipientRmaOrderByCreatedAtDesc(String recipientRma);

    List<Notification> findTop100ByOrderByCreatedAtDesc();

    long countByRecipientRmaAndReadAtIsNull(String recipientRma);

    long countByReadAtIsNull();

    List<Notification> findByRecipientRmaAndReadAtIsNull(String recipientRma);
}
