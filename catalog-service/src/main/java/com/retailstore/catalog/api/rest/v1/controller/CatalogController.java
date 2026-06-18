package com.retailstore.catalog.api.rest.v1.controller;

import com.retailstore.catalog.api.rest.v1.dto.response.*;
import com.retailstore.catalog.application.port.in.GetProductUseCase;
import com.retailstore.catalog.application.port.in.GetTagUseCase;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/catalog")
@RequiredArgsConstructor
@Tag(name = "Catalog", description = "Product catalog — browse, search, and filter products")
public class CatalogController {

    private final GetProductUseCase getProductUseCase;
    private final GetTagUseCase getTagUseCase;

    @GetMapping("/products")
    @Operation(summary = "List products",
               description = "Returns a paginated list of active products. Optionally filter by tags.")
    public ResponseEntity<PagedProductResponse> listProducts(
            @Parameter(description = "Comma-separated tag names to filter by")
            @RequestParam(required = false) List<String> tags,
            @Parameter(description = "Sort order: name_asc, name_desc, price_asc, price_desc")
            @RequestParam(defaultValue = "name_asc") String order,
            @RequestParam(defaultValue = "0")  int page,
            @RequestParam(defaultValue = "20") int size) {

        return ResponseEntity.ok(getProductUseCase.getProducts(tags, order, page, size));
    }

    @GetMapping("/products/{id}")
    @Operation(summary = "Get product by ID")
    public ResponseEntity<ProductResponse> getProduct(@PathVariable String id) {
        return ResponseEntity.ok(getProductUseCase.getById(id));
    }

    @GetMapping("/products/count")
    @Operation(summary = "Get product count")
    public ResponseEntity<Map<String, Long>> countProducts(
            @RequestParam(required = false) List<String> tags) {
        return ResponseEntity.ok(Map.of("count", getProductUseCase.countProducts(tags)));
    }

    @GetMapping("/tags")
    @Operation(summary = "List all product tags")
    public ResponseEntity<List<TagResponse>> listTags() {
        return ResponseEntity.ok(getTagUseCase.getAllTags());
    }
}
