package ir.ramtung.tinyme.messaging.event;

import ir.ramtung.tinyme.domain.entity.Trade;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

@Data
@EqualsAndHashCode(callSuper = false)
@AllArgsConstructor
@NoArgsConstructor
public class TradeEvent extends Event {
    LocalDateTime time;
    private String securityIsin;
    int price;
    int quantity;
    long buyId;
    long sellId;
}
