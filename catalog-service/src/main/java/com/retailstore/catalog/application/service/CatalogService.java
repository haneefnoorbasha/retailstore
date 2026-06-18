package com.retailstore.catalog.application.service;

import com.retailstore.catalog.api.rest.v1.dto.response.*;
import com.retailstore.catalog.api.rest.v1.mapper.CatalogMapper;
import com.retailstore.catalog.application.port.in.GetProductUseCase;
import com.retailstore.catalog.application.port.in.GetTagUseCase;
import com.retailstore.catalog.application.port.out.ProductRepositoryPort;
import com.retailstore.catalog.domain.exception.ProductNotFoundException;
import com.retailstore.catalog.domain.model.Product;
import com.retailstore.catalog.domain.repository.TagJpaRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.domain.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class CatalogService implements GetProductUseCase, GetTagUseCase {

    private final ProductRepositoryPort productRepository;
    private final TagJpaRepository tagRepository;
    private final CatalogMapper mapper;

    @Override
    @Cacheable(value = "products", key = "#id")
    public ProductResponse getById(String id) {
        log.debug("Fetching product by id: {}", id);
        Product product = productRepository.findById(id)
            .orElseThrow(() -> new ProductNotFoundException(id));
        return mapper.toProductResponse(product);
    }

    @Override
    public PagedProductResponse getProducts(List<String> tags, String order, int page, int size) {
        log.debug("Fetching products — tags={}, order={}, page={}, size={}", tags, order, page, size);

        Sort sort = resolveSort(order);
        Pageable pageable = PageRequest.of(page, size, sort);

        Page<Product> resultPage = (tags != null && !tags.isEmpty())
            ? productRepository.findByTagNames(tags, pageable)
            : productRepository.findAll(pageable);

        List<ProductResponse> products = resultPage.getContent().stream()
            .map(mapper::toProductResponse)
            .collect(Collectors.toList());

        return PagedProductResponse.builder()
            .products(products)
            .totalCount(resultPage.getTotalElements())
            .page(page)
            .size(size)
            .totalPages(resultPage.getTotalPages())
            .hasNext(resultPage.hasNext())
            .hasPrevious(resultPage.hasPrevious())
            .build();
    }

    @Override
    public long countProducts(List<String> tags) {
        return (tags != null && !tags.isEmpty())
            ? productRepository.countByTagNames(tags)
            : productRepository.countAll();
    }

    @Override
    @Cacheable(value = "tags")
    public List<TagResponse> getAllTags() {
        return tagRepository.findAll().stream()
            .map(mapper::toTagResponse)
            .collect(Collectors.toList());
    }

    private Sort resolveSort(String order) {
        return switch (order == null ? "name_asc" : order) {
            case "price_asc"  -> Sort.by("price").ascending();
            case "price_desc" -> Sort.by("price").descending();
            case "name_desc"  -> Sort.by("name").descending();
            default           -> Sort.by("name").ascending();
        };
    }
}
