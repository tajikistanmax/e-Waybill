package tj.mintrans.epd.waybill;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling // автопереходы статусов: EXPIRED/ARCHIVED (LifecycleScheduler)
public class WaybillApplication {

    public static void main(String[] args) {
        SpringApplication.run(WaybillApplication.class, args);
    }
}
