package com.retailstore.cart.domain.model;

import lombok.*;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbAttribute;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbBean;
import java.math.BigDecimal;

@DynamoDbBean
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class CartItem {
    private String itemId;
    private String productId;
    private String productName;
    private String imageUrl;
    private int quantity;
    private BigDecimal unitPrice;

    @DynamoDbAttribute("itemId")
    public String getItemId() { return itemId; }

    public BigDecimal getLineTotal() {
        return unitPrice != null ? unitPrice.multiply(BigDecimal.valueOf(quantity)) : BigDecimal.ZERO;
    }
}
