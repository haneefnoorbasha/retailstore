package com.retailstore.catalog.domain.repository;

import com.retailstore.catalog.domain.model.Product;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.List;

public interface ProductJpaRepository extends JpaRepository<Product, String> {

    @Query("SELECT DISTINCT p FROM Product p JOIN p.tags t WHERE t.name IN :tags AND p.active = true")
    Page<Product> findByTagNamesAndActive(@Param("tags") List<String> tags, Pageable pageable);

    @Query("SELECT COUNT(DISTINCT p) FROM Product p JOIN p.tags t WHERE t.name IN :tags AND p.active = true")
    long countByTagNamesAndActive(@Param("tags") List<String> tags);

    @Query("SELECT p FROM Product p WHERE p.active = true")
    Page<Product> findAllActive(Pageable pageable);
}
