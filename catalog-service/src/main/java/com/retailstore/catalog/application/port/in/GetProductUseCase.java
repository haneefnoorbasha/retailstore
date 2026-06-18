package com.retailstore.catalog.application.port.in;

import com.retailstore.catalog.api.rest.v1.dto.response.ProductResponse;
import com.retailstore.catalog.api.rest.v1.dto.response.PagedProductResponse;
import java.util.List;

public interface GetProductUseCase {
    ProductResponse getById(String id);
    PagedProductResponse getProducts(List<String> tags, String order, int page, int size);
    long countProducts(List<String> tags);
}
