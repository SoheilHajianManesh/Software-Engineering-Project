package ir.ramtung.tinyme.domain.entity;

import ir.ramtung.tinyme.messaging.request.EnterOrderRq;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;

import java.time.LocalDateTime;


@Getter
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
public class StopLimitOrder extends Order {
    int stopPrice;
    long requestId;
    public StopLimitOrder(long orderId, Security security, Side side, int quantity, int price, Broker broker, Shareholder shareholder, int stopPrice,long requestId) {
        super(orderId, security, side, quantity, price, broker, shareholder);
        this.stopPrice = stopPrice;
        this.requestId= requestId;
    }

    public StopLimitOrder(long orderId, Security security, Side side, int quantity, int price, Broker broker, Shareholder shareholder, int stopPrice) {
        super(orderId, security, side, quantity, price, broker, shareholder);
        this.stopPrice = stopPrice;
    }



    @Override
    public boolean canBeActive(int lastPrice){
        if(side==Side.BUY)
            return lastPrice >= stopPrice;
        else
            return lastPrice <= stopPrice;
    }
    @Override
    public void updateFromRequest(EnterOrderRq updateOrderRq) {
        quantity = updateOrderRq.getQuantity();
        price = updateOrderRq.getPrice();
        stopPrice = updateOrderRq.getStopPrice();
    }

    @Override
    public int getStopPrice(){
        return stopPrice;
    }



}
