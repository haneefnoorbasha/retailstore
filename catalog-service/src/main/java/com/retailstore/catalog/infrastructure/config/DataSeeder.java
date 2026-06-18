package com.retailstore.catalog.infrastructure.config;

import com.retailstore.catalog.application.port.out.ProductRepositoryPort;
import com.retailstore.catalog.domain.model.Product;
import com.retailstore.catalog.domain.model.Tag;
import com.retailstore.catalog.domain.repository.TagJpaRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.util.*;

@Slf4j
@Component
@RequiredArgsConstructor
@Profile("!test")
public class DataSeeder implements ApplicationRunner {

    private final ProductRepositoryPort productRepository;
    private final TagJpaRepository tagRepository;

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (productRepository.countAll() > 0) {
            log.info("Catalog already seeded — skipping");
            return;
        }
        log.info("Seeding catalog with sample products...");

        Tag electronics = tag("electronics", "Electronics");
        Tag clothing    = tag("clothing",    "Clothing");
        Tag books       = tag("books",       "Books");
        Tag furniture   = tag("furniture",   "Furniture");
        Tag fitness     = tag("fitness",     "Fitness");

        List<Product> products = List.of(
            product("prod-001", "Wireless Noise-Cancelling Headphones",
                "Premium ANC headphones with 30-hour battery life, spatial audio, and foldable design.",
                new BigDecimal("149.99"), 85, electronics),
            product("prod-002", "Mechanical Keyboard TKL",
                "Tenkeyless mechanical keyboard with Cherry MX switches, per-key RGB lighting.",
                new BigDecimal("79.99"), 120, electronics),
            product("prod-003", "4K USB-C Monitor 27in",
                "27-inch IPS panel, 4K UHD, 99% sRGB, USB-C 90W power delivery, adjustable stand.",
                new BigDecimal("399.99"), 35, electronics),
            product("prod-004", "Slim Running Shorts",
                "Lightweight moisture-wicking running shorts with 4-inch inseam and side pockets.",
                new BigDecimal("34.99"), 200, clothing, fitness),
            product("prod-005", "Merino Wool Crew Neck",
                "100% merino wool sweater — temperature-regulating, odour-resistant, machine washable.",
                new BigDecimal("89.99"), 60, clothing),
            product("prod-006", "Designing Data-Intensive Applications",
                "Martin Kleppmann's definitive guide to building scalable, reliable, and maintainable systems.",
                new BigDecimal("49.99"), 75, books),
            product("prod-007", "Clean Architecture",
                "Robert C. Martin on component design, SOLID principles, and software architecture.",
                new BigDecimal("39.99"), 90, books),
            product("prod-008", "Ergonomic Mesh Office Chair",
                "Lumbar support, adjustable armrests, breathable mesh back, 5-year warranty.",
                new BigDecimal("449.99"), 15, furniture),
            product("prod-009", "Standing Desk 140cm",
                "Electric height-adjustable desk with memory presets, cable management, solid MDF top.",
                new BigDecimal("699.99"), 10, furniture),
            product("prod-010", "Adjustable Dumbbell Set 5-50lb",
                "Replaces 15 sets. Click-dial adjustment, compact storage, durable neoprene coating.",
                new BigDecimal("329.99"), 25, fitness)
        );
        products.forEach(productRepository::save);
        log.info("Seeded {} products across {} tags", products.size(), 5);
    }

    private Tag tag(String name, String displayName) {
        return tagRepository.findByName(name)
            .orElseGet(() -> tagRepository.save(Tag.builder().name(name).displayName(displayName).build()));
    }

    private Product product(String id, String name, String desc, BigDecimal price, int stock, Tag... tags) {
        return Product.builder()
            .id(id).name(name).description(desc).price(price)
            .stockQuantity(stock).active(true)
            .tags(new ArrayList<>(Arrays.asList(tags)))
            .build();
    }
}
