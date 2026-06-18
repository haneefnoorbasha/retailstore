package com.retailstore.catalog.infrastructure.persistence;

import com.retailstore.catalog.application.port.out.ProductRepositoryPort;
import com.retailstore.catalog.domain.model.Product;
import com.retailstore.catalog.domain.repository.ProductJpaRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Component;
import java.util.List;
import java.util.Optional;

@Component
@RequiredArgsConstructor
public class ProductRepositoryAdapter implements ProductRepositoryPort {

    private final ProductJpaRepository jpaRepository;

    @Override public Optional<Product> findById(String id) { return jpaRepository.findById(id); }
    @Override public Page<Product> findAll(Pageable pageable) { return jpaRepository.findAllActive(pageable); }
    @Override public Page<Product> findByTagNames(List<String> tags, Pageable pageable) { return jpaRepository.findByTagNamesAndActive(tags, pageable); }
    @Override public long countAll() { return jpaRepository.count(); }
    @Override public long countByTagNames(List<String> tags) { return jpaRepository.countByTagNamesAndActive(tags); }
    @Override public Product save(Product product) { return jpaRepository.save(product); }
}
