package ir.ramtung.tinyme.domain.entity;

import java.util.LinkedList;
import java.util.List;
import java.util.Objects;

public final class MatchResult {
    private final MatchingOutcome outcome;
    private final Order remainder;
    private final LinkedList<Trade> trades;

    public static MatchResult executed(Order remainder, List<Trade> trades) {
        return new MatchResult(MatchingOutcome.EXECUTED, remainder, new LinkedList<>(trades));
    }

    public static MatchResult notEnoughCredit() {
        return new MatchResult(MatchingOutcome.NOT_ENOUGH_CREDIT, null, new LinkedList<>());
    }
    public static MatchResult notEnoughPositions() {
        return new MatchResult(MatchingOutcome.NOT_ENOUGH_POSITIONS, null, new LinkedList<>());
    }
    public static MatchResult minimumQuantityInsufficient() {
        return new MatchResult(MatchingOutcome.MINIMUM_QUANTITY_INSUFFICIENT, null, new LinkedList<>());
    }
    public static MatchResult cantUpdateMinQuantity() {
        return new MatchResult(MatchingOutcome.CANT_UPDATE_MIN_QUANTITY, null, new LinkedList<>());
    }
    public static MatchResult inActiveOrderEnqueued() {
        return new MatchResult(MatchingOutcome.INACTIVE_ORDER_ENQUEUED, null, new LinkedList<>());
    }

    public static MatchResult openingPriceAnnouncement() {
        return new MatchResult(MatchingOutcome.OPENING_PRICE_ANNOUNCEMENT, null, new LinkedList<>());
    }

    public static MatchResult auctionMatchCompleted(List<Trade> trades){
        return new MatchResult(MatchingOutcome.AUCTION_MATCH_COMPLETED, null, new LinkedList<>(trades));
    }
    private MatchResult(MatchingOutcome outcome, Order remainder, LinkedList<Trade> trades) {
        this.outcome = outcome;
        this.remainder = remainder;
        this.trades = trades;
    }
    public MatchingOutcome outcome() {
        return outcome;
    }
    public Order remainder() {
        return remainder;
    }
    public LinkedList<Trade> trades() {
        return trades;
    }
    @Override
    public boolean equals(Object obj) {
        if (obj == this) return true;
        if (obj == null || obj.getClass() != this.getClass()) return false;
        var that = (MatchResult) obj;
        return Objects.equals(this.remainder, that.remainder) &&
                Objects.equals(this.trades, that.trades);
    }

    @Override
    public int hashCode() {
        return Objects.hash(remainder, trades);
    }

    @Override
    public String toString() {
        return "MatchResult[" +
                "remainder=" + remainder + ", " +
                "trades=" + trades + ']';
    }






}
