package com.retailstore.catalog.api.rest.v1.mapper;

import com.retailstore.catalog.api.rest.v1.dto.response.ProductResponse;
import com.retailstore.catalog.api.rest.v1.dto.response.TagResponse;
import com.retailstore.catalog.domain.model.Product;
import com.retailstore.catalog.domain.model.Tag;
import org.mapstruct.*;

@Mapper(componentModel = "spring")
public interface CatalogMapper {

    @Mapping(target = "inStock",    expression = "java(product.isInStock())")
    @Mapping(target = "available",  expression = "java(product.isAvailable())")
    ProductResponse toProductResponse(Product product);

    TagResponse toTagResponse(Tag tag);
}
