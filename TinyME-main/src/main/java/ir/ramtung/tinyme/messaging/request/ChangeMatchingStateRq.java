package ir.ramtung.tinyme.messaging.request;


import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
public class ChangeMatchingStateRq {
    private String securityIsin;
    private MatchingState targetState;
    public ChangeMatchingStateRq(String securityIsin, MatchingState targetState){
        this.securityIsin = securityIsin;
        this.targetState = targetState;
    }
}